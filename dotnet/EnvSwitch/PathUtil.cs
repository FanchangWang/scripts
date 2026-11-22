using System.Text.RegularExpressions;

namespace EnvSwitch;

/// <summary>PATH 字符串的纯函数工具：切分、规范化、去空、去重、排序。</summary>
internal static class PathUtil
{
    internal static List<string> Split(string? path)
    {
        if (string.IsNullOrEmpty(path))
            return [];

        return path.Split(';', StringSplitOptions.RemoveEmptyEntries)
                   .Select(p => p.Trim())
                   .Where(p => p.Length > 0)
                   .ToList();
    }

    /// <summary>
    /// 去掉尾部目录分隔符（盘符根 C:\ 与 UNC 根保留），
    /// 使 C:\Program Files\GitHub CLI 与 C:\Program Files\GitHub CLI\ 视为同一项。
    /// C:/ 这种正斜杠根也要保留成 C:\，否则 TrimEnd 会把它剪成 C:（当前盘相对目录）。
    /// </summary>
    internal static string Canonical(string entry)
    {
        var p = entry.Trim();
        if (p.Length == 0)
            return p;

        // 盘符根：C:\ 或 C:/ → 统一成 C:\
        if (p.Length == 3 && char.IsAsciiLetter(p[0]) && p[1] == ':' && (p[2] == '\\' || p[2] == '/'))
            return p[..2] + "\\";

        var uncRoot = Regex.Match(p, @"^(\\\\[^\\/]+[\\/][^\\/]+)[\\/]*$");
        if (uncRoot.Success)
            return uncRoot.Groups[1].Value.Replace('/', '\\') + "\\";

        return p.TrimEnd('\\', '/');
    }

    internal static bool Same(string a, string b) =>
        string.Equals(Canonical(a), Canonical(b), StringComparison.OrdinalIgnoreCase);

    internal static HashSet<string> KeySet(IEnumerable<string> entries) =>
        new(entries.Select(Canonical), StringComparer.OrdinalIgnoreCase);

    /// <summary>去重（Windows 路径大小写不敏感）+ 稳定排序；输出统一为不带尾部分隔符的形式。</summary>
    internal static List<string> Normalize(string? path, bool sort = true, bool dedup = true)
    {
        var entries = Split(path);
        if (dedup)
            entries = entries.Select(Canonical)
                             .GroupBy(p => p, StringComparer.OrdinalIgnoreCase)
                             .Select(g => g.Key)
                             .ToList();
        if (sort)
            entries = entries.OrderBy(p => p, StringComparer.OrdinalIgnoreCase).ToList();
        return entries;
    }

    internal static List<string> Without(IEnumerable<string> entries, IEnumerable<string> toRemove)
    {
        var set = KeySet(toRemove);
        return entries.Where(e => !set.Contains(Canonical(e))).ToList();
    }

    /// <summary>把模板中的 %ENV% 展开为实际 home，用于识别旧版本的 PATH 条目。</summary>
    internal static string Expand(string template, string envVar, string home)
    {
        if (string.IsNullOrEmpty(template))
            return "";
        return template.Replace($"%{envVar}%", home, StringComparison.OrdinalIgnoreCase);
    }

    internal static string Join(IEnumerable<string> entries) => string.Join(';', entries);
}
