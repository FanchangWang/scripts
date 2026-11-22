using System.Diagnostics;
using System.Text;
using System.Text.Json;
using EnvSwitch;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;

Console.OutputEncoding = Encoding.UTF8;

var jsonOptions = new JsonSerializerOptions
{
    PropertyNameCaseInsensitive = true,
    PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
};

// ---------- 命令行解析 ----------
var argv = args.ToList();
string? configPath = null;
for (var i = 0; i < argv.Count; i++)
{
    if (!string.Equals(argv[i], "--config", StringComparison.OrdinalIgnoreCase))
        continue;
    if (i + 1 >= argv.Count)
    {
        Console.Error.WriteLine("--config 后面需要指定配置文件路径。");
        return 1;
    }
    configPath = argv[i + 1];
    argv.RemoveAt(i + 1);
    argv.RemoveAt(i);
    i--;
}
configPath ??= ConfigStore.DefaultPath;

if (argv.Count == 0)
{
    return await RunWeb(configPath, jsonOptions, autoOpenBrowser: true, portOverride: null);
}

switch (argv[0].ToLowerInvariant())
{
    case "help" or "-h" or "--help":
        PrintHelp();
        return 0;

    case "config":
    {
        Console.WriteLine($"配置文件：{configPath}");
        try
        {
            Console.WriteLine(ConfigStore.ReadText(configPath));
        }
        catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException)
        {
            // 与 Load 同一口径：默认路径 / 所在目录可达 = 还没生成；目录不可达 = 这次读不了
            var dir = Path.GetDirectoryName(configPath);
            if (string.Equals(configPath, ConfigStore.DefaultPath, StringComparison.OrdinalIgnoreCase)
                || string.IsNullOrEmpty(dir) || Directory.Exists(dir))
            {
                Console.WriteLine("（尚未生成，运行一次后自动生成）");
                return 0;
            }
            Console.Error.WriteLine($"[失败] {configPath}（配置文件所在目录不存在或无法访问：{dir}）");
            return 1;
        }
        catch (ConfigUnreadableException ex)
        {
            Console.Error.WriteLine($"[失败] {ex.Message}");
            return 1;
        }
        return 0;
    }

    case "list" or "ls":
        PrintList(configPath);
        return 0;

    case "use" or "apply":
    {
        if (RejectScope("use", argv))
            return 1;
        if (RejectUnknownFlags("use", argv, "--dry-run"))
            return 1;
        // 位置参数只有两个：工具链、版本。--dry-run 这类选项不算，避免把选项当成版本名
        var positional = argv.Skip(1)
            .Where(a => !a.StartsWith("--", StringComparison.Ordinal))
            .ToList();
        if (positional.Count < 2)
        {
            Console.Error.WriteLine("用法：EnvSwitch use <工具链> <版本> [--dry-run]");
            return 1;
        }
        if (positional.Count > 2)
        {
            Console.Error.WriteLine($"多余的参数：{string.Join(" ", positional.Skip(2))}。use 只接受工具链和版本两个位置参数。");
            return 1;
        }
        var dryRun = argv.Any(a => string.Equals(a, "--dry-run", StringComparison.OrdinalIgnoreCase));
        var result = EnvService.Apply(LoadConfigOrExit(configPath), positional[0], positional[1], dryRun, configPath);
        Console.WriteLine($"[{(result.Ok ? "OK" : "失败")}] {result.Message}");
        if (result.Ok)
        {
            Console.WriteLine($"  {result.EnvVar} = {result.NewValue}");
            Console.WriteLine("  PATH(修改后)：");
            foreach (var entry in PathUtil.Split(result.PathAfter))
                Console.WriteLine($"    {entry}");
        }
        return result.Ok ? 0 : 1;
    }

    // 环境变量与 PATH 一起应用的批量版本：等同于网页「立即应用全部」
    // 这是唯一会直接写入的命令，所以参数从严：拼错/多余参数一律报错，不允许静默真实写入。
    case "all" or "apply-all":
    {
        if (RejectScope("all", argv))
            return 1;
        if (RejectUnknownOptions("all", argv, "--dry-run"))
            return 1;
        var dryRun = argv.Any(a => string.Equals(a, "--dry-run", StringComparison.OrdinalIgnoreCase));
        var batch = EnvService.ApplyAll(LoadConfigOrExit(configPath), dryRun, configPath);
        Console.WriteLine($"[{(batch.Ok ? "OK" : "有失败")}] {batch.Message}");
        foreach (var item in batch.Items)
            Console.WriteLine($"  {(item.Ok ? "✔" : "✘")} {item.Toolchain} → {item.Version}：{item.Message}");
        return batch.Ok ? 0 : 1;
    }

    // PATH 整理：系统 + 用户两侧一起算、一起写（只写一侧会让条目错位，所以没有 --scope）
    case "path":
    {
        if (RejectScope("path", argv))
            return 1;
        if (RejectUnknownOptions("path", argv, "--no-sort", "--no-dedup", "--apply", "--allow-empty"))
            return 1;
        return RunPathBoth(
            configPath,
            sort: !argv.Any(a => string.Equals(a, "--no-sort", StringComparison.OrdinalIgnoreCase)),
            dedup: !argv.Any(a => string.Equals(a, "--no-dedup", StringComparison.OrdinalIgnoreCase)),
            apply: argv.Any(a => string.Equals(a, "--apply", StringComparison.OrdinalIgnoreCase)),
            allowEmpty: argv.Any(a => string.Equals(a, "--allow-empty", StringComparison.OrdinalIgnoreCase)));
    }

    case "serve":
    {
        var port = 0;
        var noBrowser = false;
        for (var i = 1; i < argv.Count; i++)
        {
            var a = argv[i];
            if (string.Equals(a, "--port", StringComparison.OrdinalIgnoreCase))
            {
                if (i + 1 >= argv.Count || !int.TryParse(argv[i + 1], out var parsed) || parsed is < 1 or > 65535)
                {
                    Console.Error.WriteLine("--port 后面需要 1–65535 之间的端口号。");
                    return 1;
                }
                port = parsed;
                i++;
            }
            else if (string.Equals(a, "--no-browser", StringComparison.OrdinalIgnoreCase))
                noBrowser = true;
            else
            {
                Console.Error.WriteLine($"多余的参数：{a}。serve 只支持：--port N / --no-browser。");
                return 1;
            }
        }
        return await RunWeb(configPath, jsonOptions, autoOpenBrowser: !noBrowser, portOverride: port > 0 ? port : null);
    }

    default:
        Console.Error.WriteLine($"未知命令：{argv[0]}。运行 EnvSwitch --help 查看用法。");
        return 1;
}

