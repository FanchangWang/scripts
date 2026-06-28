namespace AutoRunManager.Forms;

public class AddItemDialog : Form
{
    private readonly TextBox nameTextBox;
    private readonly TextBox pathTextBox;
    private readonly TextBox argsTextBox;
    private readonly ComboBox delayCombo;
    private readonly TextBox delayCustom;
    private readonly CheckBox runAsAdminCheck;

    public string AppName => nameTextBox.Text.Trim();
    public string AppPath => pathTextBox.Text.Trim();
    public string AppArgs => argsTextBox.Text.Trim();
    public int DelaySeconds
    {
        get
        {
            if (delayCombo.SelectedItem?.ToString() == "自定义")
                if (int.TryParse(delayCustom.Text, out var customVal))
                    return customVal;
            return delayCombo.SelectedItem?.ToString() switch
            {
                "0s" => 0,
                "10s" => 10,
                "30s" => 30,
                "60s" => 60,
                _ => 0
            };
        }
    }
    public bool RunAsAdmin => runAsAdminCheck.Checked;

    public AddItemDialog()
    {
        Text = "手动添加延时启动";
        Size = new Size(520, 310);
        MinimumSize = new Size(480, 280);
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = FormBorderStyle.Sizable;

        var lx = 12;
        var lw = 80;
        var cx = lx + lw + 6;
        const int rh = 32;
        const int gap = 4;
        var y = gap;

        Label MakeLbl(string text)
        {
            return new Label { Text = text, Left = lx, Top = y + 2, Width = lw, Height = rh - 4, TextAlign = ContentAlignment.MiddleLeft };
        }

        void PlaceCtrl(Control c, int h = 24)
        {
            c.Left = cx; c.Top = y + (rh - h) / 2; c.Width = ClientSize.Width - cx - 12; c.Anchor = AnchorStyles.Left | AnchorStyles.Top | AnchorStyles.Right;
            if (c is TextBox tb) tb.Height = h;
            Controls.Add(c);
        }

        nameTextBox = new TextBox { Text = "" };
        Controls.Add(MakeLbl("应用名:")); PlaceCtrl(nameTextBox); y += rh + gap;

        pathTextBox = new TextBox { Text = "" };
        var browseBtn = new Button { Text = "浏览...", Width = 70, Height = 24 };
        browseBtn.Left = cx; browseBtn.Top = y + (rh - 24) / 2;
        browseBtn.Click += (_, _) =>
        {
            using var dlg = new OpenFileDialog
            {
                Filter = "可执行文件|*.exe;*.msi;*.lnk|所有文件|*.*",
                Title = "选择要延迟启动的程序"
            };
            if (dlg.ShowDialog() == DialogResult.OK)
            {
                pathTextBox.Text = dlg.FileName;
                if (string.IsNullOrWhiteSpace(nameTextBox.Text))
                    nameTextBox.Text = Path.GetFileNameWithoutExtension(dlg.FileName);
            }
        };
        PathAdjustWidth();
        Controls.Add(MakeLbl("路径:"));
        Controls.Add(browseBtn);
        Controls.Add(pathTextBox);
        y += rh + gap;

        argsTextBox = new TextBox { Text = "" };
        Controls.Add(MakeLbl("参数:")); PlaceCtrl(argsTextBox); y += rh + gap;

        Controls.Add(MakeLbl("延时时间:"));
        delayCombo = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Items = { "0s", "10s", "30s", "60s", "自定义" }, Width = 100 };
        delayCombo.SelectedIndex = 0;
        delayCombo.Left = cx; delayCombo.Top = y + 2;
        delayCustom = new TextBox { Enabled = false, PlaceholderText = "秒", Width = 70 };
        delayCustom.Left = cx + 108; delayCustom.Top = y + 2;
        delayCombo.SelectedIndexChanged += (_, _) => delayCustom.Enabled = delayCombo.SelectedItem?.ToString() == "自定义";
        Controls.Add(delayCombo);
        Controls.Add(delayCustom);
        y += rh + gap;

        runAsAdminCheck = new CheckBox { Text = "以管理员身份启动", Left = cx, Top = y + 2, Width = 200 };
        Controls.Add(MakeLbl("启动身份:")); Controls.Add(runAsAdminCheck); y += rh + gap;

        var okBtn = new Button { Text = "添加", DialogResult = DialogResult.OK, Width = 80, Height = 28 };
        okBtn.Left = ClientSize.Width - okBtn.Width - 12 - 90; okBtn.Top = y + 2;
        okBtn.Anchor = AnchorStyles.Right | AnchorStyles.Top;
        var cancelBtn = new Button { Text = "取消", DialogResult = DialogResult.Cancel, Width = 80, Height = 28 };
        cancelBtn.Left = ClientSize.Width - cancelBtn.Width - 12; cancelBtn.Top = y + 2;
        cancelBtn.Anchor = AnchorStyles.Right | AnchorStyles.Top;
        Controls.Add(okBtn);
        Controls.Add(cancelBtn);

        AcceptButton = okBtn;
        CancelButton = cancelBtn;

        void PathAdjustWidth()
        {
            var bw = browseBtn.Width + 6;
            pathTextBox.Left = cx + bw;
            pathTextBox.Top = browseBtn.Top;
            pathTextBox.Width = ClientSize.Width - cx - bw - 12;
            pathTextBox.Anchor = AnchorStyles.Left | AnchorStyles.Top | AnchorStyles.Right;
            pathTextBox.Height = 24;
        }
    }

    protected override void OnVisibleChanged(EventArgs e)
    {
        base.OnVisibleChanged(e);
        if (Visible) nameTextBox.Focus();
    }
}
