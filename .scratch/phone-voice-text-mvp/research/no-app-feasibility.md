# 无专用 App：安卓语音片段到 Windows 中文转写的最短链路

- 调查日期：2026-11-23（Asia/Shanghai，UTC+08:00）；硬件观察时间：2026-11-23T21:32:26+08:00。
- 对应研究票：**调查无 App 语音发送与电脑转写的最短链路**。
- 当前目标：个人自用、一次发送一段中文语音片段，电脑自动进行同语言语音转写，取得可阅读、可复制的转写结果。跨语言翻译、实时边说边出字暂放。
- 本文区分 **文档事实 / 本机观察 / 研究推荐 / 真机待验证**。推荐属于研究结论，尚非用户选定方案。
- 已阅读工作区约定及领域术语；只读观察相关系统环境。此次没有开发入口、启动接收服务、安装依赖、下载模型、执行语音识别或改变连接配置，也没有检查凭据或模型缓存。

## 1. 可供研究票结案的答案

**以一次性设置最少为准，有条件优先考虑「手机已有系统录音 → 浏览器选文件发到电脑 → 电脑本地 CPU int8 中文语音转写」。** 其条件是手机确有可导出文件的现有录音功能、手机到电脑有实际可用的网络路径，并且用户接受所选传输方式。纯粹的同源网页文件上传没有 getUserMedia 的 HTTPS 前提；普通局域网 HTTP 可以承担这一入口，但 HTTP 明文传输与“不经第三方转写”是不同属性。[4], [5], [1]

**若手机认可的 HTTPS 接收入口已经存在，网页直接录音的日常操作更短。** 新建普通 `http://电脑局域网IP:端口` 页面，仅能解决访问和文件上传，不满足现代 getUserMedia 麦克风入口的安全上下文前提。手机上的 `localhost` 是手机自己的回环地址，只有先建立 USB 端口转发等连接，才会把它指向电脑服务。[1], [2], [17], [18]

当前电脑的 Windows x64、Python 3.12.10、i5-13600KF 和约 32 GiB 内存，与 faster-whisper / CTranslate2 的本地 CPU 路径相符；**可把多语言 `small`、CPU、`int8` 列为最初验证候选，而非已跑通或已达某个延迟的承诺**。当前被检查的 Python 环境尚未安装识别依赖。完整模型及 tokenizer 等资产准备好后可在本地离线转写；首次按模型名加载默认从 Hugging Face Hub 获得模型。[7], [9], [10], [11], [12]

## 2. 文档事实：网页录音的真实前提

### 2.1 getUserMedia 与 MediaRecorder 要分开看

- Media Capture and Streams 将 `navigator.mediaDevices` 及 `MediaDevices` 标为 `[SecureContext]`；获取麦克风还取决于用户授予麦克风权限、活动文档和实际设备可用性。[2]
- MediaRecorder 处理的是**已经获得的 MediaStream**，产物是录制数据 Blob；其接口本身在所查 Recording 规范中的声明为 `[Exposed=Window]`，不是“提供一个 MediaRecorder 就自动获得麦克风”。本场景的安全上下文门槛来自前面的 getUserMedia。[2], [3]
- HTTPS 的顶层页面通常符合安全上下文条件；嵌在普通 HTTP 祖先页面里的 HTTPS frame 则另有祖先上下文限制。最简入口应避免引入这类嵌入条件。[1]

### 2.2 四种地址不是同一回事

| 手机上打开的地址 | 文档判断 | 对本 MVP 的含义 |
|---|---|---|
| `http://电脑局域网IP:端口`，例如 RFC1918 私网地址 | 私网 IP 不在 Secure Contexts 的 HTTP 回环例外里 | 网页可达与麦克风入口可用是两回事；适合比较文件上传，不视作直接录音就绪 |
| `http://localhost:端口` 或 `http://127.0.0.1:端口` | 回环来源属于 potentially trustworthy 范围；localhost 名称必须保持回环解析 | 地址指向手机自己，不是当前 Windows 电脑；没有手机侧服务或已建立的转发，就没有通往电脑接收点的路径 |
| `https://可达地址:端口` | HTTPS 来源属于 potentially trustworthy 范围，仍涉及页面上下文及浏览器权限 | 要有真实 HTTPS 服务、手机认可且与访问地址相符的证书，以及可达的接收路径；不是只把 URL 前缀换一下 |
| USB 转发后，手机访问自己的 `http://localhost:设备端口` | 转发保留手机浏览器看到的回环来源 | 电脑服务可经该转发到达；此处可沿用回环例外，但 USB/调试/映射都是额外前提 |