// ---------- 网页模式 ----------
static async Task<int> RunWeb(string configPath, JsonSerializerOptions jsonOptions, bool autoOpenBrowser, int? portOverride)
{
    AppConfig config;
    try
    {
        config = ConfigStore.Load(configPath);
    }
    catch (ConfigUnreadableException ex)
    {
        Console.Error.WriteLine($"[失败] {ex.Message}");
        Console.Error.WriteLine("配置文件只是暂时读不到（不是损坏），未做任何修改。请检查是否被其它程序占用或缺少读取权限后重试。");
        return 1;
    }
    var port = portOverride ?? config.Settings.Port;

    // 端口是能存进配置的：非法值不能让程序直接崩在堆栈里，降级成自动分配即可
    if (port is < 0 or > 65535)
    {
        Console.Error.WriteLine($"[警告] 端口 {port} 不在 0–65535 范围内，已改为自动分配。");
        port = 0;
    }

    var builder = WebApplication.CreateBuilder();
    builder.Logging.ClearProviders();
    // 只留 warning/error：既不刷屏，又能在 500 时打印原因（原来全部关掉，异常变成空响应体）
    builder.Logging.AddSimpleConsole(o => o.SingleLine = true);
    builder.Logging.SetMinimumLevel(LogLevel.Warning);
    builder.WebHost.UseUrls($"http://127.0.0.1:{port}");

    var app = builder.Build();

    // 本地接口的三道闸 + 统一异常出口。
    // 只监听 127.0.0.1 挡不住浏览器：任意网页都能用 no-cors 简单请求盲打这里改环境变量 / 清空 PATH。
    var allowedHosts = new[] { "127.0.0.1", "localhost", "[::1]", "::1" };
    app.Use(async (ctx, next) =>
    {
        try
        {
            // 1) Host 白名单：挡 DNS rebinding（把自己的域名解析到 127.0.0.1 时 Host 仍是他的域名）
            if (!allowedHosts.Contains(ctx.Request.Host.Host, StringComparer.OrdinalIgnoreCase))
            {
                ctx.Response.StatusCode = StatusCodes.Status400BadRequest;
                return;
            }

            if (HttpMethods.IsPost(ctx.Request.Method))
            {
                // 2) 只收 JSON：text/plain 属于 CORS 安全列表类型（浏览器不发预检），必须挡掉
                if (ctx.Request.ContentType?.StartsWith("application/json", StringComparison.OrdinalIgnoreCase) != true)
                {
                    ctx.Response.StatusCode = StatusCodes.Status415UnsupportedMediaType;
                    return;
                }

                // 3) 带 Origin 时必须同源；跨站 JSON 请求会触发预检，而本服务不返回 CORS 头
                var origin = ctx.Request.Headers.Origin.ToString();
                if (origin.Length > 0
                    && (!Uri.TryCreate(origin, UriKind.Absolute, out var parsed)
                        || !allowedHosts.Contains(parsed.Host, StringComparer.OrdinalIgnoreCase)))
                {
                    ctx.Response.StatusCode = StatusCodes.Status403Forbidden;
                    return;
                }
            }

            await next();
        }
        catch (ConfigUnreadableException ex)
        {
            // 配置文件在、但暂时读不到：绝不能当成「配置损坏」走默认值，
            // 更不能顺手 Save（那会把用户的真配置覆盖掉）。这里只报错，什么都不改。
            app.Logger.LogWarning(ex, "配置暂时读不到：{Method} {Path}", ctx.Request.Method, ctx.Request.Path);
            if (ctx.Response.HasStarted)
                return;
            ctx.Response.Clear();
            ctx.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            ctx.Response.ContentType = "application/json; charset=utf-8";
            await ctx.Response.WriteAsJsonAsync(new
            {
                ok = false,
                message = $"配置文件暂时读不到，未做任何修改：{ex.Message}"
                          + "（可能是被其它程序占用或缺少读取权限，稍后重试即可）",
            });
        }
        catch (JsonException ex)
        {
            // 请求体不是合法 JSON 属于客户端错误，别混进 500（也就不会被当成服务端 bug 排查）
            app.Logger.LogWarning(ex, "请求体解析失败：{Method} {Path}", ctx.Request.Method, ctx.Request.Path);
            if (ctx.Response.HasStarted)
                return;
            ctx.Response.Clear();
            ctx.Response.StatusCode = StatusCodes.Status400BadRequest;
            ctx.Response.ContentType = "application/json; charset=utf-8";
            await ctx.Response.WriteAsJsonAsync(new { ok = false, message = $"请求体不是合法 JSON：{ex.Message}" });
        }
        catch (Exception ex)
        {
            app.Logger.LogError(ex, "未处理异常：{Method} {Path}", ctx.Request.Method, ctx.Request.Path);
            if (ctx.Response.HasStarted)
                return;
            ctx.Response.Clear();
            ctx.Response.StatusCode = StatusCodes.Status500InternalServerError;
            ctx.Response.ContentType = "application/json; charset=utf-8";
            await ctx.Response.WriteAsJsonAsync(new { ok = false, message = $"服务内部错误：{ex.Message}" });
        }
    });

    app.MapGet("/", () => Results.Content(Ui.Html, "text/html; charset=utf-8"));

    app.MapGet("/api/state", () =>
    {
        var cfg = ConfigStore.Load(configPath);
        // 历史坏配置（重名 / 空名）不应该让整页打不开：逐条填充，重名保留第一套
        var snapshot = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var t in cfg.Toolchains)
        {
            var name = t.Name ?? "";
            if (name.Length == 0 || snapshot.ContainsKey(name))
                continue;
            snapshot[name] = EnvService.GetEnv(t.EnvVar, EnvService.ParseWriteScope(t.EnvScope).Scope) ?? "";
        }
        return Results.Json(new
        {
            configPath,
            config = cfg,
            current = snapshot,
            userPath = EnvService.GetEnv("Path", EnvScope.User) ?? "",
            machinePath = EnvService.GetEnv("Path", EnvScope.Machine) ?? "",
        }, jsonOptions);
    });

    app.MapGet("/api/env", (string? scope) =>
        Results.Json(EnvService.GetAllDetailed(EnvService.ParseScope(scope)), jsonOptions));

    app.MapPost("/api/env/set", async (HttpContext ctx) =>
    {
        var req = await JsonSerializer.DeserializeAsync<EnvSetRequest>(ctx.Request.Body, jsonOptions);
        if (req is null)
            return Results.BadRequest(new { ok = false, message = "请求为空。" });
        var result = EnvService.SetEnv(req.Name ?? "", req.Value ?? "", EnvService.ParseScope(req.Scope));
        return Results.Json(result, jsonOptions);
    });

    app.MapPost("/api/env/delete", async (HttpContext ctx) =>
    {
        var req = await JsonSerializer.DeserializeAsync<EnvDeleteRequest>(ctx.Request.Body, jsonOptions);
        if (req is null)
            return Results.BadRequest(new { ok = false, message = "请求为空。" });
        var result = EnvService.DeleteEnv(req.Name ?? "", EnvService.ParseScope(req.Scope));
        return Results.Json(result, jsonOptions);
    });

    app.MapPost("/api/config", async (HttpContext ctx) =>
    {
        var incoming = await JsonSerializer.DeserializeAsync<AppConfig>(ctx.Request.Body, jsonOptions);
        if (incoming is null)
            return Results.BadRequest(new { ok = false, message = "配置内容为空。" });

        // 先归一：剔除数组里的 null 元素、补集合、把 null 字符串补成空串，
        // 否则后面的校验会直接 NRE（表现就是空响应体的 500）
        ConfigStore.Migrate(incoming);

        if (incoming.Toolchains.Any(t => string.IsNullOrWhiteSpace(t.Name)))
            return Results.BadRequest(new { ok = false, message = "工具链名称不能为空（环境变量名可以留空，表示不写环境变量）。" });

        // 路径类字段先校验存在性：手填错的 Home / PATH 条目不该被保存进去
        var errors = EnvService.ValidateConfig(incoming);
        if (errors.Count > 0)
            return Results.Json(new
            {
                ok = false,
                message = $"有 {errors.Count} 处配置有误，未保存：{string.Join("；", errors.Select(e => e.Message))}",
                errors,
            }, jsonOptions, statusCode: StatusCodes.Status400BadRequest);

        try
        {
            ConfigStore.Save(incoming, configPath);
        }
        catch (Exception ex)
        {
            return Results.Json(new { ok = false, message = $"配置保存失败：{ex.Message}" },
                jsonOptions, statusCode: StatusCodes.Status500InternalServerError);
        }
        return Results.Ok(new { ok = true, message = "配置已保存。" });
    });

    app.MapPost("/api/apply", async (HttpContext ctx) =>
    {
        var req = await JsonSerializer.DeserializeAsync<ApplyRequest>(ctx.Request.Body, jsonOptions);
        if (req is null || string.IsNullOrWhiteSpace(req.Toolchain) || string.IsNullOrWhiteSpace(req.Version))
            return Results.BadRequest(new { ok = false, message = "缺少工具链或版本。" });

        var cfg = ConfigStore.Load(configPath);
        return Results.Json(EnvService.Apply(cfg, req.Toolchain, req.Version, req.DryRun, configPath), jsonOptions);
    });

    app.MapPost("/api/apply/all", async (HttpContext ctx) =>
    {
        var req = await JsonSerializer.DeserializeAsync<ApplyRequest>(ctx.Request.Body, jsonOptions);
        var cfg = ConfigStore.Load(configPath);
        return Results.Json(EnvService.ApplyAll(cfg, req?.DryRun ?? false, configPath), jsonOptions);
    });

    app.MapPost("/api/path/view", async (HttpContext ctx) =>
    {
        var req = await JsonSerializer.DeserializeAsync<PathRequest>(ctx.Request.Body, jsonOptions);
        var cfg = ConfigStore.Load(configPath);
        return Results.Json(
            EnvService.BuildPathView(cfg, req?.Sort ?? true, req?.Dedup ?? true), jsonOptions);
    });

    app.MapPost("/api/path/apply", async (HttpContext ctx) =>
    {
        var req = await JsonSerializer.DeserializeAsync<PathApplyRequest>(ctx.Request.Body, jsonOptions);
        if (req is null)
            return Results.BadRequest(new { ok = false, message = "请求为空。" });
        // 两侧都没给 = 不知道要改哪一侧，直接拒绝，避免把整条 PATH 写空
        if (req.System is null && req.User is null)
            return Results.BadRequest(new { ok = false, message = "请求里没有 system 或 user 列表，未做任何修改。" });

        // 先归一再看条数：null 表示「这一侧不动」，空数组表示主动清空该侧，
        // 但 [null] / [""] 这种「给了元素却全是空值」必须拒绝——照原样写入的是空字符串，
        // 等于把整侧 PATH 清空（前端列表没加载出来时就是这个形状）。
        var system = req.System;
        if (system is not null)
        {
            var cleaned = system.Where(s => !string.IsNullOrWhiteSpace(s)).ToList();
            if (cleaned.Count == 0 && system.Count > 0)
                return Results.BadRequest(new
                {
                    ok = false,
                    message = "系统列表里没有有效条目（元素是 null 或空串），未做任何修改。",
                });
            system = cleaned;
        }

        var user = req.User;
        if (user is not null)
        {
            var cleaned = user.Where(s => !string.IsNullOrWhiteSpace(s)).ToList();
            if (cleaned.Count == 0 && user.Count > 0)
                return Results.BadRequest(new
                {
                    ok = false,
                    message = "用户列表里没有有效条目（元素是 null 或空串），未做任何修改。",
                });
            user = cleaned;
        }

        // 两侧都是空列表 = 把两条 PATH 一起清空。列表没加载出来时点「应用写入」就是这个形状，必须挡
        if (system is { Count: 0 } && user is { Count: 0 })
            return Results.BadRequest(new
            {
                ok = false,
                message = "系统与用户两侧都是空列表。若确实要清空某一侧，请只提交那一侧；未做任何修改。",
            });
        return Results.Json(
            EnvService.ApplyPathSelection(system, user, req.DryRun), jsonOptions);
    });

    // MapFallback 只匹配"像目录"的路径（nonfile），因此 /favicon.ico 之类的
    // 带扩展名请求会落到 404，浏览器控制台会报错。这里直接回 204，不返回页面内容。
    app.MapGet("/favicon.ico", () => Results.StatusCode(StatusCodes.Status204NoContent));

    // 前端用路径路由（/switch、/project、/toolchain …）：这些地址刷新时同样
    // 返回内嵌页面，页面再由 location.pathname 决定渲染哪一屏，避免刷新跳回默认页。
    // 未定义的 /api/* 仍然 404，不把接口错拼当页面处理。
    app.MapFallback((HttpContext ctx) =>
        ctx.Request.Path.StartsWithSegments("/api")
            ? Results.NotFound(new { ok = false, message = "未知的接口。" })
            : Results.Content(Ui.Html, "text/html; charset=utf-8"));

    try
    {
        await app.StartAsync();
    }
    catch (Exception ex)
    {
        Console.Error.WriteLine($"启动失败：端口 {port} 可能被占用或不可用（{ex.Message}）");
        Console.Error.WriteLine("换个端口试试：EnvSwitch serve --port 5180");
        return 1;
    }

    var addresses = app.Services.GetRequiredService<IServer>()
                       .Features.Get<IServerAddressesFeature>()?.Addresses
                   ?? new List<string>();
    var url = addresses.FirstOrDefault() ?? $"http://127.0.0.1:{port}";

    Console.WriteLine($"EnvSwitch 网页界面：{url}");
    Console.WriteLine($"配置文件：{configPath}");
    Console.WriteLine("按 Ctrl+C 退出。");

    if (autoOpenBrowser && config.Settings.OpenBrowserOnStart)
    {
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch { /* 无浏览器可用时忽略 */ }
    }

    await app.WaitForShutdownAsync();
    return 0;
}

