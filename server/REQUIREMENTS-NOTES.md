# 依赖与版本备注

本目录运行 faster-whisper 中文转写的实测环境，供实施与复现参照。

- Python 3.12.10 / pip 25.0.1（Windows x64）
- faster-whisper 1.2.1、ctranslate2 4.9.1
- **PyAV 必须用 `av==15.1.0`**：`faster_whisper/audio.py` 调 `av.open(..., metadata_errors="ignore")`，
  而 `av>=16` 的 `av.open()` 已移除该参数，会报
  `open() got an unexpected keyword argument 'metadata_errors'`；
  `av==14.x` 在 Python 3.12 无预编译 wheel，会回退源码编译并因缺 MSVC 失败。
- huggingface-hub 2.4.0（首次拉模型用；模型已缓存 `models--Systran--faster-whisper-small`）
- onnxruntime 1.32.0、tokenizers 0.23.3、numpy 2.5.5、tqdm 4.70.1

## 实测（#02）

- 6.1 秒中文 WAV（系统 TTS 合成）→「今天下午三点开会,请把手机语音转成文字发给我。」，`duration_ms≈1550`。
- 缓存就绪后 `HF_HUB_OFFLINE=1` 仍可转写；损坏文件抛 `DecodeError`。

## 复现

```
python -m pip install faster-whisper ctranslate2 av==15.1.0 huggingface-hub tokenizers onnxruntime tqdm
python server/test_asr.py
```
