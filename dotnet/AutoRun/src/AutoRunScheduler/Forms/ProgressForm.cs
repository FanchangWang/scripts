using AutoRunManager.Models;
using AutoRunScheduler.Services;

namespace AutoRunScheduler.Forms;

public partial class ProgressForm : Form
{
    private readonly TableLayoutPanel statusTable;
    private readonly Label progressLabel;
    private readonly Button closeButton;
    private readonly List<(string Name, Label StatusLabel, Label InfoLabel)> itemRows = new();
    private readonly SchedulerEngine engine = new();
    private readonly CancellationTokenSource cts = new();
    private int totalCount;
    private int completedCount;
    private DateTime overallStart;

    public ProgressForm()
    {
        Text = "AutoRun Manager - 延迟启动进度";
        FormBorderStyle = FormBorderStyle.None;
        TopMost = false;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        Size = new Size(480, 400);
        Opacity = 0.95;
        BackColor = Color.FromArgb(32, 32, 32);
        ForeColor = Color.White;

        closeButton = new Button
        {
            Text = "×",
            FlatStyle = FlatStyle.Flat,
            FlatAppearance = { BorderSize = 0 },
            ForeColor = Color.White,
            BackColor = Color.Transparent,
            Font = new Font("Segoe UI", 14, FontStyle.Bold),
            Size = new Size(36, 36),
            Location = new Point(Width - 42, 6),
            Cursor = Cursors.Hand,
        };
        closeButton.Click += (_, _) => cts.Cancel();
        closeButton.FlatAppearance.MouseOverBackColor = Color.FromArgb(64, 64, 64);

        var titleLabel = new Label
        {
            Text = "AutoRun Manager - 延迟启动进度",
            Font = new Font("Segoe UI", 11, FontStyle.Bold),
            ForeColor = Color.White,
            Location = new Point(16, 10),
            AutoSize = true,
        };

        statusTable = new TableLayoutPanel
        {
            Location = new Point(16, 48),
            Width = Width - 32,
            Height = Height - 110,
            AutoScroll = true,
            BackColor = Color.Transparent,
            ColumnCount = 2,
        };
        statusTable.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 60));
        statusTable.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 40));

        progressLabel = new Label
        {
            Text = "准备中...",
            Font = new Font("Segoe UI", 10),
            ForeColor = Color.LightGray,
            Location = new Point(16, Height - 50),
            AutoSize = true,
        };

        Controls.Add(closeButton);
        Controls.Add(titleLabel);
        Controls.Add(statusTable);
        Controls.Add(progressLabel);

        var regionPath = new System.Drawing.Drawing2D.GraphicsPath();
        regionPath.AddArc(0, 0, 16, 16, 180, 90);
        regionPath.AddArc(Width - 16, 0, 16, 16, 270, 90);
        regionPath.AddArc(Width - 16, Height - 16, 16, 16, 0, 90);
        regionPath.AddArc(0, Height - 16, 16, 16, 90, 90);
        Region = new Region(regionPath);
    }

    protected override void OnLoad(EventArgs e)
    {
        base.OnLoad(e);
        var wa = Screen.PrimaryScreen?.WorkingArea;
        if (wa.HasValue)
            Location = new Point(wa.Value.Right - Width - 12, wa.Value.Bottom - Height - 12);
        _ = RunSchedulerAsync();
    }

    private async Task RunSchedulerAsync()
    {
        var config = ConfigLoader.Load();
        if (config == null || config.Items.Count == 0)
        {
            UpdateProgress("未找到配置或配置为空");
            await Task.Delay(2000);
            Close();
            return;
        }

        var sorted = config.Items.OrderBy(i => i.DelaySeconds).ThenBy(i => i.SortOrder).ToList();
        totalCount = sorted.Count;
        overallStart = DateTime.UtcNow;
        SetupStatusRows(sorted);
        ToastNotifier.Initialize();

        engine.ItemStarted += OnItemStarted;
        engine.AllCompleted += OnAllCompleted;

        try
        {
            await engine.RunAsync(config.Items, cts.Token);
        }
        catch (OperationCanceledException)
        {
            Logger.Write("用户取消调度");
        }
        finally
        {
            engine.ItemStarted -= OnItemStarted;
            engine.AllCompleted -= OnAllCompleted;
        }
    }

    private void SetupStatusRows(List<StartupItem> items)
    {
        statusTable.SuspendLayout();
        statusTable.RowCount = items.Count + 1;

        statusTable.Controls.Add(new Label
        {
            Text = "应用",
            Font = new Font("Segoe UI", 10, FontStyle.Bold),
            ForeColor = Color.LightGray,
        }, 0, 0);
        statusTable.Controls.Add(new Label
        {
            Text = "状态",
            Font = new Font("Segoe UI", 10, FontStyle.Bold),
            ForeColor = Color.LightGray,
        }, 1, 0);

        for (var i = 0; i < items.Count; i++)
        {
            var item = items[i];
            var infoLabel = new Label
            {
                Text = $"{item.Name}  (等待 {item.DelaySeconds}s)",
                ForeColor = Color.White,
                AutoSize = true,
                Padding = new Padding(0, 2, 0, 2),
            };
            var statusLabel = new Label
            {
                Text = "⏳ 等待中",
                ForeColor = Color.LightGray,
                AutoSize = true,
                Padding = new Padding(0, 2, 0, 2),
            };

            statusTable.Controls.Add(infoLabel, 0, i + 1);
            statusTable.Controls.Add(statusLabel, 1, i + 1);
            itemRows.Add((item.Name, statusLabel, infoLabel));

            // Set row style for spacing
            statusTable.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        }

        statusTable.ResumeLayout();
    }

    private void OnItemStarted(string name, bool success)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => OnItemStarted(name, success));
            return;
        }

        completedCount++;
        var row = itemRows.Find(r => r.Name == name);
        if (row.Name == null) return;

        if (success)
        {
            row.StatusLabel.Text = "✅ 已启动";
            row.StatusLabel.ForeColor = Color.LimeGreen;
        }
        else
        {
            row.StatusLabel.Text = "❌ 启动失败";
            row.StatusLabel.ForeColor = Color.Red;
        }

        UpdateProgress($"进度: {completedCount}/{totalCount}");
    }

    private void UpdateProgress(string text)
    {
        progressLabel.Text = text;
    }

    private void OnAllCompleted()
    {
        if (InvokeRequired)
        {
            BeginInvoke(OnAllCompleted);
            return;
        }

        UpdateProgress($"已全部启动，窗口即将关闭 (总计 {totalCount} 项)");
        Logger.Write($"调度完成：{completedCount}/{totalCount} 项成功");

        _ = AutoCloseAsync();
    }

    private async Task AutoCloseAsync()
    {
        for (var i = 3; i > 0; i--)
        {
            UpdateProgress($"已全部启动，窗口 {i}s 后关闭");
            await Task.Delay(1000);
        }
        Close();
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        cts.Cancel();
        base.OnFormClosed(e);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        using var pen = new Pen(Color.FromArgb(64, 64, 64), 1);
        e.Graphics.DrawRectangle(pen, 0, 0, Width - 1, Height - 1);
    }
}
