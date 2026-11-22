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

    /// <summary>网页「环境变量」页：读取全部变量，并标出值所指向的目录已不存在的项。</summary>
    internal static Dictionary<string, EnvVarView> GetAllDetailed(EnvScope scope)
    {
        var result = new Dictionary<string, EnvVarView>(StringComparer.OrdinalIgnoreCase);
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
        try
        {
            return !Directory.Exists(expanded) && !File.Exists(expanded);
        }
        catch
        {
            return false;
        }
    }

    private static bool LooksLikePath(string v) =>
        (v.Length >= 3 && char.IsLetter(v[0]) && v[1] == ':' && (v[2] == '\\' || v[2] == '/'))
        || v.StartsWith(@"\\", StringComparison.Ordinal);

    /// <summary>PATH 条目是否还指向存在的目录；UNC/超时等无法判断的情况一律当成存在，避免误删。</summary>
    internal static bool PathEntryExists(string entry)
    {
        var expanded = Environment.ExpandEnvironmentVariables(entry).Trim().Trim('"');
        if (string.IsNullOrWhiteSpace(expanded))
            return false;
        var target = PathUtil.Canonical(expanded);
        try
        {
            var probe = Task.Run(() => Directory.Exists(target));
            return probe.Wait(1500) ? probe.Result : true;
        }
        catch
        {
            return true;
        }
    }

    /// <summary>网页「环境变量」页：写入单个变量的值（空串即置空，不当成删除）。</summary>
    internal static EnvWriteResult SetEnv(string name, string value, EnvScope scope)
    {
        var invalid = ValidateName(name);
        if (invalid is not null)
            return new EnvWriteResult { Ok = false, Message = invalid };
        try
        {
            Environment.SetEnvironmentVariable(name.Trim(), value, Target(scope));
        }
        catch (UnauthorizedAccessException)
        {
            return FailWrite("写入环境变量被拒绝。修改系统变量需要以管理员身份运行。");
        }
        catch (Exception ex)
        {
            return FailWrite($"写入环境变量失败：{ex.Message}");
        }

        var hint = IsPath(name)
            ? "（Path 建议改用「PATH 工具」页，可直接排序、去重并预览后再写入）"
            : "";
        return new EnvWriteResult
        {
            Ok = true,
            Message = $"已写入{(scope == EnvScope.Machine ? "系统" : "用户")}变量 {name.Trim()}{hint}",
        };
    }

    /// <summary>网页「环境变量」页：删除单个变量（写入 null 即删除）。</summary>
    internal static EnvWriteResult DeleteEnv(string name, EnvScope scope)
    {
        var invalid = ValidateName(name);
        if (invalid is not null)
            return new EnvWriteResult { Ok = false, Message = invalid };
        try
        {
            Environment.SetEnvironmentVariable(name.Trim(), null, Target(scope));
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
            Message = $"已删除{(scope == EnvScope.Machine ? "系统" : "用户")}变量 {name.Trim()}（不可恢复）",
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

    internal static ApplyResult Apply(
        AppConfig config,
        string toolchainName,
        string versionName,
        bool dryRun,
        string configPath)
    {
        var tc = config.Toolchains.FirstOrDefault(t =>
            string.Equals(t.Name, toolchainName, StringComparison.OrdinalIgnoreCase));
        if (tc is null)
            return Fail($"找不到工具链：{toolchainName}");
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

        // 本版本要写入 PATH 的条目：显式条目优先，否则用模板展开 Home；都为空表示本工具链不写 PATH
        var toAdd = version.Paths
            .Where(p => !string.IsNullOrWhiteSpace(p))
            .Select(p => p.Trim())
            .ToList();
        if (toAdd.Count == 0 && !string.IsNullOrWhiteSpace(tc.PathTemplate))
            toAdd.Add(PathUtil.Expand(tc.PathTemplate, tc.EnvVar, version.Home));
        // 网页已移除「是否加入 PATH」开关：有条目就写，没有就不写
        var writePath = toAdd.Count > 0;

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
            pathBefore = GetEnv("Path", pathTarget.Scope) ?? "";
            var entries = PathUtil.Without(PathUtil.Split(pathBefore), stale);
            entries.AddRange(toAdd);
            if (config.Settings.NormalizePathOnApply)
                entries = PathUtil.Normalize(PathUtil.Join(entries));
            pathAfter = PathUtil.Join(entries);

            // 「唯一」模式：把本条工具链的条目从另一侧 PATH 里移除
            if (pathTarget.Unique)
            {
                var otherPath = GetEnv("Path", pathTarget.Other) ?? "";
                var otherEntries = PathUtil.Without(PathUtil.Split(otherPath), stale.Concat(toAdd));
                if (config.Settings.NormalizePathOnApply)
                    otherEntries = PathUtil.Normalize(PathUtil.Join(otherEntries));
                otherPathAfter = PathUtil.Join(otherEntries);
            }
        }

        if (!dryRun)
        {
            try
            {
                if (writeEnv)
                {
                    Environment.SetEnvironmentVariable(tc.EnvVar, version.Home, Target(envTarget.Scope));
                    // 「唯一」：另一侧存在同名变量才删，避免无意义地要求系统写权限
                    if (envTarget.Unique && GetEnv(tc.EnvVar, envTarget.Other) is not null)
                        Environment.SetEnvironmentVariable(tc.EnvVar, null, Target(envTarget.Other));
                }
                if (writePath)
                {
                    Environment.SetEnvironmentVariable("Path", pathAfter, Target(pathTarget.Scope));
                    if (otherPathAfter is not null
                        && !string.Equals(otherPathAfter, GetEnv("Path", pathTarget.Other), StringComparison.Ordinal))
                        Environment.SetEnvironmentVariable("Path", otherPathAfter, Target(pathTarget.Other));
                }
            }
            catch (UnauthorizedAccessException)
            {
                return Fail("写入环境变量被拒绝。修改系统变量 / 系统 PATH 需要以管理员身份运行。");
            }
            catch (Exception ex)
            {
                return Fail($"写入环境变量失败：{ex.Message}");
            }

            tc.Active = version.Name;
            ConfigStore.Save(config, configPath);
        }

        var parts = new List<string> { $"{tc.Name} → {version.Name}" };
        parts.Add(writeEnv
            ? $"{tc.EnvVar}={version.Home}（{envTarget.Label}）"
            : "未配置环境变量名，跳过环境变量");
        parts.Add(writePath
            ? $"PATH 写入{pathTarget.Label}（{toAdd.Count} 条）"
            : "没有 PATH 条目，PATH 未改动");

        return new ApplyResult
        {
            Ok = true,
            DryRun = dryRun,
            Message = (dryRun ? "预览：" : "已应用：") + string.Join("；", parts),
            EnvVar = writeEnv ? tc.EnvVar : null,
            NewValue = writeEnv ? version.Home : null,
            PathBefore = pathBefore,
            PathAfter = pathAfter,
        };
    }

    internal static PathResult NormalizePath(EnvScope scope, bool sort, bool dedup, bool dryRun)
    {
        var before = GetEnv("Path", scope) ?? "";
        var after = PathUtil.Join(PathUtil.Normalize(before, sort, dedup));

        if (!dryRun)
        {
            try
            {
                Environment.SetEnvironmentVariable("Path", after, Target(scope));
            }
            catch (UnauthorizedAccessException)
            {
                return new PathResult { Ok = false, Message = "写入 PATH 被拒绝，请以管理员身份运行。" };
            }
            catch (Exception ex)
            {
                return new PathResult { Ok = false, Message = $"写入 PATH 失败：{ex.Message}" };
            }
        }

        return new PathResult
        {
            Ok = true,
            DryRun = dryRun,
            Before = before,
            After = after,
            BeforeCount = PathUtil.Split(before).Count,
            AfterCount = PathUtil.Split(after).Count,
            Message = dryRun ? "预览完成，未写入。" : "PATH 已更新。",
        };
    }

    // ---------------------------------------------------------------------
    // 网页 PATH 工具：系统 + 用户并排视图
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
    }

    internal static PathViewResult BuildPathView(AppConfig config, bool sort, bool dedup)
    {
        var system = PathUtil.Split(GetEnv("Path", EnvScope.Machine))
            .Select(v => new Marked(v, true)).ToList();
        var user = PathUtil.Split(GetEnv("Path", EnvScope.User))
            .Select(v => new Marked(v, false)).ToList();

        foreach (var item in system.Concat(user))
        {
            var (tc, ver) = Identify(item.Value, config);
            item.Toolchain = tc;
            item.Version = ver;
            item.Exists = PathEntryExists(item.Value);
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
            SystemProcessed = ToViews(Process(system, [], sort, dedup), []),
            UserCurrent = ToViews(user, duplicateUsers),
            UserProcessed = ToViews(Process(user, duplicateUsers, sort, dedup), duplicateUsers),
            Message = $"系统 {system.Count} 条，用户 {user.Count} 条{missingNote}{removedNotes}。",
        };
    }

    internal static ApplyResult ApplyPathSelection(List<string>? system, List<string>? user, bool dryRun)
    {
        if (!dryRun)
        {
            try
            {
                Environment.SetEnvironmentVariable(
                    "Path", string.Join(';', system ?? []), EnvironmentVariableTarget.Machine);
                Environment.SetEnvironmentVariable(
                    "Path", string.Join(';', user ?? []), EnvironmentVariableTarget.User);
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

        return new ApplyResult
        {
            Ok = true,
            DryRun = dryRun,
            Message = dryRun
                ? $"预览：将写入 系统 {(system?.Count ?? 0)} 条、用户 {(user?.Count ?? 0)} 条（未实际修改）"
                : $"已写入 系统 {(system?.Count ?? 0)} 条、用户 {(user?.Count ?? 0)} 条 PATH。",
        };
    }

    private static List<string> VersionsOf(IEnumerable<Marked> entries, string toolchain) =>
        entries.Where(e => string.Equals(e.Toolchain, toolchain, StringComparison.OrdinalIgnoreCase)
                           && !string.IsNullOrWhiteSpace(e.Version))
               .Select(e => e.Version!)
               .Distinct(StringComparer.OrdinalIgnoreCase)
               .ToList();

    private static List<Marked> Process(List<Marked> entries, HashSet<string> removed, bool sort, bool dedup)
    {
        // 过滤：指向不存在目录的条目直接从「处理后」列表剔除（写入时也不会带上）
        var result = entries.Where(e => e.Exists && !removed.Contains(PathUtil.Canonical(e.Value))).ToList();
        if (dedup)
            result = result.GroupBy(e => PathUtil.Canonical(e.Value), StringComparer.OrdinalIgnoreCase)
                           .Select(g => Copy(g.First(), g.Key))
                           .ToList();
        if (sort)
            result = result.OrderBy(e => e.Value, StringComparer.OrdinalIgnoreCase).ToList();
        return result;
    }

    private static Marked Copy(Marked m, string value) =>
        new(value, m.IsSystem)
        {
            Toolchain = m.Toolchain, Version = m.Version, Conflict = m.Conflict, Exists = m.Exists,
        };

    private static List<PathEntryView> ToViews(IEnumerable<Marked> entries, HashSet<string> removed) =>
        entries.Select(e => new PathEntryView
        {
            Value = e.Value,
            Toolchain = e.Toolchain,
            Version = e.Version,
            Exists = e.Exists,
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
            if (string.IsNullOrWhiteSpace(tc.Name) || string.IsNullOrWhiteSpace(tc.EnvVar))
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
}
