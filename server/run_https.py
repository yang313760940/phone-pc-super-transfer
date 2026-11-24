"""HTTPS 收口入口：把 #01 的接收服务 + #02 的 asr 拼成真机可用闭环（实施票 #04）。

用法：
  python server/run_https.py                 # 默认 0.0.0.0:8443，用 server/certs 证书
  PORT=8443 python server/run_https.py

行为：
- 以 HTTPS 监听局域网地址（0.0.0.0 以便手机经电脑 IP 访问）。
- 把 /upload 接到 asr.transcribe：解码失败→stage=decode，识别失败→stage=asr，其它→stage=internal。
- 打印手机访问 URL（含本机局域网 IP）。
"""

from __future__ import annotations

import os
import socket
import ssl
from pathlib import Path

import app
import asr

HERE = Path(__file__).resolve().parent
CERT_DIR = HERE / "certs"


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def wired_transcribe(path: str) -> dict:
    """把 asr 的异常映射到契约的 stage。"""
    try:
        return asr.transcribe(path)
    except asr.DecodeError as exc:
        # 让 app 以 stage=decode 返回：用带标记的异常类型
        raise app.StageError("decode", str(exc)) from exc
    except asr.AsrError as exc:
        raise app.StageError("asr", str(exc)) from exc


def main() -> None:
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "8443"))
    cert = CERT_DIR / "cert.pem"
    key = CERT_DIR / "key.pem"
    if not cert.is_file() or not key.is_file():
        raise SystemExit("缺少证书：先运行 python server/make_cert.py <局域网IP>")

    app.set_transcriber(wired_transcribe)

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(certfile=str(cert), keyfile=str(key))

    srv = app.serve(host=host, port=port, tls_context=ctx)
    ip = lan_ip()
    print("=" * 56)
    print(" 手机与电脑连同一局域网后，用手机浏览器打开：")
    print("   https://%s:%d/" % (ip, port))
    print(" 首次会提示证书不受信任，按浏览器指引继续/信任一次即可。")
    print(" 录音页 → 录音 → 停止即发送 → 电脑转写并显示可复制文字。")
    print("=" * 56)
    srv.serve_forever()


if __name__ == "__main__":
    main()
