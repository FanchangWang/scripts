using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;
using AutoRunManager.Models;

namespace AutoRunManager.Services;

public class RegistryService
{
    private static readonly string HkcuApprovedPath =
        @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";

    private static readonly string HklmApprovedPath =
        @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";

    private static readonly string HklmWowApprovedPath =
        @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run32";

    private static readonly (string Display, RegistryHive Hive, string SubKey, string Section)[] RegistryPaths =
    {
        ("HKCU", RegistryHive.CurrentUser, @"Software\Microsoft\Windows\CurrentVersion\Run", "HKEY_CURRENT_USER"),
        ("HKLM", RegistryHive.LocalMachine, @"Software\Microsoft\Windows\CurrentVersion\Run", "HKEY_LOCAL_MACHINE"),
        ("HKLM", RegistryHive.LocalMachine, @"Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Run", "HKLM WOW6432Node"),
    };

    private const string UwpSystemAppDataPath =
        @"Software\Classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\AppModel\SystemAppData";

    public static List<StartupEntry> Enumerate()
    {
        var entries = new List<StartupEntry>();

        foreach (var (display, hive, subKey, section) in RegistryPaths)
        {
            using var key = RegistryKey.OpenBaseKey(hive, RegistryView.Registry64).OpenSubKey(subKey);
            if (key == null) continue;

            var isHklm = hive == RegistryHive.LocalMachine;
            var isWow = subKey.Contains("WOW6432Node", StringComparison.OrdinalIgnoreCase);

            foreach (var valueName in key.GetValueNames())
            {
                var value = key.GetValue(valueName)?.ToString() ?? "";
                var parts = ParseCommand(value);

                entries.Add(new StartupEntry
                {
                    Name = valueName,
                    Path = parts.Path,
                    Args = parts.Args,
                    IsEnabled = !IsStartupApprovedDisabled(valueName, isHklm, isWow),
                    Source = "registry",
                    SourceDetail = $"{display}\\{subKey}",
                    SourceKeyName = valueName
                });
            }
        }

        return entries;
    }

    public static List<StartupEntry> EnumerateUwp()
    {
        var entries = new List<StartupEntry>();

        using var baseKey = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64)
            .OpenSubKey(UwpSystemAppDataPath);
        if (baseKey == null) return entries;

        foreach (var pkgFamilyName in baseKey.GetSubKeyNames())
        {
            using var pkgKey = baseKey.OpenSubKey(pkgFamilyName);
            if (pkgKey == null) continue;

            foreach (var taskId in pkgKey.GetSubKeyNames())
            {
                using var taskKey = pkgKey.OpenSubKey(taskId);
                if (taskKey == null) continue;

                var stateObj = taskKey.GetValue("State");
                if (stateObj is not int state) continue;

                var displayName = GetUwpDisplayName(pkgFamilyName);

                entries.Add(new StartupEntry
                {
                    Name = string.IsNullOrEmpty(displayName) ? pkgFamilyName : displayName,
                    Path = $"{pkgFamilyName}!{taskId}",
                    Args = "",
                    IsEnabled = state == 2,
                    RunAsAdmin = false,
                    Source = "uwp",
                    SourceDetail = pkgFamilyName,
                    SourceKeyName = taskId
                });
            }
        }

