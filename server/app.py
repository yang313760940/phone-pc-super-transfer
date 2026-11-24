"""极简接收服务：GET / 返回录音页，POST /upload 接收并落盘一段音频。

本模块属于实施票 #01「接收页与上传接口」：
- 只负责接收与落盘校验，转写接口以占位返回，真实转写由 #02/#04 接入。
- 单进程，仅监听局域网地址；HTTPS 证书由 #04 处理，这里只跑 HTTP 便于本票自测。
"""

from __future__ import annotations

import datetime
import json
import os
import subprocess
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BYTES = 25 * 1024 * 1024  # 单段上限 25 MB，规格契约。
HERE = Path(__file__).resolve().parent
# 允许 exe 打包版用环境变量覆盖 Web 根与落地目录（默认沿用仓库内路径，测试/自用不变）
WEB_ROOT = Path(os.environ.get("VOICE_WEB_ROOT", HERE.parent / "web"))
STORE_DIR = Path(os.environ.get("VOICE_STORE_DIR", HERE / "uploads"))
DATA_DIR = Path(os.environ.get("VOICE_DATA_DIR", HERE))

# 实施票 #05：转写文字落到电脑——剪贴板 + 本地文件备份
LATEST_TXT = DATA_DIR / "latest.txt"
HISTORY_TXT = DATA_DIR / "history.txt"


def _set_clipboard(text: str) -> bool:
    """把文字写入电脑剪贴板。优先 tkinter（标准库），退回 PowerShell Set-Clipboard。
    任何失败都返回 False，绝不抛出，避免影响对手机的响应。"""
    # 1) tkinter 方案
    try:
        import tkinter
        root = tkinter.Tk()
        root.withdraw()
        root.clipboard_clear()
        root.clipboard_append(text)
        root.update()  # 必须 update，否则关闭后剪贴板可能丢内容
        root.destroy()
        return True
    except Exception as exc:
        print("[clipboard] tkinter 失败: %s" % exc)
    # 2) PowerShell 兜底（Windows）
    if sys.platform.startswith("win"):
        try:
            # 用 UTF-8 base64 传参，避免中文经命令行参数的编码问题
            import base64
            b64 = base64.b64encode(text.encode("utf-8")).decode("ascii")
            ps = (
                "$t=[Text.Encoding]::UTF8.GetString("
                "[Convert]::FromBase64String('%s'));"
                "Set-Clipboard -Value $t" % b64
            )
            subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                           check=False, timeout=10,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return True
        except Exception as exc:
            print("[clipboard] PowerShell 失败: %s" % exc)
    return False


def deliver_to_computer(text: str) -> None:
    """转写文字落到电脑：写剪贴板 + 覆盖 latest.txt + 追加 history.txt。
    每步失败都只记日志，不影响对手机端的响应。"""
    if not text:
        return
    ok = _set_clipboard(text)
    print("[deliver] clipboard %s, len=%d" % ("ok" if ok else "FAILED", len(text)))
    try:
        LATEST_TXT.write_text(text, encoding="utf-8")
    except Exception as exc:
        print("[deliver] latest.txt 写入失败: %s" % exc)
    try:
        ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        with open(HISTORY_TXT, "a", encoding="utf-8") as f:
            f.write("[%s] %s\n" % (ts, text.replace("\n", " ")))
    except Exception as exc:
        print("[deliver] history.txt 追加失败: %s" % exc)

# 转写后端：默认占位；#04 会替换成真实 asr 实现。
_transcribe = None


class StageError(Exception):
    """转写阶段错误，带契约里的 stage（decode|asr|internal），供上传处理区分。"""

    def __init__(self, stage: str, message: str):
        super().__init__(message)
        self.stage = stage
        self.message = message


def set_transcriber(fn):
    """供 #04 注入真实转写函数：fn(path) -> {"text": str, "duration_ms": int}。"""
    global _transcribe
    _transcribe = fn


