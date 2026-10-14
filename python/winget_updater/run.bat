@echo off
cd /d %~dp0
uv run winget-updater %*
