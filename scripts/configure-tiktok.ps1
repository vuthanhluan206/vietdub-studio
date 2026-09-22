$ErrorActionPreference = 'Stop'

$clientKey = (Read-Host 'TikTok Client key').Trim()
$secureSecret = Read-Host 'TikTok Client secret (input is hidden)' -AsSecureString
$clientSecret = [System.Net.NetworkCredential]::new('', $secureSecret).Password.Trim()
if (-not $clientKey -or -not $clientSecret -or $clientKey -match '[^\x21-\x7E]' -or $clientSecret -match '[^\x21-\x7E]') {
    throw 'Client key or Client secret is empty or contains invalid characters.'
}

$encryptionKey = [Environment]::GetEnvironmentVariable('TOKEN_ENCRYPTION_KEY', 'User')
if (-not $encryptionKey) {
    $encryptionKey = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
}
$redirect = 'http://127.0.0.1:8080/api/tiktok/callback'
foreach ($entry in @{
    TIKTOK_CLIENT_KEY = $clientKey
    TIKTOK_CLIENT_SECRET = $clientSecret
    TIKTOK_REDIRECT_URI = $redirect
    TOKEN_ENCRYPTION_KEY = $encryptionKey
}.GetEnumerator()) {
    [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'User')
    Set-Item -Path "Env:$($entry.Key)" -Value $entry.Value
}
$clientSecret = $null
Write-Host 'TikTok configuration saved for this Windows account.' -ForegroundColor Green
Write-Host "Redirect URI: $redirect"
