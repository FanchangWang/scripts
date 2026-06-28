namespace AutoRunManager.Models;

public class AppConfig
{
    public int Version { get; set; } = 1;
    public List<StartupItem> Items { get; set; } = new();
}
