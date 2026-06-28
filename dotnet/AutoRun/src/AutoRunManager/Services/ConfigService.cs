using System.Text.Json;
using System.Text.Json.Serialization;
using AutoRunManager.Models;

namespace AutoRunManager.Services;

public class ConfigService
{
    private static readonly string AppDataDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "AutoRunManager");

    private static readonly string ConfigPath = Path.Combine(AppDataDir, "config.json");

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() }
    };

    public static string GetBackupPath(string type) =>
        Path.Combine(AppDataDir, "backup", type);

    public static void EnsureDirectories()
    {
        Directory.CreateDirectory(AppDataDir);
        Directory.CreateDirectory(GetBackupPath("user"));
        Directory.CreateDirectory(GetBackupPath("system"));
    }

    public static AppConfig Load()
    {
        EnsureDirectories();
        if (!File.Exists(ConfigPath))
            return new AppConfig();

        var json = File.ReadAllText(ConfigPath);
        return JsonSerializer.Deserialize<AppConfig>(json, JsonOptions) ?? new AppConfig();
    }

    public static void Save(AppConfig config)
    {
        EnsureDirectories();
        var json = JsonSerializer.Serialize(config, JsonOptions);
        File.WriteAllText(ConfigPath, json);
    }

    public static bool IsItemDelayed(string name, string source, AppConfig config)
    {
        return config.Items.Any(i =>
            i.Name.Equals(name, StringComparison.OrdinalIgnoreCase) &&
            i.Source.Equals(source, StringComparison.OrdinalIgnoreCase));
    }
}
