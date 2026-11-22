using System.Runtime.InteropServices;
using System.Text;

namespace EnvSwitch;

/// <summary>
/// 求路径的「真实身份」：穿透 junction / 符号链接（含路径中间的段），
/// 用来判断 C:\...\latest\jdk-21\bin 与 C:\...\jdk-21.0.13.0.2\bin 这类同一目录的多种写法。
/// 解析不到（路径不存在、无权限、网络路径）时退回规范化后的字符串，不影响原有行为。
/// </summary>
internal static class RealPath
{
    private const uint ShareAll = 0x7;
    private const uint OpenExisting = 3;
    private const uint BackupSemantics = 0x02000000;
    private const uint VolumeNameDos = 0x0;
    private static readonly IntPtr InvalidHandle = new(-1);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateFileW(string fileName, uint access, uint share, IntPtr security,
        uint disposition, uint flags, IntPtr template);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint GetFinalPathNameByHandleW(IntPtr handle, StringBuilder buffer, uint length, uint flags);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    /// <summary>比较用的键：展开系统变量 → 去尾部分隔符 → 解析真实目录 → 小写。</summary>
    internal static string Key(string? value)
    {
        var text = (value ?? "").Trim().Trim('"');
        if (text.Length == 0)
            return "";
        text = Environment.ExpandEnvironmentVariables(text);
        var canon = PathUtil.Canonical(text);
        if (canon.Length == 0)
            return "";
        // 网络路径解析可能很慢，直接按字符串比
        if (canon.StartsWith(@"\\", StringComparison.Ordinal) && !canon.StartsWith(@"\\?\", StringComparison.Ordinal))
            return canon.ToLowerInvariant();
        try
        {
            var real = Final(canon);
            if (!string.IsNullOrEmpty(real))
                canon = PathUtil.Canonical(real);
        }
        catch
        {
            // 忽略，退回字符串比较
        }
        return canon.ToLowerInvariant();
    }

    internal static bool Same(string a, string b) =>
        string.Equals(Key(a), Key(b), StringComparison.OrdinalIgnoreCase);

    private static string? Final(string path)
    {
        var handle = CreateFileW(path, 0, ShareAll, IntPtr.Zero, OpenExisting, BackupSemantics, IntPtr.Zero);
        if (handle == IntPtr.Zero || handle == InvalidHandle)
            return null;
        try
        {
            var buffer = new StringBuilder(512);
            var length = GetFinalPathNameByHandleW(handle, buffer, (uint)buffer.Capacity, VolumeNameDos);
            if (length == 0)
                return null;
            if (length >= buffer.Capacity)
            {
                buffer = new StringBuilder((int)length + 1);
                length = GetFinalPathNameByHandleW(handle, buffer, (uint)buffer.Capacity, VolumeNameDos);
                if (length == 0)
                    return null;
            }

            var result = buffer.ToString();
            if (result.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase))
                result = @"\\" + result[8..];
            else if (result.StartsWith(@"\\?\", StringComparison.Ordinal))
                result = result[4..];
            return result;
        }
        finally
        {
            CloseHandle(handle);
        }
    }
}
