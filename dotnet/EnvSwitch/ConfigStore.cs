using System.Text.Json;
using System.Text.RegularExpressions;

namespace EnvSwitch;

/// <summary>
/// 配置文件存在、但暂时读不到（被其它进程占用 / 没有读权限 / 所在目录不可达）。
/// 与「内容损坏」严格区分：调用方应该报错并放弃本次操作，<b>绝不</b>写回默认配置。
/// Message 只描述「哪个文件、为什么」，由调用方决定前缀（CLI / 中间件各有一句自己的说明）。
/// </summary>
internal sealed class ConfigUnreadableException(string file, Exception inner, string? hint = null)
    : Exception($"{file}（{inner.Message}）", inner)
{
    internal string File { get; } = file;

    /// <summary>
    /// 第二行的处置说明。给空串表示「第一行已经说清了，不再补」——「这个路径是一个目录」这类
    /// 原因如果后面再接一句「请检查网络盘是否已连接」，只会把人往错方向引。
    /// </summary>
    internal string Hint { get; } = hint ?? DefaultHint;

    private const string DefaultHint =
        "配置文件只是暂时读不到（不是损坏），已放弃本次操作，文件未被修改。"
        + "请检查该路径是否可访问（网络盘是否已连接、U 盘是否插着、是否有读取权限），是否被其它程序占用后重试。";
}

