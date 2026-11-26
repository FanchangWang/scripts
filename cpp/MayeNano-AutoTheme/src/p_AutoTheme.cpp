// Maye Nano plugin: Auto Theme (follow Windows light/dark mode)
//
// Calls Maye Nano's own theme-apply routine in-process, so no restart and no
// hotkey re-registration. Provides a settings window that follows the
// application's own Settings window.

#include <algorithm>
#include <string>

#include "SDK_MN.hpp"

#include <windows.h>

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <process.h>

// Plugin version — single source of truth, configured here in code.
// Update this one macro to bump the version shown in the settings window
// and reported to the host SDK (as PLUGIN_VERSION ".0").
#define PLUGIN_VERSION "2.3.0"

// ---------------------------------------------------------------------------
// constants
// ---------------------------------------------------------------------------

// Known-good versions: the RVA of the internal theme-apply routine is
// hard-coded so no runtime analysis is needed for these builds.
struct VersionEntry { const char* version; uintptr_t rva; };
static const VersionEntry KNOWN_VERSIONS[] = {
    { "7.0.1.261107", 0x1819D0 },
    { "6.2.0.260501", 0x17D970 },
    { "6.1.0.260422", 0x17D920 },
    { "6.0.0.260417", 0x17B150 },
};
static const char* SUPPORTED_VERSION_TEXT =
    "7.0.1.261107 / 6.2.0.260501 / 6.1.0.260422 / 6.0.0.260417";

#define MN_APPLY_THEME (WM_APP + 31)
#define MN_SHOW_UI     (WM_APP + 32)
#define MN_HIDE_UI     (WM_APP + 33)
#define MN_REFRESH_UI  (WM_APP + 34)
#define MN_DPI_SYNC    (WM_APP + 35)

#define ID_STATIC_INFO    2001
#define ID_STATIC_STATUS  2002
#define ID_STATIC_NOTICE  2003
#define ID_STATIC_VERSION 2004
#define ID_COMBO_LIGHT    2011
#define ID_COMBO_DARK     2012
#define ID_CHK_ENABLE     2013
#define ID_CHK_FOLLOW     2014
#define ID_BTN_SAVE       2015
#define ID_BTN_CLOSE      2016
#define ID_BTN_PREVIEW_LIGHT 2017
#define ID_BTN_PREVIEW_DARK  2018

// ---------------------------------------------------------------------------
// globals
// ---------------------------------------------------------------------------

static std::atomic<bool> g_running{ false };
static HANDLE            g_thread = nullptr;
static CRITICAL_SECTION  g_stateLock;
static bool              g_lockReady = false;

static std::wstring      g_exePath;
static std::wstring      g_exeDir;
static std::wstring      g_pluginDir;
static std::wstring      g_iniPath;
static std::wstring      g_logPath;
static std::wstring      g_firstRunFlag;

static std::string       g_lightTheme;   // empty = Default ("theme": "")
static std::string       g_darkTheme;
static bool              g_enabled          = true;
static bool              g_openWithSettings = true;
static bool              g_debugLog         = false;

static std::string       g_currentVersion;
static uintptr_t         g_applyRva   = 0;
static bool              g_functional = false;
static bool              g_adapted    = false;
static std::wstring      g_notice;

static HWND              g_dispatcher      = nullptr;
static HWND              g_settingsWnd     = nullptr;
static HWND              g_comboLight      = nullptr;
static HWND              g_comboDark       = nullptr;
static HWND              g_chkEnable       = nullptr;
static HWND              g_chkFollow       = nullptr;
static HWND              g_btnPreviewLight = nullptr;
static HWND              g_btnPreviewDark  = nullptr;
static HWND              g_staticStatus    = nullptr;
static HWND              g_staticNotice    = nullptr;
static HWND              g_appSettingsHwnd = nullptr;

static bool              g_settingsOpen = false;
static unsigned long     g_targetPid    = 0;

static int               g_dpi  = 96;
static HFONT             g_font = nullptr;

// Theme preview while our own settings window is open.
// 0 = no preview, 1 = light theme preview, 2 = dark theme preview.
// While the window is open the monitor loop pauses auto theme switching so
// the preview is not immediately overwritten; closing the window restores
// automatic switching.
static int               g_previewSlot = 0;

// settings UI dark mode (follows Windows AppsUseLightTheme)
static bool              g_uiDark       = false;
static HBRUSH            g_uiBgBrush    = nullptr;   // window / static background
static HBRUSH            g_uiEditBrush  = nullptr;   // combo edit/list background
#define MN_UI_THEME      (WM_APP + 36)

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

static int S(int v) { return MulDiv(v, g_dpi, 96); }

// Physical DPI of the primary monitor. Unlike GetDpiForSystem() this is not
// affected by a process that has not applied its DPI awareness yet.
static int PhysicalDpi() {
    typedef HRESULT(WINAPI* PFN_GetDpiForMonitor)(HMONITOR, int, UINT*, UINT*);
    HMODULE sh = LoadLibraryW(L"shcore.dll");
    int result = 96;
    if (sh) {
        auto fn = (PFN_GetDpiForMonitor)GetProcAddress(sh, "GetDpiForMonitor");
        if (fn) {
            HMONITOR hm = MonitorFromPoint(POINT{ 0, 0 }, MONITOR_DEFAULTTOPRIMARY);
            UINT x = 96, y = 96;
            if (SUCCEEDED(fn(hm, 0 /*MDT_EFFECTIVE_DPI*/, &x, &y)) && x >= 96)
                result = (int)x;
        }
        FreeLibrary(sh);
    }
    return result;
}

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
    g_debugLog         = (IniString(L"DebugLog", L"0") == L"1");
    g_enabled          = (IniString(L"Enable", L"1") == L"1");
    g_openWithSettings = (IniString(L"OpenWithSettings", L"1") == L"1");
    g_lightTheme       = ToUtf8(IniString(L"Light", L""));
    g_darkTheme        = ToUtf8(IniString(L"Dark", L"Black-1.json"));
}

