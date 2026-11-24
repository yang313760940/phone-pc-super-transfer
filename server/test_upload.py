"""#01 验收自测：启动服务，用 curl/urllib 上传，核对落盘字节数、超限拒绝、GET 页面。

跑法：python server/test_upload.py
不依赖 ASR（转写为占位返回）。
"""

from __future__ import annotations

import http.client
import io
import os
import socket
import tempfile
import threading
import time
import uuid
from pathlib import Path

import app  # 同目录导入

FIX_DIR = Path(tempfile.mkdtemp(prefix="voice-test-"))


def _multipart(fields):
    boundary = "----voicetest" + uuid.uuid4().hex
    buf = io.BytesIO()
    for name, (filename, data) in fields.items():
        buf.write(("--" + boundary + "\r\n").encode())
        disp = 'Content-Disposition: form-data; name="%s"' % name
        if filename is not None:
            disp += '; filename="%s"' % filename
        buf.write((disp + "\r\n").encode())
        buf.write(b"Content-Type: application/octet-stream\r\n\r\n")
        buf.write(data)
        buf.write(b"\r\n")
    buf.write(("--" + boundary + "--\r\n").encode())
    return boundary, buf.getvalue()


def _post(port, fields):
    boundary, body = _multipart(fields)
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
    conn.request("POST", "/upload", body=body,
                 headers={"Content-Type": "multipart/form-data; boundary=" + boundary,
                          "Content-Length": str(len(body))})
    resp = conn.getresponse()
    data = resp.read()
    conn.close()
    return resp.status, data


def main():
    # 独立存储目录，避免污染工作区
    store = FIX_DIR / "uploads"
    app.STORE_DIR = store
    app.set_transcriber(None)  # 占位

    srv = app.serve(host="127.0.0.1", port=0)
    port = srv.server_address[1]
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    time.sleep(0.2)

    ok = True

    # 验收 1：GET / 能拿到页面文件
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    conn.request("GET", "/")
    r = conn.getresponse()
    page = r.read()
    conn.close()
    cond1 = r.status == 200 and b"<html" in page.lower()
    print("验收1 GET / ->", r.status, "bytes=", len(page), "PASS" if cond1 else "FAIL")
    ok &= cond1

    # 验收 2：上传一段测试音频，落盘字节数一致
    payload = os.urandom(4096) + b"FAKEAUDIODATA"
    status, data = _post(port, {"audio": ("clip.webm", payload), "filename": ("x", b"clip-from-phone")})
    files = list(store.glob("recv-*")) if store.exists() else []
    written = files[0].stat().st_size if files else -1
    cond2 = status == 200 and written == len(payload) and b'"ok": true' in data.replace(b'"ok":true', b'"ok": true')
    print("验收2 upload ->", status, "sent=", len(payload), "written=", written, "resp=", data[:80], "PASS" if cond2 else "FAIL")
    ok &= cond2

    # 验收 3：超 25 MB 得 4xx 且不留文件
    before = len(files)
    big = os.urandom(26 * 1024 * 1024)
    status3, data3 = _post(port, {"audio": ("big.webm", big)})
    files_after = list(store.glob("recv-*")) if store.exists() else []
    cond3 = 400 <= status3 < 500 and len(files_after) == before
    print("验收3 oversize ->", status3, "files_before=", before, "files_after=", len(files_after),
          "resp=", data3[:80], "PASS" if cond3 else "FAIL")
    ok &= cond3

    srv.shutdown()
    print("RESULT:", "ALL PASS" if ok else "SOME FAIL")
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
