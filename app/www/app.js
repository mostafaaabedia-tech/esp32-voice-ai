/* =============================================================================
 *  VoiceAI phone app
 *  Chat with your local Qwen model through the ESP32, speak with your voice,
 *  hear the answer on the PAM8406 speaker, and track token usage.
 *  Plain JavaScript, no build step. Runs in the Android app (Capacitor) and in
 *  any browser (voice input in a browser needs https or localhost).
 * ========================================================================== */
'use strict';

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const CAP = window.Capacitor;
const isNative = !!(CAP && CAP.isNativePlatform && CAP.isNativePlatform());
// Native plugins have to be registered by name when there is no bundler.
const reg = (n) => (CAP.Plugins && CAP.Plugins[n]) || CAP.registerPlugin(n);
const Plugins = isNative
  ? { SpeechRecognition: reg('SpeechRecognition'), TextToSpeech: reg('TextToSpeech'), App: reg('App'), CapacitorHttp: reg('CapacitorHttp') }
  : {};

/* ------------------------------------------------------------- storage --- */
const store = {
  get(k, d) { try { const v = localStorage.getItem('vai.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('vai.' + k, JSON.stringify(v)); } catch { /* full or blocked */ } },
};

const settings = Object.assign(
  { host: '', demo: false, route: 'esp', lang: 'en-US', autosend: true, history: 8 },
  store.get('settings', {})
);
let chats = store.get('chats', []);      // [{id,title,created,updated,messages:[]}]
let usageLog = store.get('usage', []);   // [{ts,chatId,model,prompt,completion,tps,ms}]
let currentId = store.get('current', null);
let device = null;                       // last /api/info answer
let deviceOk = false;
let busy = false;

const saveSettings = () => store.set('settings', settings);
const saveChats = () => { store.set('chats', chats.slice(0, 60)); store.set('current', currentId); };
const saveUsage = () => store.set('usage', usageLog.slice(-1000));

/* ---------------------------------------------------------------- utils --- */
const fmt = (n) => Math.round(n || 0).toLocaleString('en-US');
const fmtSec = (ms) => (ms / 1000).toFixed(ms < 10000 ? 1 : 0) + 's';
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
function esc(s) { return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }

let toastTimer;
function toast(msg, ms = 2600) {
  const t = $('#toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), ms);
}

// Tiny, safe markdown: code blocks, inline code, bold, italic, lists, paragraphs
function md(src) {
  const blocks = [];
  let s = esc(src).replace(/```[a-z]*\n?([\s\S]*?)```/g, (_, code) => { blocks.push(code); return `\u0000${blocks.length - 1}\u0000`; });
  s = s.replace(/`([^`\n]+)`/g, '<code>$1</code>')
       .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
       .replace(/(^|[\s(])\*([^*\n]+)\*/g, '$1<i>$2</i>');
  const out = [];
  let list = null;
  for (const line of s.split('\n')) {
    const ul = line.match(/^\s*[-*•]\s+(.*)/);
    const ol = line.match(/^\s*\d+[.)]\s+(.*)/);
    if (ul || ol) {
      const type = ul ? 'ul' : 'ol';
      if (!list || list.type !== type) { if (list) out.push(`</${list.type}>`); out.push(`<${type}>`); list = { type }; }
      out.push(`<li>${(ul || ol)[1]}</li>`);
      continue;
    }
    if (list) { out.push(`</${list.type}>`); list = null; }
    if (line.trim()) out.push(`<p>${line}</p>`);
  }
  if (list) out.push(`</${list.type}>`);
  return out.join('').replace(/<p>\u0000(\d+)\u0000<\/p>|\u0000(\d+)\u0000/g, (_, a, b) => `<pre><code>${blocks[a ?? b]}</code></pre>`);
}
const plain = (s) => s.replace(/```[\s\S]*?```/g, ' ').replace(/[*_#`>|]/g, '').replace(/\s+/g, ' ').trim();

/* ----------------------------------------------------------------- http --- */
async function http(method, path, body, timeout = 8000) {
  if (settings.demo) return demo(method, path, body);
  if (!settings.host) throw new Error('Add your ESP32 address in Settings first');
  const base = (/^https?:\/\//.test(settings.host) ? settings.host : 'http://' + settings.host).replace(/\/+$/, '');
  const url = base + path;

  if (isNative && Plugins.CapacitorHttp) {
    try {
      const r = await Plugins.CapacitorHttp.request({
        url, method, data: body, headers: { 'Content-Type': 'application/json' },
        connectTimeout: timeout, readTimeout: timeout,
      });
      let data = r.data;
      if (typeof data === 'string') { try { data = JSON.parse(data); } catch { /* keep text */ } }
      return { status: r.status, data };
    } catch (e) {
      throw new Error(`Can't reach the ESP32 at ${settings.host}`);
    }
  }

  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeout);
  try {
    const r = await fetch(url, {
      method, signal: ctl.signal,
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
    let data = null;
    try { data = await r.json(); } catch { /* not json */ }
    return { status: r.status, data };
  } catch (e) {
    throw new Error(e.name === 'AbortError' ? 'The ESP32 is not responding' : `Can't reach the ESP32 at ${settings.host}`);
  } finally {
    clearTimeout(timer);
  }
}

/* --------------------------------------------------------- demo device --- */
const demoDev = { id: 0, done: 0, state: 'idle', reply: '', usage: {}, start: 0, pending: null, session: { requests: 0, prompt_tokens: 0, completion_tokens: 0 },
  config: { ollama_host: '192.168.1.189', ollama_port: 11434, model: 'qwen2.5:1.5b', system_prompt: 'You are a helpful voice assistant running on a small ESP32 speaker. Answer clearly and briefly in plain sentences.', max_words: 40, num_predict: 160, num_ctx: 2048, voice_mode: 0, tts_port: 5050, volume: 2 } };
const demoAnswers = [
  [/joke/i, 'Why did the robot go on holiday? Because it needed to recharge its batteries!'],
  [/space|planet|star/i, 'A day on Venus is longer than its whole year. It spins so slowly that it orbits the Sun before finishing one turn.'],
  [/word/i, 'Word of the day: **resilient**. It means able to recover quickly from problems. Example: plants in a good greenhouse are more resilient.'],
  [/greenhouse/i, 'A greenhouse lets sunlight in through glass or plastic. The light warms the soil and air inside, and the walls trap that heat so it cannot escape quickly.'],
  [/hello|hi\b|salam/i, 'Hello! I am your local voice assistant. Ask me anything and I will answer on the speaker.'],
];
async function demo(method, path, body) {
  await sleep(120);
  const d = demoDev;
  if (d.pending && Date.now() > d.start + d.pending.delay) {
    d.done = d.id; d.reply = d.pending.reply; d.usage = d.pending.usage; d.pending = null;
    d.session.requests++; d.session.prompt_tokens += d.usage.prompt_tokens; d.session.completion_tokens += d.usage.completion_tokens;
    d.state = 'speaking'; setTimeout(() => (d.state = 'idle'), 2500);
  }
  if (path === '/api/info') return { status: 200, data: { name: 'voiceai', fw: 'demo', ip: 'demo', rssi: -52, heap: 182344, uptime_s: Math.round(performance.now() / 1000), state: d.state, ollama_online: true, models: ['qwen2.5:1.5b', 'qwen2.5:0.5b', 'llama3.2:1b'], config: d.config, session: d.session } };
  if (path === '/api/status') return { status: 200, data: { id: d.id, done_id: d.done, state: d.pending ? 'thinking' : d.state, reply: d.reply, usage: d.usage } };
  if (path === '/api/ask') {
    const last = body.messages[body.messages.length - 1].content;
    const reply = (demoAnswers.find(([re]) => re.test(last)) || [0, `This is demo mode, so I am not really thinking. Connect the ESP32 in Settings and I will ask ${d.config.model} about: "${last.slice(0, 60)}".`])[1];
    const promptTok = 38 + Math.round(body.messages.reduce((a, m) => a + m.content.length, 0) / 4);
    const outTok = Math.round(reply.split(/\s+/).length * 1.35);
    const tps = 16 + Math.random() * 10;
    const gen = (outTok / tps) * 1000;
    const total = gen + 250 + Math.random() * 400;
    d.id++; d.start = Date.now();
    d.pending = { delay: total, reply, usage: { prompt_tokens: promptTok, completion_tokens: outTok, total_tokens: promptTok + outTok, tokens_per_sec: +tps.toFixed(1), total_ms: Math.round(total), load_ms: 0, prompt_ms: 180, gen_ms: Math.round(gen), num_ctx: d.config.num_ctx } };
    return { status: 202, data: { id: d.id } };
  }
  if (path === '/api/config') { Object.assign(d.config, body); return { status: 200, data: { ok: true } }; }
  if (path === '/api/speak') { d.state = 'speaking'; setTimeout(() => (d.state = 'idle'), 2000); return { status: 200, data: { ok: true } }; }
  if (path === '/api/stop') { d.state = 'idle'; return { status: 200, data: { ok: true } }; }
  return { status: 404, data: { error: 'not found' } };
}

/* ---------------------------------------------------------------- chats --- */
const currentChat = () => chats.find((c) => c.id === currentId) || null;
function ensureChat() {
  let c = currentChat();
  if (!c) {
    c = { id: uid(), title: 'New chat', created: Date.now(), updated: Date.now(), messages: [] };
    chats.unshift(c);
    currentId = c.id;
  }
  return c;
}
function makeTitle(text) {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length > 38 ? t.slice(0, 36).replace(/\s\S*$/, '') + '…' : t;
}
function newChat() {
  if (busy) return toast('Wait for the answer to finish');
  currentId = null;
  saveChats();
  renderChat();
  renderChatList();
  closeDrawer();
  $('#input').focus();
}
function chatTokens(c) {
  return c.messages.reduce((a, m) => a + (m.usage ? m.usage.prompt + m.usage.completion : 0), 0);
}

/* -------------------------------------------------------------- render --- */
const ICON = {
  copy: '<svg viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h8"/></svg>',
  speaker: '<svg viewBox="0 0 24 24"><path d="M11 5L6 9H3v6h3l5 4z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13"/></svg>',
  phone: '<svg viewBox="0 0 24 24"><rect x="6" y="2" width="12" height="20" rx="3"/><path d="M11 18h2"/></svg>',
  redo: '<svg viewBox="0 0 24 24"><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/></svg>',
  off: '<svg viewBox="0 0 24 24"><path d="M11 5L6 9H3v6h3l5 4zM22 9l-6 6M16 9l6 6"/></svg>',
  both: '<svg viewBox="0 0 24 24"><path d="M9 5L5 8H2v6h3l4 3z"/><rect x="15" y="4" width="7" height="14" rx="2"/></svg>',
};
const ROUTES = {
  esp:   { label: 'Speaker', icon: ICON.speaker },
  phone: { label: 'Phone', icon: ICON.phone },
  both:  { label: 'Speaker + phone', icon: ICON.both },
  off:   { label: 'Silent', icon: ICON.off },
};

function renderChat() {
  const box = $('#messages');
  $$('.msg', box).forEach((n) => n.remove());
  const c = currentChat();
  $('#empty').hidden = !!(c && c.messages.length);
  if (c) c.messages.forEach((m, i) => box.appendChild(m.role === 'user' ? userNode(m) : aiNode(m, i === c.messages.length - 1)));
  updateCtx();
  scrollDown(false);
}

function userNode(m) {
  const d = document.createElement('div');
  d.className = 'msg user';
  d.innerHTML = `<div class="bubble">${esc(m.content)}</div>`;
  return d;
}

function aiNode(m, isLast) {
  const d = document.createElement('div');
  d.className = 'msg ai';
  const u = m.usage;
  d.innerHTML = `
    <div class="ai-head"><span class="logo"></span>${esc(m.model || 'AI')}</div>
    <div class="ai-body">${md(m.content)}</div>
    <div class="msg-foot">
      <button class="act" data-act="copy" title="Copy">${ICON.copy}</button>
      <button class="act" data-act="esp" title="Play on speaker">${ICON.speaker}</button>
      <button class="act" data-act="phone" title="Read on phone">${ICON.phone}</button>
      ${isLast ? `<button class="act" data-act="redo" title="Regenerate">${ICON.redo}</button>` : ''}
      ${u ? `<button class="usage-chip" data-act="usage"><span><b>${fmt(u.prompt)}</b> in</span><span><b>${fmt(u.completion)}</b> out</span><span>${u.tps.toFixed(1)} tok/s</span><span>${fmtSec(u.ms)}</span></button>` : ''}
    </div>`;
  d._msg = m;
  return d;
}

function thinkingNode() {
  const d = document.createElement('div');
  d.className = 'msg ai';
  d.innerHTML = `<div class="ai-head"><span class="logo"></span>${esc(modelName())}</div>
    <div class="thinking"><span class="dots"><i></i><i></i><i></i></span><span class="tt">Thinking</span></div>`;
  return d;
}

function errorNode(text, onRetry) {
  const d = document.createElement('div');
  d.className = 'msg ai';
  d.innerHTML = `<div class="ai-head"><span class="logo"></span>Error</div><div class="ai-body error">${esc(text)}</div>
    <button class="pill-btn small ghost retry">Try again</button>`;
  $('.retry', d).onclick = () => { d.remove(); onRetry(); };
  return d;
}

function scrollDown(smooth = true) {
  const box = $('#messages');
  box.style.scrollBehavior = smooth ? 'smooth' : 'auto';
  box.scrollTop = box.scrollHeight;
}

function renderChatList() {
  const list = $('#chat-list');
  if (!chats.length) { list.innerHTML = '<div class="muted-empty">No chats yet</div>'; return; }
  list.innerHTML = chats.map((c) => `
    <div class="chat-item ${c.id === currentId ? 'active' : ''}" data-id="${c.id}">
      <span class="t">${esc(c.title)}</span>
      <button class="del" data-del="${c.id}" aria-label="Delete"><svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg></button>
    </div>`).join('');
}

function modelName() { return (device && device.config && device.config.model) || 'qwen2.5:1.5b'; }

function updateCtx() {
  const c = currentChat();
  const total = c ? chatTokens(c) : 0;
  const lastAi = c && [...c.messages].reverse().find((m) => m.usage);
  const ctx = (device && device.config && device.config.num_ctx) || (lastAi && lastAi.usage.ctx) || 2048;
  const used = lastAi ? lastAi.usage.prompt + lastAi.usage.completion : 0;
  const pct = Math.min(100, (used / ctx) * 100);
  $('#ctx-bar i').style.width = pct + '%';
  $('#ctx-bar').classList.toggle('hot', pct > 75);
  $('#ctx-text').textContent = total
    ? `${fmt(used)} / ${fmt(ctx)} context · ${fmt(total)} tokens in this chat`
    : `0 tokens in this chat · ${fmt(ctx)} context`;
}

function updateRouteChip() {
  const r = ROUTES[settings.route] || ROUTES.esp;
  $('#route-btn').innerHTML = `${r.icon}${r.label}`;
}

function updateHeader() {
  $('#model-name').textContent = modelName();
  const dot = $('#conn-dot');
  dot.className = 'dot ' + (!deviceOk ? 'bad' : device.ollama_online ? 'ok' : 'warn');
  const st = $('#device-state');
  if (!deviceOk) st.textContent = settings.host || settings.demo ? 'ESP32 offline' : 'Not connected';
  else if (!device.ollama_online) st.textContent = 'ESP32 online · Ollama offline';
  else if (device.state === 'speaking') st.innerHTML = 'Speaking on speaker <button class="stop" data-action="stop-audio">Stop</button>';
  else st.textContent = settings.demo ? 'Demo mode' : '';
}

function updateComposer() {
  const has = $('#input').value.trim().length > 0;
  $('#send-btn').disabled = busy || !has;
  $('#mic-btn').disabled = busy;
}

/* ---------------------------------------------------------------- ask ---- */
async function send(text) {
  text = (text || '').trim();
  if (!text || busy) return;
  const chat = ensureChat();
  if (!chat.messages.length) chat.title = makeTitle(text);
  chat.messages.push({ role: 'user', content: text, ts: Date.now() });
  chat.updated = Date.now();
  chats = [chat, ...chats.filter((c) => c.id !== chat.id)];
  saveChats();
  $('#input').value = '';
  autoGrow();
  renderChat();
  renderChatList();
  await askAI(chat);
}

async function askAI(chat) {
  busy = true;
  updateComposer();
  const box = $('#messages');
  // the "Regenerate" button only belongs on the newest answer
  $$('.act[data-act="redo"]', box).forEach((b) => b.remove());
  const node = thinkingNode();
  box.appendChild(node);
  scrollDown();

  const t0 = performance.now();
  const tick = setInterval(() => {
    const s = (performance.now() - t0) / 1000;
    $('.tt', node).textContent = s > 8 ? `Thinking · ${s.toFixed(0)}s (first answer loads the model)` : `Thinking · ${s.toFixed(1)}s`;
  }, 100);

  const speakEsp = settings.route === 'esp' || settings.route === 'both';
  const history = chat.messages
    .slice(-Math.max(1, settings.history))
    .map((m) => ({ role: m.role, content: m.content.slice(0, 1500) }));
  while (history.length && history[0].role !== 'user') history.shift();

  try {
    const r = await http('POST', '/api/ask', { messages: history, speak: speakEsp });
    if (r.status === 409) throw new Error('The ESP32 is still busy with another question. Try again in a moment.');
    if (r.status !== 202 && r.status !== 200) throw new Error((r.data && r.data.error) || `ESP32 answered HTTP ${r.status}`);
    const id = r.data.id;

    let s = null, misses = 0;
    for (;;) {
      await sleep(400);
      if (performance.now() - t0 > 150000) throw new Error('No answer after 150 s. Is Ollama running on the PC?');
      try { s = (await http('GET', '/api/status', null, 5000)).data; misses = 0; }
      catch (e) { if (++misses > 10) throw e; continue; }
      if (s && s.done_id >= id) break;
    }
    if (s.error) throw new Error(s.error);

    const u = s.usage || {};
    const msg = {
      role: 'assistant', content: s.reply || '(empty answer)', ts: Date.now(), model: modelName(),
      usage: {
        prompt: +u.prompt_tokens || 0, completion: +u.completion_tokens || 0,
        tps: +u.tokens_per_sec || 0, ms: +u.total_ms || Math.round(performance.now() - t0),
        load: +u.load_ms || 0, ctx: +u.num_ctx || 2048,
      },
    };
    chat.messages.push(msg);
    chat.updated = Date.now();
    usageLog.push({ ts: msg.ts, chatId: chat.id, model: msg.model, prompt: msg.usage.prompt, completion: msg.usage.completion, tps: msg.usage.tps, ms: msg.usage.ms });
    saveChats();
    saveUsage();
    node.replaceWith(aiNode(msg, true));
    updateCtx();
    scrollDown();
    if (settings.route === 'phone' || settings.route === 'both') phoneSpeak(msg.content);
    if (speakEsp) refreshDevice();
  } catch (e) {
    node.replaceWith(errorNode(e.message, () => askAI(chat)));
    scrollDown();
  } finally {
    clearInterval(tick);
    busy = false;
    updateComposer();
  }
}

async function regenerate() {
  const c = currentChat();
  if (!c || busy) return;
  if (c.messages.length && c.messages[c.messages.length - 1].role === 'assistant') c.messages.pop();
  saveChats();
  renderChat();
  await askAI(c);
}

/* ---------------------------------------------------------- speaking ----- */
async function phoneSpeak(text) {
  const t = plain(text);
  if (!t) return;
  if (isNative && Plugins.TextToSpeech) {
    try { await Plugins.TextToSpeech.stop(); } catch { /* nothing playing */ }
    Plugins.TextToSpeech.speak({ text: t, lang: settings.lang, rate: 1.0, pitch: 1.0, volume: 1.0, category: 'playback' })
      .catch(() => toast('Phone voice not available'));
  } else if ('speechSynthesis' in window) {
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(t);
    u.lang = settings.lang;
    speechSynthesis.speak(u);
  } else toast('This phone has no text-to-speech');
}

async function espSpeak(text) {
  try {
    const r = await http('POST', '/api/speak', { text: plain(text) });
    if (r.status !== 200) throw new Error((r.data && r.data.error) || 'Speaker busy');
    toast('Playing on speaker');
    setTimeout(refreshDevice, 300);
  } catch (e) { toast(e.message); }
}

async function stopAudio() {
  try { await http('POST', '/api/stop'); } catch { /* ignore */ }
  if (isNative && Plugins.TextToSpeech) Plugins.TextToSpeech.stop().catch(() => {});
  else if ('speechSynthesis' in window) speechSynthesis.cancel();
  setTimeout(refreshDevice, 200);
}

/* ------------------------------------------------------- voice input ----- */
const voice = { active: false, text: '', finished: false, web: null };

function setVoiceText(t) {
  voice.text = t;
  $('#voice-text').textContent = t || 'Listening…';
  $('#voice').classList.toggle('loud', !!t);
}

async function startVoice() {
  if (busy) return;
  voice.active = true; voice.finished = false; voice.web = null;
  setVoiceText('');
  $('#voice').hidden = false;

  const SR = Plugins.SpeechRecognition;
  if (isNative && SR) {
    try {
      const { available } = await SR.available();
      if (!available) throw new Error('Speech recognition is not available. Install or update the Google app.');
      let p = await SR.checkPermissions();
      if (p.speechRecognition !== 'granted') p = await SR.requestPermissions();
      if (p.speechRecognition !== 'granted') throw new Error('Microphone permission was denied');
      await SR.removeAllListeners();
      await SR.addListener('partialResults', (d) => { if (d && d.matches && d.matches[0]) setVoiceText(d.matches[0]); });
      await SR.addListener('listeningState', (d) => { if (d && d.status === 'stopped') finishVoice(true); });
      SR.start({ language: settings.lang, maxResults: 1, partialResults: true, popup: false })
        .then((r) => { if (r && r.matches && r.matches[0]) setVoiceText(r.matches[0]); })
        .catch((e) => { if (voice.active && !voice.text) { closeVoice(); toast(niceSpeechError(e)); } });
    } catch (e) { closeVoice(); toast(e.message); }
    return;
  }

  const W = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!W) { closeVoice(); return toast('Voice input works in the Android app (or Chrome on https).'); }
  const rec = new W();
  voice.web = rec;
  rec.lang = settings.lang;
  rec.interimResults = true;
  rec.continuous = false;
  rec.onresult = (ev) => setVoiceText([...ev.results].map((r) => r[0].transcript).join(' '));
  rec.onerror = (ev) => { if (ev.error !== 'aborted') toast(ev.error === 'not-allowed' ? 'Microphone blocked. Browsers only allow it on https.' : 'Voice error: ' + ev.error); };
  rec.onend = () => finishVoice(true);
  try { rec.start(); } catch { closeVoice(); }
}

function niceSpeechError(e) {
  const m = String((e && e.message) || e);
  if (/no match|7/i.test(m)) return "Didn't catch that. Try again.";
  if (/network|2/i.test(m)) return 'Speech needs the Google speech service (internet or offline pack).';
  return 'Voice error: ' + m;
}

function stopRecognizer() {
  if (voice.web) { try { voice.web.stop(); } catch { /* */ } }
  else if (Plugins.SpeechRecognition) Plugins.SpeechRecognition.stop().catch(() => {});
}

function finishVoice(send_) {
  if (!voice.active || voice.finished) return;
  voice.finished = true;
  stopRecognizer();
  const text = voice.text.trim();
  closeVoice();
  if (!send_ || !text) return;
  if (settings.autosend) send(text);
  else { $('#input').value = text; autoGrow(); updateComposer(); $('#input').focus(); }
}

function closeVoice() {
  voice.active = false;
  $('#voice').hidden = true;
  if (Plugins.SpeechRecognition && isNative) Plugins.SpeechRecognition.removeAllListeners().catch(() => {});
}

/* ------------------------------------------------------------- device ---- */
async function refreshDevice() {
  if (!settings.host && !settings.demo) { deviceOk = false; updateHeader(); return; }
  try {
    const r = await http('GET', '/api/info', null, 4000);
    if (r.status !== 200 || !r.data) throw new Error('bad');
    device = r.data;
    deviceOk = true;
  } catch { deviceOk = false; }
  updateHeader();
  updateCtx();
}

let pollTimer;
function schedulePoll() {
  clearTimeout(pollTimer);
  const fast = deviceOk && device && device.state === 'speaking';
  pollTimer = setTimeout(async () => {
    if (!document.hidden && !busy) await refreshDevice();
    schedulePoll();
  }, fast ? 1200 : 5000);
}

/* ------------------------------------------------------------- drawer ---- */
function openDrawer() { renderChatList(); $('#drawer').classList.add('open'); $('#scrim').hidden = false; }
function closeDrawer() { $('#drawer').classList.remove('open'); $('#scrim').hidden = true; }

/* ----------------------------------------------------------- settings ---- */
function setSeg(id, value) { $$(`#${id} button`).forEach((b) => b.classList.toggle('on', b.dataset.v === String(value))); }

function fillSettings() {
  const cfg = (device && device.config) || {};
  $('#set-host').value = settings.host;
  $('#set-demo').checked = settings.demo;
  $('#set-lang').value = settings.lang;
  $('#set-autosend').checked = settings.autosend;
  $('#set-history').value = settings.history;
  setSeg('set-route', settings.route);
  setSeg('set-voice', cfg.voice_mode ?? 0);
  $('#set-ollama').value = cfg.ollama_host || '';
  $('#set-system').value = cfg.system_prompt || '';
  $('#set-words').value = cfg.max_words || 40;
  $('#set-predict').value = cfg.num_predict || 160;
  $('#set-ctx').value = String(cfg.num_ctx || 2048);
  $('#set-volume').value = cfg.volume ?? 2;
  $('#vol-val').textContent = Math.round(((cfg.volume ?? 2) / 4) * 100) + '%';
  const models = new Set([...(device && device.models) || [], cfg.model || 'qwen2.5:1.5b']);
  $('#set-model').innerHTML = [...models].map((m) => `<option ${m === cfg.model ? 'selected' : ''}>${esc(m)}</option>`).join('');
  renderDeviceInfo();
}

function renderDeviceInfo() {
  const el = $('#device-info');
  if (!deviceOk) { el.innerHTML = `<span>Status</span><b>${settings.host || settings.demo ? 'Not reachable' : 'Not connected'}</b>`; return; }
  const d = device;
  const up = d.uptime_s > 3600 ? (d.uptime_s / 3600).toFixed(1) + ' h' : Math.round(d.uptime_s / 60) + ' min';
  const sig = d.rssi > -60 ? 'Strong' : d.rssi > -72 ? 'Good' : 'Weak';
  el.innerHTML = `
    <span>Status</span><b style="color:var(--good)">Connected</b>
    <span>Ollama</span><b style="color:${d.ollama_online ? 'var(--good)' : 'var(--bad)'}">${d.ollama_online ? 'Online' : 'Offline'} · ${esc(d.config.ollama_host)}</b>
    <span>IP</span><b>${esc(d.ip)}</b>
    <span>WiFi</span><b>${sig} (${d.rssi} dBm)</b>
    <span>Free memory</span><b>${fmt(d.heap / 1024)} KB</b>
    <span>Uptime</span><b>${up}</b>
    <span>Firmware</span><b>${esc(d.fw)}</b>`;
}

async function pushConfig(patch, quiet) {
  try {
    const r = await http('POST', '/api/config', patch);
    if (r.status !== 200) throw new Error((r.data && r.data.error) || 'Save failed');
    if (!quiet) toast('Saved to ESP32');
    await refreshDevice();
  } catch (e) { toast(e.message); }
}

/* -------------------------------------------------------------- usage ---- */
function renderUsage() {
  const body = $('#usage-body');
  const log = usageLog;
  const P = log.reduce((a, x) => a + x.prompt, 0);
  const C = log.reduce((a, x) => a + x.completion, 0);
  const T = P + C;
  const n = log.length;
  const avgTps = n ? log.reduce((a, x) => a + x.tps, 0) / n : 0;
  const avgMs = n ? log.reduce((a, x) => a + x.ms, 0) / n : 0;
  const today = new Date().toDateString();
  const todayTok = log.filter((x) => new Date(x.ts).toDateString() === today).reduce((a, x) => a + x.prompt + x.completion, 0);
  const pPct = T ? (P / T) * 100 : 50;

  const c = currentChat();
  const lastAi = c && [...c.messages].reverse().find((m) => m.usage);
  const ctx = (device && device.config && device.config.num_ctx) || 2048;
  const ctxUsed = lastAi ? lastAi.usage.prompt + lastAi.usage.completion : 0;

  const byChat = chats.map((ch) => ({ t: ch.title, v: chatTokens(ch) })).filter((x) => x.v).sort((a, b) => b.v - a.v).slice(0, 6);
  const byModel = {};
  log.forEach((x) => { byModel[x.model] = (byModel[x.model] || 0) + x.prompt + x.completion; });
  const sess = device && device.session;

  body.innerHTML = `
    <div class="stat-grid">
      <div class="stat hero">
        <div><div class="k">Total tokens</div><div class="v">${fmt(T)}</div><div class="s">${fmt(n)} answers · all time on this phone</div></div>
        <span class="free-badge">Free · local</span>
      </div>
    </div>
    <div class="chart-card">
      <div class="split-bar"><i style="width:${pPct}%"></i><i style="width:${100 - pPct}%"></i></div>
      <div class="legend"><span style="--c:var(--prompt-col)">Input ${fmt(P)}</span><span style="--c:var(--gen-col)">Output ${fmt(C)}</span></div>
    </div>
    <div class="stat-grid" style="margin-top:10px">
      <div class="stat"><div class="k">Today</div><div class="v">${fmt(todayTok)}</div><div class="s">tokens</div></div>
      <div class="stat"><div class="k">Requests</div><div class="v">${fmt(n)}</div><div class="s">answers received</div></div>
      <div class="stat"><div class="k">Avg speed</div><div class="v">${avgTps.toFixed(1)}</div><div class="s">tokens / second</div></div>
      <div class="stat"><div class="k">Avg response</div><div class="v">${(avgMs / 1000).toFixed(1)}s</div><div class="s">question to answer</div></div>
    </div>

    <div class="chart-card">
      <h3>Tokens per answer</h3><p>Last ${Math.min(24, n)} answers · input and output</p>
      ${barChart(log.slice(-24))}
      <div class="legend"><span style="--c:var(--prompt-col)">Input</span><span style="--c:var(--gen-col)">Output</span></div>
    </div>
    <div class="chart-card">
      <h3>Generation speed</h3><p>Tokens per second for each answer</p>
      ${lineChart(log.slice(-24).map((x) => x.tps))}
    </div>

    <div class="chart-card">
      <h3>Context window · current chat</h3>
      <p>How much of the model's memory the last answer used</p>
      <div class="meter"><i style="width:${Math.min(100, (ctxUsed / ctx) * 100)}%"></i></div>
      <div class="legend"><span style="--c:var(--muted)">${fmt(ctxUsed)} of ${fmt(ctx)} tokens</span></div>
    </div>

    ${sess ? `<div class="group-title">ESP32 since boot</div>
    <div class="list-card">
      <div class="li"><span>Requests</span><span>${fmt(sess.requests)}</span></div>
      <div class="li"><span>Input tokens</span><span>${fmt(sess.prompt_tokens)}</span></div>
      <div class="li"><span>Output tokens</span><span>${fmt(sess.completion_tokens)}</span></div>
    </div>` : ''}

    ${byChat.length ? `<div class="group-title">Top chats</div><div class="list-card">${byChat.map((x) => `<div class="li"><span>${esc(x.t)}</span><span>${fmt(x.v)}</span></div>`).join('')}</div>` : ''}
    ${Object.keys(byModel).length ? `<div class="group-title">By model</div><div class="list-card">${Object.entries(byModel).map(([m, v]) => `<div class="li"><span>${esc(m)}</span><span>${fmt(v)}</span></div>`).join('')}</div>` : ''}
    <p class="foot-note">Counts come from Ollama (prompt_eval_count and eval_count). Running on your own PC costs nothing per token.</p>`;
}

function barChart(rows) {
  const W = Math.max(280, ($('#usage-body').clientWidth || 340) - 60), H = 150, pad = 18;
  if (!rows.length) return `<svg class="chart" viewBox="0 0 ${W} ${H}"><text x="${W / 2}" y="${H / 2}" text-anchor="middle">No answers yet</text></svg>`;
  const max = Math.max(...rows.map((r) => r.prompt + r.completion), 1);
  const bw = (W - pad) / rows.length;
  const bars = rows.map((r, i) => {
    const hp = ((H - pad) * r.prompt) / max, hc = ((H - pad) * r.completion) / max;
    const w = Math.max(2, Math.min(26, bw * 0.7)), x = pad + i * bw + (bw - w) / 2;
    return `<rect x="${x}" y="${H - pad - hp - hc}" width="${w}" height="${hc}" rx="2" fill="var(--gen-col)"/>
            <rect x="${x}" y="${H - pad - hp}" width="${w}" height="${hp}" rx="2" fill="var(--prompt-col)"/>`;
  }).join('');
  return `<svg class="chart" viewBox="0 0 ${W} ${H}">
    <line x1="${pad}" y1="${H - pad}" x2="${W}" y2="${H - pad}" stroke="var(--line)"/>
    <text x="0" y="10">${fmt(max)}</text><text x="0" y="${H - pad}">0</text>${bars}</svg>`;
}

function lineChart(vals) {
  const W = Math.max(280, ($('#usage-body').clientWidth || 340) - 60), H = 120, pad = 18;
  if (vals.length < 2) return `<svg class="chart" style="height:120px" viewBox="0 0 ${W} ${H}"><text x="${W / 2}" y="${H / 2}" text-anchor="middle">Needs two answers</text></svg>`;
  const max = Math.max(...vals) * 1.15 || 1;
  const pts = vals.map((v, i) => [pad + (i * (W - pad - 6)) / (vals.length - 1), H - pad - ((H - pad - 8) * v) / max]);
  const d = pts.map((p, i) => (i ? 'L' : 'M') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' ');
  const area = d + ` L${pts[pts.length - 1][0]} ${H - pad} L${pts[0][0]} ${H - pad} Z`;
  return `<svg class="chart" style="height:120px" viewBox="0 0 ${W} ${H}">
    <line x1="${pad}" y1="${H - pad}" x2="${W}" y2="${H - pad}" stroke="var(--line)"/>
    <path d="${area}" fill="var(--accent-soft)" stroke="none"/><path d="${d}" fill="none" stroke="var(--accent)" stroke-width="2"/>
    ${pts.map((p) => `<circle cx="${p[0]}" cy="${p[1]}" r="2.5" fill="var(--accent)"/>`).join('')}
    <text x="0" y="10">${max.toFixed(0)}</text><text x="0" y="${H - pad}">0</text></svg>`;
}

/* -------------------------------------------------------------- sheets --- */
function openSheet(id) {
  closeDrawer();
  $$('.sheet').forEach((s) => (s.hidden = s.id !== id));
  if (id === 'usage') renderUsage();
  if (id === 'settings') { fillSettings(); refreshDevice().then(fillSettings); }
}
function closeSheets() { $$('.sheet').forEach((s) => (s.hidden = true)); }

/* -------------------------------------------------------------- events --- */
function autoGrow() {
  const t = $('#input');
  t.style.height = 'auto';
  t.style.height = Math.min(140, t.scrollHeight) + 'px';
}

const actions = {
  'open-drawer': openDrawer,
  'close-drawer': closeDrawer,
  'new-chat': newChat,
  'open-usage': () => openSheet('usage'),
  'open-settings': () => openSheet('settings'),
  'close-sheet': closeSheets,
  send: () => send($('#input').value),
  mic: startVoice,
  'voice-done': () => finishVoice(true),
  'voice-cancel': () => finishVoice(false),
  'cycle-route': () => {
    const order = ['esp', 'phone', 'both', 'off'];
    settings.route = order[(order.indexOf(settings.route) + 1) % order.length];
    saveSettings();
    updateRouteChip();
    toast('Answers: ' + ROUTES[settings.route].label);
  },
  'stop-audio': stopAudio,
  'reset-usage': () => {
    if (!confirm('Reset all usage stats on this phone?')) return;
    usageLog = [];
    saveUsage();
    renderUsage();
  },
  'test-conn': async () => {
    let h = $('#set-host').value.trim().replace(/\/+$/, '');
    settings.host = h;
    if (h) settings.demo = false;
    $('#set-demo').checked = settings.demo;
    saveSettings();
    $('#device-info').innerHTML = '<span>Status</span><b>Connecting…</b>';
    await refreshDevice();
    fillSettings();
    toast(deviceOk ? 'Connected to ESP32' : `No answer from ${h || 'ESP32'}`);
  },
  'save-device': () => pushConfig({
    model: $('#set-model').value,
    ollama_host: $('#set-ollama').value.trim(),
    system_prompt: $('#set-system').value.trim(),
    max_words: +$('#set-words').value || 40,
    num_predict: +$('#set-predict').value || 160,
    num_ctx: +$('#set-ctx').value || 2048,
  }),
  'test-voice': () => espSpeak('Hello! This is your voice assistant speaker test.'),
};

document.addEventListener('click', (e) => {
  const a = e.target.closest('[data-action]');
  if (a && actions[a.dataset.action]) { e.preventDefault(); actions[a.dataset.action](); return; }

  const chip = e.target.closest('.chip[data-prompt]');
  if (chip) return send(chip.dataset.prompt);

  const del = e.target.closest('[data-del]');
  if (del) {
    e.stopPropagation();
    if (busy && del.dataset.del === currentId) return toast('Wait for the answer to finish');
    chats = chats.filter((c) => c.id !== del.dataset.del);
    if (currentId === del.dataset.del) { currentId = null; renderChat(); }
    saveChats();
    renderChatList();
    return;
  }
  const item = e.target.closest('.chat-item');
  if (item) {
    if (busy) return toast('Wait for the answer to finish');
    currentId = item.dataset.id;
    saveChats();
    renderChat();
    closeDrawer();
    return;
  }

  const act = e.target.closest('.act, .usage-chip');
  if (act) {
    const m = act.closest('.msg')._msg;
    const kind = act.dataset.act;
    if (kind === 'copy') {
      (navigator.clipboard ? navigator.clipboard.writeText(m.content) : Promise.reject())
        .then(() => toast('Copied'), () => toast('Copy not available'));
    } else if (kind === 'esp') espSpeak(m.content);
    else if (kind === 'phone') phoneSpeak(m.content);
    else if (kind === 'redo') regenerate();
    else if (kind === 'usage') {
      const u = m.usage;
      toast(`Input ${fmt(u.prompt)} · Output ${fmt(u.completion)} · Total ${fmt(u.prompt + u.completion)} tokens\n${u.tps.toFixed(1)} tok/s · ${fmtSec(u.ms)}${u.load > 300 ? ` (model load ${fmtSec(u.load)})` : ''}`, 4200);
    }
  }

  const seg = e.target.closest('.seg button');
  if (seg) {
    const group = seg.parentElement.id;
    setSeg(group, seg.dataset.v);
    if (group === 'set-route') { settings.route = seg.dataset.v; saveSettings(); updateRouteChip(); }
    if (group === 'set-voice') pushConfig({ voice_mode: +seg.dataset.v }, true);
  }
});

$('#scrim').addEventListener('click', closeDrawer);
$('#input').addEventListener('input', () => { autoGrow(); updateComposer(); });
$('#input').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey && !('ontouchstart' in window)) { e.preventDefault(); send($('#input').value); }
});
$('#messages').addEventListener('scroll', (e) => $('.topbar').classList.toggle('scrolled', e.target.scrollTop > 4));
$('#set-demo').addEventListener('change', (e) => { settings.demo = e.target.checked; saveSettings(); refreshDevice().then(fillSettings); });
$('#set-lang').addEventListener('change', (e) => { settings.lang = e.target.value; saveSettings(); });
$('#set-autosend').addEventListener('change', (e) => { settings.autosend = e.target.checked; saveSettings(); });
$('#set-history').addEventListener('change', (e) => { settings.history = Math.max(1, Math.min(20, +e.target.value || 8)); saveSettings(); });
$('#set-volume').addEventListener('input', (e) => { $('#vol-val').textContent = Math.round((e.target.value / 4) * 100) + '%'; });
$('#set-volume').addEventListener('change', (e) => pushConfig({ volume: +e.target.value }, true));
document.addEventListener('visibilitychange', () => { if (!document.hidden) refreshDevice(); });

// Android back button: close overlays first
if (isNative && Plugins.App) {
  Plugins.App.addListener('backButton', () => {
    if (!$('#voice').hidden) return finishVoice(false);
    if ($('#drawer').classList.contains('open')) return closeDrawer();
    if ($$('.sheet').some((s) => !s.hidden)) return closeSheets();
    Plugins.App.minimizeApp();
  });
}

/* ---------------------------------------------------------------- boot --- */
(function boot() {
  const h = new Date().getHours();
  $('#greeting').textContent = h < 5 ? 'Up late? Ask me anything.' : h < 12 ? 'Good morning. What can I help with?' : h < 18 ? 'Good afternoon. What can I help with?' : 'Good evening. What can I help with?';
  if (!currentChat()) currentId = null;
  updateRouteChip();
  renderChat();
  renderChatList();
  updateComposer();
  refreshDevice().then(() => {
    if (!settings.host && !settings.demo) {
      toast('Add your ESP32 address in Settings, or turn on Demo mode', 4000);
    }
  });
  schedulePoll();
})();
