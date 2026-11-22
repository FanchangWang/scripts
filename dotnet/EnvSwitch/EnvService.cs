namespace EnvSwitch;

/// <summary>环境变量读写与版本切换的核心逻辑，CLI 与网页共用。</summary>
internal static class EnvService
{
    private static EnvironmentVariableTarget Target(EnvScope scope) =>
        scope == EnvScope.Machine ? EnvironmentVariableTarget.Machine : EnvironmentVariableTarget.User;

    internal static EnvScope ParseScope(string? scope) =>
        string.Equals(scope, "Machine", StringComparison.OrdinalIgnoreCase)
            ? EnvScope.Machine
            : EnvScope.User;

    /// <summary>写入目标：写哪一侧，以及是否要把另一侧的残留清掉（「唯一」模式）。</summary>
    internal readonly record struct WriteTarget(EnvScope Scope, bool Unique)
    {
        internal EnvScope Other => Scope == EnvScope.Machine ? EnvScope.User : EnvScope.Machine;

        internal string Label =>
            (Scope == EnvScope.Machine ? "系统" : "用户") + (Unique ? "·唯一" : "");
    }

    /// <summary>解析四值作用域：系统 / 用户 / 系统唯一 / 用户唯一。</summary>
    internal static WriteTarget ParseWriteScope(string? value) =>
        (value ?? "").Trim().ToLowerInvariant() switch
        {
            "machine" or "system" => new WriteTarget(EnvScope.Machine, false),
            "machineonly" or "systemonly" or "machine-only" or "system-only" => new WriteTarget(EnvScope.Machine, true),
            "useronly" or "user-only" => new WriteTarget(EnvScope.User, true),
            _ => new WriteTarget(EnvScope.User, false),
        };

    internal static string? GetEnv(string name, EnvScope scope) =>
        Environment.GetEnvironmentVariable(name, Target(scope));

    /// <summary>
    /// PATH 的注册表原样值：REG_EXPAND_SZ 里的 %变量% 不展开。
    /// 写入前后都用它比较 / 拼接，这样 PATH 里其它条目的原有写法不会被顺手改掉。
    /// </summary>
    internal static string GetPathRaw(EnvScope scope)
    {
        try
        {
            var raw = EnvStore.GetRaw("Path", scope);
            if (raw is not null)
                return raw;
        }
        catch
        {
            // 注册表读不到时退回进程可见值
        }
        return GetEnv("Path", scope) ?? "";
    }

    /// <summary>网页「环境变量」页：读取全部变量，并标出值所指向的目录已不存在的项。</summary>
    internal static Dictionary<string, EnvVarView> GetAllDetailed(EnvScope scope)
    {
        var result = new Dictionary<string, EnvVarView>(StringComparer.OrdinalIgnoreCase);
        Dictionary<string, string>? raw = null;
        try
        {
            raw = EnvStore.AllRaw(scope);
        }
        catch
        {
            // 注册表读不到（权限 / 异常）时退回进程环境枚举
        }

        if (raw is { Count: > 0 })
        {
            // 存在性探测是网络 I/O：逐条串行时 N 条断网路径就是 N × 1.5 秒（实测 1 条 1.5 秒、3 条 4.5 秒）。
            // 与 PATH 页一致，先并发算 invalid，再组装结果。
            var entries = raw.ToList();
            var invalid = new bool[entries.Count];
            Parallel.ForEach(Enumerable.Range(0, entries.Count), i =>
            {
                invalid[i] = IsProbablyInvalidPath(entries[i].Key, entries[i].Value);
            });
            for (var i = 0; i < entries.Count; i++)
                result[entries[i].Key] = new EnvVarView
                {
                    Value = entries[i].Value,
                    Invalid = invalid[i],
                };

            // 非字符串类型（REG_DWORD / REG_MULTI_SZ …）只能看不能改：写回去会把类型变成字符串
            try
            {
                foreach (var kv in EnvStore.NonStringKinds(scope))
                    result[kv.Key] = new EnvVarView
                    {
                        Value = $"（该值是 {kv.Value} 类型，网页只能只读查看）",
                        ReadOnly = true,
                        Kind = kv.Value,
                    };
            }
            catch
            {
                // 读类型失败不影响字符串变量的展示
            }
            return result;
        }

        foreach (System.Collections.DictionaryEntry item in Environment.GetEnvironmentVariables(Target(scope)))
        {
            var name = item.Key.ToString() ?? "";
            var value = item.Value?.ToString() ?? "";
            result[name] = new EnvVarView
            {
                Value = value,
                Invalid = IsProbablyInvalidPath(name, value),
            };
        }
        return result;
    }

    /// <summary>单值变量若整条值就是一个目录/文件路径，且它已不存在，则认为该变量已失效。</summary>
    private static bool IsProbablyInvalidPath(string name, string value)
    {
        if (IsPath(name) || string.IsNullOrWhiteSpace(value) || value.Contains(';', StringComparison.Ordinal))
            return false;
        // 先展开 %USERPROFILE% 这类引用再判断
        var expanded = Environment.ExpandEnvironmentVariables(value).Trim().Trim('"');
        if (!LooksLikePath(expanded))
            return false;
        // 走统一入口：断网的网络盘 / 拔掉的移动硬盘会让 Directory.Exists 卡十几秒（实测 19 秒），
        // 而这个方法在「环境变量」页会被逐条串行调用。超时一律当存在，不误标失效。
        return !ProbeExists(expanded);
    }

    private static bool LooksLikePath(string v) =>
        (v.Length >= 3 && char.IsLetter(v[0]) && v[1] == ':' && (v[2] == '\\' || v[2] == '/'))
        || v.StartsWith(@"\\", StringComparison.Ordinal);

    /// <summary>
    /// 看起来「意图是路径」：盘符 / UNC 开头，或者以 <c>%变量%</c> 开头的写法（<c>%USERPROFILE%\x</c>）。
    /// 展开失败时用它判断「这是不是一个本来想指向目录的值」，避免把 -Xmx%MEM% 这类文本也标成失效路径。
    /// </summary>
    private static bool LooksLikePathValue(string v)
    {
        var t = (v ?? "").Trim().Trim('"');
        if (LooksLikePath(t))
            return true;
        return t.StartsWith('%') && (t.Contains('\\') || t.Contains('/'));
    }

    /// <summary>
    /// 带超时的存在性探测，全项目唯一的存在性判断入口。
    /// 断网的网络盘 / 拔掉的移动硬盘会让 Directory.Exists 卡上几十秒，
    /// 所以统一 1.5 秒判不了就当「存在」——宁可留一条失效路径，也不误删。
    /// </summary>
    internal static bool ProbeExists(string expandedPath, bool dirOnly = false, int timeoutMs = 1500)
    {
        if (string.IsNullOrWhiteSpace(expandedPath))
            return false;
        try
        {
            var probe = Task.Run(() => dirOnly
                ? Directory.Exists(expandedPath)
                : Directory.Exists(expandedPath) || File.Exists(expandedPath));
            return probe.Wait(timeoutMs) ? probe.Result : true;
        }
        catch
        {
            return true;
        }
    }

    /// <summary>PATH 条目是否还指向存在的目录；UNC/超时等无法判断的情况一律当成存在，避免误删。</summary>
    internal static bool PathEntryExists(string entry)
    {
        var expanded = Environment.ExpandEnvironmentVariables(entry).Trim().Trim('"');
        if (string.IsNullOrWhiteSpace(expanded))
            return false;
        return ProbeExists(PathUtil.Canonical(expanded), dirOnly: true);
    }

