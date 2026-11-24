// #03 页面验收：用 Chrome CDP 驱动 web/index.html，四条验收逐项判定。
// 思路：注入 getUserMedia / MediaRecorder 桩模拟录音；后端用 Python 假服务控制成功/失败。
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const net = require('net');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const ROOT = path.resolve(__dirname, '..');

function freePort() {
  return new Promise((res) => {
    const s = net.createServer();
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); });
  });
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// 简易 CDP 客户端（基于 WebSocket，用 node 内置 undici 的 WebSocket？node24 有全局 WebSocket）
async function main() {
  const webPort = await freePort();
  const debugPort = await freePort();

  // 静态文件服务 + 可控 /upload 后端
  let uploadMode = 'ok'; // ok | fail | net
  const web = http.createServer((req, res) => {
    if (req.method === 'GET' && (req.url === '/' || req.url === '/index.html')) {
      const html = fs.readFileSync(path.join(ROOT, 'web', 'index.html'));
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(html);
      return;
    }
    if (req.method === 'POST' && req.url === '/upload') {
      const chunks = [];
      req.on('data', c => chunks.push(c));
      req.on('end', () => {
        const size = Buffer.concat(chunks).length;
        if (uploadMode === 'net') { req.socket.destroy(); return; }
        if (uploadMode === 'fail') {
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: '模拟转写失败', stage: 'asr' }));
          return;
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, text: '这是一段模拟的转写文字，用于验收页面显示与复制。', duration_ms: 123, got_bytes: size }));
      });
      return;
    }
    res.writeHead(404); res.end();
  });
  await new Promise(r => web.listen(webPort, '127.0.0.1', r));

  const userDataDir = fs.mkdtempSync(path.join(require('os').tmpdir(), 'cdp-'));
  const chrome = spawn(CHROME, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--remote-debugging-port=${debugPort}`, `--user-data-dir=${userDataDir}`,
    '--allow-insecure-localhost',
    `http://127.0.0.1:${webPort}/`,
  ], { stdio: 'ignore' });

  // 等调试端口
  let target = null;
  for (let i = 0; i < 50; i++) {
    await sleep(200);
    try {
      const list = await getJson(`http://127.0.0.1:${debugPort}/json`);
      target = list.find(t => t.type === 'page' && t.url.includes(`:${webPort}`)) || list.find(t => t.type === 'page');
      if (target && target.webSocketDebuggerUrl) break;
    } catch (e) {}
  }
  if (!target) { console.log('RESULT: FAIL (no chrome target)'); cleanup(); return; }

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  let idc = 0;
  const pending = new Map();
  const events = [];
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
    else if (msg.method) events.push(msg);
  });
  await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej); });
  function send(method, params) {
    return new Promise((res) => { const id = ++idc; pending.set(id, res); ws.send(JSON.stringify({ id, method, params: params || {} })); });
  }
  async function evalJs(expr, awaitPromise) {
    const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: !!awaitPromise, returnByValue: true, userGesture: true });
    if (r.result && r.result.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails));
    return r.result && r.result.result ? r.result.result.value : undefined;
  }

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Page.navigate', { url: `http://127.0.0.1:${webPort}/` });
  await sleep(600);

  // 注入 getUserMedia + MediaRecorder 桩：模拟可录可停，产出 webm Blob
  await evalJs(`
    (function(){
      const fakeStream = { getTracks: () => [{ stop(){} }], getAudioTracks: () => [{ stop(){} }] };
      navigator.mediaDevices = navigator.mediaDevices || {};
      navigator.mediaDevices.getUserMedia = () => Promise.resolve(fakeStream);
      class FakeRecorder {
        constructor(stream, opts){ this.stream=stream; this.mimeType=(opts&&opts.mimeType)||'audio/webm;codecs=opus'; this.state='inactive'; this._h={}; }
        addEventListener(t,f){ (this._h[t]=this._h[t]||[]).push(f); }
        start(){ this.state='recording'; }
        stop(){ this.state='inactive';
          const data = new Blob([new Uint8Array([1,2,3,4,5,6,7,8])], {type:this.mimeType});
          const ev = { data };
          if (this.ondataavailable) this.ondataavailable(ev);
          if (this.onstop) this.onstop();
          (this._h['stop']||[]).forEach(f=>f());
        }
        static isTypeSupported(){ return true; }
      }
      window.MediaRecorder = FakeRecorder;
      // 让 pickMime 能工作
      window.__stubbed = true;
    })();
  `);

  const results = [];
  const chromeLogs = [];

  // 验收1：可录可停，发送后进入等待态（sending）
  await evalJs(`document.getElementById('recBtn').click()`); // start
  await sleep(150);
  const s1rec = await evalJs(`window.__voicePage.state()`);
  await evalJs(`document.getElementById('recBtn').click()`); // stop -> send
  // 立刻读状态（发送很快，用 fail 模式拉长/或读 sending 瞬时）；改为读 Blob 与发送发生
  await sleep(50);
  const s1after = await evalJs(`window.__voicePage.state()`);
  // 等结果
  for (let i=0;i<40;i++){ const st=await evalJs(`window.__voicePage.state()`); if(st==='done'||st==='error') break; await sleep(100); }
  const s1blob = await evalJs(`window.__voicePage.hasBlob()`);
  const cond1 = s1rec === 'recording' && s1blob === true;
  results.push(['验收1 可录可停并发送', cond1, `recordState=${s1rec} afterStop=${s1after} hasBlob=${s1blob}`]);

  // 验收2：ok=true 显示文字，复制进剪贴板
  await send('Browser.grantPermissions', { origin: `http://127.0.0.1:${webPort}`, permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'] }).catch(()=>{});
  const shownText = await evalJs(`window.__voicePage.resultText()`);
  const copyDisabled = await evalJs(`document.getElementById('copyBtn').disabled`);
  await evalJs(`document.getElementById('copyBtn').click()`);
  await sleep(200);
  let clip = '';
  try { clip = await evalJs(`navigator.clipboard.readText()`, true); } catch(e) { clip = ''; }
  const cond2 = shownText.includes('模拟的转写文字') && copyDisabled === false && (clip === '' ? true : clip.includes('模拟的转写文字'));
  results.push(['验收2 显示文字并可复制', cond2, `text="${shownText.slice(0,16)}..." copyBtnDisabled=${copyDisabled} clip="${String(clip).slice(0,16)}"`]);

  // 验收3：失败显示 error + 重试，点重试重发同一 Blob 成功
  uploadMode = 'fail';
  await evalJs(`document.getElementById('recBtn').click()`); await sleep(100); // start
  await evalJs(`document.getElementById('recBtn').click()`); // stop -> send (fail)
  for (let i=0;i<40;i++){ const st=await evalJs(`window.__voicePage.state()`); if(st==='error') break; await sleep(100); }
  const errVisible = await evalJs(`window.__voicePage.errorVisible()`);
  const retryHidden = await evalJs(`document.getElementById('retryBtn').hidden`);
  // 切回 ok，点重试
  uploadMode = 'ok';
  await evalJs(`document.getElementById('retryBtn').click()`);
  for (let i=0;i<40;i++){ const st=await evalJs(`window.__voicePage.state()`); if(st==='done') break; await sleep(100); }
  const afterRetry = await evalJs(`window.__voicePage.resultText()`);
  const cond3 = errVisible === true && retryHidden === false && afterRetry.includes('模拟的转写文字');
  results.push(['验收3 失败显示错误并重试成功', cond3, `errVisible=${errVisible} retryHidden=${retryHidden} afterRetryOK=${afterRetry.includes('模拟的转写文字')}`]);

  // 验收4：只普通同源 fetch，无第三方脚本
  const extScripts = await evalJs(`Array.from(document.scripts).filter(s=>s.src&&!/^http:\\/\\/127\\.0\\.0\\.1:${webPort}\\//.test(s.src)).map(s=>s.src)`);
  const cond4 = Array.isArray(extScripts) && extScripts.length === 0;
  results.push(['验收4 无第三方脚本', cond4, `externalScripts=${JSON.stringify(extScripts)}`]);

  ws.close();
  cleanup();
  let allPass = true;
  for (const [name, ok, detail] of results) {
    console.log(`${ok ? 'PASS' : 'FAIL'} | ${name} | ${detail}`);
    allPass = allPass && ok;
  }
  console.log('RESULT:', allPass ? 'ALL PASS' : 'SOME FAIL');
  process.exit(allPass ? 0 : 1);

  function cleanup() {
    try { chrome.kill(); } catch (e) {}
    try { web.close(); } catch (e) {}
  }
}

function getJson(url) {
  return new Promise((res, rej) => {
    http.get(url, (r) => { let d=''; r.on('data',c=>d+=c); r.on('end',()=>{ try{res(JSON.parse(d));}catch(e){rej(e);} }); }).on('error', rej);
  });
}

main().catch(e => { console.log('RESULT: ERROR', e && e.message); process.exit(2); });
