# 浏览器旁观模式 · 开发自检
#
# 用法（在仓库根目录执行，需要先跑一次 ./gradlew :fabric:compileJava）：
#   pwsh -File tools/cdpcheck/run.ps1                    # 接线对照实验（约 10 秒）
#   pwsh -File tools/cdpcheck/run.ps1 -Douyin            # 真实抖音测试（约 60 秒）
#   pwsh -File tools/cdpcheck/run.ps1 -Browser "C:\path\to\msedge.exe"
#
# 不指定 -Browser 时走模组自己的探测逻辑（ChromeFinder），
# 顺便可以确认「浏览器自动探测在你机器上找得对不对」。

param(
    [switch]$Douyin,
    [string]$Browser = '',
    [string]$Room = ''
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = (Resolve-Path (Join-Path $scriptDir '..\..')).Path
$outDir    = Join-Path $scriptDir 'out'

# ---------- 找 JDK ----------
$javac = $null
$java  = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    if (Test-Path $candidate) {
        $javac = $candidate
        $java  = Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
}
if (-not $javac) {
    $cmd = Get-Command javac -ErrorAction SilentlyContinue
    if ($cmd) { $javac = $cmd.Source; $java = (Get-Command java).Source }
}
if (-not $javac) {
    Write-Error '找不到 javac。请安装 JDK 21，或设置 JAVA_HOME 环境变量。'
    exit 1
}

# ---------- 找模组编译产物 ----------
$classesDir = Join-Path $repoRoot 'fabric\build\classes\java\main'
if (-not (Test-Path $classesDir)) {
    Write-Error "找不到编译产物：$classesDir`n请先在仓库根目录执行：./gradlew :fabric:compileJava"
    exit 1
}

# ---------- 找 Gson ----------
$gsonJar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson" `
        -Recurse -Filter 'gson-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } |
    Select-Object -First 1
if (-not $gsonJar) {
    Write-Error '在 Gradle 缓存里找不到 gson jar。请先成功构建一次模组。'
    exit 1
}

# ---------- 决定跑哪个实验 ----------
$probeName = if ($Douyin) { 'CdpDouyinProbe' } else { 'CdpLocalProbe' }
$probeFile = Join-Path $scriptDir "src\com\douyindanmaku\core\chrome\$probeName.java"

if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force $outDir | Out-Null

Write-Host "实验程序 : $probeName"
Write-Host "使用 JDK : $javac"
Write-Host '正在编译…'

& $javac -encoding UTF-8 -cp "$classesDir;$($gsonJar.FullName)" -d $outDir $probeFile
if ($LASTEXITCODE -ne 0) {
    Write-Error '编译失败'
    exit 1
}

# ---------- 组参数 ----------
$probeArgs = @()
if ($Browser) { $probeArgs += $Browser }
if ($Douyin -and $Room) { $probeArgs += $Room }

if ($Douyin) {
    Write-Host '正在运行真实抖音测试（会弹出浏览器窗口，约 60 秒）…'
} else {
    Write-Host '正在运行接线对照实验（会弹出浏览器窗口，约 10 秒）…'
}

$previousEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try {
    & $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' `
        -cp "$outDir;$classesDir;$($gsonJar.FullName)" `
        "com.douyindanmaku.core.chrome.$probeName" @probeArgs
    $exitCode = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $previousEncoding
}

exit $exitCode
