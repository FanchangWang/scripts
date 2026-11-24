// Maye Nano plugin: Auto Theme (follow Windows light/dark mode)
//
// Reads HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize
// \AppsUseLightTheme and keeps cfg.json -> "theme" in sync with the
// configured light / dark theme file.
//
// The application only loads the theme at startup, so by default the plugin
// gracefully closes and relaunches Maye Nano after writing the new value.
// Set AutoRestart=0 in autotheme.ini to only update the config (the change
// then takes effect the next time you start Maye Nano normally).

#include <algorithm>
#include <string>

#include "SDK_MN.hpp"

#include <windows.h>

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <process.h>
#include <string>

static std::wstring g_logPath;
static bool         g_debugLog = false;

static void LogA(const char* fmt, ...) {
    if (!g_debugLog) return;
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    (void)vsnprintf_s(buf, sizeof(buf), _TRUNCATE, fmt, ap);
    va_end(ap);

    SYSTEMTIME t;
    GetLocalTime(&t);
    char line[1200];
    _snprintf_s(line, sizeof(line), _TRUNCATE,
        "[%02d:%02d:%02d.%03d] %s\r\n",
        t.wHour, t.wMinute, t.wSecond, t.wMilliseconds, buf);

    HANDLE f = CreateFileW(g_logPath.c_str(), FILE_APPEND_DATA, FILE_SHARE_READ,
        nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        WriteFile(f, line, (DWORD)strlen(line), &written, nullptr);
        CloseHandle(f);
    }
}

static std::atomic<bool>   g_running{ false };
static HANDLE              g_thread = nullptr;

static std::wstring        g_exePath;
static std::wstring        g_exeDir;
static std::wstring        g_pluginDir;
static std::wstring        g_iniPath;

static std::wstring        g_lightTheme;   // empty = Default ("theme": "")
static std::wstring        g_darkTheme;    // empty = Default ("theme": "")
static bool                g_enabled    = true;
static bool                g_autoRestart= true;
static DWORD               g_restartDelay = 900;
static bool                g_useInternal = true;

// Internal theme-apply routine in Maye Nano.exe (RVA 0x1819D0):
//   void ApplyTheme(const char* utf8ThemeFileName);  // nullptr = Default
// It rebuilds the global color table from .\theme\<name>.json immediately.
// Experimental: tied to this exact app build (7.0.1.261107).
static bool CallInternalTheme(const std::string& theme) {
    HMODULE mod = GetModuleHandleW(nullptr);
    if (!mod) return false;
    using PFN = void(__cdecl*)(const char*);
    auto fn = (PFN)((ULONG_PTR)mod + 0x1819D0);
    if (!fn) return false;
    fn(theme.empty() ? nullptr : theme.c_str());
    LogA("internal theme applied: '%s'", theme.c_str());
    return true;
}

// ImGui is thread-affine, so the internal call must run on the app's main
// thread. Create a message-only window during the main-thread Load callback
// and post the request to it from the monitor thread.
#define MN_APPLY_THEME (WM_APP + 31)
static HWND g_msgWindow = nullptr;

static LRESULT CALLBACK AutoThemeWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == MN_APPLY_THEME) {
        auto* theme = reinterpret_cast<std::string*>(lp);
        if (theme) {
            CallInternalTheme(*theme);
            delete theme;
        }
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

static void CreateMainThreadDispatcher() {
    if (g_msgWindow) return;
    HMODULE hmod = nullptr;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                       GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       (LPCWSTR)&AutoThemeWndProc, &hmod);
    WNDCLASSW wc{};
    wc.lpfnWndProc = AutoThemeWndProc;
    wc.hInstance = hmod;
    wc.lpszClassName = L"MN_AutoTheme_Msg";
    RegisterClassW(&wc);
    g_msgWindow = CreateWindowExW(0, L"MN_AutoTheme_Msg", nullptr, 0,
                                  0, 0, 0, 0, HWND_MESSAGE, nullptr, hmod, nullptr);
    LogA("dispatcher hwnd=%p", g_msgWindow);
}

static bool ApplyInternalTheme(const std::string& theme) {
    if (!g_msgWindow) return false;
    auto* copy = new std::string(theme);
    if (!PostMessageW(g_msgWindow, MN_APPLY_THEME, 0, (LPARAM)copy)) {
        delete copy;
        return false;
    }
    LogA("posted internal theme apply: '%s'", theme.c_str());
    return true;
}

// A theme change is written to cfg.json immediately, but the restart is
// deferred while the launcher window is visible.
static std::string         g_pendingTheme;
static bool                g_pendingRestart = false;

// ---------- small helpers ----------

static std::wstring ToWide(const std::string& s) {
    if (s.empty()) return std::wstring();
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    std::wstring w((size_t)n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], n);
    return w;
}

