@echo off
chcp 65001 >nul
setlocal

rem ==========================================================
rem  启动局域网更新服务
rem  手机和电脑连同一个 Wi-Fi 时，游戏启动会自动来检查更新。
rem  保持这个黑窗口开着即可；关掉 = 停止服务（游戏不会报错）。
rem ==========================================================

set "PY=C:\Users\yuki\.workbuddy-ai\binaries\python\versions\3.13.12\python.exe"
if not exist "%PY%" set "PY=python"

"%PY%" "%~dp0update_server.py"

echo.
echo 服务已退出。
pause
