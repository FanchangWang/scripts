using System.Diagnostics;
using System.Runtime.InteropServices;
using AutoRunManager.Models;

namespace AutoRunScheduler.Services;

public static partial class ProcessLauncher
{
    public static bool Start(StartupItem item)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(item.Path))
            {
                Logger.Write($"启动失败 [{item.Name}]: 路径为空");
                return false;
            }

            if (!File.Exists(item.Path))
            {
                var resolved = ResolveFromPath(item.Path);
                if (resolved == null)
                {
                    Logger.Write($"启动失败 [{item.Name}]: 找不到可执行文件 {item.Path}");
                    return false;
                }
                item.Path = resolved;
            }

            if (item.RunAsAdmin)
            {
                Logger.Write($"以管理员启动 [{item.Name}]: {item.Path}");
                return StartAsAdmin(item.Path, item.Args);
            }
            else
            {
                Logger.Write($"以普通用户启动 [{item.Name}]: {item.Path}");
                return StartAsUser(item.Path, item.Args);
            }
        }
        catch (Exception ex)
        {
            Logger.Write($"启动失败 [{item.Name}]: {ex.Message}");
            return false;
        }
    }

    private static bool StartAsAdmin(string path, string args)
    {
        try
        {
            using var proc = Process.Start(new ProcessStartInfo
            {
                FileName = path,
                Arguments = args,
                UseShellExecute = false,
            });
            return proc != null;
        }
        catch (Exception ex)
        {
            Logger.Write($"管理员启动失败: {ex.Message}");
            return false;
        }
    }

    private static bool StartAsUser(string path, string args)
    {
        var sessionId = Process.GetCurrentProcess().SessionId;

        if (!WTSQueryUserToken(sessionId, out var userToken))
        {
            Logger.Write($"WTSQueryUserToken(session={sessionId}) 失败，回退到 Process.Start");
            return FallbackStart(path, args);
        }

        var desktopPtr = IntPtr.Zero;
        try
        {
            if (!CreateEnvironmentBlock(out var envBlock, userToken, false))
            {
                Logger.Write("CreateEnvironmentBlock 失败，使用空环境");
                envBlock = IntPtr.Zero;
            }

            desktopPtr = Marshal.StringToHGlobalUni(@"winsta0\default");
            var cmdLine = BuildCommandLine(path, args);
            var startupInfo = new STARTUPINFO
            {
                cb = Marshal.SizeOf<STARTUPINFO>(),
                lpDesktop = desktopPtr,
            };

            var success = CreateProcessAsUser(
                userToken, IntPtr.Zero, cmdLine,
                IntPtr.Zero, IntPtr.Zero, false,
                0x00000400 | 0x00000010,
                envBlock, IntPtr.Zero,
                ref startupInfo, out var processInfo);

            if (envBlock != IntPtr.Zero)
                DestroyEnvironmentBlock(envBlock);

            if (success)
            {
                CloseHandle(processInfo.hProcess);
                CloseHandle(processInfo.hThread);
                return true;
            }

            var error = Marshal.GetLastWin32Error();
            Logger.Write($"CreateProcessAsUser 失败 (0x{error:X})，回退到 Process.Start");
            return FallbackStart(path, args);
        }
        finally
        {
            if (desktopPtr != IntPtr.Zero)
                Marshal.FreeHGlobal(desktopPtr);
            CloseHandle(userToken);
        }
    }

    private static bool FallbackStart(string path, string args)
    {
        try
        {
            using var proc = Process.Start(new ProcessStartInfo
            {
                FileName = path,
                Arguments = args,
                UseShellExecute = false,
            });
            return proc != null;
        }
        catch (Exception ex)
        {
            Logger.Write($"回退启动失败: {ex.Message}");
            return false;
        }
    }

    private static string? ResolveFromPath(string path)
    {
        if (path.Contains('\\') || path.Contains('/'))
            return null;

        var paths = Environment.GetEnvironmentVariable("PATH")?.Split(Path.PathSeparator) ?? Array.Empty<string>();
        foreach (var dir in paths)
        {
            if (string.IsNullOrWhiteSpace(dir)) continue;
            var full = Path.Combine(dir.Trim(), path);
            if (File.Exists(full)) return full;
        }
        return null;
    }

    private static string BuildCommandLine(string path, string args)
    {
        var quoted = path.Contains(' ') ? $"\"{path}\"" : path;
        return string.IsNullOrWhiteSpace(args) ? quoted : $"{quoted} {args}";
    }

    [LibraryImport("wtsapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool WTSQueryUserToken(int sessionId, out IntPtr token);

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CreateProcessAsUser(
        IntPtr hToken,
        IntPtr lpApplicationName,
        [MarshalAs(UnmanagedType.LPWStr)] string lpCommandLine,
        IntPtr lpProcessAttributes,
        IntPtr lpThreadAttributes,
        [MarshalAs(UnmanagedType.Bool)] bool bInheritHandles,
        uint dwCreationFlags,
        IntPtr lpEnvironment,
        IntPtr lpCurrentDirectory,
        ref STARTUPINFO lpStartupInfo,
        out PROCESS_INFORMATION lpProcessInformation);

    [LibraryImport("userenv.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CreateEnvironmentBlock(out IntPtr lpEnvironment, IntPtr hToken, [MarshalAs(UnmanagedType.Bool)] bool bInherit);

    [LibraryImport("userenv.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DestroyEnvironmentBlock(IntPtr lpEnvironment);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CloseHandle(IntPtr hObject);

    [StructLayout(LayoutKind.Sequential)]
    private struct STARTUPINFO
    {
        public int cb;
        public IntPtr lpReserved;
        public IntPtr lpDesktop;
        public IntPtr lpTitle;
        public uint dwX;
        public uint dwY;
        public uint dwXSize;
        public uint dwYSize;
        public uint dwXCountChars;
        public uint dwYCountChars;
        public uint dwFillAttribute;
        public uint dwFlags;
        public short wShowWindow;
        public short cbReserved2;
        public IntPtr lpReserved2;
        public IntPtr hStdInput;
        public IntPtr hStdOutput;
        public IntPtr hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_INFORMATION
    {
        public IntPtr hProcess;
        public IntPtr hThread;
        public uint dwProcessId;
        public uint dwThreadId;
    }
}