    /// <summary>
    /// 值看起来是个路径、但目录和文件都不存在 → 返回 true（用在校验用户手填的 Home / PATH 条目）。
    /// 不像是路径的值（变量名、URL、纯文本）和不正常的网络路径都算通过。
    /// </summary>
    internal static bool PathMissing(string value)
    {
        var expanded = Environment.ExpandEnvironmentVariables(value ?? "").Trim().Trim('"');
        if (string.IsNullOrWhiteSpace(expanded) || !LooksLikePath(expanded))
            return false;
        return !ProbeExists(expanded);
    }

    /// <summary>PATH 值本身是不是 REG_EXPAND_SZ —— 只有这种类型，系统才会展开里面的 %变量%。</summary>
    internal static bool PathExpandsVariables(EnvScope scope) =>
        EnvStore.KindOf("Path", scope) == Microsoft.Win32.RegistryValueKind.ExpandString;

    /// <summary>按字面判断路径是否存在（不展开变量）。同样走 <see cref="ProbeExists"/> 的超时入口。</summary>
    private static bool LiteralExists(string entry)
    {
        var text = (entry ?? "").Trim().Trim('"');
        if (text.Length == 0)
            return false;
        return ProbeExists(text);
    }

    /// <summary>
    /// 准备写进 PATH 的条目：PATH 是 REG_EXPAND_SZ 时系统会自己展开 %变量%，写法原样保留；
    /// 是普通字符串（REG_SZ）时 %变量% 不会被展开，等于一条无效路径（本机用户的 Path 就是这种），
    /// 所以先换成具体路径再写。
    /// </summary>
    internal static string PreparePathEntry(string entry, bool pathExpandsVariables, Toolchain? tc, VersionEntry? version)
    {
        var text = (entry ?? "").Trim();
        if (text.Length == 0 || !text.Contains('%', StringComparison.Ordinal) || pathExpandsVariables)
            return text;

        string expanded;
        try
        {
            expanded = (tc is not null && version is not null
                ? ExpandForCheck(text, tc, version)
                : Environment.ExpandEnvironmentVariables(text)).Trim();
        }
        catch
        {
            return text;
        }

        // 有变量在当前环境里没定义（展开后还留着 %），或者展开前后没变化 → 保持原样
        if (expanded.Contains('%', StringComparison.Ordinal)
            || string.Equals(expanded, text, StringComparison.OrdinalIgnoreCase))
            return text;

        // 只在「原写法本身无效、展开后确实存在」时替换，避免换成另一个同样无效的条目
        return !LiteralExists(text) && PathEntryExists(expanded) ? expanded : text;
    }

    /// <summary>
    /// 保存前校验：Home、每个 PATH 条目、以及「没有显式条目时才会用到」的 PATH 模板，
    /// 只要看起来是路径却不存在就拦下来，避免保存一份切换时才失败的错误配置。
    /// </summary>
    internal static List<ConfigError> ValidateConfig(AppConfig config)
    {
        var errors = new List<ConfigError>();

        // 监听端口：0 表示自动分配，其余必须是 1–65535。越界值会让 serve 启动直接抛异常。
        if (config.Settings.Port is < 0 or > 65535)
            errors.Add(new ConfigError
            {
                Toolchain = "", Field = "port", Value = config.Settings.Port.ToString(),
                Message = $"端口 {config.Settings.Port} 不在 1–65535 范围内（0 表示自动分配）。",
            });

        // 工具链重名：网页 /api/state 用名字当键，批量应用按名字找第一套；
        // 重名会让状态接口 500，还会把第二套静默按第一套写，所以保存时就拦住。
        foreach (var dup in config.Toolchains
                     .Where(t => !string.IsNullOrWhiteSpace(t.Name))
                     .GroupBy(t => t.Name.Trim(), StringComparer.OrdinalIgnoreCase)
                     .Where(g => g.Count() > 1))
            errors.Add(new ConfigError
            {
                Toolchain = dup.Key, Field = "name", Value = dup.Key,
                Message = $"有 {dup.Count()} 套工具链都叫「{dup.Key}」，名称必须唯一（批量应用和状态读取都按名称找工具链）。",
            });

        foreach (var tc in config.Toolchains)
        {
            var tcName = string.IsNullOrWhiteSpace(tc.Name) ? "(未命名)" : tc.Name.Trim();

            // 同一工具链里版本重名，切换时按名字找不到唯一版本，先拦下来
            foreach (var dup in tc.Versions
                         .Where(v => !string.IsNullOrWhiteSpace(v.Name))
                         .GroupBy(v => v.Name.Trim(), StringComparer.OrdinalIgnoreCase)
                         .Where(g => g.Count() > 1))
                errors.Add(new ConfigError
                {
                    Toolchain = tcName, Version = dup.Key, Field = "name", Value = dup.Key,
                    Message = $"工具链「{tcName}」里有 {dup.Count()} 个版本都叫「{dup.Key}」，切换时无法区分，请改名。",
                });

            foreach (var v in tc.Versions)
            {
                var verName = string.IsNullOrWhiteSpace(v.Name) ? "(未命名版本)" : v.Name.Trim();

                if (!string.IsNullOrWhiteSpace(v.Home) && PathMissing(v.Home))
                    errors.Add(new ConfigError
                    {
                        Toolchain = tcName, Version = verName, Field = "home", Value = v.Home,
                        Message = $"版本「{verName}」的 Home 不存在：{v.Home}",
                    });

                var paths = v.Paths ?? [];
                var explicitPaths = paths.Where(p => !string.IsNullOrWhiteSpace(p)).ToList();
                // 同一版本里可能有多条指向同一目录的写法（例如 latest\jdk-21\bin 与
                // jdk-21.0.13.0.2\bin，前者是指向后者目录的 junction）。写入时只保留
                // 顺序最靠前且存在的那一条，所以这里按真实目录分组：组内只要有一条存在就算通过。
                var groups = new List<(string Key, List<(int Index, string Raw)> Items)>();
                var groupOf = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
                for (var i = 0; i < paths.Count; i++)
                {
                    var raw = (paths[i] ?? "").Trim();
                    if (raw.Length == 0)
                        continue;
                    var expanded = ExpandForCheck(raw, tc, v);
                    var key = RealPath.Key(expanded);
                    if (key.Length == 0)
                        key = raw.ToLowerInvariant();
                    if (groupOf.TryGetValue(key, out var g))
                        groups[g].Items.Add((i, raw));
                    else
                    {
                        groupOf[key] = groups.Count;
                        groups.Add((key, new List<(int, string)> { (i, raw) }));
                    }
                }

                foreach (var group in groups)
                {
                    var anyExists = group.Items.Any(x => !PathMissing(ExpandForCheck(x.Raw, tc, v)));
                    if (anyExists)
                        continue;
                    var sameDir = group.Items.Count > 1;
                    foreach (var x in group.Items)
                        errors.Add(new ConfigError
                        {
                            Toolchain = tcName, Version = verName, Field = "path", Index = x.Index, Value = x.Raw,
                            Message = $"版本「{verName}」的 PATH 条目不存在：{x.Raw}"
                                      + (sameDir ? "（同版本这几条指向同一目录，但都不存在）" : ""),
                        });
                }

                // 模板只在该版本没有任何显式条目时才会被用到，所以也只在这时校验
                if (explicitPaths.Count == 0 && !string.IsNullOrWhiteSpace(tc.PathTemplate))
                {
                    var expanded = ExpandForCheck(tc.PathTemplate, tc, v);
                    foreach (var part in PathUtil.Split(expanded))
                    {
                        if (!PathMissing(part))
                            continue;
                        errors.Add(new ConfigError
                        {
                            Toolchain = tcName, Version = verName, Field = "template", Value = tc.PathTemplate,
                            Message = $"版本「{verName}」的 PATH 模板展开后不存在：{part}",
                        });
                    }
                }
            }
        }
        return errors;
    }

