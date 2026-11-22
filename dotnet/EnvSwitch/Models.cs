namespace EnvSwitch;

/// <summary>顶层配置：可管理多套工具链（Java / Node / Maven ...）。</summary>
public sealed class AppConfig
{
    public List<Toolchain> Toolchains { get; set; } = [];
    public ConfigSettings Settings { get; set; } = new();
}

public sealed class ConfigSettings
{
    /// <summary>应用版本时是否顺带对 PATH 排序 + 去重。</summary>
    public bool NormalizePathOnApply { get; set; } = true;

    /// <summary>PATH 修改作用范围：User 或 Machine（Machine 需管理员权限）。</summary>
    public string PathScope { get; set; } = "User";

    /// <summary>启动网页模式时是否自动打开浏览器。</summary>
    public bool OpenBrowserOnStart { get; set; } = true;

    /// <summary>监听端口，0 表示自动分配空闲端口。</summary>
    public int Port { get; set; }
}

/// <summary>一套工具链，例如 Java。</summary>
public sealed class Toolchain
{
    public string Name { get; set; } = "";

    /// <summary>要生成的环境变量名，例如 JAVA_HOME；留空表示本工具链不写环境变量。</summary>
    public string EnvVar { get; set; } = "";

    /// <summary>加入 PATH 的条目模板，支持 %JAVA_HOME% 形式展开，例如 %JAVA_HOME%\bin；留空且版本没有显式条目时不写 PATH。</summary>
    public string PathTemplate { get; set; } = "";

    /// <summary>是否把 PATH 条目加入 PATH（网页已改为由「条目是否为空」决定，此字段保留仅为兼容旧配置）。</summary>
    public bool AddToPath { get; set; } = true;

    /// <summary>本工具链是否生效；关闭时相当于没有配置过这套工具链。</summary>
    public bool Enabled { get; set; } = true;

    /// <summary>环境变量作用范围：User / Machine / UserOnly / MachineOnly（唯一 = 写本侧并删除另一侧同名变量）。</summary>
    public string EnvScope { get; set; } = "";

    /// <summary>PATH 作用范围：User / Machine / UserOnly / MachineOnly（唯一 = 写本侧并移除另一侧本条工具链的条目）。</summary>
    public string PathScope { get; set; } = "";

    /// <summary>当前激活的版本名。</summary>
    public string? Active { get; set; }

    public List<VersionEntry> Versions { get; set; } = [];
}

public sealed class VersionEntry
{
    public string Name { get; set; } = "";

    /// <summary>环境变量值（如 JAVA_HOME 指向的目录）。</summary>
    public string Home { get; set; } = "";

    /// <summary>
    /// 该版本要加入 PATH 的条目，可配置多个。
    /// 留空时回退为用 PathTemplate 展开 Home（兼容旧配置）。
    /// </summary>
    public List<string> Paths { get; set; } = [];
}

public enum EnvScope
{
    User,
    Machine,
}

public sealed class ApplyResult
{
    public bool Ok { get; set; }
    public string Message { get; set; } = "";
    public string? EnvVar { get; set; }
    public string? NewValue { get; set; }
    public string? PathBefore { get; set; }
    public string? PathAfter { get; set; }
    public bool DryRun { get; set; }
}

/// <summary>「立即应用」时单个工具链的结果。</summary>
public sealed class ApplyItem
{
    public string Toolchain { get; set; } = "";
    public string? Version { get; set; }
    public bool Ok { get; set; }
    public string Message { get; set; } = "";
}

/// <summary>「立即应用」批量应用到所有生效工具链的汇总结果。</summary>
public sealed class BatchApplyResult
{
    public bool Ok { get; set; }
    public bool DryRun { get; set; }
    public string Message { get; set; } = "";
    public List<ApplyItem> Items { get; set; } = [];
}

/// <summary>`env` 预览：应用当前激活版本后，某个变量会被改成什么（After=null 表示删除另一侧）。</summary>
public sealed class EnvPlanItem
{
    public string Toolchain { get; set; } = "";
    public EnvScope Scope { get; set; } = EnvScope.User;
    public string Name { get; set; } = "";
    public string? Before { get; set; }
    public string? After { get; set; }
}

/// <summary>PATH 工具网页视图中的一行。</summary>
public sealed class PathEntryView
{
    public string Value { get; set; } = "";
    public string? Badge { get; set; }
    public string? Toolchain { get; set; }
    public string? Version { get; set; }
    public bool Removed { get; set; }

    /// <summary>目录是否仍然存在；false 表示这条 PATH 已经指向不存在的目录（已失效）。</summary>
    public bool Exists { get; set; } = true;

    /// <summary>
    /// 条目里含 %变量% 但这侧 PATH 不会自动展开（值是普通字符串）时，展开后的实际路径。
    /// 界面用它提示「真正会生效的路径是什么」，写入时也会换成这个值。
    /// </summary>
    public string? Expanded { get; set; }
}

public sealed class PathViewResult
{
    public List<PathEntryView> SystemCurrent { get; set; } = [];
    public List<PathEntryView> SystemProcessed { get; set; } = [];
    public List<PathEntryView> UserCurrent { get; set; } = [];
    public List<PathEntryView> UserProcessed { get; set; } = [];
    public string Message { get; set; } = "";
}

/// <summary>保存配置时的单条校验错误，用于在网页上高亮对应输入框。</summary>
public sealed class ConfigError
{
    /// <summary>工具链名称。</summary>
    public string Toolchain { get; set; } = "";

    /// <summary>版本名称；为空表示错误挂在工具链级别。</summary>
    public string? Version { get; set; }

    /// <summary>字段：home / path / template / name。</summary>
    public string Field { get; set; } = "";

    /// <summary>PATH 条目在该版本里的下标（仅 field=path 时有值）。</summary>
    public int? Index { get; set; }

    /// <summary>出问题的原始值。</summary>
    public string Value { get; set; } = "";

    /// <summary>给人看的说明。</summary>
    public string Message { get; set; } = "";
}

/// <summary>环境变量单个写入 / 删除的结果（网页「环境变量」页用）。</summary>
public sealed class EnvWriteResult
{
    public bool Ok { get; set; }
    public string Message { get; set; } = "";
}

/// <summary>网页「环境变量」页的一行值；指向不存在的目录时标记失效。</summary>
public sealed class EnvVarView
{
    public string Value { get; set; } = "";
    public bool Invalid { get; set; }
}
