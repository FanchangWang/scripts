@echo off

REM WeChat Mini Program Pause Tool - Build Script
REM -------------------------------------------
echo.
echo WeChat Mini Program Pause Tool - Build Script
echo -------------------------------------------
echo.

REM Step 1: Install dependencies
echo 1. Installing dependencies...
python -m pip install -r requirements.txt
python -m pip install pyinstaller

REM Step 2: Build executable
echo.
echo 2. Building executable...
python -m PyInstaller --onefile --windowed --icon=NONE run.py

REM Step 3: Clean up temporary files
echo.
echo 3. Cleaning up temporary files...
del /f /q *.spec 2>nul
rmdir /s /q build 2>nul

REM Step 4: Show result
echo.
echo -------------------------------------------
echo Build completed!
echo Executable file: dist\run.exe
echo -------------------------------------------
echo.

pause
