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
    await RunWeb(configPath, jsonOptions, autoOpenBrowser: true, portOverride: null);
    return 0;
}

switch (argv[0].ToLowerInvariant())
{
    case "help" or "-h" or "--help":
        PrintHelp();
        return 0;

    case "config":
        Console.WriteLine($"配置文件：{configPath}");
        Console.WriteLine(File.Exists(configPath) ? File.ReadAllText(configPath) : "（尚未生成，运行一次后自动生成）");
        return 0;

    case "list" or "ls":
        PrintList(configPath);
        return 0;

    case "use" or "apply":
    {
        if (argv.Count < 3)
        {
            Console.Error.WriteLine("用法：EnvSwitch use <工具链> <版本> [--dry-run]");
            return 1;
        }
        var dryRun = argv.Any(a => string.Equals(a, "--dry-run", StringComparison.OrdinalIgnoreCase));
        var result = EnvService.Apply(ConfigStore.Load(configPath), argv[1], argv[2], dryRun, configPath);
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
    case "all" or "apply-all":
    {
        var dryRun = argv.Any(a => string.Equals(a, "--dry-run", StringComparison.OrdinalIgnoreCase));
        var batch = EnvService.ApplyAll(ConfigStore.Load(configPath), dryRun, configPath);
        Console.WriteLine($"[{(batch.Ok ? "OK" : "有失败")}] {batch.Message}");
        foreach (var item in batch.Items)
            Console.WriteLine($"  {(item.Ok ? "✔" : "✘")} {item.Toolchain} → {item.Version}：{item.Message}");
        return batch.Ok ? 0 : 1;
    }

    // PATH 整理：系统 + 用户两侧一起算、一起写。env 是 path 的别名（同一个实现），
    // 因为本项目处理的「环境变量」就只有 PATH。
    case "path" or "env":
    {
        // 只改一侧会让两侧条目重复或错位，所以 --scope 已被移除，出现就直接报错而不是静默忽略
        if (argv.Any(a => a.StartsWith("--scope", StringComparison.OrdinalIgnoreCase)))
        {
            Console.Error.WriteLine(
                $"{argv[0]} 不再支持 --scope：系统与用户两侧总是一起计算、一起写入（只写一侧会造成条目错位）。");
            return 1;
        }

        // env 以前有 list / get / set / delete 子命令，现在只剩「查看 / --apply 写入」两件事
        var extra = argv.Skip(1)
            .Where(a => !a.StartsWith("--", StringComparison.Ordinal))
            .ToList();
        if (extra.Count > 0)
        {
            Console.Error.WriteLine(
                $"多余的参数：{string.Join(" ", extra)}。{argv[0]} 只有 --no-sort / --no-dedup / --apply，"
                + "不带参数是查看两侧处理结果，加 --apply 才写入。");
            return 1;
        }

        return RunPathBoth(
            configPath,
            sort: !argv.Any(a => string.Equals(a, "--no-sort", StringComparison.OrdinalIgnoreCase)),
            dedup: !argv.Any(a => string.Equals(a, "--no-dedup", StringComparison.OrdinalIgnoreCase)),
            apply: argv.Any(a => string.Equals(a, "--apply", StringComparison.OrdinalIgnoreCase)));
    }

    case "serve":
    {
        var port = 0;
        var noBrowser = false;
        for (var i = 1; i < argv.Count; i++)
        {
            if (string.Equals(argv[i], "--port", StringComparison.OrdinalIgnoreCase) && i + 1 < argv.Count)
                int.TryParse(argv[++i], out port);
            else if (string.Equals(argv[i], "--no-browser", StringComparison.OrdinalIgnoreCase))
                noBrowser = true;
        }
        await RunWeb(configPath, jsonOptions, autoOpenBrowser: !noBrowser, portOverride: port > 0 ? port : null);
        return 0;
    }

    default:
        Console.Error.WriteLine($"未知命令：{argv[0]}。运行 EnvSwitch --help 查看用法。");
        return 1;
}

// ---------- 网页模式 ----------
static async Task RunWeb(string configPath, JsonSerializerOptions jsonOptions, bool autoOpenBrowser, int? portOverride)
{
    var config = ConfigStore.Load(configPath);
    var port = portOverride ?? config.Settings.Port;

    var builder = WebApplication.CreateBuilder();
    builder.Logging.ClearProviders();
    builder.WebHost.UseUrls($"http://127.0.0.1:{port}");

    var app = builder.Build();

    app.MapGet("/", () => Results.Content(Ui.Html, "text/html; charset=utf-8"));

    app.MapGet("/api/state", () =>
    {
        var cfg = ConfigStore.Load(configPath);
        var snapshot = cfg.Toolchains.ToDictionary(
            t => t.Name,
            t => EnvService.GetEnv(t.EnvVar, EnvService.ParseWriteScope(t.EnvScope).Scope) ?? "",
            StringComparer.OrdinalIgnoreCase);
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
        incoming.Toolchains ??= [];

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

        ConfigStore.Migrate(incoming);
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
        return Results.Json(
            EnvService.ApplyPathSelection(req.System, req.User, req.DryRun), jsonOptions);
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

    await app.StartAsync();

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
          EnvSwitch path [--no-sort] [--no-dedup] [--apply]
          EnvSwitch env  [--no-sort] [--no-dedup] [--apply]
                                             查看系统 + 用户两侧 PATH 的处理结果
                                             （失效条目剔除、与系统重复的用户条目移除、
                                              同目录多写法合并、可选排序去重）
                                             默认只预览；--apply 把两侧一起写回注册表
                                             写系统侧需要管理员权限，两侧要么一起成功要么不动

          EnvSwitch config                   显示配置文件路径与内容

        全局参数：
          --config <路径>                    指定配置文件（可放在任意命令前）
        """);
}

/// <summary>
/// path / env 共用实现：先把系统、用户两侧的「当前 → 处理后」都算出来并打印；
/// 加 --apply 时把两侧一起写回。只写一侧会让两侧条目重复或错位，所以这里不提供单侧写入。
/// </summary>
static int RunPathBoth(string configPath, bool sort, bool dedup, bool apply)
{
    var cfg = ConfigStore.Load(configPath);
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
    var cfg = ConfigStore.Load(configPath);
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
