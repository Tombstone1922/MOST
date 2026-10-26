param(
    [Parameter(Mandatory=$true)][string]$SigningKeyZip,
    [string]$Repository = 'Tombstone1922/MOST'
)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'Install GitHub CLI first: winget install --id GitHub.cli --exact. Then open PowerShell again.'
}
$savedPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& gh auth status 2>$null
$authResult = $LASTEXITCODE
$ErrorActionPreference = $savedPreference
if ($authResult -ne 0) { & gh auth login --web; if ($LASTEXITCODE -ne 0) { throw 'GitHub login failed' } }
$temp = Join-Path ([IO.Path]::GetTempPath()) ('most-signing-' + [guid]::NewGuid().ToString('N'))
try {
    Expand-Archive -LiteralPath (Resolve-Path -LiteralPath $SigningKeyZip).Path -DestinationPath $temp
    $keys = @(Get-ChildItem -LiteralPath $temp -Recurse -Filter most-android.p12)
    $passwords = @(Get-ChildItem -LiteralPath $temp -Recurse -Filter store-password.txt)
    if ($keys.Count -ne 1 -or $passwords.Count -ne 1) { throw 'Expected the original MOST signing-key archive' }
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($keys[0].FullName)) | & gh secret set MOST_SIGNING_KEY_BASE64 --repo $Repository
    if ($LASTEXITCODE -ne 0) { throw 'Could not save the signing key as a GitHub Secret' }
    ([IO.File]::ReadAllText($passwords[0].FullName)).TrimEnd("`r", "`n") | & gh secret set MOST_SIGNING_PASSWORD --repo $Repository
    if ($LASTEXITCODE -ne 0) { throw 'Could not save the password as a GitHub Secret' }
    & gh workflow run build.yml --repo $Repository --ref main -f publish=true
    if ($LASTEXITCODE -ne 0) { throw 'Secrets saved. Start the MOST Android workflow from GitHub Actions.' }
    Write-Host 'Signing configured. The build and OTA release have started.'
} finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
