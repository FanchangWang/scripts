@echo off
@REM 设置控制台编码为 UTF-8
chcp 65001 >nul

@REM 全局变量：单牛模式=1（默认），双牛模式=2
set "mode=1"
@REM 全局变量：是否执行解=0，1-是（默认）
@set "exec_solution=1"
@REM 脚本文件名
@set "script=main.py"

:LOOP

echo.
echo ==========================================
echo.
echo 佛系消消消
if "%mode%"=="1" (
    echo 模  式: 单牛模式
) else (
    echo 模  式: 双牛模式
)
if "%exec_solution%"=="0" (
    echo 执行解: 否
) else (
    echo 执行解: 是
)
echo 脚本文件: %script%

echo.
echo 请输入您的选择:
echo  "q 退出脚本"
echo  "w 切换单/双牛模式"
echo  "e 切换是否执行解"
echo  "r 切换脚本文件"
echo  "回车 执行脚本"
set "choice="
set /p choice="请输入: "

if /i "%choice%"=="q" (
    echo.
    echo 退出脚本
    exit /b 0
)

if /i "%choice%"=="w" (
    echo.
    if "%mode%"=="1" (
        echo 模式 切换到 双牛模式
        set "mode=2"
    ) else (
        echo 模式 切换到 单牛模式
        set "mode=1"
    )
    goto LOOP
)

if /i "%choice%"=="r" (
    echo.
    if "%script%"=="main.py" (
        echo 脚本文件 切换到 adb.py
        set "script=adb.py"
    ) else (
        echo 脚本文件 切换到 main.py
        set "script=main.py"
    )
    goto LOOP
)

if /i "%choice%"=="e" (
    echo.
    if "%exec_solution%"=="0" (
        echo 执行解 切换到 是
        set "exec_solution=1"
    ) else (
        echo 执行解 切换到 否
        set "exec_solution=0"
    )
    goto LOOP
)

echo.
echo 正在执行脚本...
cd /d "%~dp0"
python -m uv run .\%script% --mode "%mode%" --exec_solution "%exec_solution%"
if errorlevel 1 (
    echo.
    echo 脚本执行出错，返回码: %errorlevel%
)

goto LOOP
