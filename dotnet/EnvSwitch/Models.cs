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

    /// <summary>要生成的环境变量名，例如 JAVA_HOME。</summary>
    public string EnvVar { get; set; } = "";

    /// <summary>加入 PATH 的条目模板，支持 %JAVA_HOME% 形式展开，例如 %JAVA_HOME%\bin。</summary>
    public string PathTemplate { get; set; } = "";

    /// <summary>是否把 PathTemplate 加入 PATH。</summary>
    public bool AddToPath { get; set; } = true;

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

public sealed class PathResult
{
    public bool Ok { get; set; }
    public string Message { get; set; } = "";
    public string Before { get; set; } = "";
    public string After { get; set; } = "";
    public int BeforeCount { get; set; }
    public int AfterCount { get; set; }
    public bool DryRun { get; set; }
}

/// <summary>PATH 工具网页视图中的一行。</summary>
public sealed class PathEntryView
{
    public string Value { get; set; } = "";
    public string? Badge { get; set; }
    public string? Toolchain { get; set; }
    public string? Version { get; set; }
    public bool Removed { get; set; }
}

public sealed class PathViewResult
{
    public List<PathEntryView> SystemCurrent { get; set; } = [];
    public List<PathEntryView> SystemProcessed { get; set; } = [];
    public List<PathEntryView> UserCurrent { get; set; } = [];
    public List<PathEntryView> UserProcessed { get; set; } = [];
    public string Message { get; set; } = "";
}

/// <summary>环境变量单个写入 / 删除的结果（网页「环境变量」页用）。</summary>
public sealed class EnvWriteResult
{
    public bool Ok { get; set; }
    public string Message { get; set; } = "";
}
