$ErrorActionPreference = 'Stop'

# Generate the one and only BerryFCM Android release signing key.
# Run from a Windows checkout located at D:\Codex\Berry-FCM-OS7.
$repoRoot = 'D:\Codex\Berry-FCM-OS7'
$signingDir = Join-Path $repoRoot 'signing'
$keystore = Join-Path $signingDir 'berryfcm-release.p12'
$setupFile = Join-Path $signingDir 'SIGNING-SETUP.txt'

if (-not (Test-Path (Join-Path $repoRoot '.git'))) { throw "Repository not found at $repoRoot. Clone jarod360/Berry-FCM-OS7 there first." }
if ((Test-Path $keystore) -or (Test-Path $setupFile)) { throw "Signing material already exists. Refusing to overwrite it: $signingDir" }

$keytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
if (-not $keytoolCommand) { $keytoolCommand = Get-Command keytool -ErrorAction SilentlyContinue }
$keytoolPath = $null
if ($keytoolCommand) { $keytoolPath = $keytoolCommand.Source }
if (-not $keytoolPath -and $env:JAVA_HOME) { $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'; if (Test-Path $candidate) { $keytoolPath = $candidate } }
if (-not $keytoolPath) { throw 'keytool not found. Install JDK 17+ and configure JAVA_HOME or PATH.' }

New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
$rng.Dispose()
$password = ([BitConverter]::ToString($bytes) -replace '-', '') + 'aA1!'

& $keytoolPath -genkeypair -v -keystore $keystore -storetype PKCS12 -storepass $password -keypass $password -alias berryfcm -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=BerryFCM Guardian, OU=Android, O=BerryFCM, C=CN'
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $keystore)) { throw 'keytool failed to create the signing key.' }

& $keytoolPath -list -v -keystore $keystore -storetype PKCS12 -storepass $password -alias berryfcm | Select-String 'SHA256:|SHA-256:|Alias name:|Valid from:'

@"
BerryFCM Guardian release signing key
Keystore: berryfcm-release.p12
Store type: PKCS12
Alias: berryfcm
Keystore password: $password
Key password: $password

Keep this file and berryfcm-release.p12 private.
Back up both files offline. Never commit them to Git.
If the key is lost, future APKs cannot update-install over APKs signed by it.
"@ | Set-Content -LiteralPath $setupFile -Encoding UTF8

$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
Set-Clipboard -Value $base64

Write-Host "Created: $keystore" -ForegroundColor Green
Write-Host "Saved passwords: $setupFile" -ForegroundColor Green
Write-Host 'Keystore Base64 copied to clipboard for GitHub Secret BERRYFCM_KEYSTORE_BASE64.' -ForegroundColor Green
Write-Host 'Use the password above for BOTH BERRYFCM_KEYSTORE_PASSWORD and BERRYFCM_KEY_PASSWORD.'
Write-Host 'Use berryfcm for BERRYFCM_KEY_ALIAS.'
