[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$signingDirectory = Join-Path $env:USERPROFILE '.teddyremote'
$propertiesPath = Join-Path $signingDirectory 'signing.properties'
$keystorePath = Join-Path $signingDirectory 'teddyremote-release.jks'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'

if (-not (Test-Path -LiteralPath $keytool)) {
    throw "keytool wurde unter JAVA_HOME nicht gefunden: $keytool"
}

if ((Test-Path -LiteralPath $propertiesPath) -and (Test-Path -LiteralPath $keystorePath)) {
    Write-Output "Vorhandene TeddyRemote-Signatur wird verwendet: $propertiesPath"
    exit 0
}

New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null

$passwordBytes = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $random.GetBytes($passwordBytes)
}
finally {
    $random.Dispose()
}
$password = [Convert]::ToBase64String($passwordBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$alias = 'teddyremote-release'

& $keytool -genkeypair `
    -keystore $keystorePath `
    -storepass $password `
    -alias $alias `
    -keypass $password `
    -keyalg RSA `
    -keysize 3072 `
    -validity 10000 `
    -dname 'CN=TeddyRemote, O=TeddyCloud Community, C=DE'

if ($LASTEXITCODE -ne 0) {
    throw "Release-Keystore konnte nicht erzeugt werden (Exitcode $LASTEXITCODE)"
}

$portableKeystorePath = $keystorePath.Replace('\', '/')
[IO.File]::WriteAllLines(
    $propertiesPath,
    @(
        "storeFile=$portableKeystorePath",
        "storePassword=$password",
        "keyAlias=$alias",
        "keyPassword=$password"
    ),
    [Text.UTF8Encoding]::new($false)
)

Write-Output "TeddyRemote-Signatur wurde außerhalb des Projekts angelegt: $propertiesPath"