        return entries;
    }

    private static string GetUwpDisplayName(string packageFamilyName)
    {
        try
        {
            var splashPath = $@"{UwpSystemAppDataPath}\{packageFamilyName}\SplashScreen";
            using var splashKey = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64)
                .OpenSubKey(splashPath);
            if (splashKey != null)
            {
                foreach (var aumid in splashKey.GetSubKeyNames())
                {
                    using var appKey = splashKey.OpenSubKey(aumid);
                    var raw = appKey?.GetValue("AppName")?.ToString();
                    if (string.IsNullOrEmpty(raw)) continue;
                    return ResolveIndirectString(raw, packageFamilyName);
                }
            }
        }
        catch { }

        var idx = packageFamilyName.IndexOf('_');
        return idx > 0 ? packageFamilyName[..idx] : packageFamilyName;
    }

    [DllImport("shlwapi.dll", CharSet = CharSet.Unicode)]
    private static extern int SHLoadIndirectString(string pszSource, StringBuilder pszOutBuf, uint cchOutBuf, IntPtr ppvReserved);

    private static string ResolveIndirectString(string source, string packageFamilyName)
    {
        if (string.IsNullOrEmpty(source) || !source.Contains("ms-resource://"))
            return source;

        // If already in @{...?ms-resource://...} format, try direct resolution
        if (source.StartsWith("@{"))
        {
            var sb = new StringBuilder(1024);
            var hr = SHLoadIndirectString(source, sb, (uint)sb.Capacity, IntPtr.Zero);
            if (hr == 0)
                return sb.ToString();
        }

        // For bare ms-resource:// URIs, find PackageFullName from MrtCache registry
        // then construct @{PackageFullName?ms-resource://...} for SHLoadIndirectString
        try
        {
            var mrtPath = @"Software\Classes\Local Settings\MrtCache";
            using var mrtKey = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64)
                .OpenSubKey(mrtPath);
            if (mrtKey != null)
            {
                foreach (var encodedPath in mrtKey.GetSubKeyNames())
                {
                    var fullName = TryExtractFullName(encodedPath, packageFamilyName);
                    if (fullName == null) continue;

                    var indirect = $"@{{{fullName}?{source}}}";
                    var sb = new StringBuilder(1024);
                    var hr = SHLoadIndirectString(indirect, sb, (uint)sb.Capacity, IntPtr.Zero);
                    if (hr == 0)
                        return sb.ToString();
                }
            }
        }
        catch { }

        return source;
    }

    private static string? TryExtractFullName(string encodedPath, string packageFamilyName)
    {
        try
        {
            var decoded = Uri.UnescapeDataString(encodedPath);
            var match = System.Text.RegularExpressions.Regex.Match(
                decoded, @"\\WindowsApps\\(.+?)\\resources\.pri$");
            if (!match.Success) return null;

            var fullName = match.Groups[1].Value;
            var parts = packageFamilyName.Split('_');
            var namePart = parts[0];
            var pubId = parts[^1];

            if (fullName.StartsWith(namePart, StringComparison.OrdinalIgnoreCase) &&
                fullName.EndsWith("_" + pubId, StringComparison.OrdinalIgnoreCase))
                return fullName;
        }
        catch { }

        return null;
    }

    public static bool IsStartupApprovedDisabled(string valueName, bool isHklm, bool isWow = false)
    {
        if (isWow)
        {
            using var wowKey = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64)
                .OpenSubKey(HklmWowApprovedPath);
            if (wowKey != null)
            {
                var data = FindStartupApprovedData(wowKey, valueName);
                if (data != null)
                    return data[0] == 0x03;
            }
            return false;
        }

        if (isHklm)
        {
            using var hklmKey = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64)
                .OpenSubKey(HklmApprovedPath);
            if (hklmKey != null)
            {
                var data = FindStartupApprovedData(hklmKey, valueName);
                if (data != null)
                    return data[0] == 0x03;
            }
            return false;
        }

        using var hkcuKey = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64)
            .OpenSubKey(HkcuApprovedPath);
        if (hkcuKey == null) return false;

        var hkcuData = FindStartupApprovedData(hkcuKey, valueName);
        if (hkcuData != null)
            return hkcuData[0] == 0x03;
        return false;
    }

    private static byte[]? FindStartupApprovedData(RegistryKey approvedKey, string valueName)
    {
        var data = GetStartupApprovedData(approvedKey, valueName);
        if (data == null)
        {
            var withExe = valueName.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
                ? null : valueName + ".exe";
            if (withExe != null)
                data = GetStartupApprovedData(approvedKey, withExe);
        }
        if (data == null)
        {
            var withoutExe = Path.GetFileNameWithoutExtension(valueName);
            if (withoutExe != valueName)
                data = GetStartupApprovedData(approvedKey, withoutExe);
        }
        return data;
    }

    private static byte[]? GetStartupApprovedData(RegistryKey approvedKey, string valueName)
    {
        var value = approvedKey.GetValue(valueName);
        if (value is byte[] data && data.Length > 0)
            return data;
        return null;
    }

    public static void Disable(string valueName)
    {
        WriteStartupApproved(valueName, false, false);
    }

    public static void Disable(string valueName, string sourceDetail)
    {
        var isHklm = sourceDetail.StartsWith("HKLM", StringComparison.OrdinalIgnoreCase);
        var isWow = sourceDetail.Contains("WOW6432Node", StringComparison.OrdinalIgnoreCase);
        WriteStartupApproved(valueName, isHklm, isWow);
    }

    public static void DisableUwp(string pkgFamilyName, string taskId)
    {
        SetUwpState(pkgFamilyName, taskId, 0);
    }

    public static void Enable(string valueName)
    {
        using var approvedKey = RegistryKey.OpenBaseKey(
            RegistryHive.CurrentUser, RegistryView.Registry64)
            .OpenSubKey(HkcuApprovedPath, true);

        approvedKey?.DeleteValue(valueName, false);
    }

    public static void Enable(string valueName, string sourceDetail)
    {
        var isWow = sourceDetail.Contains("WOW6432Node", StringComparison.OrdinalIgnoreCase);
        var isHklm = sourceDetail.StartsWith("HKLM", StringComparison.OrdinalIgnoreCase) && !isWow;

        if (isWow)
            DeleteFromPath(HklmWowApprovedPath, RegistryHive.LocalMachine, valueName);

        if (isHklm)
            DeleteFromPath(HklmApprovedPath, RegistryHive.LocalMachine, valueName);

        if (!isHklm && !isWow)
            DeleteFromPath(HkcuApprovedPath, RegistryHive.CurrentUser, valueName);
    }

    public static void EnableUwp(string pkgFamilyName, string taskId)
    {
        SetUwpState(pkgFamilyName, taskId, 2);
    }

    private static void SetUwpState(string pkgFamilyName, string taskId, int state)
    {
        var path = Path.Combine(UwpSystemAppDataPath, pkgFamilyName, taskId);
        using var key = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64)
            .OpenSubKey(path, true);
        key?.SetValue("State", state, RegistryValueKind.DWord);
    }

    private static void DeleteFromPath(string path, RegistryHive hive, string valueName)
    {
        using var key = RegistryKey.OpenBaseKey(hive, RegistryView.Registry64)
            .OpenSubKey(path, true);
        key?.DeleteValue(valueName, false);
    }

    private static void WriteStartupApproved(string valueName, bool isHklm, bool isWow)
    {
        var approvedPath = isWow ? HklmWowApprovedPath
            : isHklm ? HklmApprovedPath
            : HkcuApprovedPath;
        var hive = isWow || isHklm ? RegistryHive.LocalMachine : RegistryHive.CurrentUser;

        using var approvedKey = RegistryKey.OpenBaseKey(hive, RegistryView.Registry64)
            .CreateSubKey(approvedPath, true);

        var timestamp = BitConverter.GetBytes(DateTime.UtcNow.ToFileTime());
        var disabledValue = new byte[12];
        disabledValue[0] = 0x03;
        Array.Copy(timestamp, 0, disabledValue, 4, 8);
        approvedKey?.SetValue(valueName, disabledValue, RegistryValueKind.Binary);
    }

    private static (string Path, string Args) ParseCommand(string command)
    {
        if (string.IsNullOrEmpty(command))
            return (string.Empty, string.Empty);

        command = command.Trim();
        if (command.StartsWith('"'))
        {
            var endQuote = command.IndexOf('"', 1);
            if (endQuote > 0)
            {
                var path = command[1..endQuote];
                var args = command[(endQuote + 1)..].Trim();
                return (path, args);
            }
        }
        else
        {
            var space = command.IndexOf(' ');
            if (space > 0)
            {
                var path = command[..space];
                var args = command[(space + 1)..].Trim();
                if (args.StartsWith('/') || args.StartsWith('-'))
                    return (path, args);
            }
        }

        return (command, string.Empty);
    }
}