static void PrintHelp()
{
    Console.WriteLine("""
        EnvSwitch — 多版本环境变量切换器 + PATH 整理工具

        用法：
          EnvSwitch                          启动网页界面（默认）
          EnvSwitch serve [--port N] [--no-browser]
                                             显式启动网页界面
          EnvSwitch list                     列出工具链、版本与当前值
          EnvSwitch use <工具链> <版本> [--dry-run]
                                             切换版本：环境变量与 PATH 一起写（--dry-run 仅预览）
          EnvSwitch all [--dry-run]          把每套「生效且有激活版本」的工具链一次性应用，
                                             环境变量与 PATH 一起写（等同网页「立即应用全部」）
          EnvSwitch path [--no-sort] [--no-dedup] [--apply] [--allow-empty]
                                             打印系统 + 用户两侧 PATH 的「当前 → 处理后」
                                             （失效条目剔除、与系统重复的用户条目移除、
                                              同目录多写法合并、可选排序去重）
                                             默认只预览；--apply 把两侧 PATH 一起写回
                                             某一侧会被清成 0 条时 --apply 会拒绝，
                                             确实要清空那一侧再加 --allow-empty

          EnvSwitch config                   显示配置文件路径与内容

        全局参数：
          --config <路径>                    指定配置文件（可放在任意命令前）
        """);
}

