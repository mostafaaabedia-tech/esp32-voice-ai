# =============================================================================
#  Copies ONE Ollama model (default qwen2.5:1.5b) out of this PC into a folder,
#  so you can carry it to another laptop on a USB stick - no internet needed.
#
#  Run: double-click export_model.bat   (or: powershell -File export_model.ps1 -Model qwen2.5:1.5b -Out E:\ollama-export)
# =============================================================================
param(
    [string]$Model = "qwen2.5:1.5b",
    [string]$Out = ""
)
$ErrorActionPreference = "Stop"

# where Ollama keeps its models on this PC
$src = if ($env:OLLAMA_MODELS) { $env:OLLAMA_MODELS } else { Join-Path $env:USERPROFILE ".ollama\models" }
if (-not $Out) { $Out = Join-Path $PSScriptRoot "ollama-export" }

# "qwen2.5:1.5b" -> library / qwen2.5 / 1.5b
$parts = $Model -split ":", 2
$name = $parts[0]
$tag = if ($parts.Count -gt 1 -and $parts[1]) { $parts[1] } else { "latest" }
$ns = "library"
if ($name.Contains("/")) { $p = $name -split "/", 2; $ns = $p[0]; $name = $p[1] }

$manifestRel = "manifests\registry.ollama.ai\$ns\$name\$tag"
$manifest = Join-Path $src $manifestRel
if (-not (Test-Path $manifest)) {
    Write-Host "Model '$Model' was not found in $src" -ForegroundColor Red
    Write-Host "Check the exact name with:  ollama list"
    exit 1
}

# the manifest lists the files (blobs) that make up the model
$m = Get-Content $manifest -Raw | ConvertFrom-Json
$digests = @($m.config.digest) + @($m.layers | ForEach-Object { $_.digest })

$outModels = Join-Path $Out "models"
New-Item -ItemType Directory -Force -Path (Join-Path $outModels "blobs") | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path (Join-Path $outModels $manifestRel)) | Out-Null
Copy-Item $manifest (Join-Path $outModels $manifestRel) -Force

$total = 0
foreach ($d in $digests) {
    $file = $d.Replace(":", "-")              # sha256:abc -> sha256-abc
    $from = Join-Path $src "blobs\$file"
    if (-not (Test-Path $from)) { Write-Host "Missing file $file - try: ollama pull $Model" -ForegroundColor Red; exit 1 }
    $size = (Get-Item $from).Length
    Write-Host ("  copying {0}  ({1:N0} MB)" -f $file.Substring(0, 19), ($size / 1MB))
    Copy-Item $from (Join-Path $outModels "blobs\$file") -Force
    $total += $size
}

Write-Host ""
Write-Host ("Done! '{0}' ({1:N0} MB) is in:" -f $Model, ($total / 1MB)) -ForegroundColor Green
Write-Host "  $Out"
Write-Host ""
Write-Host "Copy the whole 'pc' folder (with ollama-export inside) to the other laptop,"
Write-Host "install Ollama there, then double-click import_model.bat."
