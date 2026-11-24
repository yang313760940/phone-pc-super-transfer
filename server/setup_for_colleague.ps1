# 同事电脑一键安装（Windows）
# 用法（在 server 目录所在的仓库根执行）：
#   powershell -ExecutionPolicy Bypass -File server\setup_for_colleague.ps1
# 作用：检查 Python、安装依赖、按当前 IP 生成证书、提示防火墙放行、给出启动命令。App 端无需重打包。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "===== 手机语音转写 · 同事电脑安装 ====="

# 1) Python 检查
$py = (Get-Command python -ErrorAction SilentlyContinue)
if (-not $py) { Write-Error "未找到 python，请先安装 Python 3.10+ 并勾选 Add to PATH"; exit 1 }
Write-Host "Python: $((python --version) 2>&1)"

# 2) 安装依赖
Write-Host "[1/4] 安装 Python 依赖（首次较慢，会下载 faster-whisper 等）…"
python -m pip install --upgrade pip
python -m pip install faster-whisper ctranslate2 "av==15.1.0" huggingface-hub tokenizers onnxruntime tqdm
if ($LASTEXITCODE -ne 0) { Write-Error "依赖安装失败"; exit 1 }

# 3) 生成本机证书
Write-Host "[2/4] 生成本机自签证书…"
$ip = (Get-NetIPAddress -AddressFamily IPv4 |
       Where-Object { $_.IPAddress -notlike "127.*" -and $_.InterfaceAlias -notlike "Tailscale*" } |
       Select-Object -First 1).IPAddress
if (-not $ip) { Write-Error "取不到局域网 IPv4"; exit 1 }
python server\make_cert.py $ip

# 4) 防火墙放行 8443（需管理员；非管理员会提示）
Write-Host "[3/4] 放行防火墙 8443 入站（若提示权限不足，请以管理员重跑本脚本或单独执行 server\allow_firewall.ps1）…"
try { & "$root\server\allow_firewall.ps1" } catch { Write-Warning "防火墙未放行（可能非管理员），请手动放行 TCP 8443 入站。" }

Write-Host "[4/4] 完成。"
Write-Host ""
Write-Host "===== 下一步 ====="
Write-Host "  1) 启动服务：python server\run_https.py"
Write-Host "  2) 手机连同一 Wi-Fi，App 里服务器地址填：https://$ip:8443"
Write-Host "  3) App 首次连接会自动信任本机证书（TOFU），无需其他证书操作。"
Write-Host "  （首次启动服务会下载中文模型 ~1GB，请耐心等待一次。）"
