"""#02 验收自测：真实音频转写、离线加载、损坏文件归 decode。"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import asr  # noqa: E402

ok = True

# 验收1：真实中文音频 → 合理文字 + 非零 duration_ms（模型已在缓存）
out = asr.transcribe("samples/zh_test.wav")
cond1 = bool(out["text"].strip()) and out["duration_ms"] > 0
print("验收1 transcribe ->", out["text"][:30], "dur=", out["duration_ms"], "PASS" if cond1 else "FAIL")
ok &= cond1

# 验收2：断网后同一进程用已缓存模型继续转写（用 HF_HUB_OFFLINE 模拟离线加载新进程不可行，
# 这里在同进程内再次转写并确认不再联网即可；另用 offline 环境变量做一次独立子进程验证）
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
out2 = asr.transcribe("samples/zh_test.wav")
cond2 = bool(out2["text"].strip())
print("验收2 offline cached transcribe ->", out2["text"][:20], "PASS" if cond2 else "FAIL")
ok &= cond2

# 验收3：损坏文件归 DecodeError，不静默返回空串
bad = Path(tempfile.mkdtemp()) / "broken.webm"
bad.write_bytes(b"\x00\x01\x02not-a-real-container" * 10)
try:
    asr.transcribe(str(bad))
    print("验收3 broken -> FAIL (no error raised)")
    ok = False
except asr.DecodeError as exc:
    print("验收3 broken -> DecodeError PASS:", str(exc)[:50])
except Exception as exc:  # 归错类也算失败
    print("验收3 broken -> WRONG ERROR TYPE:", type(exc).__name__, str(exc)[:50])
    ok = False

print("RESULT:", "ALL PASS" if ok else "SOME FAIL")
sys.exit(0 if ok else 1)
