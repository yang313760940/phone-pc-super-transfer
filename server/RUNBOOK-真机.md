# 真机使用与验收记录（实施票 #04）

## 一、电脑侧已就绪（本次已验证）

- 证书：`server/certs/cert.pem`、`key.pem`，自签，SAN 含 `192.168.31.61`、`127.0.0.1`、`localhost`、主机名。
  重新生成：`python server/make_cert.py 192.168.31.61`（换 Wi-Fi/IP 后要重生成）。
- HTTPS 入口：`python server/run_https.py`，默认 `0.0.0.0:8443`，已接 `asr.py`：
  - 真实中文 WAV 经局域网 IP 上传 → `{"ok":true,"text":"今天下午三点开会,请把手机语音转成文字发给我。","duration_ms":1619}`
  - 损坏文件 → HTTP 422，`{"ok":false,"stage":"decode",...}`
  - 无第三方转写请求，音频仅落本机 `server/uploads/` 临时文件（可随时清理）。

## 二、真机步骤（手机与电脑连同一 Wi-Fi）

1. 电脑运行：`python server/run_https.py`，记下打印的地址（本例 `https://192.168.31.61:8443/`）。
2. 手机浏览器打开该地址；首次提示证书不受信任 → 点「高级/继续前往」或按系统指引信任一次。
3. 页面点「开始录音」→ 授权麦克风 → 录一段中文 → 点「停止并发送」。
4. 结果区显示中文文字，点「复制」。失败会显示原因与「重试」，重试复用同一段音频。

## 三、待真机确认/处理

- **Windows 防火墙入站**：当前会话非管理员，无法添加 8443 入站规则（`拒绝访问/requires elevation`）。
  若手机打不开页面（一直转圈或连接超时），用管理员 PowerShell 执行一次：
  ```
  netsh advfirewall firewall add rule name="PhoneVoice-MVP-8443" dir=in action=allow protocol=TCP localport=8443 profile=private
  ```
  或在该弹出的「Windows 安全警报」中点「允许访问」。
- **证书信任**：自签证书需手机手动接受；SAN 必须包含手机实际访问的 IP，换网络后要重新生成证书。
- **录音 mimeType**：页面已把真实 `mimeType` 写入 `window.__lastRecordedMime` 并在控制台 `console.log`，
  真机打开开发者工具或页面「查看源码→控制台」可读到（如 `audio/webm;codecs=opus` 或 `audio/mp4`）。
- **端到端耗时**：电脑侧 6.1 秒中文 WAV 约 1.6 秒出字；真机与真实口音、噪声下的准确率与耗时代实测记录。

## 四、清理

- 临时上传：`server/uploads/` 可随时删除，不会累积。
- 模型缓存：`C:\Users\<用户>\.cache\huggingface\hub\models--Systran--faster-whisper-small`，离线复用。
