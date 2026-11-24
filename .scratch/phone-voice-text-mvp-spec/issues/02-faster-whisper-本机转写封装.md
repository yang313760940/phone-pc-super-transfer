<!-- DSH-FORMAT: md-v1 -->
<!-- DSH-PARENT: 00 -->
<!-- DSH-LABELS: ready-for-agent -->
<!-- DSH-IDEMPOTENCY-KEY: deck-7d938280-5984809 -->
<!-- DSH-CREATED: 2026-11-23T14:06:11.651Z -->
<!-- DSH-ASSIGNEES: lead -->
# 02: faster-whisper 本机转写封装

**What to build:** 

**Blocked by:** None (can start immediately)

**Status:** resolved

## 目的

在电脑本机用 CPU int8 把一段音频转成中文文字，并可被上传接口调用。本票产出可独立测试的转写函数，不依赖页面。

## 范围

- `asr.py`：封装 faster-whisper，模型多语言 `small`、`device="cpu"`、`compute_type="int8"`、`language="zh"`、`task="transcribe"`。
- pip 安装 `faster-whisper`、`ctranslate2`、`av`、`huggingface-hub`、`tokenizers`、`onnxruntime`、`tqdm`。
- 首次按模型名从 Hugging Face Hub 取权重；记录下载日志。离线就绪需权重、`config.json`、`preprocessor_config.json`、`tokenizer.json`、`vocabulary.*` 齐全。
- 输入路径 → 输出 `{"text": "...", "duration_ms": 整数}`；解码失败与识别失败要能区分（供上层填 `stage=decode|asr`）。
- 用一段真实中文音频跑一次，记录实测端到端耗时；不预设秒数。

## 验收

1. 独立脚本喂一段中文音频，输出合理中文文字与非零 `duration_ms`。
2. 模型首次下载完成；断网后同一进程能继续转写已缓存模型（离线条件成立）。
3. 喂损坏文件时抛出可归为 `decode` 的错误，不静默返回空串。

## 不做什么

不写 HTTP 服务、不做页面、不并发调度、不引 GPU 路径。

## Comments

### local — 2026-11-23T14:12:47.187Z
完成情况：`server/asr.py`（faster-whisper 多语言 small / cpu / int8 / zh / transcribe；解码失败 DecodeError、识别失败 AsrError 供上层区分 stage）；`server/test_asr.py` 三项验收 ALL PASS。实测：6.1 秒中文 WAV →「今天下午三点开会,请把手机语音转成文字发给我。」duration_ms≈1550。关键约束：`av==15.1.0`（av>=16 移除 av.open 的 metadata_errors，av14 无 py3.12 wheel），已记入 `server/REQUIREMENTS-NOTES.md`。

### local — 2026-11-23T14:12:47.262Z
三项验收通过：真实中文音频转写正确且 duration_ms 非零；HF_HUB_OFFLINE 下缓存模型仍可转写；损坏文件抛 DecodeError。

## 进度

- 进度：100%（2026-11-23T22:12:47+08:00）：三项验收通过，已结案。
- 进度：90%（2026-11-23T22:12:17+08:00）：asr.py 已实测全过：真实中文音频转写正确、离线缓存可转、损坏文件归 DecodeError；已记 av==15.1.0 兼容约束。
- 进度：10%（2026-11-23T22:07:01+08:00）：已认领，开始安装 faster-whisper 依赖并编写转写封装。
