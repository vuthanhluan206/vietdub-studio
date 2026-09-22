$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Portable Windows x64 tools. Sources: ffmpeg.org/download.html and github.com/yt-dlp/yt-dlp.
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot 'tools'
$downloads = Join-Path $toolsRoot 'downloads'
$smokeRoot = Join-Path $projectRoot 'target\media-smoke'
New-Item -ItemType Directory -Force -Path $downloads, $smokeRoot | Out-Null

function Save-Download([string] $url, [string] $destination) {
    if (Test-Path -LiteralPath $destination) { return }
    Write-Host "Downloading $url"
    & curl.exe --fail --location --silent --show-error --proto '=https' --retry 2 --connect-timeout 30 --max-time 600 --output "$destination.part" $url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
    Move-Item -LiteralPath "$destination.part" -Destination $destination -Force
}

function Assert-Checksum([string] $path, [string] $expected) {
    if ($expected -notmatch '^[a-fA-F0-9]{64}$') { throw "Invalid published SHA256 for $path" }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $expected) {
        throw "SHA256 mismatch: $path. Remove this download and run setup again."
    }
}

$ffmpegVersion = '9.0.2'
$ytDlpVersion = '2026.08.19'
$ffmpegUrl = "https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-$ffmpegVersion-essentials_build.zip"
$ffmpegZip = Join-Path $downloads "ffmpeg-$ffmpegVersion.zip"
Save-Download "$ffmpegUrl.sha256" "$ffmpegZip.sha256"
Save-Download $ffmpegUrl $ffmpegZip
Assert-Checksum $ffmpegZip ((Get-Content -LiteralPath "$ffmpegZip.sha256" -Raw).Trim().Split()[0])

# Only copy selected entries to fixed paths; archive paths never control extraction destinations.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($ffmpegZip)
try {
    foreach ($name in @('ffmpeg.exe', 'ffprobe.exe')) {
        $entries = @($archive.Entries | Where-Object { $_.FullName -match "/bin/$([regex]::Escape($name))$" })
        if ($entries.Count -ne 1) { throw "Expected one $name in the FFmpeg archive" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], (Join-Path $toolsRoot $name), $true)
    }
    $license = @($archive.Entries | Where-Object { $_.Name -eq 'LICENSE' })
    if ($license.Count -eq 1) {
        [IO.Compression.ZipFileExtensions]::ExtractToFile($license[0], (Join-Path $toolsRoot 'FFmpeg-LICENSE.txt'), $true)
    }
} finally {
    $archive.Dispose()
}

$ytDlpUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/$ytDlpVersion"
$ytDlpSums = Join-Path $downloads "yt-dlp-$ytDlpVersion-SHA2-256SUMS"
$ytDlpDownload = Join-Path $downloads "yt-dlp-$ytDlpVersion.exe"
Save-Download "$ytDlpUrl/SHA2-256SUMS" $ytDlpSums
Save-Download "$ytDlpUrl/yt-dlp.exe" $ytDlpDownload
$ytDlpHash = [regex]::Match((Get-Content -LiteralPath $ytDlpSums -Raw), '(?m)^([a-fA-F0-9]{64})\s+\*?yt-dlp\.exe\r?$')
if (-not $ytDlpHash.Success) { throw 'yt-dlp.exe was not found in the published checksums' }
Assert-Checksum $ytDlpDownload $ytDlpHash.Groups[1].Value
Copy-Item -LiteralPath $ytDlpDownload -Destination (Join-Path $toolsRoot 'yt-dlp.exe') -Force

$ffmpeg = Join-Path $toolsRoot 'ffmpeg.exe'
$ffprobe = Join-Path $toolsRoot 'ffprobe.exe'
$ytDlp = Join-Path $toolsRoot 'yt-dlp.exe'
(& $ffmpeg -version)[0]
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg version check failed' }
(& $ffprobe -version)[0]
if ($LASTEXITCODE -ne 0) { throw 'ffprobe version check failed' }
& $ytDlp --version
if ($LASTEXITCODE -ne 0) { throw 'yt-dlp version check failed' }

$video = Join-Path $smokeRoot 'source.mp4'
$audio = Join-Path $smokeRoot 'audio.wav'
& $ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'color=c=blue:s=320x240:r=25:d=1' -f lavfi -i 'sine=frequency=440:duration=1' -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest $video
if ($LASTEXITCODE -ne 0) { throw 'Synthetic MP4 generation failed' }
& $ffmpeg -hide_banner -loglevel error -y -i $video -vn -ac 1 -ar 16000 -c:a pcm_s16le $audio
if ($LASTEXITCODE -ne 0) { throw 'Audio extraction failed' }
$audioInfo = & $ffprobe -v error -show_entries stream=codec_name,sample_rate,channels -of json $audio | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $audioInfo.streams[0].codec_name -ne 'pcm_s16le' -or $audioInfo.streams[0].sample_rate -ne '16000' -or $audioInfo.streams[0].channels -ne 1) {
    throw 'Extracted audio does not match mono 16kHz PCM'
}
Write-Host "Media smoke check passed: $smokeRoot"
Write-Host "Portable tools ready: $toolsRoot (system PATH unchanged)"
