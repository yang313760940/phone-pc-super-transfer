# 打包免装 Python 的 exe（PyInstaller，onedir）
# 用法（仓库根，已装好依赖与 pyinstaller）：
#   powershell -ExecutionPolicy Bypass -File server\build_exe.ps1
# 产物：dist_exe\VoiceSendServer\（整个文件夹分发；双击 VoiceSendServer.exe 即用）
# 说明：已剔除 torch/llvmlite/matplotlib/scipy 等未用的大依赖，约 295MB。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$web = (Resolve-Path web).Path
$srv = (Resolve-Path server).Path

python -m PyInstaller --noconfirm --clean --windowed --name VoiceSendServer `
  --distpath dist_exe --workpath build_exe --specpath build_exe `
  --add-data "$web;web" `
  --collect-all faster_whisper --collect-all ctranslate2 --collect-all av --collect-all onnxruntime `
  --hidden-import=make_cert `
  --exclude-module torch --exclude-module llvmlite --exclude-module matplotlib `
  --exclude-module PIL --exclude-module scipy --exclude-module pandas `
  --exclude-module tkinter --exclude-module numba `
  --paths "$srv" "$srv\exe_main.py"

Write-Host ""
Write-Host "完成：dist_exe\VoiceSendServer\VoiceSendServer.exe（整文件夹分发）"
Write-Host "首次运行会在同目录 data\ 自动生成证书；手机 App 地址填打印出的 https://IP:8443"
