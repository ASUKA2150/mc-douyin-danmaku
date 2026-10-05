# 开发者自检：编译并运行 DecodeCheck（不需要 Gradle / Minecraft）
#
# 用法（在仓库根目录执行）：
#   pwsh -File tools/selfcheck/run.ps1

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = (Resolve-Path (Join-Path $scriptDir '..\..')).Path
$coreDir   = Join-Path $repoRoot 'common\src\main\java'
$srcDir    = Join-Path $scriptDir 'src'
$outDir    = Join-Path $scriptDir 'out'

# 找 JDK：优先 JAVA_HOME，其次 PATH
$javac = $null
$java  = $null
if ($env:JAVA_HOME) {
    $candidateJavac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    if (Test-Path $candidateJavac) {
        $javac = $candidateJavac
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

Write-Host "使用 JDK: $javac"

# 参与自检的源文件。
# 只挑不依赖 Gson 真实现、也不依赖加载器的那些文件。
# 注意：新增核心文件时，如果它被下面这些文件引用，就要一起加进来。
$sources = @(
    (Join-Path $srcDir 'com\google\gson\JsonElement.java'),
    (Join-Path $srcDir 'com\google\gson\JsonArray.java'),
    (Join-Path $srcDir 'com\google\gson\JsonObject.java'),
    (Join-Path $srcDir 'com\google\gson\JsonParser.java'),
    (Join-Path $srcDir 'com\google\gson\Gson.java'),
    (Join-Path $srcDir 'com\google\gson\GsonBuilder.java'),
    (Join-Path $srcDir 'DecodeCheck.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\DanmakuLog.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\FileLog.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\config\DanmakuConfig.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\model\DanmakuMessage.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\model\DanmakuEvent.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\model\RoomStats.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\model\StatsProbe.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\proto\ProtobufReader.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\douyin\DouyinProtocol.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\text\DanmakuFormatter.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\text\DanmakuFilter.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\text\NumberText.java'),
    (Join-Path $coreDir 'com\douyindanmaku\core\text\LikeCounter.java')
)

foreach ($file in $sources) {
    if (-not (Test-Path $file)) {
        Write-Error "缺少源文件：$file"
        exit 1
    }
}

if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force $outDir | Out-Null

Write-Host '正在编译…'
& $javac -encoding UTF-8 -d $outDir @sources
if ($LASTEXITCODE -ne 0) {
    Write-Error '编译失败'
    exit 1
}

Write-Host '正在运行自检…'
$previousEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try {
    & $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $outDir DecodeCheck
    $exitCode = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $previousEncoding
}

exit $exitCode
