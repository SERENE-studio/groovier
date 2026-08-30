# Groovier 开发客户端直接启动脚本(无需 gradle,不触发 merged.jar 重写,可与运行中的服务端共存)
# 在普通终端(沙箱外)运行,GPU shader 缓存可正常写入,避免卡顿
$ErrorActionPreference = 'Stop'

$java       = "D:\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2\bin\java.exe"
$base       = "d:\bluesky_fantaste\groovier\build\moddev"
$vm         = Get-Content "$base\clientRunVmArgs.txt"
$modulePath = $vm[1] -replace '\\\\','\'
$cp         = "$base\artifacts\neoforge-21.1.235-merged.jar;d:\bluesky_fantaste\groovier\build\devrun\groovier-dev.jar"

$javaArgs = @(
    '-p', $modulePath,
    '--add-modules', 'ALL-MODULE-PATH',
    '--add-opens', 'java.base/java.util.jar=cpw.mods.securejarhandler',
    '--add-opens', 'java.base/java.lang.invoke=cpw.mods.securejarhandler',
    '--add-exports', 'java.base/sun.security.util=cpw.mods.securejarhandler',
    '--add-exports', 'jdk.naming.dns/com.sun.jndi.dns=java.naming',
    "-Dlog4j2.configurationFile=$base\clientLog4j2.xml",
    '-Djava.net.preferIPv6Addresses=system',
    '-DignoreList=mixinextras-neoforge-0.5.3.jar,client-extra,neoforge-',
    "-DlegacyClassPath.file=$base\clientLegacyClasspath.txt",
    '-Dneoforge.enableGameTest=true',
    '-Dforge.logging.markers=REGISTRIES',
    '-Xms1G', '-Xmx4G',
    '-cp', $cp,
    'cpw.mods.bootstraplauncher.BootstrapLauncher',
    '--launchTarget', 'forgeclientdev',
    '--version', '21.1.235',
    '--assetIndex', '17',
    '--assetsDir', 'D:/bluesky_fantaste/.gradle-home/caches/neoformruntime/assets',
    '--gameDir', '.',
    '--fml.fmlVersion', '4.0.42',
    '--fml.mcVersion', '1.21.1',
    '--fml.neoForgeVersion', '21.1.235',
    '--fml.neoFormVersion', '20240808.144430'
)

Set-Location "d:\bluesky_fantaste\groovier\run"
& $java @javaArgs