/// <summary>拒绝已移除的 --scope：系统与用户必须一起处理。</summary>
static bool RejectScope(string cmd, List<string> argv)
{
    if (!argv.Any(a => a.StartsWith("--scope", StringComparison.OrdinalIgnoreCase)))
        return false;
    Console.Error.WriteLine(
        $"{cmd} 不再支持 --scope：系统与用户两侧总是一起处理、一起写入（只处理一侧会造成条目错位）。");
    return true;
}

/// <summary>只允许列出的长选项；裸参数和其它 --xxx 一律拒绝，避免把旧子命令当成别的东西静默执行。</summary>
static bool RejectUnknownOptions(string cmd, List<string> argv, params string[] allowed)
{
    var bad = argv.Skip(1)
        .Where(a => allowed.All(x => !string.Equals(a, x, StringComparison.OrdinalIgnoreCase)))
        .ToList();
    if (bad.Count == 0)
        return false;
    Console.Error.WriteLine($"多余的参数：{string.Join(" ", bad)}。{cmd} 只支持：{string.Join(" / ", allowed)}。");
    return true;
}

/// <summary>
/// 只拒绝白名单之外的 --flag（位置参数不算）。给 `use &lt;工具链&gt; &lt;版本&gt;` 这种带位置参数的命令用。
/// </summary>
static bool RejectUnknownFlags(string cmd, List<string> argv, params string[] allowed)
{
    var bad = argv.Skip(1)
        .Where(a => a.StartsWith("--", StringComparison.Ordinal))
        .Where(a => allowed.All(x => !string.Equals(a, x, StringComparison.OrdinalIgnoreCase)))
        .ToList();
    if (bad.Count == 0)
        return false;
    Console.Error.WriteLine($"多余的参数：{string.Join(" ", bad)}。{cmd} 只支持：{string.Join(" / ", allowed)}。");
    return true;
}

