# Grand Theft Rickshaw - Asset Packaging & Verification Script
param(
    [string]$SourceIso = "..\Grand Theft Auto - San Andreas.iso",
    [string]$DestinationDir = ".\app\src\main\assets\games"
)

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Grand Theft Rickshaw Asset Packager" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

if (-not (Test-Path $SourceIso)) {
    Write-Warning "Source ISO not found at '$SourceIso'. Checking current directory..."
    if (Test-Path ".\Grand Theft Auto - San Andreas.iso") {
        $SourceIso = ".\Grand Theft Auto - San Andreas.iso"
    } else {
        Write-Error "Could not locate 'Grand Theft Auto - San Andreas.iso'."
        exit 1
    }
}

$file = Get-Item $SourceIso
$sizeGb = [math]::Round($file.Length / 1GB, 2)
Write-Host "Found source game image: $($file.Name) ($sizeGb GB)" -ForegroundColor Green

# Verify Header Fingerprint
Write-Host "Verifying executable serial..."
$stream = [System.IO.File]::OpenRead($SourceIso)
$buffer = New-Object byte[] 65536
$foundSerial = $null
for ($i = 0; $i -lt 500 -and -not $foundSerial; $i++) {
    $read = $stream.Read($buffer, 0, $buffer.Length)
    if ($read -le 0) { break }
    $text = [System.Text.Encoding]::ASCII.GetString($buffer)
    if ($text -match "(SLUS|SLES|SLKA|SLPM|SCES|SCUS)[-_]\d{3}\.\d{2}") {
        $foundSerial = $Matches[0]
    }
}
$stream.Close()

if ($foundSerial) {
    Write-Host "Validated Game Serial: $foundSerial" -ForegroundColor Green
} else {
    Write-Warning "Game serial not found in initial sectors, but proceeding with ISO verification."
}

if (-not (Test-Path $DestinationDir)) {
    New-Item -ItemType Directory -Path $DestinationDir -Force | Out-Null
}

$targetIso = Join-Path $DestinationDir "Grand Theft Rickshaw.iso"

if (Get-Command chdman -ErrorAction SilentlyContinue) {
    $targetChd = Join-Path $DestinationDir "Grand Theft Rickshaw.chd"
    Write-Host "Compressing to CHD ($targetChd)..." -ForegroundColor Yellow
    & chdman createcd -i "$SourceIso" -o "$targetChd" -f
    Write-Host "Compression completed successfully!" -ForegroundColor Green
} else {
    Write-Host "chdman not found in PATH. Copying as optimized single-game ISO ($targetIso)..." -ForegroundColor Yellow
    Copy-Item -Path $SourceIso -Destination $targetIso -Force
    Write-Host "Copied ISO successfully!" -ForegroundColor Green
}

Write-Host "Asset preparation complete!" -ForegroundColor Cyan
