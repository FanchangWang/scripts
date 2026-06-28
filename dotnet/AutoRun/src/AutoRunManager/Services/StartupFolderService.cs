using Microsoft.Win32;
using AutoRunManager.Models;

namespace AutoRunManager.Services;

public class StartupFolderService
{
    private static readonly string HkcuApprovedFolderPath =
        @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\StartupFolder";

    private static readonly string HklmApprovedFolderPath =
        @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\StartupFolder";

    private static readonly (string Display, string Path, string BackupType)[] StartupFolders =
    {
        ("用户启动文件夹",
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "Microsoft\\Windows\\Start Menu\\Programs\\Startup"),
            "user"),
        ("系统启动文件夹",
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
                "Microsoft\\Windows\\Start Menu\\Programs\\Startup"),
            "system"),
    };

    public static List<StartupEntry> Enumerate()
    {
        var entries = new List<StartupEntry>();

        foreach (var (display, folderPath, backupType) in StartupFolders)
        {
            if (!Directory.Exists(folderPath)) continue;

            var isSystem = backupType == "system";

            foreach (var file in Directory.GetFiles(folderPath, "*.lnk"))
            {
                var fileName = Path.GetFileName(file);
                entries.Add(new StartupEntry
                {
                    Name = Path.GetFileNameWithoutExtension(file),
                    Path = file,
                    IsEnabled = !IsStartupApprovedDisabled(fileName, isSystem),
                    Source = "startup_folder",
                    SourceDetail = display,
                    SourceKeyName = fileName
                });
            }
            foreach (var file in Directory.GetFiles(folderPath, "*.url"))
            {
                var fileName = Path.GetFileName(file);
                entries.Add(new StartupEntry
                {
                    Name = Path.GetFileNameWithoutExtension(file),
                    Path = file,
                    IsEnabled = !IsStartupApprovedDisabled(fileName, isSystem),
                    Source = "startup_folder",
                    SourceDetail = display,
                    SourceKeyName = fileName
                });
            }
        }

        return entries;
    }

    public static bool IsStartupApprovedDisabled(string valueName, bool isSystem)
    {
        var approvedPath = isSystem ? HklmApprovedFolderPath : HkcuApprovedFolderPath;
        var hive = isSystem ? RegistryHive.LocalMachine : RegistryHive.CurrentUser;

        using var approvedKey = RegistryKey.OpenBaseKey(hive, RegistryView.Registry64)
            .OpenSubKey(approvedPath);

        if (approvedKey == null) return false;

        var data = GetStartupApprovedData(approvedKey, valueName);
        return data != null && data[0] == 0x03;
    }

    private static byte[]? GetStartupApprovedData(RegistryKey approvedKey, string valueName)
    {
        var value = approvedKey.GetValue(valueName);
        if (value is byte[] data && data.Length > 0)
            return data;
        return null;
    }

    public static void Disable(string fileName, string backupType)
    {
        var isSystem = backupType == "system";
        WriteStartupApproved(fileName, isSystem);
    }

    public static void Enable(string fileName, string backupType)
    {
        var isSystem = backupType == "system";
        var approvedPath = isSystem ? HklmApprovedFolderPath : HkcuApprovedFolderPath;
        var hive = isSystem ? RegistryHive.LocalMachine : RegistryHive.CurrentUser;

        using var approvedKey = RegistryKey.OpenBaseKey(hive, RegistryView.Registry64)
            .OpenSubKey(approvedPath, true);

        approvedKey?.DeleteValue(fileName, false);
    }

    private static void WriteStartupApproved(string fileName, bool isSystem)
    {
        var approvedPath = isSystem ? HklmApprovedFolderPath : HkcuApprovedFolderPath;
        var hive = isSystem ? RegistryHive.LocalMachine : RegistryHive.CurrentUser;

        using var approvedKey = RegistryKey.OpenBaseKey(hive, RegistryView.Registry64)
            .CreateSubKey(approvedPath, true);

        var timestamp = BitConverter.GetBytes(DateTime.UtcNow.ToFileTime());
        var disabledValue = new byte[12];
        disabledValue[0] = 0x03;
        Array.Copy(timestamp, 0, disabledValue, 4, 8);
        approvedKey?.SetValue(fileName, disabledValue, RegistryValueKind.Binary);
    }

    public static string? GetDisabledBackupPath(string fileName, string backupType)
    {
        var backupDir = ConfigService.GetBackupPath(backupType);
        var backupPath = Path.Combine(backupDir, fileName);
        return File.Exists(backupPath) ? backupPath : null;
    }
}
