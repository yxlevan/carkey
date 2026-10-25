# 星舰蓝牙助手 - 一键构建 APK 脚本
# 前提：已安装 Node.js 18+ 和 Android Studio（含 Android SDK Platform 34）
# 用法：在本目录（car-key-scanner）的 PowerShell 中执行  .\build-apk.ps1
$ErrorActionPreference = 'Stop'

Write-Host '==> 检查环境...'
node -v | Out-Null
npm -v | Out-Null
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
  Write-Warning '未检测到 ANDROID_HOME 环境变量，请先在 Android Studio 中安装 SDK 并配置 ANDROID_HOME'
}

Write-Host '==> 安装依赖...'
npm install

Write-Host '==> 构建 Web 资源...'
npm run build

if (-not (Test-Path 'android')) {
  Write-Host '==> 生成 Android 工程...'
  npx cap add android
}

Write-Host '==> 同步到 Android 工程...'
npx cap sync android

Write-Host '==> Gradle 打包 Debug APK（首次需下载 Gradle 与依赖，请耐心等待）...'
Push-Location android
.\gradlew.bat assembleDebug
Pop-Location

$apk = 'android\app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
  Copy-Item $apk '..\星舰蓝牙助手-debug.apk' -Force
  Write-Host ''
  Write-Host '构建成功！APK 已输出到项目上级目录：星舰蓝牙助手-debug.apk' -ForegroundColor Green
} else {
  Write-Error '未找到 APK 产物，请检查上方 Gradle 输出'
}
