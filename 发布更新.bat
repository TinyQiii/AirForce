@echo off
chcp 65001 >nul
setlocal

rem ==========================================================
rem  一键：编译 + 发布到局域网更新目录
rem
rem  用法：双击即可。
rem  想带更新说明：把说明写在文件名后面，例如
rem     发布更新.bat 修了暂停界面
rem
rem  注意：改了内容想让手机收到更新，记得先把
rem        AirForce\app\build.gradle.kts 里的 versionCode 加 1
rem        （不然手机认为是同一个版本，不会提示更新）
rem ==========================================================

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "GRADLE=d:\trae\6abddf2151f45b3345d9588e\.tools\gradle-9.8.0\bin\gradle.bat"
set "PROJ=d:\trae\6abddf2151f45b3345d9588e\AirForce"
set "PY=C:\Users\yuki\.workbuddy-ai\binaries\python\versions\3.13.12\python.exe"
if not exist "%PY%" set "PY=python"

echo.
echo [1/2] 编译...
call "%GRADLE%" -p "%PROJ%" assembleDebug --console=plain
if errorlevel 1 (
    echo.
    echo 编译失败，请把上面的红色错误发给我。
    pause
    exit /b 1
)

echo.
echo [2/2] 发布到更新目录...
"%PY%" "%~dp0发布更新.py" %*

echo.
echo 完成。手机连同一个 Wi-Fi、电脑开着这个更新服务，游戏启动就会提示更新。
pause
