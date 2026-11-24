# 换 IP 一键对齐：重生成自签证书（当前局域网 IP）→ 重启服务即可。
# 用法：powershell -ExecutionPolicy Bypass -File server\reissue_for_ip.ps1
# 说明：App 采用「首次连接信任」（TOFU），不再内置证书，故换 IP 后【无需重装 App】，
#       只要重启服务；App 里地址改成新 IP 即可（若之前信任过旧证书，需清一次 App 数据重信任）。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ip = (Get-NetIPAddress -AddressFamily IPv4 |
       Where-Object { $_.IPAddress -notlike "127.*" -and $_.InterfaceAlias -notlike "Tailscale*" } |
       Select-Object -First 1).IPAddress
if (-not $ip) { Write-Error "取不到局域网 IPv4，请手动指定：python server/make_cert.py <IP>"; exit 1 }
Write-Host "当前局域网 IP: $ip"

python "$root\server\make_cert.py" $ip
if ($LASTEXITCODE -ne 0) { Write-Error "证书生成失败"; exit 1 }

Write-Host ""
Write-Host "后续步骤（App 无需重装）："
Write-Host "  1) 重启服务：python server\run_https.py"
Write-Host "  2) App 内服务器地址填: https://$ip:8443"
Write-Host "  3) 若该 App 之前信任过旧证书，会提示指纹不一致——到系统设置清除本 App 数据后重连即可。"
