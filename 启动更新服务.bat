@echo off
chcp 65001 >nul
setlocal

rem ==========================================================
rem  启动局域网更新服务
rem  手机和电脑连同一个 Wi-Fi 时，游戏启动会自动来检查更新。
rem  保持这个黑窗口开着即可；关掉 = 停止服务（游戏不会报错）。
rem ==========================================================

rem 需要系统里有 python（或 py）命令
set "PY=python"
where %PY% >nul 2>&1 || set "PY=py"

"%PY%" "%~dp0update_server.py"

echo.
echo 服务已退出。
pause
