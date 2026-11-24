"""打包版入口（PyInstaller 冻结后运行）：同事零环境双击即用。

行为：
- 在 exe 同目录建 data/ 存放证书、上传、latest/history（可写）。
- 若无证书，自动按当前局域网 IP 生成。
- 用打包内的 web/ 资源与 asr 转写启动 HTTPS 服务，并打印手机访问地址。
"""
from __future__ import annotations

import os
import sys
from pathlib import Path


def _base_dir() -> Path:
    # 冻结后资源在 sys._MEIPASS；源码运行时为脚本目录
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).resolve().parent))
    return Path(__file__).resolve().parent


def _exe_dir() -> Path:
    # exe 所在目录（数据写到它旁边的 data/，可写）
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def main() -> None:
    base = _base_dir()
    data_dir = _exe_dir() / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    cert_dir = data_dir / "certs"

    # 让 app / asr 找到打包资源与可写目录
    os.environ.setdefault("VOICE_WEB_ROOT", str(base / "web"))
    os.environ.setdefault("VOICE_STORE_DIR", str(data_dir / "uploads"))
    os.environ.setdefault("VOICE_DATA_DIR", str(data_dir))

    # 证书：没有就现生成（覆盖当前局域网 IP）
    cert = cert_dir / "cert.pem"
    key = cert_dir / "key.pem"
    if not cert.is_file() or not key.is_file():
        print("[exe] 未找到证书，正在按当前局域网 IP 生成…")
        import make_cert
        # make_cert 的 CERT_DIR 在源码里是 server/certs；这里直接调用其 build 逻辑落到自定义目录
        make_cert.CERT_DIR = cert_dir  # type: ignore[attr-defined]
        ips = make_cert.local_ips()
        if not ips:
            raise SystemExit("未取到局域网 IPv4，请检查网络连接")
        make_cert.build(ips, [os.environ.get("COMPUTERNAME", "pc")])
        print("[exe] 证书已生成:", cert_dir)

    import ssl
    import app
    import asr

    def wired_transcribe(path: str) -> dict:
        try:
            return asr.transcribe(path)
        except asr.DecodeError as exc:
            raise app.StageError("decode", str(exc)) from exc
        except asr.AsrError as exc:
            raise app.StageError("asr", str(exc)) from exc

    app.set_transcriber(wired_transcribe)

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(certfile=str(cert), keyfile=str(key))

    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "8443"))
    # 证书在 data/certs，服务需要它；把 app 的默认 cert 目录指过去（run_https 用不到，这里自管）
    srv = app.serve(host=host, port=port, tls_context=ctx)

    # 打印手机访问地址
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80)); ip = s.getsockname()[0]
    except OSError:
        ip = "127.0.0.1"
    finally:
        s.close()
    print("=" * 56)
    print(" 手机与电脑连同一局域网，用手机 App（VoiceSend）填地址：")
    print("   https://%s:%d" % (ip, port))
    print(" 首次连接 App 会自动信任本机证书；无需其他设置。")
    print(" 保持本窗口开启即为运行中；关闭窗口即停止服务。")
    print("=" * 56)
    srv.serve_forever()


if __name__ == "__main__":
    main()
