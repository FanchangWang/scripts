namespace AutoRunManager.Models;

public class StartupEntry
{
    public string Name { get; set; } = string.Empty;
    public string Path { get; set; } = string.Empty;
    public string Args { get; set; } = string.Empty;
    public bool IsEnabled { get; set; } = true;
    public bool IsDelayed { get; set; }
    public bool RunAsAdmin { get; set; }
    public string Source { get; set; } = string.Empty;
    public string SourceDetail { get; set; } = string.Empty;
    public string SourceKeyName { get; set; } = string.Empty;
    public string TaskFolder { get; set; } = string.Empty;
    public string TaskDelay { get; set; } = string.Empty;
    public string TaskTrigger { get; set; } = string.Empty;
}
