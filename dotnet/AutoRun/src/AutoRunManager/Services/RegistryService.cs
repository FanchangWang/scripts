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
