using System.Text.Json;
using AutoRunManager.Models;
using AutoRunManager.Services;
using Microsoft.Win32.TaskScheduler;

namespace AutoRunManager.Forms;

public partial class MainForm : Form
{
    private TabControl tabControl = null!;
    private TabPage tabRegistry = null!, tabStartupFolder = null!, tabScheduledTask = null!, tabDelayed = null!;
    private readonly Dictionary<string, DataGridView> registryGrids = new();
    private readonly Dictionary<string, DataGridView> folderGrids = new();
    private DataGridView taskGrid = null!, delayedGrid = null!;
    private AppConfig config = null!;

    public MainForm()
    {
        InitializeComponent();
        config = ConfigService.Load();
        Load += (_, _) => LoadAllData();
        Shown += (_, _) => CheckSchedulerTask();
        ApplyWindowSize();
    }

    private void CheckSchedulerTask()
    {
        try
        {
            using var ts = new TaskService();
            var exists = ts.AllTasks.Any(t =>
                t.Name.Equals("AutoRunScheduler", StringComparison.OrdinalIgnoreCase));

            if (exists) return;

            var result = MessageBox.Show(this,
                "检测到未创建开机调度计划任务。\n\n" +
                "AutoRunScheduler 需要通过计划任务在登录时触发，以实现延迟启动功能。\n\n" +
                "是否立即创建？",
                "计划任务", MessageBoxButtons.YesNo, MessageBoxIcon.Question);

            if (result == DialogResult.Yes)
                CreateSchedulerTask();
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"检测计划任务失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void ApplyWindowSize()
    {
        var screen = Screen.PrimaryScreen;
        if (screen != null)
        {
            var wa = screen.WorkingArea;
            Size = new Size((int)(wa.Width * 0.58), (int)(wa.Height * 0.65));
        }
        else
        {
            Size = new Size(1100, 700);
        }
    }

    private void InitializeComponent()
    {
        Text = "AutoRun Manager - 延迟启动管理器";
        StartPosition = FormStartPosition.CenterScreen;
        Size = new Size(1100, 700);

        tabControl = new TabControl { Dock = DockStyle.Fill, Parent = this };

        tabRegistry = new TabPage("注册表自启动");
        tabStartupFolder = new TabPage("启动文件夹");
        tabScheduledTask = new TabPage("计划任务");
        tabDelayed = new TabPage("延时启动");

        tabControl.TabPages.Add(tabRegistry);
        tabControl.TabPages.Add(tabStartupFolder);
        tabControl.TabPages.Add(tabScheduledTask);
        tabControl.TabPages.Add(tabDelayed);

        SetupRegistryTab();
        SetupStartupFolderTab();
        SetupScheduledTaskTab();
        SetupDelayedTab();
    }

    private static FlowLayoutPanel MakeRefreshBar()
    {
        var bar = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 30, FlowDirection = FlowDirection.RightToLeft };
        var btn = new Button { Text = "刷新列表", AutoSize = true, Margin = new Padding(0, 2, 4, 0) };
        btn.Click += (_, _) => Application.OpenForms.OfType<MainForm>().FirstOrDefault()?.LoadAllData();
        bar.Controls.Add(btn);
        return bar;
    }

    private void SetupRegistryTab()
    {
        var panel = new Panel { Dock = DockStyle.Fill, AutoScroll = true, Parent = tabRegistry };
        AddSection(panel, "HKEY_CURRENT_USER", "registry_hkcu", registryGrids);
        AddSection(panel, "HKEY_LOCAL_MACHINE", "registry_hklm", registryGrids);
        AddSection(panel, "HKLM WOW6432Node", "registry_wow", registryGrids);
        panel.Controls.Add(MakeRefreshBar());
    }

    private void SetupStartupFolderTab()
    {
        var panel = new Panel { Dock = DockStyle.Fill, AutoScroll = true, Parent = tabStartupFolder };
        AddSection(panel, "用户启动文件夹", "folder_user", folderGrids);
        AddSection(panel, "系统启动文件夹", "folder_system", folderGrids);
        panel.Controls.Add(MakeRefreshBar());
    }

    private void SetupScheduledTaskTab()
    {
        var panel = new Panel { Dock = DockStyle.Fill, AutoScroll = true, Parent = tabScheduledTask };
        var groupBox = new GroupBox { Text = "登录/开机触发计划任务", Dock = DockStyle.Top, Parent = panel };
        taskGrid = CreateTaskGrid();
        taskGrid.Parent = groupBox;
        taskGrid.Dock = DockStyle.Top;
        taskGrid.Tag = "scheduled_task";
        panel.Controls.Add(MakeRefreshBar());
    }

    private Panel _delayedPanel = null!;
    private Label _emptyHint = null!;

    private void SetupDelayedTab()
    {
        _delayedPanel = new Panel { Dock = DockStyle.Fill, AutoScroll = true, Parent = tabDelayed };

        _emptyHint = new Label
        {
            Text = "暂无延时启动条目\n点击上方「手动添加」或从其他标签页点击「延时启动」添加",
            TextAlign = ContentAlignment.MiddleCenter,
            Dock = DockStyle.Fill,
            ForeColor = SystemColors.GrayText,
            Font = new Font("Microsoft YaHei UI", 11),
            Parent = _delayedPanel
        };

        var groupBox = new GroupBox { Text = "延时启动列表", Dock = DockStyle.Top, Height = 30, Parent = _delayedPanel };
        delayedGrid = CreateBaseGrid();
        delayedGrid.Parent = groupBox;
        delayedGrid.Dock = DockStyle.Top;

        delayedGrid.Columns.Add(new DataGridViewTextBoxColumn { Name = "SortOrder", HeaderText = "排序", FillWeight = 5 });
        delayedGrid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Name", HeaderText = "应用名", FillWeight = 12 });
        delayedGrid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Path", HeaderText = "路径", FillWeight = 30 });
        delayedGrid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Delay", HeaderText = "延时", FillWeight = 6 });
        delayedGrid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Identity", HeaderText = "身份", FillWeight = 6 });
        delayedGrid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Source", HeaderText = "来源", FillWeight = 15 });

        delayedGrid.Columns.Add(new DataGridViewButtonColumn { Name = "OpEdit", HeaderText = "编辑", Text = "编辑", UseColumnTextForButtonValue = true, FillWeight = 5 });
        delayedGrid.Columns.Add(new DataGridViewButtonColumn { Name = "OpUp", HeaderText = "上移", Text = "上移", UseColumnTextForButtonValue = true, FillWeight = 4 });
        delayedGrid.Columns.Add(new DataGridViewButtonColumn { Name = "OpDown", HeaderText = "下移", Text = "下移", UseColumnTextForButtonValue = true, FillWeight = 4 });
        delayedGrid.Columns.Add(new DataGridViewButtonColumn { Name = "OpDelete", HeaderText = "删除", Text = "删除", UseColumnTextForButtonValue = true, FillWeight = 5 });

        delayedGrid.CellClick += OnDelayedCellClick;

        var btnBar = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 35, Parent = _delayedPanel };
        btnBar.Controls.Add(new Button { Text = "手动添加", AutoSize = true });
        btnBar.Controls.Add(new Button { Text = "导入配置", AutoSize = true });
        btnBar.Controls.Add(new Button { Text = "导出配置", AutoSize = true });
        btnBar.Controls.Add(new Button { Text = "创建计划任务", AutoSize = true });
        btnBar.Controls.Add(new Button { Text = "删除计划任务", AutoSize = true });

        foreach (Button btn in btnBar.Controls)
            btn.Click += DelayedToolbarClick;
    }

