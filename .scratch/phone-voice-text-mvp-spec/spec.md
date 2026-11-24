<!-- DSH-FORMAT: md-v1 -->
<!-- DSH-TITLE: 无 App 手机语音转写 MVP 规格 -->
<!-- DSH-STATUS: ready-for-agent -->
<!-- DSH-LABELS: ready-for-agent -->
<!-- DSH-IDEMPOTENCY-KEY: deck-b3fc3c38-5984808 -->
<!-- DSH-CREATED: 2026-11-23T14:04:40.354Z -->

## 来源

由探索地图「无 App 手机语音转写 MVP 路线」（`.scratch/phone-voice-text-mvp/map.md`）已结案的两张决策票落成，路线与最小验收点不再改动：
- 调查结论：`.scratch/phone-voice-text-mvp/research/no-app-feasibility.md`
- 路线确认：`.scratch/phone-voice-text-mvp/issues/02-敲定无-app-mvp-路线与验收边界.md`

## 目标与非目标

**目标**：个人自用、逐段录音。手机在局域网 H5 页面录一段中文语音并发送；电脑收到完整音频后在本机转写成中文，并把结果作为可直接复制的文字显示出来。

**非目标**：原生安卓 APK、跨语言翻译、边说边出字、多用户、历史库、实时流式。

## 术语

- **语音片段**：一次录制、有明确起止的一段语音。
- **语音转写**：语音对应的同语言文字（当前为中文语音对中文文字）。
- **转写结果**：一段语音片段对应、供阅读与复制的文字。
- 详见仓库根 `GLOSSARY.md`。

## 结构与约定（命名仅作建议，实施可等价替换）

- `server/`：电脑端接收与转写服务，仅监听局域网地址：
  - `GET /` 返回手机录音页；
  - `POST /upload`（multipart 单文件）接收音频、触发转写、返回转写结果 JSON；
  - 接收上限默认单段 25 MB，够数分钟的压缩语音；超限返回可读错误，不用 500 糊过去。
- `web/index.html`：H5 录音页。`getUserMedia({audio:true})` + `MediaRecorder` 录一段，停录即上传；结果区显示转写文字与「复制」按钮；失败显示原因与「重试」按钮，不丢已录音频。
- `asr.py`：faster-whisper 封装。模型默认多语言 `small`、`device="cpu"`、`compute_type="int8"`、`language="zh"`、`task="transcribe"`；首次按模型名从 Hugging Face Hub 取权重，之后可离线；权重、`config.json`、`preprocessor_config.json`、`tokenizer.json`、`vocabulary.*` 必须齐全才算离线就绪。
- 单进程、无数据库、无队列。手机与电脑同一可达局域网，页面走 HTTPS；首次手机接受自签证书例外，之后直连。

## 契约与字段

`POST /upload` 表单字段：
- `audio`（文件，必需）：`MediaRecorder` 产物，`audio/webm;codecs=opus` 优先，退回浏览器默认；电脑端只依赖 PyAV 能解码的一种真实格式。
- `filename`（字符串，可选）：仅供日志，不参与解析。

返回 JSON：
- `ok=true` 时：`{"ok": true, "text": "中文转写文字", "duration_ms": 整数}`。
- `ok=false` 时：`{"ok": false, "error": "可读原因", "stage": "decode|asr|internal"}`，HTTP 状态 4xx/5xx 择一但不抛栈给页面。

## 验收（逐条可验）

1. 手机打开 `https://电脑局域网IP:端口/`，首次经证书信任后正常加载页面。
2. 授权麦克风后录一段中文语音，点停止/发送，请求到达电脑，完整音频落盘且字节数与上传一致。
3. 电脑本机 CPU int8 转写完成，页面显示中文文字；「复制」能把整段文字放进剪贴板。
4. 断网或转写失败时，页面显示可读错误与「重试」，重试复用已录音频并重新请求。
5. 全程无第三方转写 API 调用，音频不出本机。

## 留待实测（不阻塞开工，实施首轮逐项记录）

- 真实手机录音工具/浏览器的实际 `mimeType` 与容器，以及 PyAV 对该样本的可解码性。
- 自签证书在用户手机的信任流程；证书要与访问地址相符。
- 一段典型中文语音的端到端等待时间；不预设秒数，只记录实测。

## 依赖与启动前提

- Windows x64、Python 3.12（当前环境已具备）；pip 安装 `faster-whisper`、`ctranslate2`、`av`、`huggingface-hub`、`tokenizers`、`onnxruntime`、`tqdm`。
- 系统已装 `ffmpeg`（非强制，PyAV wheel 内含 FFmpeg）。
- 首次联网取模型；防火墙放行监听端口的入站局域网流量。

## 无 App 的边界说明

本规格只是手机浏览器访问电脑页面，不打包、不分发任何安卓安装包；页面与接收点在电脑上，手机不安装任何应用。

## 进度