static void InitPaths() {
    if (!g_iniPath.empty()) return;

    wchar_t path[MAX_PATH] = { 0 };
    GetModuleFileNameW(nullptr, path, MAX_PATH);
    g_exePath = path;

    size_t slash = 0;
    for (size_t i = 0; i < g_exePath.size(); ++i) {
        if (g_exePath[i] == L'\\' || g_exePath[i] == L'/') slash = i;
    }
    g_exeDir      = slash ? g_exePath.substr(0, slash) : std::wstring();
    g_pluginDir   = g_exeDir + L"\\plugin\\mn.cc.arae.autotheme";
    g_iniPath     = g_pluginDir + L"\\autotheme.ini";
    g_logPath     = g_pluginDir + L"\\autotheme.log";
    g_firstRunFlag = g_pluginDir + L"\\first-run.done";
}

// ---------------------------------------------------------------------------
// theme files
// ---------------------------------------------------------------------------

static bool ThemeFileExists(const std::string& name) {
    if (name.empty()) return true; // Default
    std::wstring p = g_exeDir + L"\\theme\\" + ToWide(name);
    return GetFileAttributesW(p.c_str()) != INVALID_FILE_ATTRIBUTES;
}

static std::wstring CheckThemeFiles() {
    std::wstring warn;
    if (!ThemeFileExists(g_lightTheme)) {
        warn = L"主题文件丢失：浅色主题 " + ToWide(g_lightTheme);
    }
    if (!ThemeFileExists(g_darkTheme)) {
        if (!warn.empty()) warn += L"\r\n";
        warn += L"主题文件丢失：深色主题 " + ToWide(g_darkTheme);
    }
    if (!warn.empty()) warn += L"\r\n请在下方重新选择主题文件。";
    return warn;
}

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

    // Unique per thread: the monitor thread and the UI thread can both write.
    std::wstring tmp = path + L".autotheme." +
                       std::to_wstring(GetCurrentThreadId()) + L".tmp";
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

// ---------------------------------------------------------------------------
// Windows theme detection
// ---------------------------------------------------------------------------

static bool IsWindowsLightMode() {
    HKEY h = nullptr;
    const wchar_t* sub = L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    if (RegOpenKeyExW(HKEY_CURRENT_USER, sub, 0, KEY_READ, &h) != ERROR_SUCCESS)
        return false;

    DWORD value = 1, type = 0, size = sizeof(value);
    LONG r = RegQueryValueExW(h, L"AppsUseLightTheme", nullptr, &type,
                              (BYTE*)&value, &size);
    RegCloseKey(h);
    return (r == ERROR_SUCCESS) ? (value != 0) : false;
}

// ---------------------------------------------------------------------------
// app Settings window detection
// ---------------------------------------------------------------------------

static bool g_foundSettings = false;

static const wchar_t* g_enumClass = nullptr;
static HWND           g_enumFound = nullptr;

static BOOL CALLBACK EnumAppWindowProc(HWND hwnd, LPARAM) {
    DWORD owner = 0;
    GetWindowThreadProcessId(hwnd, &owner);
    if (owner != g_targetPid) return TRUE;
    wchar_t cls[128] = { 0 };
    GetClassNameW(hwnd, cls, 128);
    if (_wcsicmp(cls, g_enumClass) == 0 && IsWindow(hwnd)) {
        g_enumFound = hwnd;
        return FALSE;
    }
    return TRUE;
}

static HWND FindAppWindow(const wchar_t* cls) {
    g_enumClass = cls;
    g_enumFound = nullptr;
    EnumWindows(EnumAppWindowProc, 0);
    return g_enumFound;
}

static BOOL CALLBACK EnumSettingsProc(HWND hwnd, LPARAM) {
    DWORD owner = 0;
    GetWindowThreadProcessId(hwnd, &owner);
    if (owner != g_targetPid) return TRUE;

    wchar_t cls[128] = { 0 }, title[128] = { 0 };
    GetClassNameW(hwnd, cls, 128);
    GetWindowTextW(hwnd, title, 128);
    if (_wcsicmp(cls, L"ImGui Platform") == 0 &&
        wcscmp(title, L"Settings") == 0 &&
        IsWindowVisible(hwnd)) {
        g_foundSettings = true;
        g_appSettingsHwnd = hwnd;
        return FALSE;
    }
    return TRUE;
}

static bool IsAppSettingsVisible() {
    g_foundSettings = false;
    g_appSettingsHwnd = nullptr;
    EnumWindows(EnumSettingsProc, 0);
    return g_foundSettings;
}

// ---------------------------------------------------------------------------
// internal theme-apply function
// ---------------------------------------------------------------------------

using PFN_ApplyTheme = void(__cdecl*)(const char*);

static uintptr_t DiscoverApplyRva() {
    HMODULE mod = GetModuleHandleW(nullptr);
    auto base = (BYTE*)mod;
    auto dos = (IMAGE_DOS_HEADER*)base;
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return 0;
    auto nt = (IMAGE_NT_HEADERS64*)(base + dos->e_lfanew);
    size_t imageSize = nt->OptionalHeader.SizeOfImage;

    static const wchar_t fmt[] = L".\\theme\\{}";
    size_t patLen = (wcslen(fmt) + 1) * sizeof(wchar_t);

    uintptr_t stringVa = 0;
    for (size_t off = 0; off + patLen <= imageSize; ++off) {
        if (memcmp(base + off, fmt, patLen) == 0) {
            stringVa = (uintptr_t)(base + off);
            break;
        }
    }
    if (!stringVa) return 0;

    static const BYTE prologue[] = {
        0x48, 0x89, 0x5C, 0x24, 0x10, 0x48, 0x89, 0x7C, 0x24, 0x18, 0x55
    };
    for (size_t off = 0; off + 4 <= imageSize; ++off) {
        int32_t disp = *(const int32_t*)(base + off);
        if ((uintptr_t)(base + off) + 4 + (int64_t)disp != stringVa) continue;

        for (size_t back = 1; back <= 0x1000 && back <= off; ++back) {
            size_t p = off - back;
            if (p + sizeof(prologue) > imageSize) continue;
            if (memcmp(base + p, prologue, sizeof(prologue)) == 0) {
                uintptr_t rva = (uintptr_t)(base + p) - (uintptr_t)base;
                LogA("discovered apply rva=0x%llx", (unsigned long long)rva);
                return rva;
            }
        }
    }
    return 0;
}

