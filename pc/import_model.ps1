# =============================================================================
#  On the NEW laptop: puts the copied model into Ollama and sets Ollama up so
#  the ESP32 and the phone app can reach it over WiFi.
#
#  1. Install Ollama first (ollama.com, or copy OllamaSetup.exe on the USB too)
#  2. Right-click import_model.bat -> Run as administrator  (needed once for the firewall)
# =============================================================================
param(
    [string]$From = "",
    [string]$Model = "qwen2.5:1.5b"
)
$ErrorActionPreference = "Stop"

if (-not $From) { $From = Join-Path $PSScriptRoot "ollama-export\models" }
if (-not (Test-Path (Join-Path $From "manifests"))) {
    Write-Host "No copied model found in $From" -ForegroundColor Red
    Write-Host "Run export_model.bat on the old PC first and copy the ollama-export folder next to this file."
    exit 1
}
$dst = if ($env:OLLAMA_MODELS) { $env:OLLAMA_MODELS } else { Join-Path $env:USERPROFILE ".ollama\models" }

# 1) stop Ollama while we copy
Write-Host "Stopping Ollama..."
Get-Process -Name "ollama app", "ollama" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# 2) copy the model files (merges with models already there)
Write-Host "Copying the model into $dst ..."
New-Item -ItemType Directory -Force -Path $dst | Out-Null
Copy-Item (Join-Path $From "*") $dst -Recurse -Force

# 3) settings for the local model server (saved for your Windows user)
#    OLLAMA_HOST=0.0.0.0  -> listen on WiFi, not only on this laptop
#    OLLAMA_KEEP_ALIVE=30m -> keep the model in memory, so answers stay fast
[Environment]::SetEnvironmentVariable("OLLAMA_HOST", "0.0.0.0:11434", "User")
[Environment]::SetEnvironmentVariable("OLLAMA_KEEP_ALIVE", "30m", "User")
$env:OLLAMA_HOST = "0.0.0.0:11434"
$env:OLLAMA_KEEP_ALIVE = "30m"
Write-Host "Set OLLAMA_HOST=0.0.0.0:11434 and OLLAMA_KEEP_ALIVE=30m"

# 4) firewall: let the ESP32 / phone in on port 11434 (private networks)
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($isAdmin) {
    if (-not (Get-NetFirewallRule -DisplayName "Ollama 11434" -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName "Ollama 11434" -Direction Inbound -Protocol TCP -LocalPort 11434 -Action Allow -Profile Private, Domain | Out-Null
    }
    Write-Host "Firewall: port 11434 open on private networks"
} else {
    Write-Host "Firewall not changed (not run as administrator). Windows will ask the first time - choose 'Private networks'." -ForegroundColor Yellow
}
$public = Get-NetConnectionProfile -ErrorAction SilentlyContinue | Where-Object { $_.NetworkCategory -eq "Public" }
foreach ($n in $public) {
    Write-Host ""
    Write-Host "Your WiFi '$($n.Name)' is set to PUBLIC, so the ESP32 will be blocked." -ForegroundColor Yellow
    Write-Host "Fix: Settings > Network & internet > Wi-Fi > $($n.Name) > Network profile type: Private"
    if ($isAdmin) {
        $a = Read-Host "Make it Private now? (y/n)"
        if ($a -eq "y") { Set-NetConnectionProfile -InterfaceIndex $n.InterfaceIndex -NetworkCategory Private; Write-Host "Done." -ForegroundColor Green }
    }
}

# 5) start Ollama again with the new settings
$app = Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama app.exe"
if (Test-Path $app) { Start-Process $app } else { Start-Process "ollama" -ArgumentList "serve" -WindowStyle Minimized }
Write-Host "Starting Ollama..."
Start-Sleep -Seconds 5
$ErrorActionPreference = "Continue"     # ollama prints progress on stderr
Write-Host ""
try { & ollama list } catch {}
Write-Host ""
Write-Host "Loading $Model once (first answer is slow, after that it's fast)..."
try { & ollama run $Model "Say hi in 5 words." } catch { Write-Host "Could not run the model: $_" -ForegroundColor Yellow }

# 6) what to type in config.h and the app
$ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" -and $_.PrefixOrigin -ne "WellKnown" } |
    Select-Object -ExpandProperty IPAddress
Write-Host ""
Write-Host "=============================================================" -ForegroundColor Green
Write-Host " This laptop is now your AI server. Its IP address:" -ForegroundColor Green
foreach ($ip in $ips) { Write-Host "    $ip" -ForegroundColor Green }
Write-Host ""
Write-Host " Use the WiFi one (same network as the ESP32 and your phone):"
Write-Host "  - Phone app: Settings > PC address (Ollama) > <that IP> > Save & test"
Write-Host "  - ESP32 config.h:  #define DEFAULT_OLLAMA_HOST  `"<that IP>`""
Write-Host "    (or just Save in the app - it sends the new address to the ESP32)"
Write-Host "  - Test from the phone browser:  http://<that IP>:11434  -> 'Ollama is running'"
Write-Host "=============================================================" -ForegroundColor Green
