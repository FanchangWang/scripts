using AutoRunScheduler.Forms;
using AutoRunScheduler.Services;

namespace AutoRunScheduler;

static class Program
{
    private static readonly Mutex InstanceMutex = new(true, "AutoRunManager.Scheduler");

    [STAThread]
    static void Main()
    {
        if (!InstanceMutex.WaitOne(TimeSpan.Zero, true))
        {
            Logger.Write("已有实例运行，退出");
            return;
        }

        try
        {
            Logger.Write("调度端启动");
            ApplicationConfiguration.Initialize();
            Application.Run(new ProgressForm());
            Logger.Write("调度端正常退出");
        }
        catch (Exception ex)
        {
            Logger.Write($"调度端异常: {ex}");
        }
        finally
        {
            InstanceMutex.ReleaseMutex();
        }
    }
}
