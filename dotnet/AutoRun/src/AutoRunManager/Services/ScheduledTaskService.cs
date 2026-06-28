using AutoRunManager.Models;
using Microsoft.Win32.TaskScheduler;
using TaskService = Microsoft.Win32.TaskScheduler.TaskService;

namespace AutoRunManager.Services;

public class ScheduledTaskService
{
    public static List<StartupEntry> Enumerate()
    {
        var entries = new List<StartupEntry>();

        using var ts = new TaskService();
        var tasks = ts.AllTasks.Where(t =>
        {
            try
            {
                return t.Definition.Triggers.Any(tr =>
                    tr is LogonTrigger || tr is BootTrigger)
                    && (t.Folder?.Path?.StartsWith("\\Microsoft") != true)
                    && !t.Name.Equals("AutoRunScheduler", StringComparison.OrdinalIgnoreCase);
            }
            catch
            {
                return false;
            }
        });

        foreach (var task in tasks)
        {
            try
            {
                var def = task.Definition;
                var trigger = def.Triggers.FirstOrDefault(tr =>
                    tr is LogonTrigger || tr is BootTrigger);

                var folder = task.Folder?.Path ?? "\\";
                var runAsAdmin = def.Principal?.RunLevel == TaskRunLevel.Highest;
                var triggerDelay = trigger switch
                {
                    LogonTrigger lt => lt.Delay,
                    BootTrigger bt => bt.Delay,
                    _ => TimeSpan.Zero
                };
                var delayDesc = FormatDelay(triggerDelay);
                var triggerDesc = trigger switch
                {
                    LogonTrigger => "登录时",
                    BootTrigger => "启动时",
                    _ => ""
                };
                var actionPath = GetActionPath(def);
                var actionArgs = GetActionArgs(def);

                entries.Add(new StartupEntry
                {
                    Name = task.Name,
                    Path = actionPath,
                    Args = actionArgs,
                    IsEnabled = task.Enabled,
                    RunAsAdmin = runAsAdmin,
                    Source = "scheduled_task",
                    SourceDetail = task.Path,
                    SourceKeyName = task.Path,
                    TaskFolder = folder,
                    TaskDelay = delayDesc,
                    TaskTrigger = triggerDesc
                });
            }
            catch
            {
            }
        }

        return entries;
    }

    public static void Disable(string taskName)
    {
        using var ts = new TaskService();
        var task = ts.GetTask(taskName);
        if (task != null)
            task.Enabled = false;
    }

    public static void Enable(string taskName)
    {
        using var ts = new TaskService();
        var task = ts.GetTask(taskName);
        if (task != null)
            task.Enabled = true;
    }

    public static string GetTaskFolderPath(string taskPath)
    {
        var idx = taskPath.LastIndexOf('\\');
        return idx > 0 ? taskPath[..idx] : "\\";
    }

    private static string FormatDelay(TimeSpan? delay)
    {
        if (delay == null || delay.Value == TimeSpan.Zero)
            return "无";

        if (delay.Value.Days > 0)
            return $"{(int)delay.Value.TotalDays}天";

        if (delay.Value.TotalHours >= 1)
        {
            var hours = (int)delay.Value.TotalHours;
            var mins = delay.Value.Minutes;
            return mins > 0 ? $"{hours}时{mins}分" : $"{hours}时";
        }

        if (delay.Value.TotalMinutes >= 1)
            return $"{(int)delay.Value.TotalMinutes}分";

        return $"{(int)delay.Value.TotalSeconds}s";
    }

    private static string GetActionPath(TaskDefinition def)
    {
        try
        {
            if (def.Actions.Count > 0 && def.Actions[0] is ExecAction exec)
                return exec.Path;
        }
        catch { }
        return string.Empty;
    }

    private static string GetActionArgs(TaskDefinition def)
    {
        try
        {
            if (def.Actions.Count > 0 && def.Actions[0] is ExecAction exec)
                return exec.Arguments;
        }
        catch { }
        return string.Empty;
    }
}