    /// <summary>先把 %环境变量名% 换成该版本 Home，再展开 %USERPROFILE% 这类系统变量。</summary>
    private static string ExpandForCheck(string value, Toolchain tc, VersionEntry v)
    {
        var replaced = value;
        if (!string.IsNullOrWhiteSpace(tc.EnvVar))
            replaced = replaced.Replace($"%{tc.EnvVar}%", v.Home ?? "", StringComparison.OrdinalIgnoreCase);
        return Environment.ExpandEnvironmentVariables(replaced);
    }

    /// <summary>展开后还有没展开的变量（判不了是否存在）时放行，避免误报。</summary>
    private static bool MissingForCheck(string raw, Toolchain tc, VersionEntry v)
    {
        var expanded = ExpandForCheck(raw, tc, v);
        if (expanded.Contains('%', StringComparison.Ordinal))
            return false;
        return PathMissing(expanded);
    }

    /// <summary>
    /// 同一个版本里多条 PATH 若指向同一个真实目录（例如 latest\jdk-21\bin 与
    /// jdk-21.0.13.0.2\bin，前者是指向后者目录的 junction），只保留一条：
    /// 按配置顺序，第一条存在就留第一条；第一条不存在就留后面第一条存在的。
    /// 其余写法视为同一项的重复写法，写入时会被删掉。
    /// </summary>
    internal static List<string> CollapseEquivalent(List<string> paths, Toolchain tc, VersionEntry v)
    {
        var result = new List<string>();
        var exists = new List<bool>();
        var slots = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        foreach (var raw in paths)
        {
            var trimmed = (raw ?? "").Trim();
            if (trimmed.Length == 0)
                continue;
            var key = RealPath.Key(ExpandForCheck(trimmed, tc, v));
            if (key.Length == 0)
                key = trimmed.ToLowerInvariant();
            var present = !MissingForCheck(trimmed, tc, v);
            if (slots.TryGetValue(key, out var slot))
            {
                // 已经占位的写法不存在、现在这条存在 → 换成这条（顺序位置不变）
                if (!exists[slot] && present)
                {
                    result[slot] = trimmed;
                    exists[slot] = true;
                }
                continue;
            }
            slots[key] = result.Count;
            result.Add(trimmed);
            exists.Add(present);
        }
        return result;
    }

    /// <summary>网页「环境变量」页：写入单个变量的值（空串即置空，不当成删除）。</summary>
    internal static EnvWriteResult SetEnv(string name, string value, EnvScope scope)
    {
        var invalid = ValidateName(name);
        if (invalid is not null)
            return new EnvWriteResult { Ok = false, Message = invalid };
        var target = name.Trim();

        // Path 不能用这个页清空：写空串等于把该侧全部 PATH 条目一起抹掉（命令全部失效）。
        // 与 PATH 页的护栏对齐：要改 PATH 去那边勾选条目、预览后再写。
        if (IsPath(target) && string.IsNullOrWhiteSpace(value))
            return new EnvWriteResult
            {
                Ok = false,
                Message = "清空 Path 会让该侧所有 PATH 条目一起失效，这里不能这么写。"
                          + "请到「PATH 工具」页勾选条目后再应用（那里有预览和二次确认）。",
            };

        try
        {
            // 原来是非字符串类型时拒绝改写：写进去会把 REG_DWORD 变成 REG_SZ
            var kind = EnvStore.KindOf(target, scope);
            if (kind is not null && !EnvStore.IsStringKind(kind.Value))
                return new EnvWriteResult
                {
                    Ok = false,
                    Message = $"{target} 在{(scope == EnvScope.Machine ? "系统" : "用户")}侧是 {kind.Value} 类型，"
                              + "网页只支持字符串变量，改写会破坏它的类型。请用注册表编辑器修改。",
                };

            // 与「切换版本」时的写入对齐：目标不是 REG_EXPAND_SZ 时，%变量% 存进去不会被系统展开，
            // 字面值不指向任何目录。能展开成真实路径就先展开；展不开则明确标成无效，不静默存坏值。
            var stored = value;
            var note = "";
            var valueInvalid = false;
            if (value.Contains('%', StringComparison.Ordinal) && !EnvStore.IsExpandString(target, scope))
            {
                var expanded = Environment.ExpandEnvironmentVariables(value).Trim();
                if (!expanded.Contains('%', StringComparison.Ordinal)
                    && LooksLikePathValue(expanded)
                    && !string.Equals(expanded, value, StringComparison.Ordinal))
                {
                    stored = expanded;
                    note = $"；值里的 %变量% 已换成具体路径（{target} 不是 REG_EXPAND_SZ，系统不会替你展开）";
                }
                else if (LooksLikePathValue(value) || LooksLikePathValue(expanded))
                {
                    // 展不开、写法又明显是路径（%USERPROFILE%\x 或盘符开头）：这个值必然指向不了任何目录，
                    // 必须标成无效，不能让界面显示「正常」
                    valueInvalid = true;
                    note = $"；值里的 %变量% 无法展开，而 {target} 是普通字符串（不是 REG_EXPAND_SZ），"
                           + "系统不会替你展开，这个值无效";
                }
            }

            EnvStore.Set(target, stored, scope);
            return new EnvWriteResult
            {
                Ok = true,
                // 新值重新判断一次：界面直接用这个结果刷新「已失效」标记，不要沿用保存前的旧标记
                Invalid = valueInvalid || IsProbablyInvalidPath(target, stored),
                Message = $"已写入{(scope == EnvScope.Machine ? "系统" : "用户")}变量 {target}"
                          + (IsPath(target)
                              ? "（Path 建议改用「PATH 工具」页，可直接排序、去重并预览后再写入）"
                              : "")
                          + note,
            };
        }
        catch (UnauthorizedAccessException)
        {
            return FailWrite("写入环境变量被拒绝。修改系统变量需要以管理员身份运行。");
        }
        catch (Exception ex)
        {
            return FailWrite($"写入环境变量失败：{ex.Message}");
        }
    }