static std::string ToUtf8(const std::wstring& w) {
    if (w.empty()) return std::string();
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    std::string s((size_t)n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), &s[0], n, nullptr, nullptr);
    return s;
}

static std::wstring IniString(const wchar_t* key, const wchar_t* def) {
    wchar_t buf[512] = { 0 };
    GetPrivateProfileStringW(L"AutoTheme", key, def, buf, 512, g_iniPath.c_str());
    return std::wstring(buf);
}

static void LoadIni() {
    g_debugLog       = (IniString(L"DebugLog", L"0") == L"1");
    g_useInternal    = (IniString(L"UseInternalCall", L"1") == L"1");
    g_enabled     = (IniString(L"Enable", L"1") == L"1");
    g_autoRestart = (IniString(L"AutoRestart", L"1") == L"1");
    // Empty / missing value means "Default" -> cfg.json "theme": ""
    g_lightTheme  = IniString(L"Light", L"");
    g_darkTheme   = IniString(L"Dark", L"");

    DWORD d = GetPrivateProfileIntW(L"AutoTheme", L"RestartDelayMs", 900, g_iniPath.c_str());
    g_restartDelay = (d > 0 && d < 60000) ? d : 900;
}

// ---------- Windows theme detection ----------

static bool IsWindowsLightMode() {
    HKEY h = nullptr;
    const wchar_t* sub = L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    if (RegOpenKeyExW(HKEY_CURRENT_USER, sub, 0, KEY_READ, &h) != ERROR_SUCCESS)
        return false; // registry missing -> assume dark

    DWORD value = 1, type = 0, size = sizeof(value);
    LONG r = RegQueryValueExW(h, L"AppsUseLightTheme", nullptr, &type,
                              (BYTE*)&value, &size);
    RegCloseKey(h);
    LogA("Reg AppsUseLightTheme r=%ld value=%u", (long)r, (unsigned)value);
    return (r == ERROR_SUCCESS) ? (value != 0) : false;
}

// ---------- apply / relaunch ----------

// The plugin API's GetCfgItem/SetCfgItem use a private cfg.ini inside the
// plugin folder, not the application's cfg.json. Read cfg.json directly to
// learn the theme the application actually has on disk.
static std::string ReadAppTheme() {
    std::wstring path = g_exeDir + L"\\cfg.json";
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
        nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return std::string();

    DWORD size = GetFileSize(f, nullptr);
    std::string text;
    if (size && size < 4 * 1024 * 1024) {
        text.resize(size);
        DWORD read = 0;
        if (ReadFile(f, &text[0], size, &read, nullptr)) text.resize(read);
        else text.clear();
    }
    CloseHandle(f);

    size_t k = text.find("\"theme\"");
    if (k == std::string::npos) return std::string();
    size_t colon = text.find(':', k);
    if (colon == std::string::npos) return std::string();
    size_t q1 = text.find('"', colon);
    if (q1 == std::string::npos) return std::string();
    size_t q2 = text.find('"', q1 + 1);
    if (q2 == std::string::npos) return std::string();
    return text.substr(q1 + 1, q2 - q1 - 1);
}