/// <summary>
/// path：先把系统、用户两侧 PATH 的「当前 → 处理后」都算出来并打印；
/// 加 --apply 时把两侧一起写回。只写一侧会让两侧条目重复或错位，所以这里不提供单侧写入。
/// </summary>
static int RunPathBoth(string configPath, bool sort, bool dedup, bool apply, bool allowEmpty)
{
    var cfg = LoadConfigOrExit(configPath);
    var view = EnvService.BuildPathView(cfg, sort, dedup);

    PrintPathSide("系统", view.SystemCurrent, view.SystemProcessed);
    PrintPathSide("用户", view.UserCurrent, view.UserProcessed);

    Console.WriteLine();
    Console.WriteLine(view.Message + $"（排序={sort}，去重={dedup}）");

    if (!apply)
    {
        Console.WriteLine();
        Console.WriteLine("未写入。上面的「处理后」就是将要写的完整内容；确认无误后加 --apply 才会真正落盘。");
        return 0;
    }

    // 某一侧「当前有条目、处理后 0 条」= 这次 apply 会把那一整侧 PATH 清成空值。
    // 失效目录会被筛掉、去重也可能全并掉，所以这不该悄悄发生：要么是误伤（先查为什么全没了），要么清空需明说。
    var cleared = new List<string>();
    if (view.SystemCurrent.Count > 0 && view.SystemProcessed.Count == 0)
        cleared.Add($"系统（{view.SystemCurrent.Count} 条全部被剔除）");
    if (view.UserCurrent.Count > 0 && view.UserProcessed.Count == 0)
        cleared.Add($"用户（{view.UserCurrent.Count} 条全部被剔除）");
    if (cleared.Count > 0 && !allowEmpty)
    {
        Console.Error.WriteLine($"[失败] {string.Join("、", cleared)}，--apply 会把这一侧 PATH 写成空值，未做任何修改。");
        Console.Error.WriteLine("失效目录会被「处理后」筛掉。确实要清空这一侧就加 --allow-empty；"
                                + "否则先确认那些目录是否还在。");
        return 1;
    }

    var result = EnvService.ApplyPathSelection(
        view.SystemProcessed.Select(x => x.Value).ToList(),
        view.UserProcessed.Select(x => x.Value).ToList(),
        dryRun: false);
    Console.WriteLine($"[{(result.Ok ? "OK" : "失败")}] {result.Message}");
    return result.Ok ? 0 : 1;
}