依据：Secure Contexts §3.1、§5.2；Android 的 adb reverse 说明；Chrome 的 Android USB port forwarding 说明。[1], [17], [18]

**电脑浏览器里 localhost 测试成功，并不证明手机能访问同一服务或能录音。** 证书警告后的实际权限行为也应记为真机验证项，而不是把任意自签证书当作已验证的 HTTPS 入口。[1], [2], [18]

### 2.3 HTTPS 与“电脑接收到音频”也不是同一件事

- **直接局域网 HTTPS**：可由电脑提供页面及接收点，音频不必经过第三方；但要准备证书、信任和网络可达性。当前这些条件没有观察或建立。[1]
- **USB adb reverse / Chrome 端口转发**：官方提供这种设备回环到电脑的连接。成本包括电脑侧相关工具、USB 连接、手机开发者调试设置、设备确认及端口映射；日常也依赖连接保持。此次只调查，未操作。当前 PATH 查询未找到 adb。[17], [18]
- **HTTPS 转发**：以 Cloudflare Quick Tunnel 为单一例子，电脑要有 cloudflared、已启动的本地 HTTP 服务以及到第三方网络的连接；临时公网地址会变化，进程停止后失效，官方没有可用性保证。页面与上传共用该入口时，音频即使最终在电脑本地识别，传输仍经过第三方代理。当前 PATH 查询未找到 cloudflared。[19], [20]
- **公网 HTTPS 静态录音页 → 私网 HTTP 接收点**不是自动成立的捷径：还涉及跨源请求、混合内容及浏览器 Local Network Access 行为。Chrome 文档记录 Chrome 142 开始的 LNA 提示与针对部分私网请求的混合内容例外；不等于所有安卓浏览器一致。此 MVP 不把这组额外条件列为默认路线。[21]

两条入口都需要电脑处于工作状态、相应接收处理在运行。局域网方式还要有真实可达的监听地址及允许所需入站流量的网络/Windows 防火墙条件；“同一个 Wi-Fi 名称”本身不是连通性测试。此次没有检测手机连接、监听端口或防火墙配置。[22]

## 3. 文档事实：已有录音后网页选文件上传

### 3.1 普通 HTTP 足够到哪一层

HTML 的 File Upload state 是用户选择文件，再由页面/表单发送文件内容；这一文件选择与普通同源提交路径没有 getUserMedia 的 `[SecureContext]` 门槛。因此，**直接打开电脑的局域网 HTTP 上传页面，再上传已有文件，在平台基础能力层面可行**。浏览器的 HTTP 提示、系统文件提供器、网络访问许可及具体 OEM 行为仍待真机验证。[4], [5]

这里“HTTP 足够”只表示已有文件上传的协议/API 前提，不表示链路已有实现、接收程序已经运行或音频链路已加密。普通 HTTP 没有 HTTPS 提供的传输机密性；是否接受这一点由用户决定。[1], [4]

### 3.2 capture 是提示，不是安卓录音入口保证

- HTML Media Capture 的 `capture` 表示捕获机制及朝向偏好；规范要求是 **SHOULD invoke** 指定捕获类型的 picker，而非每台设备必须给出某个录音 UI。[5]
- 缺省或无效取值采用 implementation-specific 状态；若 MIME 类型没有关联的 capture control，规范要求按没有 capture 属性处理。[5]
- WHATWG 同样将 `accept` 定义为文件类型 **hint**，并举例说 `audio/*` 时浏览器**可以**提供麦克风录一段音频的选项。[4]

所以，“点网页按钮就一定弹系统录音并交回文件”应保留为**真机上可能成立的减操作项**；可靠的候选描述仍是“已有录音文件可以被选中上传”。本研究没有观察该手机的系统录音工具、文件保存位置或文件选择器。

### 3.3 必要的音频兼容条件

