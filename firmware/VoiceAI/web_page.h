// Small fallback chat page served by the ESP32 at http://<esp-ip>/
// The full experience (voice input, token dashboard, chats) is the phone app.
// Note: browsers only allow the microphone on https pages, so voice input is
// in the app, not here.
#pragma once

const char WEB_PAGE[] PROGMEM = R"HTML(<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>VoiceAI</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}
body{margin:0;font:16px system-ui,sans-serif;background:#141414;color:#eee;display:flex;flex-direction:column;height:100vh}
header{padding:14px 16px;border-bottom:1px solid #2a2a2a;display:flex;justify-content:space-between;align-items:center}
header b{font-size:17px}#st{font-size:12px;color:#999}
#log{flex:1;overflow:auto;padding:16px;display:flex;flex-direction:column;gap:12px}
.m{max-width:85%;padding:10px 14px;border-radius:16px;white-space:pre-wrap;line-height:1.45}
.u{align-self:flex-end;background:#d97757;color:#fff}.a{align-self:flex-start;background:#262626}
.t{font-size:11px;color:#888;margin-top:6px}
form{display:flex;gap:8px;padding:12px;border-top:1px solid #2a2a2a}
input{flex:1;padding:12px 14px;border-radius:22px;border:1px solid #333;background:#1e1e1e;color:#eee;font-size:16px}
button{border:0;border-radius:22px;padding:0 18px;background:#d97757;color:#fff;font-weight:600}
</style></head><body>
<header><b>VoiceAI</b><span id="st">...</span></header>
<div id="log"></div>
<form id="f"><input id="q" placeholder="Ask something..." autocomplete="off"><button>Send</button></form>
<script>
const log=document.getElementById('log'),q=document.getElementById('q'),st=document.getElementById('st');
let hist=[];
function add(t,c){const d=document.createElement('div');d.className='m '+c;d.textContent=t;log.appendChild(d);log.scrollTop=1e9;return d}
fetch('/api/info').then(r=>r.json()).then(i=>st.textContent=i.config.model+(i.ollama_online?' · online':' · Ollama offline'));
document.getElementById('f').onsubmit=async e=>{e.preventDefault();const t=q.value.trim();if(!t)return;q.value='';
 add(t,'u');hist.push({role:'user',content:t});const b=add('Thinking...','a');
 const r=await fetch('/api/ask',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({messages:hist.slice(-8),speak:true})});
 if(!r.ok){b.textContent=(await r.json()).error;return}const {id}=await r.json();
 for(let n=0;n<240;n++){await new Promise(s=>setTimeout(s,500));const s=await (await fetch('/api/status')).json();
  if(s.done_id>=id){if(s.error){b.textContent='Error: '+s.error;return}
   b.textContent=s.reply;hist.push({role:'assistant',content:s.reply});const u=s.usage,m=document.createElement('div');m.className='t';
   m.textContent=`${u.prompt_tokens} in · ${u.completion_tokens} out · ${u.tokens_per_sec} tok/s · ${(u.total_ms/1000).toFixed(1)}s`;b.appendChild(m);return}}
 b.textContent='Timed out';}
</script></body></html>)HTML";
