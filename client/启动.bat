@echo off
chcp 65001 >nul
rem 转写客户端启动器：双击运行，打开转写历史窗口
cd /d "%~dp0.."
python client\app.py
if errorlevel 1 pause