- Android 官方平台格式表列有 AAC 与 `.m4a/.mp4/.3gp` 容器、AMR 与 `.amr/.3gp`、较新版本的 Opus/Ogg、PCM/WAVE 等组合；这是平台能力清单，不是该手机录音工具的实际输出承诺。[6]
- 网页 MediaRecorder 的容器和 codec 由浏览器实现及选项决定。可关注 WebM/Opus、MP4/AAC 等候选，但应记录真实 `mimeType`/文件并验证解码。`isTypeSupported` 的肯定结果也不保证具体资源条件下录制成功；所查 2026 Recording 草案还将它标为兼容性保留接口。[3]
- 后缀、MIME、真正容器/codec 是不同层次。HTML 规范明确指出后缀可变、MIME 信息也可能不可靠；只让文件出现在 picker 中，不等于电脑端已经支持它。[4]
- faster-whisper 使用 PyAV 打开容器并解码音轨，然后进行单声道/采样率变换，源码默认采样率为 16 kHz。因此可以先让电脑解码手机的原始文件，**没有必要预先要求用户把每段录音手工变成 WAV**；实际可解码性仍取决于所用 PyAV/FFmpeg 构建及真实样本。[7], [13], [16]

极简验证只需检查用户真实手机输出的一种正常音频格式，而不是先制作全格式转换体系。[6], [13]

## 4. 当前电脑：只读观察

以下均为本会话工具结果，不是官方性能承诺。只查询相关硬件、命令与指定 Python 发行包；没有枚举用户目录或检查账户/凭据。

| 项目 | 简短结果 |
|---|---|
| OS | Microsoft Windows 10 IoT 企业版 LTSC，10.0.19044，64 位 |
| CPU | Intel Core i5-13600KF，14 个核心、20 个逻辑处理器 |
| 内存 | TotalPhysicalMemory 约 31.82 GiB（32 GB 级别） |
| GPU | NVIDIA GeForce RTX 3070 Ti；nvidia-smi 报 8192 MiB 显存，驱动 591.74；另有两个虚拟显示设备 |
| Python | `python --version` 为 3.12.10；标准库指针位宽检查为 64 位 |
| pip | 当前 Python 的 pip 25.0.1 |
| ffmpeg / ffprobe | 命令存在，版本均为 8.0.1；实际音频解码未测试 |
| Python 识别依赖 | 当前 `python` 的 metadata 中，faster-whisper、ctranslate2、av、huggingface-hub 均未安装 |
| adb / cloudflared / uv | 当前 PATH 的 Get-Command 查询未找到；这不是全盘“未安装”扫描 |
| py / python3 | py 启动器可发现；python3 仅发现 WindowsApps 入口，没有测试其启动能力 |

显存采用 nvidia-smi 的专门查询；没有将 Win32_VideoController 的 AdapterRAM 报告值当作整卡显存。

### 已执行的观察命令与结果范围

硬件查询使用下列命令与字段（从实际组合查询中提取，省略变量赋值和 JSON 排版）；输出仅摘要为上表：

```powershell
Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK'
Get-CimInstance Win32_OperatingSystem   # Caption, Version, BuildNumber, OSArchitecture
Get-CimInstance Win32_ComputerSystem  # TotalPhysicalMemory
Get-CimInstance Win32_Processor       # Name, NumberOfCores, NumberOfLogicalProcessors
Get-CimInstance Win32_VideoController # Name, DriverVersion, AdapterRAM
Get-Command python,python3,py,pip,pip3,ffmpeg,ffprobe,adb,nvidia-smi,uv,cloudflared -ErrorAction SilentlyContinue
```

随后仅核对工具/包版本和 GPU 环境：

```powershell
python --version
python -m pip --version
python -c 'import importlib.metadata as m; import sys,struct; print("Python architecture:", struct.calcsize("P")*8); names=("faster-whisper","ctranslate2","av","huggingface-hub");
for name in names:
 try: print(name+": "+m.version(name))
 except m.PackageNotFoundError: print(name+": not installed in this Python")'
ffmpeg -version | Select-Object -First 1
ffprobe -version | Select-Object -First 1
nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader
```

其他虚拟环境、宿主自带 Python、Visual C++ runtime、CUDA/cuDNN 安装状态、模型缓存及 API 凭据均未检查。机器有 NVIDIA 显卡和驱动，**不等于 faster-whisper 的 GPU 路径已就绪**。

## 5. 文档事实：轻量本地中文 ASR 的最少前提

### 5.1 CPU int8 与中文模型