static void PrintPathSide(string label, List<PathEntryView> current, List<PathEntryView> processed)
{
    Console.WriteLine($"\n{label} PATH：当前 {current.Count} 条 → 处理后 {processed.Count} 条");
    Console.WriteLine($"  {label} · 当前：");
    foreach (var e in current)
    {
        var marks = new List<string>();
        if (!string.IsNullOrEmpty(e.Badge))
            marks.Add(e.Badge);
        if (!string.IsNullOrEmpty(e.Expanded))
            marks.Add($"实际生效 {e.Expanded}");
        Console.WriteLine($"    {e.Value}" + (marks.Count > 0 ? "   [" + string.Join("；", marks) + "]" : ""));
    }
    Console.WriteLine($"  {label} · 处理后：");
    foreach (var e in processed)
        Console.WriteLine($"    {e.Value}");
}

static void PrintList(string configPath)
{
    var cfg = LoadConfigOrExit(configPath);
    Console.WriteLine($"配置文件：{configPath}");
    if (cfg.Toolchains.Count == 0)
    {
        Console.WriteLine("（没有工具链，请在网页界面中添加）");
        return;
    }

    foreach (var t in cfg.Toolchains)
    {
        Console.WriteLine($"\n{t.Name}   [{t.EnvVar}]   生效={t.Enabled}  变量范围={t.EnvScope}  PATH范围={t.PathScope}  激活={t.Active ?? "(无)"}");
        Console.WriteLine($"  当前值：{(string.IsNullOrWhiteSpace(t.EnvVar) ? "(未配置环境变量)" : EnvService.GetEnv(t.EnvVar, EnvService.ParseWriteScope(t.EnvScope).Scope) ?? "(未设置)")}");
        foreach (var v in t.Versions)
            Console.WriteLine($"  {(string.Equals(v.Name, t.Active, StringComparison.OrdinalIgnoreCase) ? "*" : " ")} {v.Name,-6} {v.Home}");
    }
}

