using System.Text.Json;
using System.Text.Json.Serialization;
using AutoRunManager.Models;

namespace AutoRunScheduler.Services;

[JsonSerializable(typeof(AppConfig))]
[JsonSourceGenerationOptions(WriteIndented = true)]
internal partial class SchedulerJsonContext : JsonSerializerContext
{
}

public static class ConfigLoader
{
    private static readonly string ConfigPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "AutoRunManager", "config.json");

    public static AppConfig? Load()
    {
        try
        {
            if (!File.Exists(ConfigPath))
                return null;

            var json = File.ReadAllText(ConfigPath);
            return JsonSerializer.Deserialize(json, SchedulerJsonContext.Default.AppConfig);
        }
        catch (Exception ex)
        {
            Logger.Write($"配置读取失败: {ex.Message}");
            return null;
        }
    }
}
