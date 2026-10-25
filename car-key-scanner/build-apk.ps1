# ============================================================
# 星舰蓝牙助手 - 本地一键打包 APK（Windows）
# 首次运行会自动下载安装 JDK17 / Android SDK / Gradle（约 1GB，需耐心）
# 之后每次打包只需双击运行本脚本，约 1~2 分钟出包
# 用法：右键本文件 -> 使用 PowerShell 运行
#       若提示禁止运行脚本，先执行一次：
#       Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
# ============================================================
$ErrorActionPreference = 'Stop'
$ProjectDir = $PSScriptRoot
$ToolsDir   = Join-Path $ProjectDir '.build-tools'
$SdkDir     = Join-Path $ToolsDir 'android-sdk'
$JdkDir     = Join-Path $ToolsDir 'jdk17'
$OutApk     = Join-Path (Split-Path $ProjectDir -Parent) '星舰蓝牙助手-debug.apk'

function Info($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "[OK] $msg" -ForegroundColor Green }
function Die($msg)  { Write-Host "`n[失败] $msg" -ForegroundColor Red; Read-Host '按回车退出'; exit 1 }

# ---------- 0. 基础环境：Node ----------
Info '检查 Node.js'
try { node -v | Out-Null; Ok "Node $(node -v)" }
catch {
  Info '未检测到 Node.js，尝试用 winget 自动安装...'
  try {
    winget install OpenJS.NodeJS.LTS --silent --accept-package-agreements --accept-source-agreements
    $env:Path = [System.Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [System.Environment]::GetEnvironmentVariable('Path','User')
    node -v | Out-Null
  } catch { Die 'Node.js 安装失败，请手动到 https://nodejs.org 下载 LTS 版安装后重试' }
}

# ---------- 1. JDK 17 ----------
Info '检查 JDK 17'
$javaOk = $false
try {
  $v = (javac -version 2>&1) -join ''
  if ($v -match '17\.') { $javaOk = $true; Ok $v }
} catch {}
if (-not $javaOk) {
  if (Test-Path (Join-Path $JdkDir 'bin\javac.exe')) {
    $env:JAVA_HOME = $JdkDir; $env:Path = "$JdkDir\bin;$env:Path"
    Ok "使用本地 JDK: $JdkDir"
  } else {
    Info '下载 JDK 17（约 180MB，腾讯镜像）...'
    New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
    $zip = Join-Path $ToolsDir 'jdk17.zip'
    $url = 'https://mirrors.cloud.tencent.com/Adoptium/17/jdk/x64/windows/'
    try {
      $page = Invoke-WebRequest -UseBasicParsing $url
      $href = ($page.Links | Where-Object { $_.href -match 'OpenJDK17U-jdk_x64_windows_hotspot_.*\.zip$' } | Select-Object -Last 1).href
      if (-not $href) { throw 'no link' }
      if ($href -notmatch '^http') { $href = $url + $href }
    } catch { Die 'JDK 下载地址获取失败，请手动安装 Adoptium JDK 17 后重试' }
    Invoke-WebRequest -UseBasicParsing $href -OutFile $zip
    Info '解压 JDK...'
    Expand-Archive $zip -DestinationPath "$ToolsDir\jdk17-tmp" -Force
    $inner = Get-ChildItem "$ToolsDir\jdk17-tmp" | Select-Object -First 1
    Move-Item $inner.FullName $JdkDir -Force
    Remove-Item "$ToolsDir\jdk17-tmp" -Recurse -Force; Remove-Item $zip -Force
    $env:JAVA_HOME = $JdkDir; $env:Path = "$JdkDir\bin;$env:Path"
    Ok "JDK 就绪: $JdkDir"
  }
}

# ---------- 2. Android SDK ----------
Info '检查 Android SDK'
$cmdTools = Join-Path $SdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $cmdTools)) {
  Info '下载 Android SDK 命令行工具（约 130MB）...'
  New-Item -ItemType Directory -Force -Path (Join-Path $SdkDir 'cmdline-tools') | Out-Null
  $zip = Join-Path $ToolsDir 'cmdtools.zip'
  Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip
  Expand-Archive $zip -DestinationPath (Join-Path $SdkDir 'cmdline-tools') -Force
  Rename-Item (Join-Path $SdkDir 'cmdline-tools\cmdline-tools') 'latest' -Force
  Remove-Item $zip -Force
}
$env:ANDROID_HOME = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir
$env:Path = "$SdkDir\platform-tools;$SdkDir\cmdline-tools\latest\bin;$env:Path"
Ok "SDK 目录: $SdkDir"

Info '安装 SDK 组件（platform 34 / build-tools，首次约 300MB）'
# 自动接受全部许可
"yes`nyes`nyes`nyes`nyes`nyes`nyes`nyes`nyes`nyes`nyes`nyes`nyes`nyes`nyes`nyes" -split "`n" |
  & $cmdTools --sdk_root="$SdkDir" --licenses | Out-Null
& $cmdTools --sdk_root="$SdkDir" 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0'
Ok 'SDK 组件就绪'

# ---------- 3. 前端依赖与构建 ----------
Push-Location $ProjectDir
Info '安装 npm 依赖（国内镜像）'
npm install --registry=https://registry.npmmirror.com

Info '构建 Web 资源'
npm run build

if (-not (Test-Path 'android')) {
  Info '生成 Android 工程'
  npx cap add android
}
Info '同步 Capacitor'
npx cap sync android

# ---------- 4. 配置国内 Gradle 镜像 ----------
Info '配置 Gradle 阿里云镜像（加速依赖下载）'
$initDir = Join-Path $env:USERPROFILE '.gradle\init.d'
New-Item -ItemType Directory -Force -Path $initDir | Out-Null
@"
allprojects {
    buildscript {
        repositories {
            maven { url 'https://maven.aliyun.com/repository/google' }
            maven { url 'https://maven.aliyun.com/repository/central' }
            maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        }
    }
    repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/central' }
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
    }
}
"@ | Out-File -Encoding utf8 (Join-Path $initDir 'aliyun-mirror.gradle')

# ---------- 5. 打包 ----------
Info 'Gradle 打包（首次需下载 Gradle 本体约 130MB + 依赖，请耐心等待）'
Push-Location (Join-Path $ProjectDir 'android')
try {
  & .\gradlew.bat assembleDebug
  if ($LASTEXITCODE -ne 0) { Die 'Gradle 构建失败，请把上方红色错误截图发给开发者' }
} finally { Pop-Location; Pop-Location }

$apk = Join-Path $ProjectDir 'android\app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
  Copy-Item $apk $OutApk -Force
  Write-Host ''
  Ok "构建成功！APK 已输出：$OutApk"
  Write-Host '把这个 APK 传到手机安装即可。'
} else {
  Die '未找到 APK 产物'
}
Read-Host '按回车退出'