static void InitThemeFunction() {
    g_currentVersion = (mn::_MN_API_FN && mn::_MN_CTX)
        ? mn::Api::GetVersion() : std::string();

    bool known = false;
    for (const auto& e : KNOWN_VERSIONS) {
        if (g_currentVersion == e.version) {
            g_applyRva = e.rva;
            g_functional = true;
            known = true;
            break;
        }
    }
    if (!known) {
        g_applyRva = DiscoverApplyRva();
        g_functional = (g_applyRva != 0);
        g_adapted = g_functional;
    }
    LogA("version='%s' rva=0x%llx functional=%d adapted=%d",
         g_currentVersion.c_str(), (unsigned long long)g_applyRva,
         (int)g_functional, (int)g_adapted);
}

static std::wstring StatusText() {
    if (g_functional && !g_adapted)
        return L"版本匹配（当前 " + ToWide(g_currentVersion) + L"），功能可用";
    if (g_functional && g_adapted)
        return L"版本已自动适配（当前 " + ToWide(g_currentVersion) + L"），功能可用";
    return L"版本不匹配：当前 " + ToWide(g_currentVersion) +
           L"，支持 " + ToWide(SUPPORTED_VERSION_TEXT) + L"，功能已静默";
}

// The app only updates the DWM border color in its own Settings handler.
// Replicate that step after our in-process theme apply, otherwise the outer
// border keeps the previous theme's color.
static DWORD BorderColorForTheme(const std::string& theme) {
    if (theme.empty()) return 0x00BCBCBC; // Default theme border

    std::wstring path = g_exeDir + L"\\theme\\" + ToWide(theme);
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return 0x00BCBCBC;

    DWORD size = GetFileSize(f, nullptr);
    std::string text;
    if (size && size < 1024 * 1024) {
        text.resize(size);
        DWORD read = 0;
        if (ReadFile(f, &text[0], size, &read, nullptr)) text.resize(read);
        else text.clear();
    }
    CloseHandle(f);

    size_t k = text.find("\"win_border\"");
    if (k == std::string::npos) return 0x00BCBCBC;
    size_t colon = text.find(':', k);
    if (colon == std::string::npos) return 0x00BCBCBC;
    unsigned long long v = _strtoui64(text.c_str() + colon + 1, nullptr, 0);
    return (DWORD)(v & 0x00FFFFFF);
}

static void ApplyDwmBorder(const std::string& theme) {
    HMODULE dwm = GetModuleHandleW(L"dwmapi.dll");
    if (!dwm) return;
    using PFN = HRESULT(WINAPI*)(HWND, DWORD, LPCVOID, DWORD);
    auto fn = (PFN)GetProcAddress(dwm, "DwmSetWindowAttribute");
    HWND hwnd = FindAppWindow(L"_ArWin_MayeNanoLauncher");
    if (!fn || !hwnd) return;

    DWORD color = BorderColorForTheme(theme);
    fn(hwnd, 34 /*DWMWA_BORDER_COLOR*/, &color, sizeof(color));
    LogA("DWM border set to 0x%06X", color);
}