    /// <summary>网页「环境变量」页：删除单个变量（写入 null 即删除）。</summary>
    internal static EnvWriteResult DeleteEnv(string name, EnvScope scope)
    {
        var invalid = ValidateName(name);
        if (invalid is not null)
            return new EnvWriteResult { Ok = false, Message = invalid };

        var target = name.Trim();

        // 删除整条 PATH 比清空更彻底：这里不放行，请到 PATH 页取消勾选后再应用。
        if (IsPath(target))
            return new EnvWriteResult
            {
                Ok = false,
                Message = "不能在「环境变量」页删除 Path：删掉后该侧所有命令都会失效。"
                          + "请到「PATH 工具」页把不要的条目取消勾选，预览确认后再应用。",
            };

        // 本来就没有这个变量时别报「已删除（不可恢复）」，否则用户会以为删掉了什么。
        // 用类型判断（不是 GetRaw）：REG_DWORD 之类读不出字符串，但它确实存在，是可以删的。
        if (EnvStore.KindOf(target, scope) is null)
            return new EnvWriteResult
            {
                Ok = true,
                Message = $"{(scope == EnvScope.Machine ? "系统" : "用户")}侧没有变量 {target}，无需删除。",
            };

        try
        {
            EnvStore.Set(target, null, scope);
        }
        catch (UnauthorizedAccessException)
        {
            return FailWrite("删除环境变量被拒绝。修改系统变量需要以管理员身份运行。");
        }
        catch (Exception ex)
        {
            return FailWrite($"删除环境变量失败：{ex.Message}");
        }

        return new EnvWriteResult
        {
            Ok = true,
            Message = $"已删除{(scope == EnvScope.Machine ? "系统" : "用户")}变量 {target}（不可恢复）",
        };
    }

    private static string? ValidateName(string name)
    {
        if (string.IsNullOrWhiteSpace(name))
            return "变量名不能为空。";
        if (name.Contains('=', StringComparison.Ordinal) || name.Contains('\0'))
            return "变量名不能包含 = 或空字符。";
        if (name.Trim().Length > 255)
            return "变量名过长。";
        return null;
    }

    private static bool IsPath(string name) =>
        string.Equals(name.Trim(), "Path", StringComparison.OrdinalIgnoreCase);

    private static EnvWriteResult FailWrite(string message) => new() { Ok = false, Message = message };

    private static readonly EnvScope[] BothScopes = [EnvScope.Machine, EnvScope.User];

    /// <summary>
    /// 批量应用的读写缓冲：一次把两侧变量与 PATH 读进内存，逐套工具链在内存上串行处理，
    /// 最后按侧一次性写注册表、只广播一次 WM_SETTINGCHANGE（避免 10 套工具链广播 20 次）。
    /// </summary>
    internal sealed class ApplyBuffer
    {
        private readonly Dictionary<EnvScope, Dictionary<string, string?>> _now = new();
        private readonly Dictionary<EnvScope, Dictionary<string, string?>> _orig = new();
        private readonly Dictionary<EnvScope, string> _pathNow = new();
        private readonly Dictionary<EnvScope, string> _pathOrig = new();

        internal static ApplyBuffer Load()
        {
            var buffer = new ApplyBuffer();
            foreach (var scope in BothScopes)
            {
                var raw = new Dictionary<string, string?>(StringComparer.OrdinalIgnoreCase);
                try
                {
                    foreach (var kv in EnvStore.AllRaw(scope))
                        raw[kv.Key] = kv.Value;
                }
                catch
                {
                    // 读不到就当作空；真正的权限问题会在 CommitScope 里报出来
                }
                buffer._now[scope] = new Dictionary<string, string?>(raw, StringComparer.OrdinalIgnoreCase);
                buffer._orig[scope] = new Dictionary<string, string?>(raw, StringComparer.OrdinalIgnoreCase);
                buffer._pathNow[scope] = GetPathRaw(scope);
                buffer._pathOrig[scope] = buffer._pathNow[scope];
            }
            return buffer;
        }

        internal string? Get(string name, EnvScope scope) =>
            _now[scope].TryGetValue(name, out var value) ? value : null;

        internal void Set(string name, string? value, EnvScope scope) => _now[scope][name] = value;

        internal string Path(EnvScope scope) => _pathNow[scope];

        internal void SetPath(string value, EnvScope scope) => _pathNow[scope] = value;

        /// <summary>该侧是否还有没写下去的改动（PATH 或 Path 之外的变量）。</summary>
        internal bool HasChanges(EnvScope scope) =>
            !string.Equals(_pathOrig[scope], _pathNow[scope], StringComparison.Ordinal)
            || ChangedNames(scope).Count > 0;

        private List<string> ChangedNames(EnvScope scope) =>
            _orig[scope].Keys.Concat(_now[scope].Keys)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .Where(n => !IsPath(n)
                            && !string.Equals(_orig[scope].GetValueOrDefault(n), _now[scope].GetValueOrDefault(n), StringComparison.Ordinal))
                .ToList();

        /// <summary>把这一侧的改动写进注册表；返回哪些项写成功了、失败原因是什么（失败前可能已写进一部分）。</summary>
        internal CommitReport CommitScope(EnvScope scope)
        {
            var label = scope == EnvScope.Machine ? "系统" : "用户";
            if (!HasChanges(scope))
                return new CommitReport(null, []);
            if (!EnvStore.CanWrite(scope))
                return new CommitReport($"写入{label}侧被拒绝，请以管理员身份运行。", []);

            var written = new List<CommitEntry>();
            string? names = null;
            try
            {
                // 变量与 PATH 分开记：任一变量写失败时，能说清到底写进去了哪些。
                // 「删除」也要记成删除：批量失败时「另一侧同名变量被删掉」和「写入了新值」是两种副作用。
                foreach (var name in ChangedNames(scope))
                {
                    names = name;
                    var deleting = !_now[scope].TryGetValue(name, out var v) || v is null;
                    EnvStore.SetSilent(name, deleting ? null : v, scope);
                    written.Add(new CommitEntry(name, deleting));
                    names = null;
                }
                if (!string.Equals(_pathOrig[scope], _pathNow[scope], StringComparison.Ordinal))
                {
                    names = "Path";
                    EnvStore.SetSilent("Path", _pathNow[scope], scope);
                    written.Add(new CommitEntry("Path", false));
                }
                names = null;

                // 基准同步到已写入状态，同一侧不会被写第二遍
                _orig[scope] = new Dictionary<string, string?>(_now[scope], StringComparer.OrdinalIgnoreCase);
                _pathOrig[scope] = _pathNow[scope];
                return new CommitReport(null, written);
            }
            catch (UnauthorizedAccessException)
            {
                return new CommitReport(
                    $"写入{label}侧被拒绝，请以管理员身份运行。"
                    + (names is not null ? $"（第 {written.Count + 1} 项 {names} 未写入）" : ""),
                    written);
            }
            catch (Exception ex)
            {
                return new CommitReport(
                    $"写入{label}侧失败：{ex.Message}"
                    + (names is not null ? $"（第 {written.Count + 1} 项 {names} 未写入）" : ""),
                    written);
            }
        }
    }

    /// <summary>一次按侧提交的结果：错误文案（null = 成功）与已经真正落盘的项（写入 / 删除分开记）。</summary>
    internal sealed record CommitReport(string? Error, List<CommitEntry> Entries);

    /// <summary>已落盘的一项：<see cref="Deleted"/> 为 true 表示这一侧的这个变量被删掉了。</summary>
    internal sealed record CommitEntry(string Name, bool Deleted);

