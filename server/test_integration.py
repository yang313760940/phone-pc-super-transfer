"""集成校验：把 #02 的 asr.transcribe 注入 #01 的 /upload，走一遍真实端到端（HTTP 层）。"""
import io
import sys
import threading
import time
import uuid
import http.client
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "server"))
import app  # noqa: E402
import asr  # noqa: E402

app.set_transcriber(asr.transcribe)
srv = app.serve(host="127.0.0.1", port=0)
port = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()
time.sleep(0.2)

data = (ROOT / "samples" / "zh_test.wav").read_bytes()
b = "----t" + uuid.uuid4().hex
body = io.BytesIO()
body.write(("--" + b + "\r\n").encode())
body.write(b'Content-Disposition: form-data; name="audio"; filename="zh_test.wav"\r\n\r\n')
body.write(data)
body.write(b"\r\n")
body.write(("--" + b + "--\r\n").encode())
raw = body.getvalue()

c = http.client.HTTPConnection("127.0.0.1", port, timeout=120)
c.request("POST", "/upload", body=raw,
          headers={"Content-Type": "multipart/form-data; boundary=" + b,
                   "Content-Length": str(len(raw))})
r = c.getresponse()
out = r.read().decode("utf-8")
print("STATUS", r.status)
print("RESP", out)
srv.shutdown()
sys.exit(0 if (r.status == 200 and '"ok": true' in out.replace('"ok":true', '"ok": true') and "今天" in out) else 1)