/// <summary>配置文件读写；首次运行时自动探测本机 Java 安装作为默认配置。</summary>
internal static class ConfigStore
{
    internal static string DefaultPath =>
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "EnvSwitch",
            "config.json");

    /// <summary>「看不到配置」的几种原因：真的还没建、这个位置访问不了、只是没在限时内响应、或指的其实是个目录。</summary>
    internal enum MissingConfigKind
    {
        FirstRun,
        Unreachable,
        Timeout,
        PathIsDirectory,
    }

    /// <summary>刚判过「不可达」的短缓存：轮询 / 用户连点时不必每次都重新等一轮探测。</summary>
    private sealed record UnreachableStamp(string Dir, DateTime Until, bool TimedOut);

    private static UnreachableStamp? _unreachableCache;

    /// <summary>
    /// 上一次 <see cref="Load"/> 是否用了「内存里兜底的默认配置」（真配置没读成、也没写成）。
    /// 这种配置只够拿来看，不能据此改注册表：写入类命令要先查这个标志再决定是否拒绝。
    /// </summary>
    /// <remarks>
    /// CLI 是单进程一次性执行，所以不做并发保护也能用；Web 请求虽然会写这个标志，
    /// 但服务侧 `Load` 传 `allowFirstRun: false`，「首次运行」与「内容损坏」两条兜底出口都会先抛，
    /// 走不到置位那一行，也没有任何地方读它（第九轮 I1 修的就是「内容损坏」这条漏网的出口）。
    /// </remarks>
    internal static bool LastLoadUsedFallback { get; private set; }

    /// <summary>
    /// 这个路径是不是「我们默认挑的那个」。判定只有这一份：`ClassifyMissing` 与
    /// <see cref="Load"/> 里「显式 / 默认」的分流都调它。
    /// </summary>
    /// <remarks>
    /// 不能用「调用方传没传 path」来判——`Program` 早就把 `configPath ??= DefaultPath` 兜上了，
    /// `path is not null` 恒真（第六轮 E1 已经踩过一次同型的坑）。
    /// </remarks>
    internal static bool IsDefaultPath(string file) =>
        string.Equals(file, DefaultPath, StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// 判断一个配置路径「看不到」时该按哪种原因处理。`Load`、`config` 命令、`Save` 共用这一份判断，
    /// 避免几处各写一遍又抄漏（默认路径的特判曾经只在 `config` 命令里有，`Load` 没跟上）。
    /// 默认路径、裸文件名（没有目录成分 = 当前目录）一律算「还没建」。
    /// </summary>
    internal static MissingConfigKind ClassifyMissing(string file)
    {
        if (IsDefaultPath(file))
            return MissingConfigKind.FirstRun;
        var dir = Path.GetDirectoryName(file);
        if (string.IsNullOrEmpty(dir))
            return MissingConfigKind.FirstRun;
        var kind = ClassifyDirectory(dir);
        if (kind != MissingConfigKind.FirstRun)
            return kind;
        // 给的路径本身就是个目录（`--config %TEMP%`）：写进去只会得到「Access is denied」
        if (Directory.Exists(file))
            return MissingConfigKind.PathIsDirectory;
        return MissingConfigKind.FirstRun;
    }

    /// <summary>
    /// 目录这一侧要分两件事：**根（盘符 / 共享）连不连得上**，和**目录本身建没建**。
    /// 前者才是「不可达」（断开的网络盘、不存在的盘符、UNC 服务器无响应）；根可达而目录不存在，
    /// 对本地盘和在线共享都只是「还没建」，交给 <see cref="Save"/> 的 `CreateDirectory`。
    /// </summary>
    private static MissingConfigKind ClassifyDirectory(string dir)
    {
        var root = Path.GetPathRoot(dir);
        if (string.IsNullOrEmpty(root))
            return MissingConfigKind.FirstRun;

        if (!DirectoryReachable(root, out var timedOut))
            return timedOut ? MissingConfigKind.Timeout : MissingConfigKind.Unreachable;

        if (Directory.Exists(dir))
            return MissingConfigKind.FirstRun;
        // 路径上那一段其实是个文件（`C:\Windows\notepad.exe\cfg.json`）：Save 建不出这个目录
        if (File.Exists(dir))
            return MissingConfigKind.Unreachable;
        return MissingConfigKind.FirstRun;
    }

    /// <summary>
    /// 路径可达性探测，上限 1.5 秒：断开的网络盘上 `Directory.Exists` 本身能卡十几秒。
    /// 超时按**不可达**处理——宁可这次报错，也不拿一份默认配置往那个位置写。
    /// 判过不可达的结果缓存 2 秒，避免轮询 / 连点时反复等。
    /// </summary>
    private static bool DirectoryReachable(string dir, out bool timedOut)
    {
        timedOut = false;
        var cached = _unreachableCache;
        if (cached is not null && cached.Until > DateTime.UtcNow
            && string.Equals(cached.Dir, dir, StringComparison.OrdinalIgnoreCase))
        {
            timedOut = cached.TimedOut;
            return false;
        }

        bool ok;
        try
        {
            var probe = System.Threading.Tasks.Task.Run(() => Directory.Exists(dir));
            if (!probe.Wait(1500))
            {
                timedOut = true;
                ok = false;
            }
            else
            {
                ok = probe.Result;
            }
        }
        catch
        {
            ok = false;
        }

        _unreachableCache = ok ? null : new UnreachableStamp(dir, DateTime.UtcNow.AddSeconds(2), timedOut);
        return ok;
    }

    /// <summary>常驻服务的请求里发现配置「回到了首次运行」：目录已被改名/删除，不能重建也不能假装 200。</summary>
    private static ConfigUnreadableException FirstRunNotAllowed(string file) =>
        new(file, new DirectoryNotFoundException(
            $"配置文件已不在原位（目录可能被改名或删除）：{file}"),
            "配置文件已不在原位，已放弃本次操作，文件未被修改。把目录/文件改回原名后重试即可。");

    /// <summary>显式 `--config` 的位置写不进去：不能拿「探测出来的」配置继续干活。</summary>
    private static ConfigUnreadableException CannotWrite(string file, Exception saveException) =>
        new(file, saveException,
            "这个位置写不进去，已放弃本次操作，配置文件与环境变量都未被修改。"
            + "请换一个可写的位置（或把配置文件放到别处，再用 --config 指向它）。");

    /// <summary>显式 `--config` 的配置读不懂、又修不了（备份失败 / 写回失败）。</summary>
    private static ConfigUnreadableException CannotRecover(string file, string reason, string hint) =>
        new(file, new InvalidDataException(reason), hint);

    /// <summary>不可达 / 超时的统一异常：文案由这里决定，调用方只负责打印。</summary>
    private static ConfigUnreadableException Unreachable(string file, MissingConfigKind kind) =>
        new(file, new DirectoryNotFoundException(UnreachableMessage(file, kind)),
            // 目录类错误第一行已经很明确，别再补一句网络盘的猜测
            kind == MissingConfigKind.PathIsDirectory ? "" : null);

    /// <summary>不可达 / 超时给用户看的那句话（`config` 命令也要打印同一句）。</summary>
    internal static string UnreachableMessage(string file, MissingConfigKind kind)
    {
        if (kind == MissingConfigKind.PathIsDirectory)
            return "这个路径是一个目录，请给出配置文件名";
        var dir = Path.GetDirectoryName(file);
        return kind == MissingConfigKind.Timeout
            // 不承诺「重试就能过」：最常见的触发就是路径打错，那重试多少次都一样
            ? $"配置文件所在目录未在 1.5 秒内响应：可能是路径不存在、网络盘断开，也可能只是慢（稍后重试可确认）：{dir}"
            : $"配置文件所在目录不存在或无法访问：{dir}";
    }

    /// <param name="allowFirstRun">
    /// CLI 单次调用允许「首次运行就地把配置建出来」；常驻服务在请求里必须传 false——
    /// 服务启动时配置一定已经落过盘，请求里再判成首次运行只可能是目录被改名/删除，
    /// 那时既不能重建目录写一份假配置，也不能假装 `200` 返回默认配置。
    /// </param>
    internal static AppConfig Load(string? path = null, bool allowFirstRun = true)
    {
        var file = path ?? DefaultPath;
        LastLoadUsedFallback = false;   // 每次读取都复位，避免上一次的结果误导写入类命令

        // 先判「这个位置能不能访问」，再碰文件：默认路径与裸文件名不探测（前者目录本来就可能还没建，
        // 后者就是当前目录）。探测上限 1.5 秒——`File.Exists` 与 `ReadAllText` 在断开的网络盘上要等
        // SMB 超时（实测 21 秒），所以不可达时必须在这之前退出，否则每个 Web 请求都要付一次。
        var kind = ClassifyMissing(file);
        if (kind != MissingConfigKind.FirstRun)
            throw Unreachable(file, kind);

        if (!File.Exists(file))
        {
            // 探测之后到 `File.Exists` 之间目录才消失的竞态：同一份判定再来一次
            kind = ClassifyMissing(file);
            if (kind != MissingConfigKind.FirstRun)
                throw Unreachable(file, kind);
            // 走到这里才是真正的「首次运行」：常驻服务的请求不许就地建配置
            if (!allowFirstRun)
                throw FirstRunNotAllowed(file);

            var config = DetectDefaultConfig();
            return FirstRunFallback(config, file, explicitPath: !IsDefaultPath(file));
        }

        string json;
        try
        {
            json = ReadText(file);
        }
        catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException)
        {
            // 刚判过存在、读取时却没了：同一份判定再来一次（这类竞态极少，但别退回「一律算首次运行」）
            kind = ClassifyMissing(file);
            if (kind != MissingConfigKind.FirstRun)
                throw Unreachable(file, kind);
            if (!allowFirstRun)
                throw FirstRunNotAllowed(file);

            var config = DetectDefaultConfig();
            return FirstRunFallback(config, file, explicitPath: !IsDefaultPath(file));
        }

        try
        {
            var config = JsonSerializer.Deserialize<AppConfig>(json, JsonOptions) ?? DetectDefaultConfig();
            Migrate(config);
            return config;
        }
        catch (Exception ex)
        {
            // 服务运行中（allowFirstRun: false）不备份、不重建、不兜底，直接交给 503 通道：
            // 否则每请求重打一遍警告，备份成功但写回失败时还会每秒落一个新的 .bad 文件。
            if (!allowFirstRun)
                throw new ConfigUnreadableException(file,
                    // 统一包成 InvalidDataException：中间件靠这个类型把「内容坏了」和「暂时读不到」分成两句，
                    // 原始的异常可能来自 Deserialize 也可能来自 Migrate（校验旧配置），不统一就会误标成「读不到」。
                    new InvalidDataException(ex.Message, ex),
                    "服务运行中不使用内存兜底的默认配置，本次未做任何修改。请手工修好这份 JSON（或换一份可用配置）后重试。");
            // 读到文本了、但解析不出来（JSON 语法坏 / 结构对不上）：这才按「损坏」处理。
            Console.Error.WriteLine($"[警告] 配置内容无法解析（{file}）：{ex.Message}");
            return RecoverFromBroken(file, explicitPath: !IsDefaultPath(file));
        }
    }

    /// <summary>
    /// 首次运行把默认配置落盘。默认路径写不进去时只警告（内存里接着用，程序还能跑）；
    /// **显式 `--config` 写不进去就必须抛**——那份配置是「探测出来的」而不是用户的，
    /// 拿它继续执行会让 `use` 把 JAVA_HOME / PATH 真写进注册表（D1/C2 禁止的正是这种事）。
    /// </summary>
    private static AppConfig FirstRunFallback(AppConfig config, string file, bool explicitPath)
    {
        try
        {
            Save(config, file);
        }
        catch (Exception saveException) when (explicitPath)
        {
            throw CannotWrite(file, saveException);
        }
        catch (Exception saveException)
        {
            Console.Error.WriteLine($"[警告] 默认配置未能写入 {file}：{saveException.Message}");
            // 这份配置只在内存里：只读命令还能用，写入类命令必须拒绝（见 Program 的守卫）
            LastLoadUsedFallback = true;
        }
        return config;
    }

    /// <summary>
    /// 内容坏了才走这里：先备份原文件，再写默认配置。
    /// 备份失败时**不写回**——那份配置只是解析不出来、内容还在，覆盖就真没救了。
    /// 「读不到」绝不能进这个分支。
    /// </summary>
    /// <param name="explicitPath">
    /// 显式 `--config` 时，备份失败或写回失败都直接抛：用户点名的那份配置读不懂、又修不了，
    /// 再拿「探测出来的默认配置」接着跑（`use` 会真写注册表）正是 D1/C2/H1 一路要禁止的事。
    /// 默认路径维持「警告 + 内存继续」，否则 `%LOCALAPPDATA%` 只读时程序就彻底起不来了。
    /// </param>
    private static AppConfig RecoverFromBroken(string file, bool explicitPath)
    {
        var backedUp = false;
        var backup = $"{file}.bad-{DateTime.Now:yyyyMMdd-HHmmss}";
        var backupFailure = "";
        try
        {
            File.Copy(file, backup, overwrite: true);
            backedUp = true;
            Console.Error.WriteLine($"[警告] 原配置已备份为：{backup}");
        }
        catch (Exception ex)
        {
            backupFailure = ex.Message;
            Console.Error.WriteLine($"[警告] 原配置备份失败（{ex.Message}）：为避免覆盖仍可修复的原文件，本次不写回默认配置。");
        }

        var fallback = DetectDefaultConfig();
        if (backedUp)
        {
            try
            {
                Save(fallback, file);
                // 写回成功才是「已恢复」：磁盘上现在是一份能读的默认配置，后续不再走兜底
                Console.Error.WriteLine("[警告] 已使用默认配置继续运行。");
            }
            catch (Exception saveException)
            {
                // 以前这里是空 catch：下次启动又会判定损坏、又备份一个 .bad-<新时间戳>，
                // 而用户以为自己「已经恢复默认配置了」，所以要明确说没写回
                var message = $"备份成功但默认配置未能写回 {file}：{saveException.Message}"
                              + "（下次启动仍会按损坏处理并再备份一次）";
                if (explicitPath)
                    throw CannotRecover(file, message, "原文件已备份为上面那个 .bad 文件，但默认配置没能写回。");
                Console.Error.WriteLine($"[警告] {message}");
                LastLoadUsedFallback = true;
            }
        }
        else
        {
            if (explicitPath)
                throw CannotRecover(file,
                    $"配置内容无法解析，且该位置不可写（备份失败：{backupFailure}），本次不写回默认配置",
                    "原配置文件未被修改。请手工修好这份 JSON，或换一个可写的位置/换一份配置。");
            Console.Error.WriteLine("[警告] 已使用默认配置继续运行（原文件保持原样，未写回）。");
            LastLoadUsedFallback = true;
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

    /// <param name="allowCreateDirectory">
    /// 首次运行时允许把配置目录建出来；服务运行中保存（用户已经在用这份配置）必须传 false——
    /// 否则目录被改名后 `CreateDirectory` 会原地重建并写一份新配置，看着还「保存成功」。
    /// </param>
    internal static void Save(AppConfig config, string? path = null, bool allowCreateDirectory = true)
    {
        var file = path ?? DefaultPath;

        // 写侧也要有同一道护栏：目录被搬走 / 网络盘断线时，`CreateDirectory` 会在旧位置「重建」出目录
        // 并写进一份新配置，看起来还保存成功了——实际用户的真配置在别处。这里先判，不可达就抛。
        var kind = ClassifyMissing(file);
        if (kind != MissingConfigKind.FirstRun)
            throw Unreachable(file, kind);

        var dir = Path.GetDirectoryName(file);
        if (!string.IsNullOrEmpty(dir))
        {
            if (allowCreateDirectory)
                Directory.CreateDirectory(dir);
            else if (!Directory.Exists(dir))
                throw new ConfigUnreadableException(file,
                    new DirectoryNotFoundException($"配置文件所在目录已不存在（可能被改名或删除）：{dir}"));
        }

        // 先写临时文件再整体替换：中途出错 / 断电不会把已有配置截成半份 JSON
        var text = JsonSerializer.Serialize(config, JsonOptions);
        var tmp = file + ".tmp";
        File.WriteAllText(tmp, text);
        try
        {
            File.Move(tmp, file, overwrite: true);
        }
        catch
        {
            // 替换失败时别把半成品 `.tmp` 留在用户目录里
            try { File.Delete(tmp); } catch { /* 删不掉就留给下次覆盖 */ }
            throw;
        }
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
