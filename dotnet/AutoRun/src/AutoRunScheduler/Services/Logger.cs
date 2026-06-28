namespace AutoRunScheduler.Services;

public static class Logger
{
    private static readonly string LogPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "AutoRunManager", "scheduler.log");

    private static readonly object Lock = new();

    static Logger()
    {
        var dir = Path.GetDirectoryName(LogPath);
        if (dir != null) Directory.CreateDirectory(dir);
    }

    public static void Write(string message)
    {
        lock (Lock)
        {
            try
            {
                var line = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {message}";
                File.AppendAllText(LogPath, line + Environment.NewLine);
            }
            catch
            {
            }
        }
    }
}