static bool InvokeApply(PFN_ApplyTheme fn, const char* name) {
    // Best-effort: an incorrectly discovered RVA must not kill the host.
    __try {
        fn(name);
        return true;
    }
    __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

static void CallInternalTheme(const std::string& theme) {
    if (!g_functional || !g_applyRva) return;
    HMODULE mod = GetModuleHandleW(nullptr);
    if (!mod) return;
    auto fn = (PFN_ApplyTheme)((BYTE*)mod + g_applyRva);
    if (!InvokeApply(fn, theme.empty() ? nullptr : theme.c_str())) {
        if (g_lockReady) EnterCriticalSection(&g_stateLock);
        g_functional = false; // disable the feature instead of crashing again
        if (g_lockReady) LeaveCriticalSection(&g_stateLock);
        LogA("internal theme call faulted; feature disabled");
        return;
    }
    LogA("internal theme applied: '%s'", theme.c_str());
    ApplyDwmBorder(theme);
}

// ---------------------------------------------------------------------------
// settings UI
// ---------------------------------------------------------------------------

// Apply light/dark styling to our own settings window. Uses only APIs that
// exist on Windows 10+, and degrades silently on older builds.
static void ApplySettingsTheme(bool dark) {
    if (g_uiBgBrush)   { DeleteObject(g_uiBgBrush);   g_uiBgBrush = nullptr; }
    if (g_uiEditBrush) { DeleteObject(g_uiEditBrush); g_uiEditBrush = nullptr; }
    if (dark) {
        g_uiBgBrush   = CreateSolidBrush(RGB(32, 32, 32));
        g_uiEditBrush = CreateSolidBrush(RGB(45, 45, 48));
    }

    if (g_settingsWnd) {
        // Dark title bar (DWMWA_USE_IMMERSIVE_DARK_MODE = 20, Win10 19041+)
        HMODULE dwm = GetModuleHandleW(L"dwmapi.dll");
        if (dwm) {
            using PFN = HRESULT(WINAPI*)(HWND, DWORD, LPCVOID, DWORD);
            auto fn = (PFN)GetProcAddress(dwm, "DwmSetWindowAttribute");
            if (fn) {
                DWORD v = dark ? 1 : 0;
                fn(g_settingsWnd, 20, &v, sizeof(v));
            }
        }
    }

    // uxtheme.dll is not linked at build time -> load dynamically.
    // Undocumented per-window dark opt-in (ordinal 133) and SetWindowTheme.
    HMODULE ux = LoadLibraryW(L"uxtheme.dll");
    using PFN_THEME = HRESULT(WINAPI*)(HWND, LPCWSTR, LPCWSTR);
    using PFN_ALLOW = BOOL(WINAPI*)(HWND, BOOL);
    PFN_THEME setTheme = ux ? (PFN_THEME)GetProcAddress(ux, "SetWindowTheme") : nullptr;
    PFN_ALLOW allowDark = ux ? (PFN_ALLOW)GetProcAddress(ux, MAKEINTRESOURCEA(133)) : nullptr;

    auto apply = [&](HWND h, LPCWSTR darkTheme, LPCWSTR lightTheme) {
        if (!h) return;
        if (allowDark) allowDark(h, dark ? TRUE : FALSE);
        if (setTheme)  setTheme(h, dark ? darkTheme : lightTheme, nullptr);
        SendMessageW(h, WM_THEMECHANGED, 0, 0);
        InvalidateRect(h, nullptr, TRUE);
    };

    if (setTheme || allowDark) {
        // ComboBox needs "DarkMode_CFD", not "DarkMode_Explorer".
        apply(g_comboLight, L"DarkMode_CFD", L"CFD");
        apply(g_comboDark,  L"DarkMode_CFD", L"CFD");
        // Buttons / checkboxes use "DarkMode_Explorer".
        apply(g_chkEnable,  L"DarkMode_Explorer", L"Explorer");
        apply(g_chkFollow,  L"DarkMode_Explorer", L"Explorer");
        apply(g_btnPreviewLight, L"DarkMode_Explorer", L"Explorer");
        apply(g_btnPreviewDark,  L"DarkMode_Explorer", L"Explorer");
        if (g_settingsWnd) {
            apply(GetDlgItem(g_settingsWnd, ID_BTN_SAVE),  L"DarkMode_Explorer", L"Explorer");
            apply(GetDlgItem(g_settingsWnd, ID_BTN_CLOSE), L"DarkMode_Explorer", L"Explorer");
        }
    }
    if (ux) FreeLibrary(ux);

    // Repaint parent + all children so the new brushes take effect at once.
    if (g_settingsWnd) {
        RedrawWindow(g_settingsWnd, nullptr, nullptr,
                     RDW_INVALIDATE | RDW_ERASE | RDW_ALLCHILDREN | RDW_UPDATENOW);
    }
    LogA("settings theme -> %s", dark ? "dark" : "light");
}

// Keep the two preview buttons in sync with g_previewSlot.
static void UpdatePreviewButtons() {
    if (g_btnPreviewLight)
        SetWindowTextW(g_btnPreviewLight, g_previewSlot == 1 ? L"取消预览" : L"预览主题");
    if (g_btnPreviewDark)
        SetWindowTextW(g_btnPreviewDark, g_previewSlot == 2 ? L"取消预览" : L"预览主题");
}

// Apply one specific theme right now. Shared by auto switching and preview so
// both go through the same config + host-apply path.
static void ApplyThemeNow(const std::string& desired) {
    std::string current = ReadAppTheme();
    if (current == desired) return;
    if (!ThemeFileExists(desired)) {
        LogA("theme file missing, skip apply: '%s'", desired.c_str());
        return;
    }
    if (!WriteAppTheme(desired)) return;
    if (mn::_MN_API_FN && mn::_MN_CTX)
        (void)mn::Api::SetCfgItem("theme", desired.c_str());

    auto* copy = new std::string(desired);
    if (!PostMessageW(g_dispatcher, MN_APPLY_THEME, 0, (LPARAM)copy)) delete copy;
}

// slot: 0 = cancel preview (restore auto theme), 1 = light, 2 = dark.
static void ApplyPreview(int slot) {
    std::string theme;
    if (g_lockReady) EnterCriticalSection(&g_stateLock);
    g_previewSlot = slot;
    if (slot == 1)      theme = g_lightTheme;
    else if (slot == 2) theme = g_darkTheme;
    else                theme = IsWindowsLightMode() ? g_lightTheme : g_darkTheme;
    if (g_lockReady) LeaveCriticalSection(&g_stateLock);

    if (g_enabled && g_functional) ApplyThemeNow(theme);
    LogA("preview slot=%d theme='%s'", slot, theme.c_str());
    UpdatePreviewButtons();
}

// Hide our settings window and let auto theme switching take over again.
static void HideSettingsUI() {
    if (!g_settingsWnd) return;
    ShowWindow(g_settingsWnd, SW_HIDE);
    if (g_previewSlot != 0) ApplyPreview(0);
}

static HWND MakeCtrl(HWND parent, const wchar_t* cls, const wchar_t* text,
                     int id, DWORD style) {
    HWND h = CreateWindowExW(0, cls, text, WS_CHILD | WS_VISIBLE | style,
        0, 0, 0, 0, parent, (HMENU)(INT_PTR)id, nullptr, nullptr);
    if (h && g_font) SendMessageW(h, WM_SETFONT, (WPARAM)g_font, TRUE);
    return h;
}

static void CreateFontForDpi(int dpi) {
    if (g_font) DeleteObject(g_font);
    g_font = CreateFontW(-MulDiv(12, dpi, 96), 0, 0, 0, FW_NORMAL,
        0, 0, 0, DEFAULT_CHARSET, OUT_TT_PRECIS, CLIP_DEFAULT_PRECIS,
        DEFAULT_QUALITY, DEFAULT_PITCH | FF_DONTCARE, L"Microsoft YaHei UI");
    if (!g_font) g_font = (HFONT)GetStockObject(DEFAULT_GUI_FONT);
}

static void LayoutControls(HWND hwnd) {
    struct Item { HWND h; int x, y, w, ht; };
    Item items[] = {
        { GetDlgItem(hwnd, ID_STATIC_INFO),   16,  14, 480, 44 },
        { g_staticStatus,                     16,  64, 480, 22 },
        { g_staticNotice,                     16,  92, 480, 58 },
        { GetDlgItem(hwnd, 2101),             16, 160, 110, 20 }, // label Light
        { g_comboLight,                      132, 156, 280, 200 },
        { g_btnPreviewLight,                 418, 156,  86, 23 }, // light preview
        { GetDlgItem(hwnd, 2102),             16, 194, 110, 20 }, // label Dark
        { g_comboDark,                       132, 190, 280, 200 },
        { g_btnPreviewDark,                  418, 190,  86, 23 }, // dark preview
        { g_chkEnable,                        16, 228, 396, 24 },
        { g_chkFollow,                        16, 260, 396, 24 },
        { GetDlgItem(hwnd, 2103),             16, 296, 480, 36 }, // hint
        { GetDlgItem(hwnd, ID_BTN_SAVE),      16, 342, 110, 30 },
        { GetDlgItem(hwnd, ID_BTN_CLOSE),    136, 342, 110, 30 },
        { GetDlgItem(hwnd, ID_STATIC_VERSION), 340, 348, 156, 20 }, // plugin version
    };
    for (const auto& it : items) {
        if (it.h) {
            MoveWindow(it.h, S(it.x), S(it.y), S(it.w), S(it.ht), TRUE);
            if (g_font) SendMessageW(it.h, WM_SETFONT, (WPARAM)g_font, TRUE);
        }
    }
}

static void FillCombo(HWND combo, const std::string& current) {
    if (!combo) return;
    SendMessageW(combo, CB_RESETCONTENT, 0, 0);
    SendMessageW(combo, CB_ADDSTRING, 0, (LPARAM)L"Default");

    std::wstring pattern = g_exeDir + L"\\theme\\*.json";
    WIN32_FIND_DATAW fd{};
    HANDLE h = FindFirstFileW(pattern.c_str(), &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
            std::wstring name(fd.cFileName);
            if (name.size() > 5 &&
                _wcsicmp(name.c_str() + name.size() - 5, L".json") == 0) {
                name.resize(name.size() - 5);
                SendMessageW(combo, CB_ADDSTRING, 0, (LPARAM)name.c_str());
            }
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }

    std::wstring want = current.empty() ? L"Default" : ToWide(current);
    if (want.size() > 5 && _wcsicmp(want.c_str() + want.size() - 5, L".json") == 0)
        want.resize(want.size() - 5);
    LRESULT idx = SendMessageW(combo, CB_FINDSTRINGEXACT, (WPARAM)-1, (LPARAM)want.c_str());
    SendMessageW(combo, CB_SETCURSEL, idx < 0 ? 0 : idx, 0);
}

static std::wstring ComboSelection(HWND combo) {
    if (!combo) return L"";
    LRESULT i = SendMessageW(combo, CB_GETCURSEL, 0, 0);
    if (i == CB_ERR) return L"";
    wchar_t buf[512] = { 0 };
    LRESULT n = SendMessageW(combo, CB_GETLBTEXT, (WPARAM)i, (LPARAM)buf);
    if (n <= 0) return L"";
    if (wcscmp(buf, L"Default") == 0) return L"";
    return std::wstring(buf) + L".json";
}

static void RefreshSettingsUI() {
    if (!g_settingsWnd) return;

    const wchar_t* infoText =
        L"本窗口用于设置 Maye Nano 自动跟随 Windows 浅色 / 深色模式切换应用主题。\r\n"
        L"本窗口打开时自动切换暂停，可用「预览主题」临时查看效果；关闭窗口后恢复自动切换。";
    SetWindowTextW(GetDlgItem(g_settingsWnd, ID_STATIC_INFO), infoText);
    SetWindowTextW(g_staticStatus, StatusText().c_str());
    SetWindowTextW(g_staticNotice, g_notice.c_str());
    SetWindowTextW(GetDlgItem(g_settingsWnd, 2103),
        L"也可通过 设置 → 插件 → AutoTheme → 右侧「···」→ 选项 打开本窗口。");
    std::wstring verText = L"插件版本 v" + ToWide(PLUGIN_VERSION);
    SetWindowTextW(GetDlgItem(g_settingsWnd, ID_STATIC_VERSION), verText.c_str());

    SendMessageW(g_chkEnable, BM_SETCHECK, g_enabled ? BST_CHECKED : BST_UNCHECKED, 0);
    SendMessageW(g_chkFollow, BM_SETCHECK, g_openWithSettings ? BST_CHECKED : BST_UNCHECKED, 0);
    FillCombo(g_comboLight, g_lightTheme);
    FillCombo(g_comboDark, g_darkTheme);
    UpdatePreviewButtons();
}

// Place the window next to / above the app Settings window, or the launcher.
// show=false 时只重新定位/布局，不显示窗口。
// 用于 MN_DPI_SYNC：启动时 DPI 从 96 变为 144 会触发它，
// 若这里显示窗口，就会绕过 g_notice / MN_SHOW_UI 的控制而误弹设置窗口。
static void PlaceWindow(bool show = true) {
    HWND anchor = g_appSettingsHwnd;
    if (!anchor || !IsWindow(anchor)) {
        anchor = FindAppWindow(L"_ArWin_MayeNanoLauncher");
        if (anchor && !IsWindowVisible(anchor)) anchor = nullptr;
    }

    // Use the same DPI as the application window (the process can be
    // DPI-virtualized while the app itself is per-monitor aware).
    if (anchor) {
        int d = (int)GetDpiForWindow(anchor);
        if (d >= 96 && d != g_dpi) {
            g_dpi = d;
            CreateFontForDpi(g_dpi);
            LayoutControls(g_settingsWnd);
        }
    }

    int w = S(520), h = S(420); // logical size, keep it stable

    int x = 0, y = 0;
    RECT ar{};
    MONITORINFO mi{};
    mi.cbSize = sizeof(mi);
    GetMonitorInfoW(MonitorFromWindow(anchor ? anchor : g_settingsWnd,
                                     MONITOR_DEFAULTTONEAREST), &mi);
    ar = mi.rcWork;

    if (anchor) {
        RECT a{};
        GetWindowRect(anchor, &a);
        x = a.left;
        y = a.top - h - 12; // just above the Settings window
        if (y < ar.top) {
            x = a.right + 12; // otherwise to its right
            y = a.top;
        }
        if (x + w > ar.right) x = a.left - w - 12;
    } else {
        x = ar.left + (ar.right - ar.left - w) / 2;
        y = ar.top + (ar.bottom - ar.top - h) / 2;
    }
    if (x < (int)ar.left) x = (int)ar.left;
    if (y < (int)ar.top) y = (int)ar.top;
    if (x + w > ar.right) x = ar.right - w;
    if (y + h > ar.bottom) y = ar.bottom - h;

    bool topMost = false;
    HANDLE f = CreateFileW((g_exeDir + L"\\cfg.json").c_str(), GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f != INVALID_HANDLE_VALUE) {
        DWORD size = GetFileSize(f, nullptr);
        std::string text;
        if (size && size < 4 * 1024 * 1024) {
            text.resize(size);
            DWORD read = 0;
            if (ReadFile(f, &text[0], size, &read, nullptr)) text.resize(read);
        }
        CloseHandle(f);
        topMost = text.find("\"IsTopMost\":true") != std::string::npos;
    }

    SetWindowTextW(g_settingsWnd, L"Auto Theme 插件设置");
    UINT posFlags = show ? SWP_SHOWWINDOW : SWP_NOZORDER;
    SetWindowPos(g_settingsWnd, topMost ? HWND_TOPMOST : HWND_TOP,
                 x, y, w, h, posFlags);
    if (show) {
        ShowWindow(g_settingsWnd, SW_SHOW);
        SetForegroundWindow(g_settingsWnd);
        LogA("show settings at %d,%d %dx%d dpi=%d", x, y, w, h, g_dpi);
    }
}

static void SaveSettingsUI() {
    std::wstring light = ComboSelection(g_comboLight);
    std::wstring dark  = ComboSelection(g_comboDark);
    bool enabled = SendMessageW(g_chkEnable, BM_GETCHECK, 0, 0) == BST_CHECKED;
    bool follow  = SendMessageW(g_chkFollow, BM_GETCHECK, 0, 0) == BST_CHECKED;

    WritePrivateProfileStringW(L"AutoTheme", L"Light", light.c_str(), g_iniPath.c_str());
    WritePrivateProfileStringW(L"AutoTheme", L"Dark", dark.c_str(), g_iniPath.c_str());
    WritePrivateProfileStringW(L"AutoTheme", L"Enable", enabled ? L"1" : L"0", g_iniPath.c_str());
    WritePrivateProfileStringW(L"AutoTheme", L"OpenWithSettings", follow ? L"1" : L"0", g_iniPath.c_str());

    if (g_lockReady) EnterCriticalSection(&g_stateLock);
    g_lightTheme = ToUtf8(light);
    g_darkTheme  = ToUtf8(dark);
    g_enabled    = enabled;
    g_openWithSettings = follow;
    g_previewSlot = 0;   // saving ends any active preview
    if (g_lockReady) LeaveCriticalSection(&g_stateLock);

    if (g_functional && g_enabled) {
        std::string desired = IsWindowsLightMode() ? g_lightTheme : g_darkTheme;
        if (ThemeFileExists(desired) && WriteAppTheme(desired)) CallInternalTheme(desired);
    }
    g_notice.clear();
    RefreshSettingsUI();
    MessageBoxW(g_settingsWnd, L"配置已保存，立即生效。", L"Auto Theme",
                MB_OK | MB_ICONINFORMATION);
    // 确认后自动关闭设置窗口（并恢复自动主题切换）
    HideSettingsUI();
}

static LRESULT CALLBACK SettingsWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_CREATE:
        // g_dpi is set once in CreateWindows(); do not change it here.
        LogA("WM_CREATE dpi=%d", g_dpi);
        CreateFontForDpi(g_dpi);
        MakeCtrl(hwnd, L"Static", L"", ID_STATIC_INFO, SS_LEFT);
        g_staticStatus = MakeCtrl(hwnd, L"Static", L"", ID_STATIC_STATUS, SS_LEFT);
        g_staticNotice = MakeCtrl(hwnd, L"Static", L"", ID_STATIC_NOTICE, SS_LEFT);
        MakeCtrl(hwnd, L"Static", L"浅色主题 (Light)", 2101, SS_LEFT);
        g_comboLight = MakeCtrl(hwnd, L"ComboBox", L"", ID_COMBO_LIGHT,
                                CBS_DROPDOWNLIST | WS_TABSTOP);
        g_btnPreviewLight = MakeCtrl(hwnd, L"Button", L"预览主题",
                            ID_BTN_PREVIEW_LIGHT, BS_PUSHBUTTON | WS_TABSTOP);
        MakeCtrl(hwnd, L"Static", L"深色主题 (Dark)", 2102, SS_LEFT);
        g_comboDark = MakeCtrl(hwnd, L"ComboBox", L"", ID_COMBO_DARK,
                               CBS_DROPDOWNLIST | WS_TABSTOP);
        g_btnPreviewDark = MakeCtrl(hwnd, L"Button", L"预览主题",
                           ID_BTN_PREVIEW_DARK, BS_PUSHBUTTON | WS_TABSTOP);
        g_chkEnable = MakeCtrl(hwnd, L"Button", L"启用自动切换主题", ID_CHK_ENABLE,
                               BS_AUTOCHECKBOX | WS_TABSTOP);
        g_chkFollow = MakeCtrl(hwnd, L"Button", L"打开软件设置窗口时自动弹出本窗口",
                               ID_CHK_FOLLOW, BS_AUTOCHECKBOX | WS_TABSTOP);
        MakeCtrl(hwnd, L"Static", L"", 2103, SS_LEFT);
        MakeCtrl(hwnd, L"Static", L"", ID_STATIC_VERSION, SS_RIGHT);
        MakeCtrl(hwnd, L"Button", L"保存", ID_BTN_SAVE, BS_PUSHBUTTON | WS_TABSTOP);
        MakeCtrl(hwnd, L"Button", L"关闭", ID_BTN_CLOSE, BS_PUSHBUTTON | WS_TABSTOP);
        LayoutControls(hwnd);
        return 0;

    case WM_DPICHANGED: {
        LogA("WM_DPICHANGED dpiX=%u dpiY=%u", LOWORD(wp), HIWORD(wp));
        g_dpi = HIWORD(wp);
        if (g_dpi < 96) g_dpi = 96;
        CreateFontForDpi(g_dpi);
        RECT* suggested = (RECT*)lp;
        // Windows suggests a physical-pixel rect that can shrink the window;
        // keep our logical size instead.
        SetWindowPos(hwnd, nullptr, suggested->left, suggested->top,
                     S(520), S(420), SWP_NOZORDER | SWP_NOACTIVATE);
        LayoutControls(hwnd);
        RefreshSettingsUI();
        return 0;
    }
    case WM_ERASEBKGND:
        // Paint the whole client area. Without this, the window class brush
        // (COLOR_WINDOW) paints white and only the Static controls above are
        // dark -> a half-white / half-black window.
        if (g_uiDark && g_uiBgBrush) {
            HDC dc = (HDC)wp;
            RECT rc{};
            GetClientRect(hwnd, &rc);
            FillRect(dc, &rc, g_uiBgBrush);
            return TRUE;
        }
        return DefWindowProcW(hwnd, msg, wp, lp);
    case WM_CTLCOLOREDIT:
    case WM_CTLCOLORLISTBOX:
        // Combo text field / dropdown list (WM_CTLCOLOR* goes to the parent).
        if (g_uiDark && g_uiEditBrush) {
            HDC dc = (HDC)wp;
            SetTextColor(dc, RGB(230, 230, 230));
            SetBkColor(dc, RGB(45, 45, 48));
            return (LRESULT)g_uiEditBrush;
        }
        return DefWindowProcW(hwnd, msg, wp, lp);
    case WM_CTLCOLORSTATIC: {
        // Make static text readable on the dark background
        HDC dc = (HDC)wp;
        if (g_uiDark) {
            SetTextColor(dc, RGB(230, 230, 230));
            SetBkMode(dc, TRANSPARENT);
            return (LRESULT)g_uiBgBrush;
        }
        SetTextColor(dc, RGB(0, 0, 0));
        SetBkMode(dc, TRANSPARENT);
        return (LRESULT)GetSysColorBrush(COLOR_WINDOW);
    }
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case ID_BTN_SAVE:  SaveSettingsUI(); return 0;
        case ID_BTN_CLOSE: HideSettingsUI(); return 0;
        case ID_BTN_PREVIEW_LIGHT:
            ApplyPreview(g_previewSlot == 1 ? 0 : 1);
            return 0;
        case ID_BTN_PREVIEW_DARK:
            ApplyPreview(g_previewSlot == 2 ? 0 : 2);
            return 0;
        }
        return 0;
    case WM_CLOSE:
        HideSettingsUI();
        return 0;
    case WM_DESTROY:
        if (g_font) { DeleteObject(g_font); g_font = nullptr; }
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