- faster-whisper 官方 README 提供 CPU + `compute_type="int8"` 的使用方式；CTranslate2 量化文档列出 x86-64 CPU 的 int8 支持。因此 GPU 不是本候选路线的必需条件。[7], [10]
- 用多语言模型进行中文语音转写；`small` 等无 `.en` 后缀的模型与英语专用 `.en` 型号要区分。中文语言码是 `zh`，本目标对应 `task="transcribe"` 而不是翻译任务。[14], [15]
- **研究推荐**：就当前硬件和“逐段、个人自用、减少设置”目标，先把多语言 small / CPU int8 作为验证基线；没有在此决定最终模型。准确率、数字/专名、繁简输出及标点表现均待用户真实中文语音片段验证。[7], [15]
- 官方 small CPU benchmark 在另一台 i7-12700K 上执行。其结果不是本机、该手机音频或中文语音片段的测量；本文不将其换算成“几秒出字”。[7]

### 5.2 依赖、首次下载与离线

1. faster-whisper README 声明 Python ≥3.9；CTranslate2 的预编译 wheel 支持 Windows x86-64，并要求 Windows Visual C++ runtime。[7], [9]
2. 项目 requirements 列有 CTranslate2、huggingface_hub、tokenizers、onnxruntime、av、tqdm。现成 CTranslate2 转换模型的推理路径不要求先建立训练/模型转换环境。[8], [7]
3. PyAV 的二进制 wheel 内含 FFmpeg 库，因此 **系统另装 ffmpeg CLI 不是 faster-whisper 解码的强制前提**。本机虽然已有 ffmpeg CLI，它与未来安装的 PyAV 所带 FFmpeg 构建仍是两份环境。[7], [13], [16]
4. 版本下限应看整个组合，而非只看顶层 README：此次所查 PyAV 当前 main README 要求 Python ≥3.12。当前 Python 3.12.10 满足它及上述文档下限；尚未通过实际安装验证具体 Win64 wheel/版本组合。旧 PyAV stable 文档不应冒充最新分支依赖说明。[16], [7]
5. 按 `small` 等模型名创建模型时，默认经 Hugging Face Hub 获得 CTranslate2 权重。首次准备 Python 包及模型需要下载来源可达，或提前取得完整本地文件；这不是把语音上传给 Hugging Face。[7], [11]
6. 后续从完整本地目录加载可进行离线语音转写。完整性包括权重、配置、词表和 `tokenizer.json` 等关联资产；下载器列出的模式包括 `model.bin`、`config.json`、`preprocessor_config.json`、`tokenizer.json`、`vocabulary.*`。仅保留一个权重文件不是完整离线准备。[11], [12]
7. 源码中若本地 tokenizer 缺失，会走 `from_pretrained` 路径；因此即使模型主体已有缓存，也应验证所有资产已本地化。`local_files_only` 是本地缓存加载条件，不是凭空补齐缺失资源。[11], [12]

“离线”在这里指**语音转写无需互联网**。手机与电脑之间的局域网或 USB 连接、电脑接收处理和模型已准备，仍各自是前提；HTTPS 第三方转发则另需联网。[11], [18], [19]

### 5.3 GPU 不是最简路线的隐藏必选项

存在需要保留的第一方资料版本差异：

- faster-whisper README 的 GPU 段列 CUDA 12/cuBLAS、cuDNN 9；CTranslate2 静态安装页还写 cuDNN 8。[7], [9]
- CTranslate2 4.5.0 changelog 记录切换 cuDNN 9；4.6.3 又记录纯 CUDA Conv1d 实现使 cuDNN 成为可选依赖。因此 GPU 依赖应按具体版本/构建核对；混用这些文档句子不足以证明本机已就绪。[23]

这些差异不影响 CPU int8 被列为候选。此次没有安装或测试任何 GPU 识别依赖。[7], [10]

## 6. 只比较必要候选

两条候选共享一次性的电脑接收、ASR 包/模型准备及文字展示条件。下表只比较手机入口和连接层的**额外成本**，不是实施工时估算或已经存在的产品。

日常操作数按主要动作估算，不含说话时长、等待转写、电脑复制、首次权限确认；文件 picker 内部点击可能额外增加。没有真机点击计数。

