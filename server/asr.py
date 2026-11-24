"""本机 CPU int8 中文语音转写封装（实施票 #02）。

设计要点：
- 只用多语言 small + device=cpu + compute_type=int8 + language=zh + task=transcribe。
- 首次按模型名从 Hugging Face Hub 取权重（日志可见），之后可离线运行。
- 解码失败归 DecodeError（上层填 stage=decode），识别失败归 AsrError（stage=asr），
  便于 #04 在契约里区分错误阶段。

不做 HTTP、不做页面、不并发调度、不引 GPU 路径。
"""

from __future__ import annotations

import time
from pathlib import Path

MODEL_SIZE = "small"          # 多语言模型（非 .en）
LANGUAGE = "zh"
TASK = "transcribe"
DEVICE = "cpu"
COMPUTE_TYPE = "int8"

_model = None
_t2s = None


def _to_simplified(text: str) -> str:
    """繁→简转换：Whisper 中文常输出繁体，统一转简体。
    懒加载并缓存 OpenCC；若未安装则原样返回（不阻断转写）。"""
    if not text:
        return text
    global _t2s
    if _t2s is None:
        try:
            from opencc import OpenCC
            _t2s = OpenCC("t2s")
        except Exception as exc:  # 缺依赖时降级
            print("[asr] opencc 不可用，跳过繁转简: %s" % exc)
            _t2s = False
    if _t2s is False:
        return text
    try:
        return _t2s.convert(text)
    except Exception as exc:
        print("[asr] 繁转简失败，保留原文: %s" % exc)
        return text


class DecodeError(RuntimeError):
    """音频无法被解码（容器/编码问题）。"""


class AsrError(RuntimeError):
    """音频可解码但识别环节失败。"""


def _get_model():
    global _model
    if _model is None:
        from faster_whisper import WhisperModel  # 延迟导入，便于无依赖环境加载模块
        print("[asr] loading model size=%s device=%s compute=%s" % (MODEL_SIZE, DEVICE, COMPUTE_TYPE))
        _model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
        print("[asr] model ready")
    return _model


def transcribe(path: str) -> dict:
    """转写一段音频，返回 {"text": str, "duration_ms": int}。"""
    p = Path(path)
    if not p.is_file() or p.stat().st_size == 0:
        raise DecodeError("音频文件不存在或为空: %s" % path)

    model = _get_model()
    started = time.perf_counter()
    try:
        segments, info = model.transcribe(
            str(p), language=LANGUAGE, task=TASK, beam_size=5, vad_filter=True,
        )
        # segments 是惰性迭代器：真正的解码/识别在迭代时发生
        parts = [seg.text.strip() for seg in segments if seg.text and seg.text.strip()]
    except Exception as exc:
        # PyAV/ffmpeg 打不开容器或编码 → 归为解码失败；其余归识别失败。
        msg = str(exc)
        lowered = msg.lower()
        if any(k in lowered for k in ("decode", "invalid data", "could not open", "no such", "av", "codec", "format")):
            raise DecodeError("音频解码失败: %s" % msg) from exc
        raise AsrError("识别失败: %s" % msg) from exc

    text = _to_simplified("".join(parts))
    duration_ms = int((time.perf_counter() - started) * 1000)
    return {"text": text, "duration_ms": duration_ms}


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("用法: python asr.py <audio-file>")
        raise SystemExit(2)
    try:
        out = transcribe(sys.argv[1])
        print("TEXT:", out["text"])
        print("DURATION_MS:", out["duration_ms"])
    except DecodeError as exc:
        print("DECODE_ERROR:", exc)
        raise SystemExit(3)
    except AsrError as exc:
        print("ASR_ERROR:", exc)
        raise SystemExit(4)