static LRESULT CALLBACK DispatcherWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case MN_APPLY_THEME: {
        auto* theme = reinterpret_cast<std::string*>(lp);
        if (theme) { CallInternalTheme(*theme); delete theme; }
        return 0;
    }
    case MN_SHOW_UI:   PlaceWindow(); RefreshSettingsUI(); return 0;
    case MN_HIDE_UI:   HideSettingsUI(); return 0;
    case MN_REFRESH_UI: RefreshSettingsUI(); return 0;
    case MN_UI_THEME:
        g_uiDark = (wp == 0);
        ApplySettingsTheme(g_uiDark);
        RefreshSettingsUI();
        return 0;
    case MN_DPI_SYNC:
        if (wp >= 96) {
            g_dpi = (int)wp;
            CreateFontForDpi(g_dpi);
            if (g_settingsWnd) {
                LayoutControls(g_settingsWnd);
                RefreshSettingsUI();
                // 仅重新定位，不要显示窗口（否则首次启动误弹设置窗口）
                PlaceWindow(false);
            }
        }
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

static void CreateWindows() {
    HMODULE hmod = nullptr;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                       GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       (LPCWSTR)&DispatcherWndProc, &hmod);

    WNDCLASSW wc{};
    wc.lpfnWndProc = DispatcherWndProc;
    wc.hInstance = hmod;
    wc.lpszClassName = L"MN_AutoTheme_Msg";
    RegisterClassW(&wc);
    g_dispatcher = CreateWindowExW(0, L"MN_AutoTheme_Msg", nullptr, 0,
        0, 0, 0, 0, HWND_MESSAGE, nullptr, hmod, nullptr);

    WNDCLASSW sc{};
    sc.lpfnWndProc = SettingsWndProc;
    sc.hInstance = hmod;
    sc.lpszClassName = L"MN_AutoTheme_Settings";
    sc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    sc.style = CS_HREDRAW | CS_VREDRAW;
    RegisterClassW(&sc);

    int dpi = 0;
    HWND appWin = FindAppWindow(L"_ArWin_MayeNanoLauncher");
    if (appWin) dpi = (int)GetDpiForWindow(appWin);
    if (dpi < 96) dpi = PhysicalDpi();
    if (dpi < 96) dpi = (int)GetDpiForSystem();
    if (dpi < 96) dpi = 96;
    g_dpi = dpi;
    LogA("create windows dpi=%d appWin=%p", g_dpi, appWin);
    g_settingsWnd = CreateWindowExW(0, L"MN_AutoTheme_Settings",
        L"Auto Theme 插件设置", WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT, CW_USEDEFAULT, S(520), S(420),
        nullptr, nullptr, hmod, nullptr);

    LogA("dispatcher=%p settings=%p dpi=%d", g_dispatcher, g_settingsWnd, g_dpi);
}