| 候选 | 一次性额外设置 | 每段主要操作（估算） | 日常连接/联网前提 | 音频经过第三方？ |
|---|---|---|---|---|
| **已有系统录音 → 同源局域网 HTTP 网页选文件上传** | 较少：确认文件可导出/可选、局域网接收可达；无需为录音引入证书或 USB 调试 | 约 6 个动作：打开录音、开始、停止/保存、转到网页、选文件、发送；单独保存/命名可能另加动作 | 手机到电脑有局域网路径；本地 ASR 资源准备后，日常不依赖互联网 | 这条传输/ASR 路线不需要第三方；HTTP 为明文 |
| **同源、手机认可的 HTTPS 网页直接录音 → 电脑本地 ASR** | 若 HTTPS 入口已存在则较少；否则增加证书、信任和服务设置 | 约 4 个动作：打开页面、开始、停止、发送；若以后选择“停止即发送”可合并为 3 个 | HTTPS 接收入口可达、麦克风许可和实际录制/解码可用；直接局域网方式不强制经互联网转发 | 直接连电脑时不需要第三方 |
| 网页直接录音，使用 USB 回环转发解决连接 | 另有工具、调试、设备确认及端口映射 | 网页约 4 个动作，另有接线/恢复映射成本 | USB 和有效转发保持；不强制依赖互联网 | 转发本身不经第三方 |
| 网页直接录音，使用 HTTPS 第三方转发解决入口 | 另有电脑转发程序、临时地址及持续网络连接 | 网页约 4 个动作，地址变更后可能重开入口 | 手机、电脑和代理网络均可达，电脑转发进程在运行 | **经过第三方代理，即使 ASR 留在电脑** |

前三行依据 [1], [4], [5], [17], [18]；第三方转发行依据 [19], [20]；操作数是研究估算，不是规范承诺。

系统录音功能若自行启用云同步，其行为不由上述本地接收/ASR 路线保证；这同样属于真实手机待核对项。

外部语音转写 API 在此仅记录一般前提：**需要用户提供凭据且音频离开本机**；未检查凭据、测试 API 或展开供应商比较。第一方文件转写示例可见文件上传和 Bearer 凭据要求。[24]

## 7. 研究推荐与剩余的人类选择/真机验证

### 推荐（尚待用户选择）

- 若主要追求**首次设置最少**，且手机已经有可导出文件的录音功能、实际使用范围允许手机直连电脑：优先考虑已有录音后网页选文件上传，再在电脑本地 CPU int8 转写。
- 若主要追求**日常少切换/少操作**，且手机认可的 HTTPS 接收入口已存在或用户愿意承担其初始成本：网页直接录音更合适。
- `capture` 若在真机上能直接唤起现有录音工具，可作为文件上传入口的省步骤机会；先不把它设成唯一入口。
- USB 转发、第三方 HTTPS 转发、GPU 加速均是有具体前提的备选成本，不是极简 MVP 默认同时引入的组件。

上述排序基于已查事实；没有替用户确认连接范围、HTTP 明文/第三方代理的取舍、最终模型或实际发送操作方式。

### 仍应真实验证的最小项目

1. **连接范围**：仅在电脑附近/同一可达局域网使用，还是手机移动网络也要使用；当前用户尚未选择。真实路由、监听地址和防火墙可达性未观察。
2. **手机入口**：现有录音工具是否存在、是否给出可选/可导出的文件；浏览器版本、文件选择器及 `capture` 的实际 UI 和 HTTP 提示。
3. **直接录音前提**：真实手机打开目标地址时的安全上下文、首次麦克风许可、起止录制及产物 MIME/codec；不是用电脑 localhost 测试替代。
4. **一个真实文件**：确认完整上传后可被所选 PyAV 解码；重点检查手机实际格式，而不是先支持所有平台格式。
5. **本机中文识别**：安装/模型准备之后再验证真实中文、数字/专名、标点、繁简输出，以及首次加载和后续转写的实际端到端等待时间。本研究没有执行这些测量。
6. **结果与隐私取舍**：电脑端取得可阅读、可复制的文字；自动写入剪贴板暂不额外假定。用户对明文局域网传输、代理经第三方及录音功能云同步的实际选择仍由用户本人确认。

## 官方来源

全部于 2026-11-23 查阅；主分支源码与 living docs 代表查阅当日所见，不等于本机已安装版本。