    /// <summary>
    /// 应用一套工具链的一个版本。传 <paramref name="buffer"/> 时只在内存里算与改，
    /// 由调用方最后统一提交；<paramref name="saveConfig"/> 为 false 时也不落配置。
    /// </summary>
    internal static ApplyResult Apply(
        AppConfig config,
        string toolchainName,
        string versionName,
        bool dryRun,
        string configPath,
        ApplyBuffer? buffer = null,
        bool saveConfig = true)
    {
        var matches = config.Toolchains
            .Where(t => string.Equals(t.Name, toolchainName, StringComparison.OrdinalIgnoreCase))
            .ToList();
        if (matches.Count == 0)
            return Fail($"找不到工具链：{toolchainName}");
        if (matches.Count > 1)
            return Fail($"配置里有 {matches.Count} 套工具链都叫 {toolchainName}，无法确定用哪一套。先改名再应用。");
        var tc = matches[0];
        if (!tc.Enabled)
            return Fail($"工具链 {tc.Name} 处于「不生效」状态，未做任何修改。");

        var version = tc.Versions.FirstOrDefault(v =>
            string.Equals(v.Name, versionName, StringComparison.OrdinalIgnoreCase));
        if (version is null)
            return Fail($"工具链 {tc.Name} 中没有版本：{versionName}");

        var writeEnv = !string.IsNullOrWhiteSpace(tc.EnvVar);
        if (writeEnv && string.IsNullOrWhiteSpace(version.Home))
            return Fail("该版本的环境变量值（Home）为空，无法写入环境变量。");

        var envTarget = ParseWriteScope(tc.EnvScope);
        var pathTarget = ParseWriteScope(tc.PathScope);

        // Home 里写了 %变量%：原值不是 REG_EXPAND_SZ 时系统不会展开，必须我们自己展开，
        // 否则会出现「保存校验按展开后放行、真正写进去却是字面值」的坏变量。
        var envValue = version.Home;
        if (writeEnv && envValue.Contains('%')
            && !EnvStore.IsExpandString(tc.EnvVar.Trim(), envTarget.Scope))
        {
            var expanded = Environment.ExpandEnvironmentVariables(envValue);
            if (expanded.Contains('%'))
                return Fail($"环境变量值里的变量无法展开：{envValue}。请填具体路径，或改用能被系统展开的写法。");
            envValue = expanded;
        }

        // 本版本要写入 PATH 的条目：显式条目优先，否则用模板展开 Home；都为空表示本工具链不写 PATH
        var rawPaths = version.Paths
            .Where(p => !string.IsNullOrWhiteSpace(p))
            .Select(p => p.Trim())
            .ToList();
        if (rawPaths.Count == 0 && !string.IsNullOrWhiteSpace(tc.PathTemplate))
            rawPaths.Add(PathUtil.Expand(tc.PathTemplate, tc.EnvVar, version.Home));

        // 同一真实目录的多种写法只留一条（按配置顺序，取第一条存在的）
        var toAddRaw = CollapseEquivalent(rawPaths, tc, version);
        var mergedAway = Math.Max(0, rawPaths.Count - toAddRaw.Count);

        // PATH 是普通字符串（REG_SZ）时 %变量% 不会被系统展开，写入前换成具体路径
        var pathExpands = PathExpandsVariables(pathTarget.Scope);
        var toAdd = toAddRaw.Select(p => PreparePathEntry(p, pathExpands, tc, version)).ToList();
        var expandedCount = toAddRaw.Zip(toAdd).Count(t => !string.Equals(t.Item1, t.Item2, StringComparison.Ordinal));

        // 网页已移除「是否加入 PATH」开关：有条目就写，没有就不写
        var writePath = toAdd.Count > 0;

        // 保存时已经校验过；这里再挡一次旧配置里残留的不存在条目，
        // 免得把一堆指向空目录的路径写进 PATH（现象就是「只有其中一条能用」）
        var missingEntries = toAdd.Where(p => MissingForCheck(p, tc, version)).ToList();
        if (missingEntries.Count > 0)
            return Fail($"PATH 条目不存在，未做任何修改：{string.Join("、", missingEntries)}");

        // 该工具链所有版本可能留下的 PATH 条目（变量形式 + 显式路径 + 展开后的实际路径）
        var stale = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (!string.IsNullOrWhiteSpace(tc.PathTemplate))
            stale.Add(tc.PathTemplate);
        foreach (var v in tc.Versions)
            foreach (var p in AllVersionPaths(tc, v))
                stale.Add(p);

        string pathBefore = "", pathAfter = "";
        string? otherPathAfter = null;
        if (writePath)
        {
            pathBefore = buffer?.Path(pathTarget.Scope) ?? GetPathRaw(pathTarget.Scope);
            var entries = PathUtil.Without(PathUtil.Split(pathBefore), stale);
            // 再按真实目录剔一遍：PATH 里若残留同一目录的别的写法（配置里没有的别名、
            // 历史遗留的 latest 写法等），一并删掉，只留上面选中的那一条
            var addKeys = new HashSet<string>(
                toAdd.Select(p => RealPath.Key(ExpandForCheck(p, tc, version))), StringComparer.OrdinalIgnoreCase);
            addKeys.Remove("");
            if (addKeys.Count > 0)
                entries = entries.Where(e => !addKeys.Contains(RealPath.Key(e))).ToList();
            entries.AddRange(toAdd);
            if (config.Settings.NormalizePathOnApply)
                entries = PathUtil.Normalize(PathUtil.Join(entries), sort: config.Settings.SortPathOnApply);
            pathAfter = PathUtil.Join(entries);

            // 「唯一」模式：把本条工具链的条目从另一侧 PATH 里移除
            if (pathTarget.Unique)
            {
                var otherPath = buffer?.Path(pathTarget.Other) ?? GetPathRaw(pathTarget.Other);
                var otherEntries = PathUtil.Without(PathUtil.Split(otherPath), stale.Concat(toAdd));
                if (config.Settings.NormalizePathOnApply)
                    otherEntries = PathUtil.Normalize(PathUtil.Join(otherEntries), sort: config.Settings.SortPathOnApply);
                otherPathAfter = PathUtil.Join(otherEntries);
            }
        }

        var touched = new List<EnvScope>();
        if (writeEnv)
        {
            touched.Add(envTarget.Scope);
            if (envTarget.Unique) touched.Add(envTarget.Other);
        }
        if (writePath)
        {
            touched.Add(pathTarget.Scope);
            if (pathTarget.Unique) touched.Add(pathTarget.Other);
        }

        if (!dryRun && buffer is not null)
        {
            // 批量：只改内存，最后由 ApplyAll 一次性写回并广播
            if (writeEnv)
            {
                buffer.Set(tc.EnvVar, envValue, envTarget.Scope);
                // 「唯一」：另一侧存在同名变量才删（存在性以内存快照为准）
                if (envTarget.Unique && buffer.Get(tc.EnvVar, envTarget.Other) is not null)
                    buffer.Set(tc.EnvVar, null, envTarget.Other);
            }
            if (writePath)
            {
                buffer.SetPath(pathAfter, pathTarget.Scope);
                if (otherPathAfter is not null
                    && !string.Equals(otherPathAfter, buffer.Path(pathTarget.Other), StringComparison.Ordinal))
                    buffer.SetPath(otherPathAfter, pathTarget.Other);
            }
            tc.Active = version.Name;
        }
        else if (!dryRun)
        {
            // 分两步写并记下已写入的部分：写环境变量成功、写 PATH 被拒时，不能只报一句「被拒绝」
            var done = new List<string>();
            var otherLabel = envTarget.Other == EnvScope.Machine ? "系统" : "用户";
            if (writeEnv)
            {
                try
                {
                    EnvStore.Set(tc.EnvVar, envValue, envTarget.Scope);
                }
                catch (UnauthorizedAccessException)
                {
                    return FailPart(done, "写入环境变量被拒绝。修改系统变量 / 系统 PATH 需要以管理员身份运行。");
                }
                catch (Exception ex)
                {
                    return FailPart(done, $"写入环境变量失败：{ex.Message}");
                }

                // 「唯一」：另一侧存在同名变量才删，避免无意义地要求系统写权限。
                // 删除与写入分开 try ——「删掉另一侧」也是已经发生的副作用，部分写入报告必须说全。
                if (envTarget.Unique && GetEnv(tc.EnvVar, envTarget.Other) is not null)
                {
                    try
                    {
                        EnvStore.Set(tc.EnvVar, null, envTarget.Other);
                        done.Add($"{tc.EnvVar}（{envTarget.Label}），并删除了{otherLabel}侧同名变量");
                    }
                    catch (UnauthorizedAccessException)
                    {
                        done.Add($"{tc.EnvVar}（{envTarget.Label}）");
                        return FailPart(done, $"已写入变量，但删除{otherLabel}侧同名变量被拒绝（需要管理员身份）。");
                    }
                    catch (Exception ex)
                    {
                        done.Add($"{tc.EnvVar}（{envTarget.Label}）");
                        return FailPart(done, $"已写入变量，但删除{otherLabel}侧同名变量失败：{ex.Message}");
                    }
                }
                else
                    done.Add($"{tc.EnvVar}（{envTarget.Label}）");
            }
            if (writePath)
            {
                var otherPathLabel = pathTarget.Other == EnvScope.Machine ? "系统" : "用户";
                var otherPathWritten = false;
                try
                {
                    EnvStore.Set("Path", pathAfter, pathTarget.Scope);
                    if (otherPathAfter is not null
                        && !string.Equals(otherPathAfter, GetPathRaw(pathTarget.Other), StringComparison.Ordinal))
                    {
                        EnvStore.Set("Path", otherPathAfter, pathTarget.Other);
                        otherPathWritten = true;
                    }
                    done.Add(otherPathWritten
                        ? $"PATH（{pathTarget.Label}），并整理了{otherPathLabel}侧 PATH"
                        : $"PATH（{pathTarget.Label}）");
                }
                catch (UnauthorizedAccessException)
                {
                    return FailPart(done, "写入 PATH 被拒绝。修改系统变量 / 系统 PATH 需要以管理员身份运行。");
                }
                catch (Exception ex)
                {
                    return FailPart(done, $"写入 PATH 失败：{ex.Message}");
                }
            }

            tc.Active = version.Name;
            if (saveConfig)
            {
                try
                {
                    ConfigStore.Save(config, configPath);
                }
                catch (Exception ex)
                {
                    // 环境变量 / PATH 已经写进去了，只是「当前激活版本」没记下来，
                    // 这里要明确说清，避免用户以为整次切换都失败了
                    return Fail($"环境变量与 PATH 已写入，但保存配置失败：{ex.Message}"
                                + "（激活版本未能记录，请检查配置文件所在目录是否可写）");
                }
            }
        }

        var parts = new List<string> { $"{tc.Name} → {version.Name}" };
        parts.Add(writeEnv
            ? $"{tc.EnvVar}={envValue}（{envTarget.Label}）"
            : "未配置环境变量名，跳过环境变量");
        parts.Add(writePath
            ? $"PATH 写入{pathTarget.Label}（{toAdd.Count} 条）"
              + (mergedAway > 0
                  ? $"；同版本另外 {mergedAway} 条与它们指向同一目录，已按顺序合并（只留第 1 条存在的写法）"
                  : "")
              + (expandedCount > 0
                  ? $"；{expandedCount} 条 %变量% 已展开成具体路径（该 PATH 是普通字符串，系统不会自动展开）"
                  : "")
            : "没有 PATH 条目，PATH 未改动");

        return new ApplyResult
        {
            Ok = true,
            DryRun = dryRun,
            Message = (dryRun ? "预览：" : buffer is null ? "已应用：" : "待写入：") + string.Join("；", parts),
            EnvVar = writeEnv ? tc.EnvVar : null,
            NewValue = writeEnv ? envValue : null,
            PathBefore = pathBefore,
            PathAfter = pathAfter,
            TouchedScopes = touched,
        };
    }