static void Post(UINT msg) {
    if (g_dispatcher) PostMessageW(g_dispatcher, msg, 0, 0);
}

// ---------------------------------------------------------------------------
// monitor
// ---------------------------------------------------------------------------

static void ApplyTheme(bool light) {
    bool enabled = false, functional = false;
    std::string desired;
    if (g_lockReady) EnterCriticalSection(&g_stateLock);
    enabled = g_enabled;
    functional = g_functional;
    desired = light ? g_lightTheme : g_darkTheme;
    if (g_lockReady) LeaveCriticalSection(&g_stateLock);

    if (!enabled || !functional) return;

    // Our settings window is open: the user may be previewing a theme.
    // Pause automatic switching so the preview is not immediately replaced.
    if (g_settingsWnd && IsWindowVisible(g_settingsWnd)) return;

    ApplyThemeNow(desired);
}

static unsigned __stdcall MonitorThread(LPVOID) {
    int lastPostedDpi = 0;
    bool lastLight = IsWindowsLightMode();
    while (g_running.load()) {
        bool light = IsWindowsLightMode();
        ApplyTheme(light);

        // UI theme follows the system: ask the dispatcher thread to restyle
        if (light != lastLight) {
            lastLight = light;
            PostMessageW(g_dispatcher, MN_UI_THEME, light ? 1 : 0, 0);
            LogA("ui theme change posted: %s", light ? "light" : "dark");
        }

        // The launcher window appears after plugins load. Ask the UI thread
        // (via our dispatcher) to adopt its DPI; never touch controls here.
        HWND appWin = FindAppWindow(L"_ArWin_MayeNanoLauncher");
        if (appWin) {
            int d = (int)GetDpiForWindow(appWin);
            if (d >= 96 && d != lastPostedDpi) {
                lastPostedDpi = d;
                PostMessageW(g_dispatcher, MN_DPI_SYNC, (WPARAM)d, 0);
                LogA("dpi sync posted: %d", d);
            }
        }

        // Maye Nano re-asserts its own topmost state; keep our popup above it.
        // But do NOT re-assert while a theme dropdown is open: this loop runs
        // every 300ms, and forcing the settings window back to the top pushes
        // it above its own (non-topmost) dropdown list, hiding the choices.
        const UINT CB_GETDROPPEDSTATE_MSG = 0x0157;
        bool dropdownOpen =
            (g_comboLight && SendMessageW(g_comboLight, CB_GETDROPPEDSTATE_MSG, 0, 0)) ||
            (g_comboDark  && SendMessageW(g_comboDark,  CB_GETDROPPEDSTATE_MSG, 0, 0));
        if (!dropdownOpen && g_settingsWnd && IsWindowVisible(g_settingsWnd)) {
            LONG_PTR ex = GetWindowLongPtr(g_settingsWnd, GWL_EXSTYLE);
            HWND z = (ex & WS_EX_TOPMOST) ? HWND_TOPMOST : HWND_TOP;
            SetWindowPos(g_settingsWnd, z, 0, 0, 0, 0,
                         SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        }

        bool nowOpen = IsAppSettingsVisible();
        if (nowOpen && !g_settingsOpen && g_openWithSettings) Post(MN_SHOW_UI);
        if (!nowOpen && g_settingsOpen && g_settingsWnd && IsWindowVisible(g_settingsWnd))
            Post(MN_HIDE_UI);
        g_settingsOpen = nowOpen;

        HKEY h = nullptr;
        const wchar_t* sub = L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
        if (RegOpenKeyExW(HKEY_CURRENT_USER, sub, 0, KEY_READ, &h) == ERROR_SUCCESS) {
            HANDLE ev = CreateEventW(nullptr, FALSE, FALSE, nullptr);
            if (ev) {
                RegNotifyChangeKeyValue(h, FALSE, REG_NOTIFY_CHANGE_LAST_SET, ev, TRUE);
                WaitForSingleObject(ev, 300);
                CloseHandle(ev);
            } else {
                Sleep(300);
            }
            RegCloseKey(h);
        } else {
            Sleep(300);
        }
    }
    return 0;
}

// ---------------------------------------------------------------------------
// plugin entry points
// ---------------------------------------------------------------------------

static mn::SEARCH_RESULT_LIST EmptySearch(const char*, const char*, const char*) {
    return { nullptr, 0 };
}
static void EmptySearchFree() {}

static void StartMonitor() {
    InitPaths();
    if (g_running.exchange(true)) return;

    g_targetPid = GetCurrentProcessId();
    if (!g_lockReady) {
        InitializeCriticalSection(&g_stateLock);
        g_lockReady = true;
    }
    LoadIni();
    InitThemeFunction();
    CreateWindows();

    // Settings window follows Windows light/dark mode from the start
    g_uiDark = !IsWindowsLightMode();
    ApplySettingsTheme(g_uiDark);

    bool firstRun = (GetFileAttributesW(g_firstRunFlag.c_str()) == INVALID_FILE_ATTRIBUTES);
    if (firstRun) {
        HANDLE f = CreateFileW(g_firstRunFlag.c_str(), GENERIC_WRITE, 0, nullptr,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f != INVALID_HANDLE_VALUE) CloseHandle(f);
        g_notice += L"首次安装：请确认下面的主题选择，然后点击「保存」。\r\n"
                    L"之后仅在主题文件丢失或打开软件设置时才会弹出。";
    }

    if (!g_functional) {
        if (!g_notice.empty()) g_notice += L"\r\n";
        g_notice += L"版本不匹配，自动主题功能已静默：请更新插件或禁用本插件。";
    }

    std::wstring missing = CheckThemeFiles();
    if (!missing.empty()) {
        if (!g_notice.empty()) g_notice += L"\r\n";
        g_notice += missing;
    }

    if (!g_notice.empty()) {
        Post(MN_REFRESH_UI);
        Post(MN_SHOW_UI);
    }
    if (!g_enabled || !g_functional) return;

    g_thread = (HANDLE)_beginthreadex(nullptr, 0, MonitorThread, nullptr, 0, nullptr);
}

static void StopMonitor() {
    g_running = false;
    if (g_thread) {
        WaitForSingleObject(g_thread, 1000);
        CloseHandle(g_thread);
        g_thread = nullptr;
    }
    if (g_lockReady) {
        DeleteCriticalSection(&g_stateLock);
        g_lockReady = false;
    }
}

mn::_MN_RESULT _MN_Notify(unsigned int msg, void* p1, void* /*p2*/) {
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
        Post(MN_SHOW_UI);
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
        .version     = PLUGIN_VERSION ".0",
        .author      = "guyuexuan",
        .email       = "",
        .homepage    = "",
        .type        = mn::code::type::Search,
        .fnNotify    = _MN_Notify,
    };
}
