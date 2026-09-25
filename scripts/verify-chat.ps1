param()
$ErrorActionPreference = 'Stop'
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$socketDirectory = Join-Path $projectDirectory '.gradle'
New-Item -ItemType Directory -Path $socketDirectory -Force | Out-Null
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    # Windows Corretto 17 的默认 Unix-domain 回环管道在本机失败；仅为本次子进程指定可用实现。
    $env:JAVA_TOOL_OPTIONS = (($previousJavaOptions + ' -Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.WindowsSelectorProvider -Djdk.net.unixdomain.tmpdir=' + $socketDirectory.Replace('\','/')).Trim())
    Push-Location $projectDirectory
    try {
        & .\gradlew.bat :agent-core:test :model:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "聊天工程检查失败（退出码 $LASTEXITCODE）" }
    } finally { Pop-Location }
} finally { $env:JAVA_TOOL_OPTIONS = $previousJavaOptions }
