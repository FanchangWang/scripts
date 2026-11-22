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

    case "env":
    {
        var sub = argv.Count > 1 && !argv[1].StartsWith("--", StringComparison.Ordinal) ? argv[1].ToLowerInvariant() : "list";
        var scopeOption = GetOption(argv, "--scope");
        var both = scopeOption is null;
        var single = EnvService.ParseScope(scopeOption);

        List<(EnvScope Scope, string Label)> Targets()
        {
            if (both)
                return new List<(EnvScope, string)> { (EnvScope.User, "用户"), (EnvScope.Machine, "系统") };
            return new List<(EnvScope, string)> { (single, single == EnvScope.Machine ? "系统" : "用户") };
        }

        switch (sub)
        {
            case "list" or "ls":
            {
                var filter = GetOption(argv, "--filter");
                foreach (var (scope, label) in Targets())
                {
                    var items = EnvService.GetAllDetailed(scope)
                        .OrderBy(x => x.Key, StringComparer.OrdinalIgnoreCase)
                        .Where(x => filter is null || x.Key.Contains(filter, StringComparison.OrdinalIgnoreCase))
                        .ToList();
                    Console.WriteLine($"\n{label}环境变量：{items.Count} 个" + (filter is null ? "" : $"（筛选「{filter}」）"));
                    foreach (var kv in items)
                        Console.WriteLine($"  {kv.Key} = {kv.Value.Value}"
                                          + (kv.Value.Invalid ? "   [已失效]" : ""));
                }
                return 0;
            }

            case "get":
            {
                if (argv.Count < 3 || argv[2].StartsWith("--", StringComparison.Ordinal))
                {
                    Console.Error.WriteLine("用法：EnvSwitch env get <变量名> [--scope User|Machine]");
                    return 1;
                }
                var name = argv[2];
                var missing = 0;
                foreach (var (scope, label) in Targets())
                {
                    var value = EnvService.GetEnv(name, scope);
                    if (value is null)
                    {
                        Console.WriteLine($"{label}  {name}  （未设置）");
                        missing++;
                    }
                    else
                        Console.WriteLine($"{label}  {name} = {(value.Length == 0 ? "（空串）" : value)}");
                }
                return missing == Targets().Count ? 2 : 0;
            }

            case "set":
            {
                if (argv.Count < 3 || argv[2].StartsWith("--", StringComparison.Ordinal))
                {
                    Console.Error.WriteLine("用法：EnvSwitch env set <变量名> [值] [--scope User|Machine]（值省略即写成空串）");
                    return 1;
                }
                var name = argv[2];
                var value = argv.Count > 3 && !argv[3].StartsWith("--", StringComparison.Ordinal) ? argv[3] : "";
                var scope = both ? EnvScope.User : single;
                var r = EnvService.SetEnv(name, value, scope);
                Console.WriteLine($"[{(r.Ok ? "OK" : "失败")}] {r.Message}");
                return r.Ok ? 0 : 1;
            }

            case "delete" or "del" or "rm":
            {
                if (argv.Count < 3 || argv[2].StartsWith("--", StringComparison.Ordinal))
                {
                    Console.Error.WriteLine("用法：EnvSwitch env delete <变量名> [--scope User|Machine]");
                    return 1;
                }
                var scope = both ? EnvScope.User : single;
                var r = EnvService.DeleteEnv(argv[2], scope);
                Console.WriteLine($"[{(r.Ok ? "OK" : "失败")}] {r.Message}");
                return r.Ok ? 0 : 1;
            }

            default:
                Console.Error.WriteLine($"未知的 env 子命令：{sub}（可用 list / get / set / delete）");
                return 1;
        }
    }

    case "path":
    {
        var sort = !argv.Any(a => string.Equals(a, "--no-sort", StringComparison.OrdinalIgnoreCase));
        var dedup = !argv.Any(a => string.Equals(a, "--no-dedup", StringComparison.OrdinalIgnoreCase));
        var apply = argv.Any(a => string.Equals(a, "--apply", StringComparison.OrdinalIgnoreCase));
        var scopeArg = GetOption(argv, "--scope");
        var scope = EnvService.ParseScope(scopeArg);

        var result = EnvService.NormalizePath(scope, sort, dedup, dryRun: !apply);
        Console.WriteLine($"[{(result.Ok ? "OK" : "失败")}] {result.Message}");
        Console.WriteLine($"  范围：{scope}  条目数：{result.BeforeCount} -> {result.AfterCount}");
        Console.WriteLine("  修改后：");
        foreach (var entry in PathUtil.Split(result.After))
            Console.WriteLine($"    {entry}");
        return result.Ok ? 0 : 1;
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

    app.MapPost("/api/path", async (HttpContext ctx) =>
    {
        var req = await JsonSerializer.DeserializeAsync<PathRequest>(ctx.Request.Body, jsonOptions);
        if (req is null)
            return Results.BadRequest(new { ok = false, message = "请求为空。" });

        var result = EnvService.NormalizePath(
            EnvService.ParseScope(req.Scope), req.Sort, req.Dedup, dryRun: req.DryRun);
        return Results.Json(result, jsonOptions);
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
          EnvSwitch path [--sort|--no-sort] [--dedup|--no-dedup]
                        [--scope User|Machine] [--apply]
                                             PATH 排序去重；默认仅预览，--apply 才写入

          EnvSwitch env [list] [--filter 关键词] [--scope User|Machine]
                                             列出环境变量；不带 --scope 时用户与系统都列
          EnvSwitch env get <变量名> [--scope User|Machine]
                                             读取单个变量（退出码 2 表示两侧都没设置）
          EnvSwitch env set <变量名> [值] [--scope User|Machine]
                                             写入变量（值省略即写空串；缺省 --scope 为 User）
          EnvSwitch env delete <变量名> [--scope User|Machine]
                                             删除变量（不可恢复；缺省 --scope 为 User）

          EnvSwitch config                   显示配置文件路径与内容

        全局参数：
          --config <路径>                    指定配置文件（可放在任意命令前）
          --scope 既支持 --scope Machine，也支持 --scope=Machine
        """);
}

/// <summary>读一个选项值：同时支持 `--scope Machine` 与 `--scope=Machine`。</summary>
static string? GetOption(List<string> argv, string name)
{
    for (var i = 0; i < argv.Count; i++)
    {
        var a = argv[i];
        if (a.StartsWith(name + "=", StringComparison.OrdinalIgnoreCase))
            return a[(name.Length + 1)..];
        if (!string.Equals(a, name, StringComparison.OrdinalIgnoreCase))
            continue;
        if (i + 1 < argv.Count && !argv[i + 1].StartsWith("--", StringComparison.Ordinal))
            return argv[i + 1];
        return null;
    }
    return null;
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
    public string? Scope { get; set; }
    public bool Sort { get; set; } = true;
    public bool Dedup { get; set; } = true;
    public bool DryRun { get; set; }
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
