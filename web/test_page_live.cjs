// #03 与真实后端联调：页面打 server/app.py 的真实 /upload（占位转写）。
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const net = require('net');
const os = require('os');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const ROOT = path.resolve(__dirname, '..');
const PY = 'python';

const freePort = () => new Promise(r => { const s=net.createServer(); s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>r(p));}); });
const sleep = ms => new Promise(r=>setTimeout(r,ms));
const getJson = url => new Promise((res,rej)=>http.get(url,r=>{let d='';r.on('data',c=>d+=c);r.on('end',()=>{try{res(JSON.parse(d));}catch(e){rej(e);}});}).on('error',rej));

async function main(){
  const appPort = await freePort();
  const debugPort = await freePort();
  // 真实后端：server/app.py，占位转写，存储放临时目录
  const env = Object.assign({}, process.env, { PORT: String(appPort), VOICE_STORE_DIR: fs.mkdtempSync(path.join(os.tmpdir(),'voice-store-')) });
  const py = spawn(PY, [path.join(ROOT,'server','app.py')], { cwd: ROOT, env, stdio: 'ignore' });
  for (let i=0;i<50;i++){ await sleep(150); try{ const r=await getJson(`http://127.0.0.1:${appPort}/healthz`); if(r.ok) break; }catch(e){} }

  const userDir = fs.mkdtempSync(path.join(os.tmpdir(),'cdp2-'));
  const chrome = spawn(CHROME, ['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check',
    `--remote-debugging-port=${debugPort}`, `--user-data-dir=${userDir}`, `http://127.0.0.1:${appPort}/`], { stdio:'ignore' });

  let target=null;
  for(let i=0;i<50;i++){ await sleep(200); try{ const l=await getJson(`http://127.0.0.1:${debugPort}/json`); target=l.find(t=>t.type==='page'&&t.url.includes(':'+appPort)); if(target&&target.webSocketDebuggerUrl)break; }catch(e){} }
  if(!target){ console.log('RESULT: FAIL no target'); cleanup(); return; }
  const ws=new WebSocket(target.webSocketDebuggerUrl); let idc=0; const pend=new Map();
  ws.addEventListener('message',ev=>{const m=JSON.parse(ev.data); if(m.id&&pend.has(m.id)){pend.get(m.id)(m);pend.delete(m.id);}});
  await new Promise((res,rej)=>{ws.addEventListener('open',res);ws.addEventListener('error',rej);});
  const send=(method,params)=>new Promise(res=>{const id=++idc;pend.set(id,res);ws.send(JSON.stringify({id,method,params:params||{}}));});
  const evalJs=async(e,aw)=>{const r=await send('Runtime.evaluate',{expression:e,awaitPromise:!!aw,returnByValue:true,userGesture:true});return r.result&&r.result.result?r.result.result.value:undefined;};
  await send('Page.enable'); await send('Runtime.enable');
  await send('Page.navigate',{url:`http://127.0.0.1:${appPort}/`}); await sleep(600);

  // 桩录音，走真实 /upload
  await evalJs(`(function(){const fs={getTracks:()=>[{stop(){}}]};navigator.mediaDevices=navigator.mediaDevices||{};navigator.mediaDevices.getUserMedia=()=>Promise.resolve(fs);
    class R{constructor(s,o){this.mimeType=(o&&o.mimeType)||'audio/webm;codecs=opus';this.state='inactive';this._h={};}addEventListener(t,f){(this._h[t]=this._h[t]||[]).push(f);}start(){this.state='recording';}
    stop(){this.state='inactive';const d=new Blob([new Uint8Array([9,8,7,6,5,4,3,2,1])],{type:this.mimeType});if(this.ondataavailable)this.ondataavailable({data:d});if(this.onstop)this.onstop();}static isTypeSupported(){return true;}}
    window.MediaRecorder=R;})();`);

  await evalJs(`document.getElementById('recBtn').click()`); await sleep(120);
  await evalJs(`document.getElementById('recBtn').click()`);
  for(let i=0;i<40;i++){const st=await evalJs(`window.__voicePage.state()`);if(st==='done'||st==='error')break;await sleep(100);}
  const st=await evalJs(`window.__voicePage.state()`);
  const text=await evalJs(`window.__voicePage.resultText()`);
  // 占位转写返回空 text，页面显示“（空）”；真实后端 ok=true
  const ok = st==='done' && text==='（空）';
  console.log(`INFO state=${st} text="${text}"`);
  console.log('RESULT:', ok?'ALL PASS (页面已打通真实 /upload)':'SOME FAIL');

  ws.close(); cleanup();
  process.exit(ok?0:1);
  function cleanup(){ try{chrome.kill();}catch(e){} try{py.kill();}catch(e){} }
}
main().catch(e=>{console.log('RESULT: ERROR',e&&e.message);process.exit(2);});
