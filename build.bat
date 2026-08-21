@echo off
rem ============================================================
rem  PinGB 拼豆助手 - 一键构建脚本
rem  依赖：JDK 17+ / Android SDK build-tools 36.0.0 / android.jar
rem  产物：PinGB-App.apk（debug 签名，可直接安装）
rem ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not exist "%SDK%" set "SDK=%ANDROID_HOME%"
set "BT=%SDK%\build-tools\36.0.0"
set "PLATFORM=%SDK%\platforms\android-37\android.jar"
if not exist "%PLATFORM%" set "PLATFORM=%SDK%\platforms\android-36\android.jar"
if not exist "%PLATFORM%" (
    echo [ERROR] android.jar not found. Install platforms;android-37 in SDK Manager.
    exit /b 1
)
if not exist "%BT%\aapt2.exe" (
    echo [ERROR] build-tools;36.0.0 not found. Install via SDK Manager.
    exit /b 1
)

echo [1/6] compile resources ...
if exist compiled_res rd /s /q compiled_res
mkdir compiled_res
"%BT%\aapt2.exe" compile --dir res -o compiled_res\res.zip
if errorlevel 1 exit /b 1

echo [2/6] link base.apk ...
"%BT%\aapt2.exe" link -o base.apk --manifest AndroidManifest.xml -I "%PLATFORM%" compiled_res\res.zip
if errorlevel 1 exit /b 1

echo [3/6] javac ...
if exist classes rd /s /q classes
mkdir classes
javac -Xlint:-options -source 8 -target 8 -classpath "%PLATFORM%" -d classes ^
    src\com\pingb\app\*.java src\com\pingb\app\ble\*.java
if errorlevel 1 exit /b 1

echo [4/6] d8 dex ...
if exist dex rd /s /q dex
mkdir dex
dir /s /b classes\*.class > classes.list
java -cp "%BT%\lib\d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "%PLATFORM%" --output dex @classes.list
if errorlevel 1 exit /b 1

echo [5/6] assemble apk ...
python assemble.py
if errorlevel 1 exit /b 1

echo [6/6] zipalign + sign ...
if not exist debug.keystore (
    keytool -genkeypair -keystore debug.keystore -alias androiddebugkey ^
        -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" ^
        -keyalg RSA -validity 10000
)
"%BT%\zipalign.exe" -f 4 app-unsigned.apk app-aligned.apk
"%BT%\apksigner.bat" sign --ks debug.keystore --ks-pass pass:android ^
    --out PinGB-App.apk app-aligned.apk
if errorlevel 1 exit /b 1

echo.
echo BUILD OK: PinGB-App.apk
