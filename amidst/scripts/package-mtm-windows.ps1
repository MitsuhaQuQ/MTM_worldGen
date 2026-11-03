# Packages Amidst with MTM Worldgen as a portable Windows app: AMIDST-MTM.exe with its own Java runtime, zipped.
# Run after "mvn package". Needs JDK 24 or newer for jpackage.
param(
    [Parameter(Mandatory = $true)][string]$MtmJar,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Out = "target\package"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem "$root\target\amidst-v*.jar" | Select-Object -First 1
if (-not $jar) { throw "No Amidst jar in target, run mvn package first" }
$version = (Get-Content "$root\src\main\resources\amidst\metadata.properties" |
    Where-Object { $_ -match "^amidst\.version\.(major|minor|patch)=" } |
    ForEach-Object { $_.Split("=")[1] }) -join "."

$outDir = Join-Path $root $Out
$stage = Join-Path $outDir "stage"
Remove-Item -Recurse -Force $outDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item $jar.FullName $stage
$mtmName = Split-Path -Leaf $MtmJar

& "$Jdk\bin\jpackage.exe" --type app-image --name AMIDST-MTM --app-version $version `
    --input $stage --main-jar $jar.Name `
    --icon "$root\src\main\resources\amidst\icon\amidst.ico" `
    --add-modules "java.base,java.desktop,java.prefs,java.sql,java.logging,java.naming,jdk.management,jdk.charsets,jdk.localedata,jdk.crypto.cryptoki,jdk.zipfs,jdk.accessibility" `
    --java-options "-Damidst.mtmjar=`$APPDIR\mods\$mtmName" `
    --dest $outDir
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }
# Added after jpackage, which would put it on the class path: Amidst loads it on its own class loader
New-Item -ItemType Directory -Force "$outDir\AMIDST-MTM\app\mods" | Out-Null
Copy-Item $MtmJar "$outDir\AMIDST-MTM\app\mods\$mtmName"

$mtmVersion = [regex]::Match($mtmName, "-(\d[^-]*)\.jar$").Groups[1].Value
$zip = Join-Path $outDir "AMIDST-MTM-$version-mtm$mtmVersion-windows.zip"
Compress-Archive -Path "$outDir\AMIDST-MTM" -DestinationPath $zip
Write-Output $zip
