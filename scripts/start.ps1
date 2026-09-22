param([switch]$Build)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    foreach ($name in 'TIKTOK_CLIENT_KEY', 'TIKTOK_CLIENT_SECRET', 'TIKTOK_REDIRECT_URI', 'TOKEN_ENCRYPTION_KEY') {
        if (-not (Test-Path "Env:$name")) {
            $saved = [Environment]::GetEnvironmentVariable($name, 'User')
            if ($saved) { Set-Item -Path "Env:$name" -Value $saved }
        }
    }
    $provider = if ($env:AI_PROVIDER) { $env:AI_PROVIDER } else { 'local' }
    if ($provider -eq 'openai') {
        $validApiKey = $env:OPENAI_API_KEY -and $env:OPENAI_API_KEY.Length -ge 20 `
            -and $env:OPENAI_API_KEY -notmatch '[^\x21-\x7E]'
        if (!$validApiKey) {
            Write-Host 'OPENAI_API_KEY is missing or invalid.' -ForegroundColor Yellow
            $secureApiKey = Read-Host 'Paste a NEW OpenAI API key (or press Enter to stop)' -AsSecureString
            if ($secureApiKey.Length -eq 0) { throw 'OpenAI mode requires OPENAI_API_KEY.' }
            $env:OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $secureApiKey).Password
            if ($env:OPENAI_API_KEY.Length -lt 20 -or $env:OPENAI_API_KEY -match '[^\x21-\x7E]') {
                throw 'The OpenAI API key contains invalid characters.'
            }
        }
    } else {
        $env:AI_PROVIDER = 'local'
        $required = @('.\tools\whisper\whisper-cli.exe', '.\tools\whisper\ggml-base.bin',
            '.\tools\piper\piper.exe', '.\tools\piper\vi_VN-vivos-x_low.onnx')
        if ($required.Where({ -not (Test-Path -LiteralPath $_) }).Count -gt 0) {
            throw 'Local AI is missing. Run: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-local-ai.ps1'
        }
        $ollamaModels = $null
        try { $ollamaModels = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -TimeoutSec 2 }
        catch {
            $ollamaCommand = Get-Command ollama -ErrorAction SilentlyContinue
            $ollamaPath = if ($ollamaCommand) { $ollamaCommand.Source } else { $null }
            if (-not $ollamaPath) {
                $ollamaPath = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe'
            }
            if (-not (Test-Path -LiteralPath $ollamaPath)) { throw 'Ollama is missing. Run scripts/setup-local-ai.ps1.' }
            Start-Process -FilePath $ollamaPath -ArgumentList 'serve' -WindowStyle Hidden
            for ($attempt = 0; $attempt -lt 30; $attempt++) {
                Start-Sleep -Milliseconds 500
                try { $ollamaModels = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -TimeoutSec 2; break } catch { }
            }
        }
        if (-not $ollamaModels -or -not $ollamaModels.models.Where({ $_.name -eq 'qwen3:1.7b' })) {
            throw 'Ollama model qwen3:1.7b is missing. Run scripts/setup-local-ai.ps1.'
        }
    }
    if ($Build -or !(Test-Path -LiteralPath './target/demo-0.0.1-SNAPSHOT.jar')) {
        & mvn package
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
    }
    $javaExecutable = 'java'
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
    }
    Write-Host "Open http://127.0.0.1:8080 after startup. AI provider: $provider."
    & $javaExecutable -jar './target/demo-0.0.1-SNAPSHOT.jar'
    if ($LASTEXITCODE -ne 0) { throw 'Application stopped with an error.' }
} finally {
    Pop-Location
}