/// <summary>
/// CLI 统一取配置：读不到（被占用 / 无权限）时打印原因并以 1 退出，<b>不</b>写回默认配置。
/// 与「配置损坏」分开：损坏才备份 + 重建，读不到只是这一次做不了。
/// </summary>
static AppConfig LoadConfigOrExit(string configPath)
{
    try
    {
        return ConfigStore.Load(configPath);
    }
    catch (ConfigUnreadableException ex)
    {
        Console.Error.WriteLine($"[失败] {ex.Message}");
        Console.Error.WriteLine("配置文件只是暂时读不到（不是损坏），已放弃本次操作，文件未被修改。"
                                + "请检查是否被其它程序占用或缺少读取权限后重试。");
        Console.Out.Flush();
        Console.Error.Flush();
        Environment.Exit(1);
        throw; // 满足返回类型；Environment.Exit 不会返回
    }
}

internal sealed class ApplyRequest
{
    public string? Toolchain { get; set; }
    public string? Version { get; set; }
    public bool DryRun { get; set; }
}

internal sealed class PathRequest
{
    public bool Sort { get; set; } = true;
    public bool Dedup { get; set; } = true;
}

internal sealed class PathApplyRequest
{
    public List<string>? System { get; set; }
    public List<string>? User { get; set; }
    public bool DryRun { get; set; }
}

internal sealed class EnvSetRequest
{
    public string? Scope { get; set; }
    public string? Name { get; set; }
    public string? Value { get; set; }
}

internal sealed class EnvDeleteRequest
{
    public string? Scope { get; set; }
    public string? Name { get; set; }
}
