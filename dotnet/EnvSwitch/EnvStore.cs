using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace EnvSwitch;

/// <summary>
/// 环境变量写入通道。
///
/// 不用 <see cref="Environment.SetEnvironmentVariable(string, string?, EnvironmentVariableTarget)"/>：
/// 它每次写入都会向所有顶层窗口广播 WM_SETTINGCHANGE，并逐个等窗口响应。窗口一多、
/// 或者某个程序的消息循环挂起，单次写入实测能拖到 20 秒以上——网页上点一下「切换版本」
/// 就长时间没有任何反馈。这里改为直接写注册表，再广播一次且只等很短的时间：
/// 写入本身是毫秒级，广播超时也只会让个别程序晚一点看到新值，不影响结果。
/// </summary>
internal static class EnvStore
{
    private const string UserSubKey = "Environment";
    private const string MachineSubKey = @"SYSTEM\CurrentControlSet\Control\Session Manager\Environment";

    private const uint WmSettingChange = 0x001A;
    private const uint SmtoAbortIfHung = 0x0002;
    private static readonly IntPtr HwndBroadcast = new(0xFFFF);

    /// <summary>广播最多等这么久（毫秒），避免被挂起的窗口拖住。</summary>
    private const uint BroadcastTimeoutMs = 200;

    internal static void Set(string name, string? value, EnvScope scope)
    {
        var (hive, subKey) = scope == EnvScope.Machine
            ? (RegistryHive.LocalMachine, MachineSubKey)
            : (RegistryHive.CurrentUser, UserSubKey);

        try
        {
            using var baseKey = RegistryKey.OpenBaseKey(hive, RegistryView.Default);
            using var key = baseKey.OpenSubKey(subKey, writable: true)
                ?? throw new UnauthorizedAccessException($"打不开环境变量注册表项：{hive}\\{subKey}。");

            if (value is null)
                key.DeleteValue(name, throwOnMissingValue: false);
            else
                key.SetValue(name, value, KindFor(key, name));
        }
        catch (System.Security.SecurityException ex)
        {
            // 统一成 UnauthorizedAccessException，调用方的提示文案才能复用
            throw new UnauthorizedAccessException(ex.Message, ex);
        }

        Broadcast();
    }

    /// <summary>写 PATH 这类「值已存在」的变量时沿用原来的类型，避免把 REG_EXPAND_SZ 降级成普通字符串。</summary>
    private static RegistryValueKind KindFor(RegistryKey key, string name)
    {
        try
        {
            return key.GetValueKind(name) == RegistryValueKind.ExpandString
                ? RegistryValueKind.ExpandString
                : RegistryValueKind.String;
        }
        catch
        {
            // 值不存在时 GetValueKind 会抛异常（消息是 "The specified registry key does not exist."）
            return RegistryValueKind.String;
        }
    }

    /// <summary>
    /// 读取现有值的注册表类型：REG_EXPAND_SZ 里的 %VAR% 会被系统展开，REG_SZ 不会。
    /// 值不存在时返回 null。
    /// </summary>
    internal static RegistryValueKind? KindOf(string name, EnvScope scope)
    {
        var (hive, subKey) = scope == EnvScope.Machine
            ? (RegistryHive.LocalMachine, MachineSubKey)
            : (RegistryHive.CurrentUser, UserSubKey);
        try
        {
            using var baseKey = RegistryKey.OpenBaseKey(hive, RegistryView.Default);
            using var key = baseKey.OpenSubKey(subKey, writable: false);
            return key?.GetValueKind(name);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// 读取注册表里的原样值：REG_EXPAND_SZ 的 %变量% 不展开。
    /// 界面编辑 / 写回时用它，避免把「%USERPROFILE%\go」这种写法固化成展开后的绝对路径。
    /// 值不存在时返回 null。
    /// </summary>
    internal static string? GetRaw(string name, EnvScope scope)
    {
        using var baseKey = RegistryKey.OpenBaseKey(HiveOf(scope), RegistryView.Default);
        using var key = baseKey.OpenSubKey(SubKeyOf(scope), writable: false);
        if (key is null)
            return null;
        return key.GetValue(name, null, RegistryValueOptions.DoNotExpandEnvironmentNames)?.ToString();
    }

    /// <summary>枚举一侧的全部变量（原样值，不展开）。读不到时抛异常，由调用方回退。</summary>
    internal static Dictionary<string, string> AllRaw(EnvScope scope)
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        using var baseKey = RegistryKey.OpenBaseKey(HiveOf(scope), RegistryView.Default);
        using var key = baseKey.OpenSubKey(SubKeyOf(scope), writable: false);
        if (key is null)
            return result;
        foreach (var name in key.GetValueNames())
        {
            var value = key.GetValue(name, null, RegistryValueOptions.DoNotExpandEnvironmentNames);
            result[name] = value?.ToString() ?? "";
        }
        return result;
    }

    private static RegistryHive HiveOf(EnvScope scope) =>
        scope == EnvScope.Machine ? RegistryHive.LocalMachine : RegistryHive.CurrentUser;

    private static string SubKeyOf(EnvScope scope) =>
        scope == EnvScope.Machine ? MachineSubKey : UserSubKey;

    /// <summary>通知系统环境已变化；超时或失败都忽略，写入已经落盘。</summary>
    internal static void Broadcast()
    {
        try
        {
            _ = SendMessageTimeout(
                HwndBroadcast, WmSettingChange, UIntPtr.Zero, "Environment",
                SmtoAbortIfHung, BroadcastTimeoutMs, out _);
        }
        catch
        {
            // 广播只是通知，失败不影响已写入的值
        }
    }

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr SendMessageTimeout(
        IntPtr hWnd, uint msg, UIntPtr wParam, string lParam,
        uint flags, uint timeout, out UIntPtr result);
}