    /// <summary>
    /// 「立即应用」：一次读两侧快照 → 逐套工具链在内存里串行处理 → 按侧一次写回 → 广播一次。
    /// 这样 10 套工具链只有 1 次 WM_SETTINGCHANGE，而不是每套 2 次。
    /// </summary>
    internal static BatchApplyResult ApplyAll(AppConfig config, bool dryRun, string configPath)
    {
        var items = new List<ApplyItem>();
        var scopesOf = new Dictionary<string, List<EnvScope>>(StringComparer.OrdinalIgnoreCase);
        var skipped = new List<string>();
        // 跳过原因分开计数：收尾提示要指向真正的原因，不能一律让人去勾「生效」
        var dupSkipped = 0;
        var disabledSkipped = 0;
        var noActiveSkipped = 0;

        // 1) 先把两侧变量与 PATH 读进内存（只读一次）
        var buffer = dryRun ? null : ApplyBuffer.Load();

        // 1.5) 历史坏配置里可能还留着同名工具链：Apply 按名字取第一套，第二套会被静默写成第一套的版本。
        //      这种一套都不应用，直接报出来让用户改名（保存时已经拦住新写入的重名）。
        var dupNames = config.Toolchains
            .Where(t => !string.IsNullOrWhiteSpace(t.Name))
            .GroupBy(t => t.Name.Trim(), StringComparer.OrdinalIgnoreCase)
            .Where(g => g.Count() > 1)
            .Select(g => g.Key)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        // 1.8) 激活版本必须在逐套 Apply 之前采集：Apply 会边算边把 tc.Active 改成新版本，
        //      事后再采集拿到的就是新值，失败回滚会变成空操作（这条以前就是死代码）。
        var origActive = new Dictionary<string, string?>(StringComparer.OrdinalIgnoreCase);
        foreach (var tc in config.Toolchains)
            origActive[tc.Name] = tc.Active;

        // 2) 逐套在内存上处理，不碰注册表
        foreach (var tc in config.Toolchains)
        {
            if (string.IsNullOrWhiteSpace(tc.Name) || !tc.Enabled)
            {
                skipped.Add($"{tc.Name}（不生效）");
                if (!tc.Enabled) disabledSkipped++;
                continue;
            }
            if (dupNames.Contains(tc.Name.Trim()))
            {
                skipped.Add($"{tc.Name}（配置里有同名工具链，先改名再应用）");
                dupSkipped++;
                continue;
            }
            if (string.IsNullOrWhiteSpace(tc.Active))
            {
                skipped.Add($"{tc.Name}（没选激活版本）");
                noActiveSkipped++;
                continue;
            }
            var r = Apply(config, tc.Name, tc.Active, dryRun, configPath, buffer, saveConfig: false);
            items.Add(new ApplyItem
            {
                Toolchain = tc.Name,
                Version = tc.Active,
                Ok = r.Ok,
                Message = r.Message,
            });
            scopesOf[tc.Name] = r.TouchedScopes;
        }

        if (items.Count == 0)
        {
            // 提示要按真正的原因写：因重名被跳过时，光让人「勾选生效」是无效建议
            var hints = new List<string>();
            if (dupSkipped > 0)
                hints.Add("先把重名的工具链改成不同的名字");
            if (disabledSkipped > 0)
                hints.Add("勾选「生效」");
            if (noActiveSkipped > 0)
                hints.Add("在版本表格里选定激活版本");
            var tail = hints.Count == 0 ? "" : $"请{string.Join("、", hints)}后再试。";
            return new BatchApplyResult
            {
                Ok = false,
                DryRun = dryRun,
                Message = skipped.Count > 0
                    ? $"没有可应用的版本：{string.Join("、", skipped)}。{tail}"
                    : "还没有工具链，先到「工具链配置」新建。",
            };
        }

        // 3) 按侧一次写回，全部写完只广播一次
        if (buffer is not null)
        {
            var hadChanges = BothScopes.Where(buffer.HasChanges).ToList();
            var reports = new Dictionary<EnvScope, CommitReport>();
            foreach (var scope in hadChanges)
                reports[scope] = buffer.CommitScope(scope);

            // 只要有一侧真的落盘了（写入或删除）就广播：部分写入时另一侧也要看到新值
            if (reports.Values.Any(r => r.Entries.Count > 0))
                EnvStore.Broadcast();

            // 4) 某侧没写进去：涉及该侧的工具链一律算失败，并把激活版本回滚
            foreach (var item in items)
            {
                if (!item.Ok)
                    continue;
                var scopes = scopesOf.GetValueOrDefault(item.Toolchain) ?? [];
                var bad = scopes.Where(s => reports.TryGetValue(s, out var r) && r.Error is not null)
                                .Distinct().ToList();
                if (bad.Count == 0)
                    continue;
                item.Ok = false;
                var detail = bad.Select(s =>
                {
                    var r = reports[s];
                    return r.Entries.Count == 0
                        ? r.Error!
                        : $"{r.Error}（该侧已{string.Join("、", r.Entries.Select(e => (e.Deleted ? "删除 " : "写入 ") + e.Name))}，未回滚）";
                }).Distinct();
                item.Message = item.Message.Replace("待写入：", "未写入：")
                              + "；" + string.Join("；", detail);
                var tc = config.Toolchains.FirstOrDefault(t =>
                    string.Equals(t.Name, item.Toolchain, StringComparison.OrdinalIgnoreCase));
                if (tc is not null)
                    tc.Active = origActive[tc.Name];
            }

            // 5) 补正措辞：真正写下去的项「待写入 → 已应用」
            foreach (var item in items.Where(i => i.Ok))
                item.Message = item.Message.Replace("待写入：", "已应用：");

            // 5) 配置只保存一次（原来每套工具链存一次）
            if (items.Any(i => i.Ok))
            {
                try
                {
                    ConfigStore.Save(config, configPath);
                }
                catch (Exception ex)
                {
                    foreach (var item in items.Where(i => i.Ok).ToList())
                        item.Message += $"；环境变量与 PATH 已写入，但保存配置失败：{ex.Message}（激活版本未能记录）";
                }
            }
        }

        var failed = items.Count(i => !i.Ok);
        var okCount = items.Count - failed;
        var message = (dryRun ? "预览：" : "已应用：") + $"{okCount} 个工具链"
                      + (failed > 0 ? $"，{failed} 个失败" : "")
                      + (skipped.Count > 0 ? $"；跳过 {string.Join("、", skipped)}" : "");
        return new BatchApplyResult
        {
            Ok = failed == 0,
            DryRun = dryRun,
            Message = message,
            Items = items,
        };
    }