// An empty theme means the application default: cfg.json "theme": ""
static bool WriteAppTheme(const std::string& theme) {
    std::wstring path = g_exeDir + L"\\cfg.json";
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
        nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;

    DWORD size = GetFileSize(f, nullptr);
    std::string text;
    if (size && size < 4 * 1024 * 1024) {
        text.resize(size);
        DWORD read = 0;
        if (ReadFile(f, &text[0], size, &read, nullptr)) text.resize(read);
        else text.clear();
    }
    CloseHandle(f);
    if (text.empty()) return false;

    size_t k = text.find("\"theme\"");
    if (k == std::string::npos) return false;
    size_t colon = text.find(':', k);
    if (colon == std::string::npos) return false;
    size_t q1 = text.find('"', colon);
    if (q1 == std::string::npos) return false;
    size_t q2 = text.find('"', q1 + 1);
    if (q2 == std::string::npos) return false;

    std::string updated = text.substr(0, q1 + 1) + theme + text.substr(q2);

    // Atomic-ish replace so the app never reads a half-written cfg.json.
    std::wstring tmp = path + L".autotheme.tmp";
    HANDLE o = CreateFileW(tmp.c_str(), GENERIC_WRITE, 0, nullptr,
                           CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (o == INVALID_HANDLE_VALUE) return false;
    DWORD written = 0;
    BOOL ok = WriteFile(o, updated.data(), (DWORD)updated.size(), &written, nullptr);
    CloseHandle(o);
    if (!ok) { DeleteFileW(tmp.c_str()); return false; }
    if (!MoveFileExW(tmp.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING)) {
        DeleteFileW(tmp.c_str());
        return false;
    }
    return true;
}

// The launcher UI runs in a child process, so scan every top-level window.
static bool g_launcherVisible = false;

static BOOL CALLBACK LauncherWindowProc(HWND hwnd, LPARAM) {
    wchar_t cls[128] = { 0 };
    GetClassNameW(hwnd, cls, 128);
    if (_wcsicmp(cls, L"_ArWin_MayeNanoLauncher") == 0 && IsWindowVisible(hwnd)) {
        g_launcherVisible = true;
        return FALSE;
    }
    return TRUE;
}

static bool IsLauncherVisible() {
    g_launcherVisible = false;
    EnumWindows(LauncherWindowProc, 0);
    return g_launcherVisible;
}

static void LaunchRestartHelper(const std::string& theme) {
    std::wstring ps = g_pluginDir + L"\\restart.ps1";
    LogA("LaunchRestartHelper ps=%S", ps.c_str());
    if (GetFileAttributesW(ps.c_str()) == INVALID_FILE_ATTRIBUTES) {
        LogA("restart.ps1 missing");
        return;
    }

    wchar_t sysdir[MAX_PATH] = { 0 };
    GetSystemDirectoryW(sysdir, MAX_PATH);
    std::wstring powershell = std::wstring(sysdir) + L"\\WindowsPowerShell\\v1.0\\powershell.exe";

    wchar_t cmd[4096] = { 0 };
    swprintf_s(cmd, 4096,
        L"-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass "
        L"-File \"%s\" -ProcId %lu -Cfg \"%s\\cfg.json\" -Exe \"%s\" -Theme \"%s\" -Delay %lu",
        ps.c_str(), (unsigned long)GetCurrentProcessId(), g_exeDir.c_str(),
        g_exePath.c_str(), ToWide(theme).c_str(), g_restartDelay);

    SHELLEXECUTEINFOW sei{};
    sei.cbSize = sizeof(sei);
    sei.fMask = SEE_MASK_NOCLOSEPROCESS;
    sei.lpVerb = L"open";
    sei.lpFile = powershell.c_str();
    sei.lpParameters = cmd;
    sei.nShow = SW_HIDE;
    BOOL ok = ShellExecuteExW(&sei);
    LogA("ShellExecute ok=%d err=%lu cmd=%S", (int)ok, (unsigned long)GetLastError(), cmd);
}

static BOOL CALLBACK CloseMainWindowProc(HWND hwnd, LPARAM lpid) {
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid == (DWORD)lpid) {
        wchar_t title[256] = { 0 };
        if (GetWindowTextW(hwnd, title, 256) > 0) {
            if (wcsstr(title, L"Maye") || wcsstr(title, L"Nano")) {
                PostMessageW(hwnd, WM_CLOSE, 0, 0);
                return FALSE;
            }
        }
    }
    return TRUE;
}

static void CloseApp() {
    EnumWindows(CloseMainWindowProc, (LPARAM)GetCurrentProcessId());
}

static void ApplyTheme(bool light) {
    std::string desired = ToUtf8(light ? g_lightTheme : g_darkTheme);
    std::string current = ReadAppTheme();
    LogA("ApplyTheme light=%d current='%s' desired='%s'", (int)light,
         current.c_str(), desired.c_str());

    // "" and a missing theme key both mean Default.
    // A pending restart stays pending even if cfg.json already has the right
    // value: the running process still needs to reload the theme.
    if (current == desired)
        return;

    if (!WriteAppTheme(desired)) {
        LogA("WriteAppTheme failed");
        return;
    }
    if (mn::_MN_API_FN && mn::_MN_CTX)
        (void)mn::Api::SetCfgItem("theme", desired.c_str()); // plugin's own record

    if (g_useInternal && ApplyInternalTheme(desired)) {
        g_pendingRestart = false;
        g_pendingTheme.clear();
        return; // applied in-process, no restart needed
    }

    if (g_autoRestart) {
        g_pendingTheme = desired;
        g_pendingRestart = true;
        LogA("config written; restart pending=%d visible=%d",
             (int)g_pendingRestart, (int)IsLauncherVisible());
    }
}

static void TryPendingRestart() {
    if (!g_pendingRestart) return;
    if (IsLauncherVisible()) return; // never kill the app while the window is shown

    LogA("launcher hidden -> restarting for theme '%s'", g_pendingTheme.c_str());
    LaunchRestartHelper(g_pendingTheme);
    g_pendingRestart = false;
    g_pendingTheme.clear();
}

// ---------- benign search callbacks (host requires them for Search plugins) ----------

static mn::SEARCH_RESULT_LIST EmptySearch(const char*, const char*, const char*) {
    return { nullptr, 0 };
}

static void EmptySearchFree() {}

// ---------- monitor ----------