def _json(handler, status, payload):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _parse_multipart(content_type: str, body: bytes):
    """极简 multipart/form-data 解析：返回 {字段名: (filename, bytes)}。"""
    if "boundary=" not in content_type:
        return None
    boundary = content_type.split("boundary=", 1)[1].strip().strip('"')
    sep = ("--" + boundary).encode()
    fields = {}
    for part in body.split(sep):
        part = part.strip(b"\r\n")
        if not part or part == b"--":
            continue
        head, _, data = part.partition(b"\r\n\r\n")
        lines = head.decode("utf-8", "replace").split("\r\n")
        disp = None
        for line in lines:
            if line.lower().startswith("content-disposition"):
                disp = line
                break
        if disp is None:
            continue
        name = None
        filename = ""
        for seg in disp.split(";")[1:]:
            seg = seg.strip()
            if seg.startswith("name="):
                name = seg[5:].strip('"')
            elif seg.startswith("filename="):
                filename = seg[9:].strip('"')
        if name is None:
            continue
        fields[name] = (filename, data.rstrip(b"\r\n"))
    return fields


class Handler(BaseHTTPRequestHandler):
    server_version = "VoiceRecv/0.1"

    def log_message(self, fmt, *args):  # 精简日志
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))

    def _serve_file(self, rel: str):
        path = (WEB_ROOT / rel.lstrip("/")).resolve()
        if not str(path).startswith(str(WEB_ROOT.resolve())) or not path.is_file():
            self.send_error(404)
            return
        data = path.read_bytes()
        ctype = "text/html; charset=utf-8" if path.suffix == ".html" else "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            self._serve_file("index.html")
        elif self.path == "/healthz":
            _json(self, 200, {"ok": True})
        else:
            self.send_error(404)

    def do_POST(self):
        if self.path != "/upload":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            _json(self, 400, {"ok": False, "error": "缺少有效的 Content-Length", "stage": "internal"})
            return
        if length <= 0:
            _json(self, 400, {"ok": False, "error": "空请求体", "stage": "internal"})
            return
        if length > MAX_BYTES:
            # 先把超限请求体丢弃，避免客户端因为写不完而收到连接复位；
            # 丢弃后仍以 4xx 明确拒绝，且不落任何文件。
            remaining = length
            while remaining > 0:
                chunk = self.rfile.read(min(65536, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
            self.close_connection = True
            _json(self, 413, {"ok": False, "error": "音频超过 25 MB 上限", "stage": "internal"})
            return
        body = self.rfile.read(length)
        fields = _parse_multipart(self.headers.get("Content-Type", ""), body)
        if fields is None or "audio" not in fields:
            _json(self, 400, {"ok": False, "error": "缺少 audio 文件字段", "stage": "internal"})
            return
        filename, data = fields["audio"]
        if not data:
            _json(self, 400, {"ok": False, "error": "audio 内容为空", "stage": "internal"})
            return

        STORE_DIR.mkdir(parents=True, exist_ok=True)
        suffix = Path(filename).suffix or ".bin"
        fd, tmp_path = tempfile.mkstemp(dir=str(STORE_DIR), prefix="recv-", suffix=suffix)
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(data)
            written = os.path.getsize(tmp_path)
            if written != len(data):
                os.unlink(tmp_path)
                _json(self, 500, {"ok": False, "error": "落盘字节数与上传不一致", "stage": "internal"})
                return
        except Exception as exc:  # 落盘失败要清掉半截文件
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
            _json(self, 500, {"ok": False, "error": "写入失败: %s" % exc, "stage": "internal"})
            return

        self.log_message("upload ok: %s bytes=%d -> %s", filename or "-", written, tmp_path)

        if _transcribe is None:
            _json(self, 200, {"ok": True, "text": "", "duration_ms": 0})
            return
        try:
            result = _transcribe(tmp_path)
            deliver_to_computer(result["text"])  # #05：文字落到电脑（剪贴板 + 文件备份）
            _json(self, 200, {"ok": True, "text": result["text"], "duration_ms": int(result["duration_ms"])})
        except StageError as exc:
            status = 422 if exc.stage == "decode" else 500
            _json(self, status, {"ok": False, "error": exc.message, "stage": exc.stage})
        except Exception as exc:
            _json(self, 500, {"ok": False, "error": str(exc), "stage": "internal"})


def serve(host: str = "0.0.0.0", port: int = 8765, tls_context=None):
    httpd = ThreadingHTTPServer((host, port), Handler)
    if tls_context is not None:
        httpd.socket = tls_context.wrap_socket(httpd.socket, server_side=True)
    print("listening on %s:%d (https=%s)" % (host, port, tls_context is not None))
    return httpd


if __name__ == "__main__":
    srv = serve(port=int(os.environ.get("PORT", "8765")))
    print("GET http://127.0.0.1:%d/  POST /upload" % srv.server_address[1])
    srv.serve_forever()