    // ---------------------------------------------------------------------
    // PATH 整理：系统 + 用户并排视图（网页 PATH 页与 CLI path 共用）
    // ---------------------------------------------------------------------

    private sealed class Marked(string value, bool isSystem)
    {
        public string Value { get; } = value;
        public bool IsSystem { get; } = isSystem;
        public string? Toolchain { get; set; }
        public string? Version { get; set; }
        public string? Conflict { get; set; }

        /// <summary>这条 PATH 指向的目录是否还在；不在即「已失效」，处理后会被自动移除。</summary>
        public bool Exists { get; set; } = true;

        /// <summary>条目里含 %变量%、但这侧 PATH 不会自动展开时，展开后的实际路径（界面提示用）。</summary>
        public string? Expanded { get; set; }
    }

    internal static PathViewResult BuildPathView(AppConfig config, bool sort, bool dedup)
    {
        var machineExpands = PathExpandsVariables(EnvScope.Machine);
        var userExpands = PathExpandsVariables(EnvScope.User);

        var system = PathUtil.Split(GetPathRaw(EnvScope.Machine))
            .Select(v => new Marked(v, true)).ToList();
        var user = PathUtil.Split(GetPathRaw(EnvScope.User))
            .Select(v => new Marked(v, false)).ToList();

        // 存在性探测可能是网络 I/O，50 多条串行最坏要一分多钟；并发一轮，每条各有 1.5 秒上限
        Parallel.ForEach(system.Concat(user), item =>
        {
            var (tc, ver) = Identify(item.Value, config);
            item.Toolchain = tc;
            item.Version = ver;
            item.Exists = PathEntryExists(item.Value);
        });

        foreach (var item in system.Concat(user))
        {
            // 只有这侧 PATH 是普通字符串（REG_SZ）时 %变量% 才不会展开，才需要提示实际路径
            var expands = item.IsSystem ? machineExpands : userExpands;
            if (!expands && item.Value.Contains('%', StringComparison.Ordinal))
            {
                var expanded = Environment.ExpandEnvironmentVariables(item.Value).Trim();
                item.Expanded = string.Equals(expanded, item.Value, StringComparison.OrdinalIgnoreCase) ? null : expanded;
            }
        }

        // 规则 1：系统里已有的条目，从用户处理后列表中自动移除
        var systemValues = PathUtil.KeySet(system.Select(s => s.Value));
        var duplicateUsers = PathUtil.KeySet(
            user.Where(u => systemValues.Contains(PathUtil.Canonical(u.Value))).Select(u => u.Value));

        // 规则 2：同一工具链、不同版本（只依据已配置的版本）标注徽标
        foreach (var tc in config.Toolchains)
        {
            if (string.IsNullOrWhiteSpace(tc.Name))
                continue;

            var systemVersions = VersionsOf(system, tc.Name);
            var userVersions = VersionsOf(user, tc.Name);
            var other = new Dictionary<bool, List<string>>
            {
                [true] = userVersions,
                [false] = systemVersions,
            };

            foreach (var item in system.Concat(user))
            {
                if (!string.Equals(item.Toolchain, tc.Name, StringComparison.OrdinalIgnoreCase))
                    continue;
                var conflicts = other[item.IsSystem]
                    .Where(v => !string.Equals(v, item.Version, StringComparison.OrdinalIgnoreCase))
                    .Distinct(StringComparer.OrdinalIgnoreCase)
                    .ToList();
                if (conflicts.Count > 0)
                    item.Conflict = $"{tc.Name} {item.Version} ↔ {string.Join(" / ", conflicts)}";
            }
        }

        var removedNotes = duplicateUsers.Count > 0
            ? $"；{duplicateUsers.Count} 条与系统重复的用户条目已自动移除"
            : "";
        var missing = system.Concat(user).Count(e => !e.Exists);
        var missingNote = missing > 0 ? $"；{missing} 条指向已不存在的目录，已从处理后列表剔除" : "";

        return new PathViewResult
        {
            SystemCurrent = ToViews(system, []),
            SystemProcessed = ToViews(Process(system, [], sort, dedup, machineExpands), []),
            UserCurrent = ToViews(user, duplicateUsers),
            UserProcessed = ToViews(Process(user, duplicateUsers, sort, dedup, userExpands), duplicateUsers),
            Message = $"系统 {system.Count} 条，用户 {user.Count} 条{missingNote}{removedNotes}。",
        };
    }

