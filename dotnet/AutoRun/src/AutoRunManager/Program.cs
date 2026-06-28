using AutoRunManager.Forms;

namespace AutoRunManager;

static class Program
{
    [STAThread]
    static void Main()
    {
        try
        {
            ApplicationConfiguration.Initialize();
            Application.Run(new MainForm());
        }
        catch (Exception ex)
        {
            var log = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "AutoRunManager", "crash.log");
            Directory.CreateDirectory(Path.GetDirectoryName(log)!);
            File.WriteAllText(log, $"{DateTime.Now}: {ex}\n");
            throw;
        }
    }
}