- 浏览器规范：[Secure Contexts][1]、[Media Capture and Streams][2]、[MediaStream Recording][3]、[HTML 文件上传][4]、[HTML Media Capture][5]。
- 手机与连接：[Android 音频格式][6]、[Chrome USB 转发][17]、[Android adb reverse][18]、[Chrome Local Network Access][21]、[Windows Firewall 入站规则][22]。
- 本地 ASR：[faster-whisper README][7]、[运行依赖][8]、[CTranslate2 安装][9]、[CTranslate2 量化][10]、[中文语言码/任务][14]、[Whisper 模型语言][15]。
- 离线与解码：[下载器][11]、[本地模型加载][12]、[音频解码][13]、[PyAV 当前 README][16]。
- GPU 资料差异：[CTranslate2 changelog][23]。
- 第三方转发：[Cloudflare Quick Tunnels][19]、[代理连接路径][20]。
- 外部 API 一般前提：[第一方文件转写示例][24]。

[1]: https://www.w3.org/TR/secure-contexts/#is-origin-trustworthy "W3C Secure Contexts：回环、HTTPS 与 localhost 条件"
[2]: https://www.w3.org/TR/mediacapture-streams/#dom-mediadevices-getusermedia "W3C Media Capture and Streams：SecureContext、麦克风权限"
[3]: https://www.w3.org/TR/mediastream-recording/ "W3C MediaStream Recording：Blob、容器/codec、能力查询"
[4]: https://html.spec.whatwg.org/multipage/input.html#file-upload-state-(type=file) "WHATWG HTML：File Upload state、accept hint"
[5]: https://www.w3.org/TR/html-media-capture/#the-capture-attribute "W3C HTML Media Capture：SHOULD 与 implementation-specific"
[6]: https://developer.android.com/media/platform/supported-formats "Android 官方平台音频格式表"
[7]: https://github.com/SYSTRAN/faster-whisper/blob/master/README.md "faster-whisper 官方 README"
[8]: https://github.com/SYSTRAN/faster-whisper/blob/master/requirements.txt "faster-whisper 官方运行依赖"
[9]: https://opennmt.net/CTranslate2/installation.html "CTranslate2 官方安装文档"
[10]: https://opennmt.net/CTranslate2/quantization.html "CTranslate2 官方量化/CPU int8 文档"
[11]: https://github.com/SYSTRAN/faster-whisper/blob/master/faster_whisper/utils.py "官方模型下载器与本地缓存条件"
[12]: https://github.com/SYSTRAN/faster-whisper/blob/master/faster_whisper/transcribe.py "官方本地模型加载及 tokenizer fallback"
[13]: https://github.com/SYSTRAN/faster-whisper/blob/master/faster_whisper/audio.py "官方 PyAV 解码与重采样实现"
[14]: https://github.com/SYSTRAN/faster-whisper/blob/master/faster_whisper/tokenizer.py "官方语言码 zh 及 transcribe 任务"
[15]: https://github.com/openai/whisper/blob/main/README.md#available-models-and-languages "Whisper 官方多语言与英语专用模型说明"
[16]: https://github.com/PyAV-Org/PyAV/blob/main/README.md "PyAV 当前官方 README：Python 版本与内含 FFmpeg 的 wheel"
[17]: https://developer.chrome.com/docs/devtools/remote-debugging/local-server#usb-port-forwarding "Chrome 官方 Android USB port forwarding"
[18]: https://developer.android.com/develop/ui/views/layout/webapps/access-local-server "Android 官方 adb reverse 与设备 localhost"
[19]: https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/ "Cloudflare 官方 Quick Tunnels 前提和临时地址"
[20]: https://developers.cloudflare.com/tunnel/concepts/ "Cloudflare 官方代理连接路径"
[21]: https://developer.chrome.com/blog/local-network-access "Chrome 官方 Local Network Access 与混合内容例外"
[22]: https://learn.microsoft.com/en-us/windows/security/operating-system-security/network-security/windows-firewall/rules "Microsoft 官方 Windows Firewall 入站规则"
[23]: https://github.com/OpenNMT/CTranslate2/blob/master/CHANGELOG.md "CTranslate2 官方 4.5.0 / 4.6.3 GPU 依赖变更"
[24]: https://developers.openai.com/api/docs/guides/speech-to-text "第一方文件转写示例：上传音频与 Bearer 凭据"