    internal static ApplyResult ApplyPathSelection(List<string>? system, List<string>? user, bool dryRun)
    {
        // 传 null 表示「这一侧不动」：只改勾选过的那一侧，避免误清空另一半 PATH。
        // 传空数组则是用户主动清空该侧（界面里取消勾选全部条目）。
        // 空值元素在这一层再过滤一次：任何入口漏过一个 "" 都会让整条 PATH 变成空串。
        var systemEntries = system?
            .Where(p => !string.IsNullOrWhiteSpace(p))
            .Select(p => PreparePathEntry(p, PathExpandsVariables(EnvScope.Machine), null, null))
            .ToList();
        var userEntries = user?
            .Where(p => !string.IsNullOrWhiteSpace(p))
            .Select(p => PreparePathEntry(p, PathExpandsVariables(EnvScope.User), null, null))
            .ToList();

        // 给了元素、清理后一条都不剩：拒绝。走到写入就是把整条 PATH 写成空串，且没有任何提示。
        // 空数组（Count 0）是调用方明确要求清空该侧，不在此列——CLI 那侧另有 --allow-empty 把关。
        if (system is { Count: > 0 } && systemEntries is { Count: 0 })
            return Fail("系统侧提交的条目里没有有效项（元素是 null、空串或只有空白），未做任何修改。");
        if (user is { Count: > 0 } && userEntries is { Count: 0 })
            return Fail("用户侧提交的条目里没有有效项（元素是 null、空串或只有空白），未做任何修改。");

        if (!dryRun)
        {
            try
            {
                if (systemEntries is not null)
                    EnvStore.Set("Path", string.Join(';', systemEntries), EnvScope.Machine);
                if (userEntries is not null)
                    EnvStore.Set("Path", string.Join(';', userEntries), EnvScope.User);
            }
            catch (UnauthorizedAccessException)
            {
                return Fail("写入系统 PATH 被拒绝，请以管理员身份运行。");
            }
            catch (Exception ex)
            {
                return Fail($"写入 PATH 失败：{ex.Message}");
            }
        }

        var parts = new List<string>
        {
            systemEntries is null ? "系统：未提供，保持原样" : $"系统 {systemEntries.Count} 条",
            userEntries is null ? "用户：未提供，保持原样" : $"用户 {userEntries.Count} 条",
        };
        return new ApplyResult
        {
            Ok = true,
            DryRun = dryRun,
            Message = (dryRun ? "预览：将写入 " : "已写入 ") + string.Join('、', parts)
                      + (dryRun ? "（未实际修改）" : " PATH。"),
        };
    }

    private static List<string> VersionsOf(IEnumerable<Marked> entries, string toolchain) =>
        entries.Where(e => string.Equals(e.Toolchain, toolchain, StringComparison.OrdinalIgnoreCase)
                           && !string.IsNullOrWhiteSpace(e.Version))
               .Select(e => e.Version!)
               .Distinct(StringComparer.OrdinalIgnoreCase)
               .ToList();

    private static List<Marked> Process(List<Marked> entries, HashSet<string> removed, bool sort, bool dedup, bool expands)
    {
        // 过滤：指向不存在目录的条目直接从「处理后」列表剔除（写入时也不会带上）；
        // 同时把 %变量% 换成实际要写入的具体路径（这侧 PATH 是 REG_SZ 时）
        var result = entries
            .Where(e => e.Exists && !removed.Contains(PathUtil.Canonical(e.Value)))
            .Select(e =>
            {
                var write = PreparePathEntry(e.Value, expands, null, null);
                return string.Equals(write, e.Value, StringComparison.Ordinal) ? e : Copy(e, write);
            })
            .ToList();
        if (dedup)
            // 按真实目录去重：C:\...\latest\jdk-21\bin 与 C:\...\jdk-21.0.13.0.2\bin 是同一目录，只留前面那条
            result = result.GroupBy(e => RealPath.Key(e.Value), StringComparer.OrdinalIgnoreCase)
                           .Select(g => Copy(g.First(), PathUtil.Canonical(g.First().Value)))
                           .ToList();
        if (sort)
            result = result.OrderBy(e => e.Value, StringComparer.OrdinalIgnoreCase).ToList();
        return result;
    }

    private static Marked Copy(Marked m, string value) =>
        new(value, m.IsSystem)
        {
            Toolchain = m.Toolchain, Version = m.Version, Conflict = m.Conflict, Exists = m.Exists,
            Expanded = m.Expanded,
        };

    private static List<PathEntryView> ToViews(IEnumerable<Marked> entries, HashSet<string> removed) =>
        entries.Select(e => new PathEntryView
        {
            Value = e.Value,
            Toolchain = e.Toolchain,
            Version = e.Version,
            Exists = e.Exists,
            Expanded = e.Expanded,
            Badge = !e.Exists
                ? "已失效"
                : string.IsNullOrEmpty(e.Conflict)
                    ? (removed.Contains(PathUtil.Canonical(e.Value)) ? "与系统重复 · 已移除" : null)
                    : e.Conflict,
            Removed = removed.Contains(PathUtil.Canonical(e.Value)),
        }).ToList();

    /// <summary>用配置中的版本 Home 识别 PATH 条目属于哪套工具链的哪个版本。</summary>
    private static (string? Toolchain, string? Version) Identify(string entry, AppConfig config)
    {
        foreach (var tc in config.Toolchains)
        {
            // 纯 PATH 工具链（EnvVar 为空）也要能认出归属，否则它在 PATH 页上一片空白
            if (string.IsNullOrWhiteSpace(tc.Name))
                continue;

            if (!string.IsNullOrWhiteSpace(tc.PathTemplate) && Eq(entry, tc.PathTemplate))
                return (tc.Name, tc.Active);

            foreach (var v in tc.Versions)
            {
                if (AllVersionPaths(tc, v).Any(p => Eq(entry, p)))
                    return (tc.Name, v.Name);
            }
        }
        return (null, null);
    }

    /// <summary>一个版本在 PATH 中可能出现的所有形式：显式路径 + 模板展开 + Home/bin。</summary>
    private static IEnumerable<string> AllVersionPaths(Toolchain tc, VersionEntry v)
    {
        var list = v.Paths.Where(p => !string.IsNullOrWhiteSpace(p)).Select(p => p.Trim()).ToList();
        if (!string.IsNullOrWhiteSpace(tc.PathTemplate))
            list.Add(PathUtil.Expand(tc.PathTemplate, tc.EnvVar, v.Home));
        if (!string.IsNullOrWhiteSpace(v.Home))
        {
            list.Add(v.Home);
            list.Add(Path.Combine(v.Home, "bin"));
        }
        return PathUtil.KeySet(list.Where(p => !string.IsNullOrWhiteSpace(p)));
    }

    private static bool Eq(string a, string b) => PathUtil.Same(a, b);

    private static ApplyResult Fail(string message) => new() { Ok = false, Message = message };

    /// <summary>部分写入后的失败：把已经落盘的部分点明，避免用户以为什么都没改。</summary>
    private static ApplyResult FailPart(List<string> done, string message) => new()
    {
        Ok = false,
        Message = done.Count == 0
            ? message
            : $"{message}（已写入 {string.Join("、", done)}，这部分不会回滚）",
    };
}