    private void DelayedToolbarClick(object? sender, EventArgs e)
    {
        if (sender is not Button btn) return;
        switch (btn.Text)
        {
            case "手动添加": ShowAddDialog(); break;
            case "导入配置": ImportConfig(); break;
            case "导出配置": ExportConfig(); break;
            case "创建计划任务": CreateSchedulerTask(); break;
            case "删除计划任务": DeleteSchedulerTask(); break;
        }
    }

    private void ShowAddDialog()
    {
        using var dlg = new AddItemDialog();
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        if (string.IsNullOrWhiteSpace(dlg.AppName) || string.IsNullOrWhiteSpace(dlg.AppPath)) return;

        var config = ConfigService.Load();
        var maxOrder = config.Items.Count > 0 ? config.Items.Max(i => i.SortOrder) : 0;

        config.Items.Add(new StartupItem
        {
            Name = dlg.AppName,
            Path = dlg.AppPath,
            Args = dlg.AppArgs,
            DelaySeconds = dlg.DelaySeconds,
            RunAsAdmin = dlg.RunAsAdmin,
            SortOrder = maxOrder + 1,
            Source = "manual",
            SourceDetail = ""
        });

        ConfigService.Save(config);
        this.config = config;
        RefreshDelayedGrid();
    }

    private void ImportConfig()
    {
        using var dlg = new OpenFileDialog { Filter = "JSON 配置|*.json", Title = "导入延时启动配置" };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;

        try
        {
            var json = File.ReadAllText(dlg.FileName);
            var imported = JsonSerializer.Deserialize<AppConfig>(json);
            if (imported == null) return;

            config = ConfigService.Load();
            foreach (var item in imported.Items)
            {
                if (!config.Items.Any(i => i.Name == item.Name && i.Source == item.Source))
                    config.Items.Add(item);
            }

            ConfigService.Save(config);
            this.config = ConfigService.Load();
            LoadAllData();
            MessageBox.Show(this, "配置导入成功。", "导入成功", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"导入失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void ExportConfig()
    {
        using var dlg = new SaveFileDialog { Filter = "JSON 配置|*.json", FileName = "AutoRunConfig.json", Title = "导出延时启动配置" };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;

        try
        {
            var json = JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(dlg.FileName, json);
            MessageBox.Show(this, "配置导出成功。", "导出成功", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"导出失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void CreateSchedulerTask()
    {
        var exePath = Path.Combine(AppContext.BaseDirectory, "AutoRunScheduler.exe");
        if (!File.Exists(exePath))
        {
            MessageBox.Show(this, $"未找到调度端程序: {exePath}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        var psi = new System.Diagnostics.ProcessStartInfo("schtasks")
        {
            Arguments = $"/create /tn \"AutoRunScheduler\" /tr \"\\\"{exePath}\\\"\" /sc onlogon /delay 0000:03 /rl HIGHEST /f",
            UseShellExecute = false,
            CreateNoWindow = true
        };

        try
        {
            using var proc = System.Diagnostics.Process.Start(psi);
            proc?.WaitForExit();
            if (proc?.ExitCode == 0)
                MessageBox.Show(this, "计划任务创建成功。\n任务名: AutoRunScheduler\n触发器: 登录时延迟 3 秒", "成功", MessageBoxButtons.OK, MessageBoxIcon.Information);
            else
                MessageBox.Show(this, "计划任务创建失败。", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"创建计划任务失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void DeleteSchedulerTask()
    {
        var result = MessageBox.Show(this, "确定要删除 AutoRunScheduler 计划任务吗？", "确认删除", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
        if (result != DialogResult.Yes) return;

        var psi = new System.Diagnostics.ProcessStartInfo("schtasks")
        {
            Arguments = "/delete /tn \"AutoRunScheduler\" /f",
            UseShellExecute = false,
            CreateNoWindow = true
        };

        try
        {
            using var proc = System.Diagnostics.Process.Start(psi);
            proc?.WaitForExit();
            if (proc?.ExitCode == 0)
                MessageBox.Show(this, "计划任务已删除。", "成功", MessageBoxButtons.OK, MessageBoxIcon.Information);
            else
                MessageBox.Show(this, "计划任务删除失败。", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"删除计划任务失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void OnDelayedCellClick(object? sender, DataGridViewCellEventArgs e)
    {
        if (sender is not DataGridView grid || e.RowIndex < 0 || grid.Rows[e.RowIndex].Tag is not StartupItem item) return;

        switch (grid.Columns[e.ColumnIndex].Name)
        {
            case "OpEdit": EditItem(item); break;
            case "OpDelete": DeleteItem(item); break;
            case "OpUp": MoveItem(item, -1); break;
            case "OpDown": MoveItem(item, 1); break;
        }
    }

    private void EditItem(StartupItem item)
    {
        using var dlg = new EditItemDialog(item);
        if (dlg.ShowDialog(this) != DialogResult.OK) return;

        item.Name = dlg.AppName;
        item.Path = dlg.AppPath;
        item.Args = dlg.AppArgs;
        item.DelaySeconds = dlg.DelaySeconds;
        item.RunAsAdmin = dlg.RunAsAdmin;

        ConfigService.Save(config);
        RefreshDelayedGrid();
    }

    private void DeleteItem(StartupItem item)
    {
        var result = MessageBox.Show(this, $"确定要从延时启动中移除\"{item.Name}\"吗？\n原始启动项将被恢复。", "确认删除", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
        if (result != DialogResult.Yes) return;

        RestoreOriginalStartup(item);
        config.Items.Remove(item);
        ReorderItems();
        ConfigService.Save(config);
        RefreshDelayedGrid();
        ReloadOriginalTabs();
    }

    private void MoveItem(StartupItem item, int direction)
    {
        var sorted = config.Items.OrderBy(i => i.DelaySeconds).ThenBy(i => i.SortOrder).ToList();
        var idx = sorted.FindIndex(i => i.Id == item.Id);
        if (idx < 0) return;

        var swapIdx = idx + direction;
        if (swapIdx < 0 || swapIdx >= sorted.Count) return;

        var other = sorted[swapIdx];
        if (item.DelaySeconds != other.DelaySeconds) return;

        (item.SortOrder, other.SortOrder) = (other.SortOrder, item.SortOrder);

        ConfigService.Save(config);
        RefreshDelayedGrid();
    }

    private void RestoreOriginalStartup(StartupItem item)
    {
        switch (item.Source)
        {
            case "registry":
                RegistryService.Enable(item.SourceKeyName, item.SourceDetail);
                break;
            case "startup_folder":
                var backupType = item.SourceDetail.Contains("系统") ? "system" : "user";
                StartupFolderService.Enable(item.SourceKeyName, backupType);
                break;
            case "scheduled_task":
                ScheduledTaskService.Enable(item.SourceKeyName);
                break;
        }
    }

    private void ReloadOriginalTabs()
    {
        LoadRegistryData();
        LoadStartupFolderData();
        LoadScheduledTaskData();
    }

    private static DataGridView CreateBaseGrid()
    {
        return new DataGridView
        {
            AllowUserToAddRows = false,
            AllowUserToDeleteRows = false,
            AllowUserToResizeRows = false,
            ReadOnly = true,
            RowHeadersVisible = false,
            SelectionMode = DataGridViewSelectionMode.FullRowSelect,
            AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill,
            BackgroundColor = SystemColors.Window,
            BorderStyle = BorderStyle.Fixed3D,
            ScrollBars = ScrollBars.None
        };
    }

    private DataGridView CreateStandardGrid()
    {
        var grid = CreateBaseGrid();
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Name", HeaderText = "应用名", FillWeight = 15 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Path", HeaderText = "命令/路径", FillWeight = 45 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Status", HeaderText = "状态", FillWeight = 7 });
        grid.Columns.Add(new DataGridViewButtonColumn { Name = "DelayOp", HeaderText = "延迟操作", FillWeight = 10 });
        grid.Columns.Add(new DataGridViewButtonColumn { Name = "ToggleOp", HeaderText = "启用/禁用", FillWeight = 10 });
        grid.CellClick += OnGridCellClick;
        return grid;
    }

    private DataGridView CreateTaskGrid()
    {
        var grid = CreateBaseGrid();
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Name", HeaderText = "应用名", FillWeight = 12 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Path", HeaderText = "命令/路径", FillWeight = 22 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Folder", HeaderText = "文件夹", FillWeight = 12 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Trigger", HeaderText = "执行时机", FillWeight = 6 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "RunLevel", HeaderText = "启动身份", FillWeight = 6 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Delay", HeaderText = "延时时间", FillWeight = 7 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Status", HeaderText = "状态", FillWeight = 5 });
        grid.Columns.Add(new DataGridViewButtonColumn { Name = "DelayOp", HeaderText = "延迟操作", FillWeight = 8 });
        grid.Columns.Add(new DataGridViewButtonColumn { Name = "ToggleOp", HeaderText = "启用/禁用", FillWeight = 8 });
        grid.CellClick += OnGridCellClick;
        return grid;
    }

    private void AddSection(Panel parent, string title, string tag, Dictionary<string, DataGridView> gridDict)
    {
        var groupBox = new GroupBox { Text = title, Dock = DockStyle.Top, Height = 30, Parent = parent };
        var grid = CreateStandardGrid();
        grid.Parent = groupBox;
        grid.Dock = DockStyle.Top;
        grid.Tag = tag;
        gridDict[tag] = grid;
    }

    private static string FormatPathWithArgs(string path, string args)
    {
        if (string.IsNullOrWhiteSpace(args))
            return path;
        var quoted = path.Contains(' ') ? $"\"{path}\"" : path;
        return $"{quoted} {args}";
    }

    private static string IdentityText(bool runAsAdmin) => runAsAdmin ? "Admin" : "User";

    private static void ResizeGridToContent(DataGridView grid)
    {
        if (grid.Columns.Count == 0) return;

        var h = grid.ColumnHeadersVisible ? grid.ColumnHeadersHeight : 0;
        foreach (DataGridViewRow row in grid.Rows)
            if (row.Visible)
                h += row.Height;

        h += 2;
        grid.Height = h;

        if (grid.Parent is GroupBox gb)
        {
            var pad = TextRenderer.MeasureText("X", gb.Font).Height + 6;
            gb.Height = h + pad;
        }
    }

    private void OnGridCellClick(object? sender, DataGridViewCellEventArgs e)
    {
        if (sender is not DataGridView grid || e.RowIndex < 0) return;
        if (grid.Rows[e.RowIndex].Tag is not StartupEntry entry) return;

        var col = grid.Columns[e.ColumnIndex].Name;

        switch (col)
        {
            case "DelayOp":
                HandleDelayOp(entry);
                break;
            case "ToggleOp":
                HandleToggleOp(entry);
                break;
            default:
                return;
        }

        LoadAllData();
    }

    private void HandleDelayOp(StartupEntry entry)
    {
        if (entry.IsDelayed)
        {
            RemoveFromDelay(entry);
            return;
        }

        var cfg = ConfigService.Load();
        using var dlg = new DelayStartDialog(entry);
        if (dlg.ShowDialog(this) != DialogResult.OK) return;

        var maxOrder = cfg.Items.Count > 0 ? cfg.Items.Max(i => i.SortOrder) : 0;
        cfg.Items.Add(new StartupItem
        {
            Name = dlg.AppName,
            Path = dlg.AppPath,
            Args = dlg.AppArgs,
            DelaySeconds = dlg.DelaySeconds,
            RunAsAdmin = dlg.RunAsAdmin,
            SortOrder = maxOrder + 1,
            Source = entry.Source,
            SourceDetail = entry.SourceDetail,
            SourceKeyName = entry.SourceKeyName
        });

        ConfigService.Save(cfg);
        DisableEntry(entry);
    }

    private void HandleToggleOp(StartupEntry entry)
    {
        if (entry.IsDelayed) return;

        if (entry.IsEnabled)
        {
            DisableEntry(entry);
            entry.IsEnabled = false;
        }
        else
        {
            EnableEntry(entry);
            entry.IsEnabled = true;
        }
    }

    private void RefreshGridRow(DataGridView grid, int rowIndex, StartupEntry entry)
    {
        entry.IsDelayed = ConfigService.IsItemDelayed(entry.Name, entry.Source, config);
        var row = grid.Rows[rowIndex];
        row.Cells["Status"].Value = entry.IsDelayed ? "已延时" : entry.IsEnabled ? "启用" : "禁用";
        row.Cells["DelayOp"].Value = entry.IsDelayed ? "移出延时" : "延时启动";
        row.Cells["ToggleOp"].Value = entry.IsDelayed ? "" : entry.IsEnabled ? "禁用" : "启用";
        if (row.Cells["ToggleOp"] is DataGridViewButtonCell toggle)
        {
            toggle.ReadOnly = entry.IsDelayed;
            toggle.Style.ForeColor = entry.IsDelayed ? SystemColors.GrayText : SystemColors.ControlText;
        }
    }

    private void RemoveFromDelay(StartupEntry entry)
    {
        var cfg = ConfigService.Load();
        var item = cfg.Items.FirstOrDefault(i =>
            i.Name.Equals(entry.Name, StringComparison.OrdinalIgnoreCase) &&
            i.Source.Equals(entry.Source, StringComparison.OrdinalIgnoreCase));

        if (item == null) return;

        RestoreOriginalStartup(item);
        cfg.Items.Remove(item);
        ConfigService.Save(cfg);
        ReloadOriginalTabs();
    }

    private static void DisableEntry(StartupEntry entry)
    {
        switch (entry.Source)
        {
            case "registry":
                RegistryService.Disable(entry.SourceKeyName, entry.SourceDetail);
                break;
            case "startup_folder":
                {
                    var backupType = entry.SourceDetail.Contains("系统") ? "system" : "user";
                    StartupFolderService.Disable(entry.SourceKeyName, backupType);
                }
                break;
            case "scheduled_task":
                ScheduledTaskService.Disable(entry.SourceKeyName);
                break;
        }
    }

    private static void EnableEntry(StartupEntry entry)
    {
        switch (entry.Source)
        {
            case "registry":
                RegistryService.Enable(entry.SourceKeyName, entry.SourceDetail);
                break;
            case "startup_folder":
                {
                    var backupType = entry.SourceDetail.Contains("系统") ? "system" : "user";
                    StartupFolderService.Enable(entry.SourceKeyName, backupType);
                }
                break;
            case "scheduled_task":
                ScheduledTaskService.Enable(entry.SourceKeyName);
                break;
        }
    }

    private void LoadAllData()
    {
        config = ConfigService.Load();
        LoadRegistryData();
        LoadStartupFolderData();
        LoadScheduledTaskData();
        RefreshDelayedGrid();
    }

    private void LoadRegistryData()
    {
        try
        {
            var all = RegistryService.Enumerate();

            PopulateStandardGrid(all.Where(e => e.SourceDetail.StartsWith("HKCU")), "registry_hkcu");
            PopulateStandardGrid(all.Where(e => e.SourceDetail.StartsWith("HKLM") && !e.SourceDetail.Contains("WOW")), "registry_hklm");
            PopulateStandardGrid(all.Where(e => e.SourceDetail.Contains("WOW")), "registry_wow");
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"注册表读取失败: {ex.Message}", "错误");
        }
    }

    private void LoadStartupFolderData()
    {
        try
        {
            var all = StartupFolderService.Enumerate();
            PopulateStandardGrid(all.Where(e => e.SourceDetail.Contains("用户")), "folder_user");
            PopulateStandardGrid(all.Where(e => e.SourceDetail.Contains("系统")), "folder_system");
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"启动文件夹读取失败: {ex.Message}", "错误");
        }
    }

    private void LoadScheduledTaskData()
    {
        try
        {
            var entries = ScheduledTaskService.Enumerate();
            taskGrid.Rows.Clear();

            foreach (var entry in entries)
            {
                entry.IsDelayed = ConfigService.IsItemDelayed(entry.Name, entry.Source, config);
                var idx = taskGrid.Rows.Add(
                    entry.Name,
                    FormatPathWithArgs(entry.Path, entry.Args),
                    entry.TaskFolder,
                    entry.TaskTrigger,
                    IdentityText(entry.RunAsAdmin),
                    entry.TaskDelay,
                    entry.IsDelayed ? "已延时" : entry.IsEnabled ? "启用" : "禁用",
                    entry.IsDelayed ? "移出延时" : "延时启动",
                    entry.IsDelayed ? "" : entry.IsEnabled ? "禁用" : "启用");
                var row = taskGrid.Rows[idx];
                row.Tag = entry;
                if (row.Cells["ToggleOp"] is DataGridViewButtonCell toggle)
                {
                    toggle.ReadOnly = entry.IsDelayed;
                    toggle.Style.ForeColor = entry.IsDelayed ? SystemColors.GrayText : SystemColors.ControlText;
                }
            }

            ResizeGridToContent(taskGrid);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"计划任务读取失败: {ex.Message}", "错误");
        }
    }

    private void RefreshDelayedGrid()
    {
        delayedGrid.Rows.Clear();
        var sorted = config.Items.OrderBy(i => i.DelaySeconds).ThenBy(i => i.SortOrder).ToList();

        var hasItems = sorted.Count > 0;
        _emptyHint.Visible = !hasItems;
        if (delayedGrid.Parent is GroupBox gb)
            gb.Visible = hasItems;

        for (var i = 0; i < sorted.Count; i++)
        {
            var item = sorted[i];
            var idx = delayedGrid.Rows.Add(
                i + 1,
                item.Name,
                FormatPathWithArgs(item.Path, item.Args),
                $"{item.DelaySeconds}s",
                IdentityText(item.RunAsAdmin),
                DescribeSource(item));
            delayedGrid.Rows[idx].Tag = item;
        }

        ResizeGridToContent(delayedGrid);
    }

    private static string DescribeSource(StartupItem item)
    {
        return item.Source switch
        {
            "registry" => item.SourceDetail switch
            {
                var d when d.StartsWith("HKCU") => "注册表 HKEY_CURRENT_USER",
                var d when d.Contains("WOW") => "注册表 HKLM WOW6432Node",
                var d when d.StartsWith("HKLM") => "注册表 HKEY_LOCAL_MACHINE",
                _ => item.SourceDetail
            },
            "startup_folder" => item.SourceDetail.Contains("系统") ? "系统启动文件夹" : "用户启动文件夹",
            "scheduled_task" => "计划任务",
            "manual" => "手动添加",
            _ => item.Source
        };
    }

    private void ReorderItems()
    {
        for (var i = 0; i < config.Items.Count; i++)
            config.Items[i].SortOrder = i + 1;
    }

    private void PopulateStandardGrid(IEnumerable<StartupEntry> entries, string tag)
    {
        var grid = registryGrids.GetValueOrDefault(tag) ?? folderGrids.GetValueOrDefault(tag);
        if (grid == null) return;

        grid.Rows.Clear();

        var list = entries.ToList();
        foreach (var entry in list)
        {
            entry.IsDelayed = ConfigService.IsItemDelayed(entry.Name, entry.Source, config);
            var idx = grid.Rows.Add(
                entry.Name,
                FormatPathWithArgs(entry.Path, entry.Args),
                entry.IsDelayed ? "已延时" : entry.IsEnabled ? "启用" : "禁用",
                entry.IsDelayed ? "移出延时" : "延时启动",
                entry.IsDelayed ? "" : entry.IsEnabled ? "禁用" : "启用");
            var row = grid.Rows[idx];
            row.Tag = entry;
            if (row.Cells["ToggleOp"] is DataGridViewButtonCell toggle)
            {
                toggle.ReadOnly = entry.IsDelayed;
                toggle.Style.ForeColor = entry.IsDelayed ? SystemColors.GrayText : SystemColors.ControlText;
            }
        }

        if (grid.Parent is GroupBox gb)
            gb.Visible = list.Count > 0;

        ResizeGridToContent(grid);
    }
}
