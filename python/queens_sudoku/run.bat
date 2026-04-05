@echo off
@REM 设置控制台编码为 UTF-8
chcp 65001 >nul

@REM 全局变量：单牛模式=1（默认），双牛模式=2
set "mode=1"

:LOOP

echo.
echo ==========================================
echo.
if "%mode%"=="1" (
    echo  佛系消消消 单牛模式
) else (
    echo  佛系消消消 双牛模式
)
echo.
set "choice="
set /p choice="输入 q 退出, e 切换单/双牛模式, 回车执行: "

if /i "%choice%"=="q" (
    echo.
    echo 退出脚本
    exit /b 0
)

if /i "%choice%"=="e" (
    echo.
    if "%mode%"=="1" (
        echo 切换到 双牛模式
        set "mode=2"
    ) else (
        echo 切换到 单牛模式
        set "mode=1"
    )
    goto LOOP
)

echo.
echo 正在执行脚本...
cd /d "%~dp0"
python -m uv run .\main.py --%mode%
if errorlevel 1 (
    echo.
    echo 脚本执行出错，返回码: %errorlevel%
)

goto LOOP
