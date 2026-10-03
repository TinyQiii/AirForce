@echo off
setlocal

rem ==========================================================
rem  One-click: build the game and install it on the phone.
rem
rem  To change the APP NAME, edit this file:
rem     AirForce\app\src\main\res\values\strings.xml
rem  and change the line:
rem     <string name="app_name">AirForce</string>
rem  Save it, then just double-click this script.
rem ==========================================================

rem ---- 路径配置（默认值适配标准安装，装到别处请自行改）----
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "GRADLE=%~dp0.tools\gradle-9.8.0\bin\gradle.bat"
if not exist "%GRADLE%" set "GRADLE=gradle"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
set "PROJ=%~dp0AirForce"
set "APK=%PROJ%\app\build\outputs\apk\debug\app-debug.apk"

echo.
echo [1/3] Checking phone connection...
"%ADB%" devices
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo.
    echo ERROR: no phone detected.
    echo   - plug in the USB cable
    echo   - on the phone choose "File Transfer" mode
    echo   - make sure USB debugging is ON
    pause
    exit /b 1
)

echo.
echo [2/3] Building... (first run is slower, later runs are quick)
call "%GRADLE%" -p "%PROJ%" assembleDebug --console=plain
if errorlevel 1 (
    echo.
    echo BUILD FAILED. Please send me the red errors above.
    pause
    exit /b 1
)

echo.
echo [3/3] Installing to phone...
"%ADB%" push "%APK%" /data/local/tmp/airforce.apk >nul
"%ADB%" shell pm install -r -t /data/local/tmp/airforce.apk
if errorlevel 1 (
    echo Overwrite install failed, retrying as a clean install...
    "%ADB%" uninstall com.example.airforce
    "%ADB%" shell pm install -t /data/local/tmp/airforce.apk
)

echo.
echo Launching the game...
"%ADB%" shell am start -n com.example.airforce/.MainActivity

echo.
echo DONE! Look at the new name on your phone's home screen.
pause
exit /b 0