@echo off
setlocal
rem Run from project root; this script lives in scripts\
cd /d "%~dp0.."

rem ---- Toolchain paths (adjust for your machine) ----
set "VC=C:\Program Files\Microsoft Visual Studio\18\Community\VC\Tools\MSVC\14.52.37025"
set "SDK=C:\Program Files (x86)\Windows Kits\10"
set "SDKVER=10.0.26100.0"
set "CLANGINC=C:\Program Files\LLVM\lib\clang\21\include"
set "CLANG=C:\Program Files\LLVM\bin\clang++.exe"

rem ---- Output directories ----
if not exist build mkdir build
if not exist dist mkdir dist

rem Clang 21 ships an empty yvals_core.h shim that shadows MSVC's STL, and this
rem MSVC STL expects Clang 22+. -nostdinc + explicit -isystem (VC first) plus a
rem version macro override makes the real MSVC STL available to Clang 21.
"%CLANG%" -target x86_64-pc-windows-msvc -std=c++20 -O2 -shared ^
  -nostdinc -Wno-macro-redefined -D__clang_major__=22 ^
  -isystem "%VC%\include" ^
  -isystem "%SDK%\Include\%SDKVER%\ucrt" ^
  -isystem "%SDK%\Include\%SDKVER%\um" ^
  -isystem "%SDK%\Include\%SDKVER%\shared" ^
  -isystem "%CLANGINC%" ^
  src\p_AutoTheme.cpp ^
  -L "%VC%\lib\x64" ^
  -L "%SDK%\Lib\%SDKVER%\ucrt\x64" ^
  -L "%SDK%\Lib\%SDKVER%\um\x64" ^
  -ladvapi32 -lshell32 -luser32 -lgdi32 -lkernel32 ^
  -o dist\mn.cc.arae.autotheme.dll

if errorlevel 1 (
  echo BUILD FAILED
  exit /b 1
)
if exist "dist\mn.cc.arae.autotheme.exp" move /Y "dist\mn.cc.arae.autotheme.exp" "build\" >nul
if exist "dist\mn.cc.arae.autotheme.lib" move /Y "dist\mn.cc.arae.autotheme.lib" "build\" >nul
echo BUILD OK -^> dist\mn.cc.arae.autotheme.dll
endlocal