static unsigned __stdcall MonitorThread(LPVOID) {
    while (g_running.load()) {
        ApplyTheme(IsWindowsLightMode());
        TryPendingRestart();

        const wchar_t* sub = L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
        HKEY h = nullptr;
        if (RegOpenKeyExW(HKEY_CURRENT_USER, sub, 0, KEY_READ, &h) == ERROR_SUCCESS) {
            HANDLE ev = CreateEventW(nullptr, FALSE, FALSE, nullptr);
            if (ev) {
                RegNotifyChangeKeyValue(h, FALSE, REG_NOTIFY_CHANGE_LAST_SET, ev, TRUE);
                // Check often while a restart is waiting for the window to hide.
                WaitForSingleObject(ev, g_pendingRestart ? 500 : 5000);
                CloseHandle(ev);
            } else {
                Sleep(g_pendingRestart ? 500 : 5000);
            }
            RegCloseKey(h);
        } else {
            Sleep(g_pendingRestart ? 500 : 5000);
        }
    }
    return 0;
}

static void InitPaths() {
    if (!g_iniPath.empty()) return;

    wchar_t path[MAX_PATH] = { 0 };
    GetModuleFileNameW(nullptr, path, MAX_PATH);
    g_exePath = path;

    // Manual scan avoids MSVC STL's wmemchr (its intrinsics confuse Clang 21).
    size_t slash = 0;
    for (size_t i = 0; i < g_exePath.size(); ++i) {
        if (g_exePath[i] == L'\\' || g_exePath[i] == L'/') slash = i;
    }
    g_exeDir = slash ? g_exePath.substr(0, slash) : std::wstring();
    g_pluginDir    = g_exeDir + L"\\plugin\\mn.cc.arae.autotheme";
    g_iniPath      = g_pluginDir + L"\\autotheme.ini";
    g_logPath      = g_pluginDir + L"\\autotheme.log";
    LogA("InitPaths exe=%S", g_exePath.c_str());
}

static void CreateMainThreadDispatcher(); // defined below

static void StartMonitor() {
    InitPaths();
    LogA("StartMonitor called");
    if (g_running.exchange(true)) { LogA("already running"); return; }

    LoadIni();
    if (g_useInternal) CreateMainThreadDispatcher();
    if (!g_enabled) { g_running = false; LogA("disabled by ini"); return; }
    LogA("ini: light=%S dark=%S autoRestart=%d enabled=%d",
         g_lightTheme.c_str(), g_darkTheme.c_str(), (int)g_autoRestart, (int)g_enabled);

    g_thread = (HANDLE)_beginthreadex(nullptr, 0, MonitorThread, nullptr, 0, nullptr);
    LogA("monitor thread=%p", g_thread);
}

static void StopMonitor() {
    g_running = false;
    if (g_thread) {
        WaitForSingleObject(g_thread, 1500);
        CloseHandle(g_thread);
        g_thread = nullptr;
    }
}

// ---------- plugin entry points ----------

mn::_MN_RESULT _MN_Notify(unsigned int msg, void* p1, void* /*p2*/) {
    LogA("_MN_Notify msg=%u", msg);
    switch (msg) {
    case mn::code::notify::Load:
    case mn::code::notify::Enable:
        StartMonitor();
        break;

    case mn::code::notify::Disable:
    case mn::code::notify::Destroy:
        StopMonitor();
        break;

    case mn::code::notify::Search_Init: {
        // The host only keeps Search-type plugins alive. Register a keyword
        // that is never used in practice so this stays invisible to users.
        LogA("Search_Init");
        auto info = (mn::_SEARCH_INFO*)p1;
        if (!info) return 0;
        info->name = "Auto Theme";
        info->desc = "Auto Theme (system light/dark)";
        info->boost = 0;
        info->keys = "__autotheme_no_search__\0\0";
        info->regex = nullptr;
        info->fn = EmptySearch;
        info->fn_free = EmptySearchFree;
        return True;
    }

    case mn::code::notify::Open_Option:
        MessageBoxW(nullptr,
            L"Auto Theme follows Windows light/dark mode.\n\n"
            L"Edit autotheme.ini next to this plugin to choose the light and "
            L"dark theme files.",
            L"Auto Theme", MB_OK | MB_ICONINFORMATION);
        break;
    }
    return 0;
}

__MN_PLUGIN_IN__ mn::_PLUGIN_INFO _MN_Info(void* fn, void* ctx) {
    InitPaths();
    mn::_MN_API_FN = (mn::PFN_API_FN)fn;
    mn::_MN_CTX = ctx;

    return {
        .sdk_version = MN_SDK_VERSION,
        .name        = "Auto Theme",
        .description = "Auto-switch Maye Nano theme with Windows light/dark mode",
        .version     = "1.0.0.0",
        .author      = "guyuexuan",
        .email       = "",
        .homepage    = "",
        .type        = mn::code::type::Search,
        .fnNotify    = _MN_Notify,
    };
}
