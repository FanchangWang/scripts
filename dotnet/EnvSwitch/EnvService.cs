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
            // 用注册表的原样值展示：编辑保存时不会把 %USERPROFILE% 这类写法固化成具体路径
            foreach (var kv in raw)
                result[kv.Key] = new EnvVarView
                {
                    Value = kv.Value,
                    Invalid = IsProbablyInvalidPath(kv.Key, kv.Value),
                };
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

    /// <summary>
    /// 值看起来是个路径、但目录和文件都不存在 → 返回 true（用在校验用户手填的 Home / PATH 条目）。
    /// 不像是路径的值（变量名、URL、纯文本）和不正常的网络路径都算通过。
    /// </summary>
    internal static bool PathMissing(string value)
    {
        var expanded = Environment.ExpandEnvironmentVariables(value ?? "").Trim().Trim('"');
        if (string.IsNullOrWhiteSpace(expanded) || !LooksLikePath(expanded))
            return false;
        try
        {
            var probe = Task.Run(() => Directory.Exists(expanded) || File.Exists(expanded));
            return probe.Wait(1500) ? !probe.Result : false; // 超时判不了，先放行
        }
        catch
        {
            return false;
        }
    }

    /// <summary>PATH 值本身是不是 REG_EXPAND_SZ —— 只有这种类型，系统才会展开里面的 %变量%。</summary>
    internal static bool PathExpandsVariables(EnvScope scope) =>
        EnvStore.KindOf("Path", scope) == Microsoft.Win32.RegistryValueKind.ExpandString;

    /// <summary>按字面判断路径是否存在（不展开变量）。</summary>
    private static bool LiteralExists(string entry)
    {
        var text = (entry ?? "").Trim().Trim('"');
        if (text.Length == 0)
            return false;
        try
        {
            return Directory.Exists(text) || File.Exists(text);
        }
        catch
        {
            return false;
        }
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
        try
        {
            EnvStore.Set(name.Trim(), value, scope);
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
            EnvStore.Set(name.Trim(), null, scope);
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
            pathBefore = GetPathRaw(pathTarget.Scope);
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
                entries = PathUtil.Normalize(PathUtil.Join(entries));
            pathAfter = PathUtil.Join(entries);

            // 「唯一」模式：把本条工具链的条目从另一侧 PATH 里移除
            if (pathTarget.Unique)
            {
                var otherPath = GetPathRaw(pathTarget.Other);
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
                    EnvStore.Set(tc.EnvVar, version.Home, envTarget.Scope);
                    // 「唯一」：另一侧存在同名变量才删，避免无意义地要求系统写权限
                    if (envTarget.Unique && GetEnv(tc.EnvVar, envTarget.Other) is not null)
                        EnvStore.Set(tc.EnvVar, null, envTarget.Other);
                }
                if (writePath)
                {
                    EnvStore.Set("Path", pathAfter, pathTarget.Scope);
                    if (otherPathAfter is not null
                        && !string.Equals(otherPathAfter, GetPathRaw(pathTarget.Other), StringComparison.Ordinal))
                        EnvStore.Set("Path", otherPathAfter, pathTarget.Other);
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

        var parts = new List<string> { $"{tc.Name} → {version.Name}" };
        parts.Add(writeEnv
            ? $"{tc.EnvVar}={version.Home}（{envTarget.Label}）"
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
            Message = (dryRun ? "预览：" : "已应用：") + string.Join("；", parts),
            EnvVar = writeEnv ? tc.EnvVar : null,
            NewValue = writeEnv ? version.Home : null,
            PathBefore = pathBefore,
            PathAfter = pathAfter,
        };
    }

    /// <summary>
    /// 「立即应用」：把每套生效且有激活版本的工具链都应用一遍（配置里的工具链通常是多套，
    /// 只应用当前选中那套会让另一套停留在旧版本）。
    /// </summary>
    internal static BatchApplyResult ApplyAll(AppConfig config, bool dryRun, string configPath)
    {
        var items = new List<ApplyItem>();
        var skipped = new List<string>();
        foreach (var tc in config.Toolchains)
        {
            if (string.IsNullOrWhiteSpace(tc.Name) || !tc.Enabled)
            {
                skipped.Add($"{tc.Name}（不生效）");
                continue;
            }
            if (string.IsNullOrWhiteSpace(tc.Active))
            {
                skipped.Add($"{tc.Name}（没选激活版本）");
                continue;
            }
            var r = Apply(config, tc.Name, tc.Active, dryRun, configPath);
            items.Add(new ApplyItem
            {
                Toolchain = tc.Name,
                Version = tc.Active,
                Ok = r.Ok,
                Message = r.Message,
            });
        }

        if (items.Count == 0)
            return new BatchApplyResult
            {
                Ok = false,
                DryRun = dryRun,
                Message = skipped.Count > 0
                    ? $"没有可应用的版本：{string.Join("、", skipped)}。先勾选「生效」并在版本表格选定激活版本。"
                    : "还没有工具链，先到「工具链配置」新建。",
            };

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
    // PATH 整理：系统 + 用户并排视图（网页 PATH 页与 CLI path / env 共用）
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

        foreach (var item in system.Concat(user))
        {
            var (tc, ver) = Identify(item.Value, config);
            item.Toolchain = tc;
            item.Version = ver;
            item.Exists = PathEntryExists(item.Value);

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
        var systemEntries = system?
            .Select(p => PreparePathEntry(p, PathExpandsVariables(EnvScope.Machine), null, null)).ToList();
        var userEntries = user?
            .Select(p => PreparePathEntry(p, PathExpandsVariables(EnvScope.User), null, null)).ToList();

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
