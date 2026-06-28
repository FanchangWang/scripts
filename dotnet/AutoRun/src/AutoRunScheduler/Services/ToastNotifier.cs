namespace AutoRunScheduler.Services;

public static class ToastNotifier
{
    private static NotifyIcon? _notifyIcon;

    public static void Initialize()
    {
        try
        {
            _notifyIcon = new NotifyIcon
            {
                Icon = SystemIcons.Information,
                Visible = true,
                Text = "AutoRun Scheduler"
            };
        }
        catch (Exception ex)
        {
            Logger.Write($"通知初始化失败: {ex.Message}");
        }
    }

    public static void Show(string title, string body)
    {
        try
        {
            _notifyIcon?.ShowBalloonTip(5000, title, body, ToolTipIcon.Error);
        }
        catch (Exception ex)
        {
            Logger.Write($"通知显示失败: {ex.Message}");
        }
    }

    public static void Cleanup()
    {
        _notifyIcon?.Dispose();
        _notifyIcon = null;
    }
}
