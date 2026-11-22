using System.Text.Json;
using System.Text.RegularExpressions;

namespace EnvSwitch;

/// <summary>配置文件读写；首次运行时自动探测本机 Java 安装作为默认配置。</summary>
internal static class ConfigStore
{
    internal static string DefaultPath =>
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "EnvSwitch",
            "config.json");

    internal static AppConfig Load(string? path = null)
    {
        var file = path ?? DefaultPath;
        if (!File.Exists(file))
        {
            var config = DetectDefaultConfig();
            Save(config, file);
            return config;
        }

        try
        {
            var json = File.ReadAllText(file);
            var config = JsonSerializer.Deserialize<AppConfig>(json, JsonOptions) ?? DetectDefaultConfig();
            Migrate(config);
            return config;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[警告] 配置读取失败（{file}）：{ex.Message}");
            // 读不懂的配置先另存一份：否则下面的 Save 会把用户原来的内容永久覆盖掉
            try
            {
                var backup = $"{file}.bad-{DateTime.Now:yyyyMMdd-HHmmss}";
                File.Copy(file, backup, overwrite: true);
                Console.Error.WriteLine($"[警告] 原配置已备份为：{backup}");
            }
            catch { /* 备份失败也要继续，至少程序还能启动 */ }

            var fallback = DetectDefaultConfig();
            try { Save(fallback, file); } catch { /* 落盘失败时内存里仍可用 */ }
            Console.Error.WriteLine("[警告] 已使用默认配置继续运行。");
            return fallback;
        }
    }

    /// <summary>旧配置补齐新字段：工具链没有作用域时继承全局 pathScope（再默认 User）。</summary>
    internal static void Migrate(AppConfig config)
    {
        // JSON 里写 null 的字段反序列化后会是 null，这里统一补成空集合，避免后续 NRE
        config.Toolchains ??= [];
        config.Settings ??= new ConfigSettings();
        var defaultScope = string.IsNullOrWhiteSpace(config.Settings.PathScope) ? "User" : config.Settings.PathScope;
        foreach (var t in config.Toolchains)
        {
            t.Versions ??= [];
            foreach (var v in t.Versions)
                v.Paths ??= [];
            if (string.IsNullOrWhiteSpace(t.PathScope))
                t.PathScope = defaultScope;
            if (string.IsNullOrWhiteSpace(t.EnvScope))
                t.EnvScope = t.PathScope;
            // 网页已改为「有条目就写 PATH」，旧的加入/不加入标记统一归位
            t.AddToPath = true;
        }
    }

    internal static void Save(AppConfig config, string? path = null)
    {
        var file = path ?? DefaultPath;
        var dir = Path.GetDirectoryName(file);
        if (!string.IsNullOrEmpty(dir))
            Directory.CreateDirectory(dir);

        // 先写临时文件再整体替换：中途出错 / 断电不会把已有配置截成半份 JSON
        var text = JsonSerializer.Serialize(config, JsonOptions);
        var tmp = file + ".tmp";
        File.WriteAllText(tmp, text);
        File.Move(tmp, file, overwrite: true);
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    // ---------------------------------------------------------------------
    // 首次运行：探测 Java
    // ---------------------------------------------------------------------

    private static AppConfig DetectDefaultConfig()
    {
        var java = new Toolchain
        {
            Name = "Java",
            EnvVar = "JAVA_HOME",
            PathTemplate = "%JAVA_HOME%\\bin",
            AddToPath = true,
            Enabled = true,
            EnvScope = "User",
            PathScope = "User",
            Versions = [],
        };

        foreach (var (name, home) in DetectJavaHomes())
        {
            if (java.Versions.Any(v => string.Equals(v.Home, home, StringComparison.OrdinalIgnoreCase)))
                continue;
            java.Versions.Add(new VersionEntry { Name = name, Home = home });
        }

        java.Versions = java.Versions
            .OrderBy(v => ParseMajor(v.Name))
            .ThenBy(v => v.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();

        var current = Environment.GetEnvironmentVariable("JAVA_HOME");
        java.Active = java.Versions.FirstOrDefault(v =>
                string.Equals(v.Home, current, StringComparison.OrdinalIgnoreCase))?.Name
            ?? java.Versions.FirstOrDefault()?.Name;

        return new AppConfig
        {
            Toolchains = java.Versions.Count > 0 ? [java] : [],
            Settings = new ConfigSettings(),
        };
    }

    private static List<(string Name, string Home)> DetectJavaHomes()
    {
        var roots = new List<string>();
        void Add(string? p)
        {
            if (!string.IsNullOrWhiteSpace(p) && Directory.Exists(p) && !roots.Contains(p, StringComparer.OrdinalIgnoreCase))
                roots.Add(p);
        }

        var pf = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        var pf86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var user = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

        Add(Environment.GetEnvironmentVariable("JAVA_HOME"));
        Add(Path.Combine(pf, "Java"));
        Add(Path.Combine(pf, "Eclipse Adoptium"));
        Add(Path.Combine(pf, "Zulu"));
        Add(Path.Combine(pf86, "Java"));
        Add(Path.Combine(local, "Programs", "Eclipse Adoptium"));
        Add(Path.Combine(user, ".jdks"));
        Add(Path.Combine(user, "code-tools", "runtimes"));

        var found = new List<(string Name, string Home)>();

        void Inspect(string dir)
        {
            if (!File.Exists(Path.Combine(dir, "bin", "java.exe")))
                return;
            var major = ReadJavaMajor(dir) ?? MajorFromName(Path.GetFileName(dir));
            if (major is not null)
                found.Add((major.Value.ToString(), dir));
        }

        foreach (var root in roots)
        {
            Inspect(root);
            try
            {
                foreach (var dir in Directory.EnumerateDirectories(root))
                {
                    Inspect(dir);
                    // 兼容 code-tools/runtimes/jdk-25\jdk-25.x 这类双层目录
                    try
                    {
                        foreach (var nested in Directory.EnumerateDirectories(dir))
                            Inspect(nested);
                    }
                    catch { /* 忽略无权限目录 */ }
                }
            }
            catch { /* 忽略无权限目录 */ }
        }

        // 每个大版本只保留一个最优安装，避免 JRE / latest / x86 副本刷屏
        return found
            .GroupBy(x => x.Name, StringComparer.Ordinal)
            .Select(g => g.OrderBy(x => Score(x.Home)).First())
            .OrderBy(x => int.Parse(x.Name))
            .ToList();
    }

    private static int Score(string home)
    {
        var score = 0;
        if (home.Contains("(x86)", StringComparison.OrdinalIgnoreCase)) score += 100;
        if (home.Contains("latest", StringComparison.OrdinalIgnoreCase)) score += 50;
        var leaf = Path.GetFileName(home.TrimEnd('\\', '/'));
        if (leaf.Contains("jre", StringComparison.OrdinalIgnoreCase)
            && !leaf.Contains("jdk", StringComparison.OrdinalIgnoreCase)) score += 20;
        score += home.Length / 1000;
        return score;
    }

    /// <summary>优先读取 release 文件的 JAVA_VERSION，避免执行任何 exe。</summary>
    private static int? ReadJavaMajor(string home)
    {
        var release = Path.Combine(home, "release");
        if (!File.Exists(release))
            return null;
        try
        {
            foreach (var line in File.ReadLines(release))
            {
                var m = Regex.Match(line, "JAVA_VERSION=\"?([0-9.]+)", RegexOptions.IgnoreCase);
                if (m.Success)
                    return NormalizeMajor(m.Groups[1].Value);
            }
        }
        catch { /* 忽略 */ }
        return null;
    }

    private static int? MajorFromName(string name)
    {
        var m = Regex.Match(name, @"(?:jdk|jre|zulu|jbr|openjdk)[-_ ]?([0-9][0-9.]*)", RegexOptions.IgnoreCase);
        return m.Success ? NormalizeMajor(m.Groups[1].Value) : null;
    }

    /// <summary>Java 8 在 release 里写作 1.8.x，需要换算成 8。</summary>
    private static int? NormalizeMajor(string version)
    {
        var parts = version.Split('.');
        if (parts.Length >= 2 && parts[0] == "1" && int.TryParse(parts[1], out var v))
            return v;
        return int.TryParse(parts[0], out var major) ? major : null;
    }

    private static int ParseMajor(string name) =>
        int.TryParse(name, out var v) ? v : int.MaxValue;
}
