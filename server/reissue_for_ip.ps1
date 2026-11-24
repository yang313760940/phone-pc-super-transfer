# 换 IP 一键对齐：重生成自签证书（当前局域网 IP）→ 同步到 App 资源 → 提示重建。
# 用法（管理员或普通均可，只要能写文件）：
#   powershell -ExecutionPolicy Bypass -File server\reissue_for_ip.ps1
# 之后需重启 run_https.py，并重建/重装 App（证书 pinning 必须与服务端一致）。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ip = (Get-NetIPAddress -AddressFamily IPv4 |
       Where-Object { $_.IPAddress -notlike "127.*" -and $_.InterfaceAlias -notlike "Tailscale*" } |
       Select-Object -First 1).IPAddress
if (-not $ip) { Write-Error "取不到局域网 IPv4，请手动指定：python server/make_cert.py <IP>"; exit 1 }
Write-Host "当前局域网 IP: $ip"

python "$root\server\make_cert.py" $ip
if ($LASTEXITCODE -ne 0) { Write-Error "证书生成失败"; exit 1 }

$dst = "$root\app\app\src\main\assets\cert.pem"
Copy-Item "$root\server\certs\cert.pem" $dst -Force
Write-Host "已同步证书到 App 资源: $dst"

Write-Host ""
Write-Host "后续步骤："
Write-Host "  1) 重启服务：python server\run_https.py"
Write-Host "  2) 重建 App：在 app\ 下跑 gradle :app:assembleDebug（或用你的构建脚本）"
Write-Host "  3) 重装：adb install -r app\app\build\outputs\apk\debug\app-debug.apk"
Write-Host "  4) App 内服务器地址填: https://$ip:8443"
