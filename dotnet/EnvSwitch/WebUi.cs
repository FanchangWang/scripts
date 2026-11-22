using System.Text;

namespace EnvSwitch;

/// <summary>网页 UI 以嵌入资源提供，保证单文件 exe 不依赖外部 html。</summary>
internal static class Ui
{
    internal static string Html { get; } = Load();

    private static string Load()
    {
        using var stream = typeof(Ui).Assembly.GetManifestResourceStream("EnvSwitch.Ui.ui.html")
            ?? throw new InvalidOperationException("内嵌的 ui.html 资源缺失。");
        using var reader = new StreamReader(stream, Encoding.UTF8);
        return reader.ReadToEnd();
    }
}
