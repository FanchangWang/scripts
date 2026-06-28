using AutoRunManager.Models;

namespace AutoRunScheduler.Services;

public class SchedulerEngine
{
    public event Action<string, bool>? ItemStarted;
    public event Action? AllCompleted;

    public async Task RunAsync(List<StartupItem> items, CancellationToken cancellationToken = default)
    {
        var sorted = items
            .OrderBy(i => i.DelaySeconds)
            .ThenBy(i => i.SortOrder)
            .ToList();

        var overallStart = DateTime.UtcNow;

        foreach (var item in sorted)
        {
            cancellationToken.ThrowIfCancellationRequested();

            var elapsed = (int)(DateTime.UtcNow - overallStart).TotalSeconds;
            var remaining = Math.Max(0, item.DelaySeconds - elapsed);

            if (remaining > 0)
            {
                Logger.Write($"等待 {remaining}s 后启动 [{item.Name}]");
                await Task.Delay(TimeSpan.FromSeconds(remaining), cancellationToken);
            }

            Logger.Write($"正在启动 [{item.Name}]...");
            var success = ProcessLauncher.Start(item);

            ItemStarted?.Invoke(item.Name, success);

            if (!success)
            {
                ToastNotifier.Show("启动失败", $"{item.Name}\n{item.Path}");
            }
        }

        AllCompleted?.Invoke();
    }
}
