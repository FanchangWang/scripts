using System.Text.Json;
using System.Text.RegularExpressions;

namespace EnvSwitch;

/// <summary>
/// 配置文件存在、但暂时读不到（被其它进程占用 / 没有读权限）。
/// 与「内容损坏」严格区分：调用方应该报错并放弃本次操作，<b>绝不</b>写回默认配置。
/// </summary>
internal sealed class ConfigUnreadableException(string file, Exception inner)
    : Exception($"配置文件暂时读不到：{file}（{inner.Message}）", inner)
{
    internal string File { get; } = file;
}

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

        string json;
        try
        {
            json = ReadText(file);
        }
        catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException)
        {
            // 「看不到文件」有三种：真首次运行 / 目录不可达 / 没有权限（File.Exists 与 ReadAllText 在这里都可能
            // 报成「不存在」）。显式 --config 指向的路径，只有所在目录确实可达时才敢认定是新建配置；
            // 否则就是「这次做不了」——照默认配置继续干活会拿一份不是用户的配置去写注册表。
            var dir = Path.GetDirectoryName(file);
            if (path is not null && (string.IsNullOrEmpty(dir) || !Directory.Exists(dir)))
                throw new ConfigUnreadableException(
                    file,
                    new DirectoryNotFoundException($"配置文件所在目录不存在或无法访问：{dir}"));

            var config = DetectDefaultConfig();
            // 首次运行落盘失败（目录不可写）时不要让程序崩掉，内存里还能用
            try { Save(config, file); }
            catch (Exception saveException)
            {
                Console.Error.WriteLine($"[警告] 默认配置未能写入 {file}：{saveException.Message}");
            }
            return config;
        }

        try
        {
            var config = JsonSerializer.Deserialize<AppConfig>(json, JsonOptions) ?? DetectDefaultConfig();
            Migrate(config);
            return config;
        }
        catch (Exception ex)
        {
            // 读到文本了、但解析不出来（JSON 语法坏 / 结构对不上）：这才按「损坏」处理。
            Console.Error.WriteLine($"[警告] 配置内容无法解析（{file}）：{ex.Message}");
            return RecoverFromBroken(file);
        }
    }

    /// <summary>
    /// 内容坏了才走这里：先备份原文件，再写默认配置。
    /// 备份失败时**不写回**——那份配置只是解析不出来、内容还在，覆盖就真没救了。
    /// 「读不到」绝不能进这个分支。
    /// </summary>
    private static AppConfig RecoverFromBroken(string file)
    {
        var backedUp = false;
        var backup = $"{file}.bad-{DateTime.Now:yyyyMMdd-HHmmss}";
        try
        {
            File.Copy(file, backup, overwrite: true);
            backedUp = true;
            Console.Error.WriteLine($"[警告] 原配置已备份为：{backup}");
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[警告] 原配置备份失败（{ex.Message}）：为避免覆盖仍可修复的原文件，本次不写回默认配置。");
        }

        var fallback = DetectDefaultConfig();
        if (backedUp)
        {
            try { Save(fallback, file); } catch { /* 落盘失败时内存里仍可用 */ }
            Console.Error.WriteLine("[警告] 已使用默认配置继续运行。");
        }
        else
        {
            Console.Error.WriteLine("[警告] 已使用默认配置继续运行（原文件保持原样，未写回）。");
        }
        return fallback;
    }

    /// <summary>
    /// 读配置文本。读不到（被占用 / 无权限 / 目录不可达）时重试一次——杀软扫描、同步盘、备份软件造成的
    /// 占用通常是瞬时的。重试仍失败就抛 <see cref="ConfigUnreadableException"/>：
    /// 这种情况**绝不能**当成「配置损坏」去写回默认值，那会把用户的真配置永久覆盖掉。
    /// 只有「文件确实不存在」才把 <see cref="FileNotFoundException"/> 交给调用方判断是不是首次运行。
    /// </summary>
    internal static string ReadText(string file)
    {
        try
        {
            return File.ReadAllText(file);
        }
        catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException)
        {
            // 「文件/目录不存在」交给 Load 判断是首次运行还是路径不可达，不能在这里一律当成读不到
            throw;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            System.Threading.Thread.Sleep(80);
            try
            {
                return File.ReadAllText(file);
            }
            catch (Exception retry) when (retry is FileNotFoundException or DirectoryNotFoundException)
            {
                throw;
            }
            catch (Exception retry)
            {
                throw new ConfigUnreadableException(file, retry);
            }
        }
    }

    /// <summary>旧配置补齐新字段：剔除 null 元素、补集合、把 null 字符串补成空串，再继承作用域。</summary>
    internal static void Migrate(AppConfig config)
    {
        // JSON 里写 null 的字段反序列化后会是 null，这里统一补成空集合 / 空串，避免后续 NRE
        config.Toolchains ??= [];
        config.Settings ??= new ConfigSettings();
        config.Toolchains.RemoveAll(t => t is null);
        var defaultScope = string.IsNullOrWhiteSpace(config.Settings.PathScope) ? "User" : config.Settings.PathScope;
        foreach (var t in config.Toolchains)
        {
            t.Name ??= "";
            t.EnvVar ??= "";
            t.PathTemplate ??= "";
            t.Active ??= "";
            t.Versions ??= [];
            t.Versions.RemoveAll(v => v is null);
            foreach (var v in t.Versions)
            {
                v.Name ??= "";
                v.Home ??= "";
                v.Paths ??= [];
                v.Paths.RemoveAll(p => p is null);
            }
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
