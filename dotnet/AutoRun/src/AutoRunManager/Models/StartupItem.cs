namespace AutoRunManager.Models;

public class StartupItem
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string Name { get; set; } = string.Empty;
    public string Path { get; set; } = string.Empty;
    public string Args { get; set; } = string.Empty;
    public int DelaySeconds { get; set; }
    public bool RunAsAdmin { get; set; }
    public int SortOrder { get; set; }
    public string Source { get; set; } = string.Empty;
    public string SourceDetail { get; set; } = string.Empty;
    public string SourceKeyName { get; set; } = string.Empty;
}
