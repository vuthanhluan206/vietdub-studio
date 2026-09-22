$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot 'tools'
$downloads = Join-Path $toolsRoot 'downloads'
$whisperRoot = Join-Path $toolsRoot 'whisper'
$piperRoot = Join-Path $toolsRoot 'piper'
New-Item -ItemType Directory -Force -Path $downloads, $whisperRoot, $piperRoot | Out-Null

function Save-Download([string] $url, [string] $destination) {
    if (Test-Path -LiteralPath $destination) { return }
    Write-Host "Downloading $url"
    & curl.exe --fail --location --silent --show-error --proto '=https' --retry 2 --connect-timeout 30 --max-time 1800 --output "$destination.part" $url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
    Move-Item -LiteralPath "$destination.part" -Destination $destination -Force
}

function Assert-Checksum([string] $path, [string] $expected) {
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $expected) {
        throw "SHA256 mismatch: $path. Remove this download and run setup again."
    }
}

function Expand-SafeZip([string] $archivePath, [string] $destination, [string] $prefix) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $root = [IO.Path]::GetFullPath($destination) + [IO.Path]::DirectorySeparatorChar
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        foreach ($entry in $archive.Entries) {
            if (-not $entry.Name) { continue }
            $relative = $entry.FullName.Replace('/', [IO.Path]::DirectorySeparatorChar)
            if ($prefix -and $relative.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
                $relative = $relative.Substring($prefix.Length)
            }
            if (-not $relative) { continue }
            $target = [IO.Path]::GetFullPath((Join-Path $destination $relative))
            if (-not $target.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe archive path' }
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    } finally { $archive.Dispose() }
}

$whisperZip = Join-Path $downloads 'whisper-b5130-x64.zip'
Save-Download 'https://github.com/ggml-org/whisper.cpp/releases/download/b5130/whisper-bin-x64.zip' $whisperZip
Assert-Checksum $whisperZip 'F9EC6C52A2E949B62AB51FA21D0D497958F9E41C3010C157C4E42932D5316F3C'
Expand-SafeZip $whisperZip $whisperRoot ('Release' + [IO.Path]::DirectorySeparatorChar)

$whisperModel = Join-Path $whisperRoot 'ggml-base.bin'
Save-Download 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin' $whisperModel
Assert-Checksum $whisperModel '60ED5BC3DD14EEA856493D334349B405782DDCAF0028D4B5DF4088345FBA2EFE'

$piperZip = Join-Path $downloads 'piper_windows_amd64-2023.11.14-2.zip'
Save-Download 'https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip' $piperZip
Assert-Checksum $piperZip 'F3C58906402B24F3A96D92145F58ACBA6D86C9B5DB896D207F78DC80811EFCEA'
Expand-SafeZip $piperZip $piperRoot ('piper' + [IO.Path]::DirectorySeparatorChar)

$voice = Join-Path $piperRoot 'vi_VN-vivos-x_low.onnx'
$voiceConfig = "$voice.json"
$voiceBase = 'https://huggingface.co/rhasspy/piper-voices/resolve/main/vi/vi_VN/vivos/x_low'
Save-Download "$voiceBase/vi_VN-vivos-x_low.onnx" $voice
Save-Download "$voiceBase/vi_VN-vivos-x_low.onnx.json" $voiceConfig
Assert-Checksum $voice '6AB13374EB0862021A545BEFE7727AEF59E16117F1C075AA9E0362237ECC98AE'

$ollama = Get-Command ollama -ErrorAction SilentlyContinue
if (-not $ollama) {
    Write-Host 'Installing Ollama for the current Windows machine...'
    & winget install --id Ollama.Ollama --exact --silent --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) { throw 'Ollama installation failed' }
}
$ollamaPath = if ($ollama) { $ollama.Source } else { Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe' }
if (-not (Test-Path -LiteralPath $ollamaPath)) { throw 'ollama.exe was not found after installation' }

try { Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -TimeoutSec 2 | Out-Null }
catch {
    Start-Process -FilePath $ollamaPath -ArgumentList 'serve' -WindowStyle Hidden
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Milliseconds 500
        try { Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -TimeoutSec 2 | Out-Null; break } catch { }
    }
}
& $ollamaPath pull qwen3:1.7b
if ($LASTEXITCODE -ne 0) { throw 'Could not download qwen3:1.7b' }

& (Join-Path $whisperRoot 'whisper-cli.exe') --help | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Whisper local check failed' }
"Xin chào, đây là giọng nói thử nghiệm." | & (Join-Path $piperRoot 'piper.exe') --model $voice --output_file (Join-Path $piperRoot 'voice-check.wav')
if ($LASTEXITCODE -ne 0) { throw 'Piper local check failed' }
Write-Host 'Local AI is ready: Whisper.cpp + qwen3:1.7b + Piper Vietnamese.' -ForegroundColor Green
