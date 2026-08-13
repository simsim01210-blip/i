$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$java21 = Join-Path $projectRoot '.tools\jdk21\jdk-21.0.12+8'
$gradle = Join-Path $projectRoot 'gradlew.bat'
$dist = Join-Path $projectRoot 'dist'
$versionLine = Select-String -LiteralPath (Join-Path $projectRoot 'gradle.properties') `
    -Pattern '^mod_version=(.+)$' | Select-Object -First 1
if ($null -eq $versionLine) {
    throw 'mod_version is missing from gradle.properties'
}
$modVersion = $versionLine.Matches[0].Groups[1].Value.Trim()

if (-not (Test-Path $java21)) {
    throw "Java 21 not found: $java21"
}

$env:JAVA_HOME = $java21
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$targets = @(
    @{ mc='1.20.1'; yarn='1.20.1+build.10'; api='0.92.6+1.20.1'; java='17'; compat='legacy'; input='legacy' },
    @{ mc='1.20.2'; yarn='1.20.2+build.4';  api='0.91.6+1.20.2'; java='17'; compat='legacy'; input='legacy' },
    @{ mc='1.20.3'; yarn='1.20.3+build.1';  api='0.91.1+1.20.3'; java='17'; compat='legacy'; input='legacy' },
    @{ mc='1.20.4'; yarn='1.20.4+build.3';  api='0.97.3+1.20.4'; java='17'; compat='legacy'; input='legacy' },
    @{ mc='1.20.5'; yarn='1.20.5+build.1';  api='0.97.8+1.20.5'; java='21'; compat='legacy'; input='legacy' },
    @{ mc='1.20.6'; yarn='1.20.6+build.3';  api='0.100.8+1.20.6'; java='21'; compat='legacy'; input='legacy' },
    @{ mc='1.21';    yarn='1.21+build.9';    api='0.102.0+1.21'; java='21'; compat='legacy'; input='legacy' },
    @{ mc='1.21.1';  yarn='1.21.1+build.3';  api='0.116.15+1.21.1'; java='21'; compat='legacy'; input='legacy' },
    @{ mc='1.21.2';  yarn='1.21.2+build.1';  api='0.106.1+1.21.2'; java='21'; compat='render_layer'; input='legacy' },
    @{ mc='1.21.3';  yarn='1.21.3+build.2';  api='0.114.1+1.21.3'; java='21'; compat='render_layer'; input='legacy' },
    @{ mc='1.21.4';  yarn='1.21.4+build.8';  api='0.119.4+1.21.4'; java='21'; compat='render_layer'; input='legacy' },
    @{ mc='1.21.5';  yarn='1.21.5+build.1';  api='0.128.2+1.21.5'; java='21'; compat='render_layer_1_21_5'; input='legacy' },
    @{ mc='1.21.6';  yarn='1.21.6+build.1';  api='0.128.2+1.21.6'; java='21'; compat='render_pipeline'; input='legacy' },
    @{ mc='1.21.7';  yarn='1.21.7+build.8';  api='0.129.0+1.21.7'; java='21'; compat='render_pipeline'; input='legacy' },
    @{ mc='1.21.8';  yarn='1.21.8+build.1';  api='0.136.1+1.21.8'; java='21'; compat='render_pipeline'; input='legacy' },
    @{ mc='1.21.9';  yarn='1.21.9+build.1';  api='0.134.1+1.21.9'; java='21'; compat='render_pipeline'; input='modern' },
    @{ mc='1.21.10'; yarn='1.21.10+build.3'; api='0.138.4+1.21.10'; java='21'; compat='render_pipeline'; input='modern' },
    @{ mc='1.21.11'; yarn='1.21.11+build.6'; api='0.141.6+1.21.11'; java='21'; compat='render_pipeline_1_21_11'; input='modern' }
)

foreach ($target in $targets) {
    $archive = "planetmap-mc$($target.mc)"
    Write-Host "Building Minecraft $($target.mc)..."
    & $gradle clean build `
        "-Pminecraft_version=$($target.mc)" `
        "-Pyarn_mappings=$($target.yarn)" `
        "-Pfabric_version=$($target.api)" `
        "-Pjava_version=$($target.java)" `
        "-Pcompat_layer=$($target.compat)" `
        "-Pinput_layer=$($target.input)" `
        "-Parchives_base_name=$archive" `
        "-Pminecraft_dependency=~$($target.mc)"
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed for Minecraft $($target.mc)"
    }

    $jar = Get-ChildItem (Join-Path $projectRoot 'build\libs') -Filter "$archive-$modVersion.jar" |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "Release JAR not found for Minecraft $($target.mc)"
    }
    $releaseName = "planetmap-$($target.mc)-lil0ri.jar"
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $dist $releaseName) -Force
}

# Remove the superseded 1.0.14-style release names only after every target
# has built and copied successfully.
Get-ChildItem $dist -Filter "planetmap-mc*-$modVersion.jar" |
    Remove-Item -Force

Get-ChildItem $dist -Filter "planetmap-*-lil0ri.jar" |
    Sort-Object Name |
    ForEach-Object {
        $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        [PSCustomObject]@{
            Name = $_.Name
            Bytes = $_.Length
            SHA256 = $hash.Hash
        }
    }
