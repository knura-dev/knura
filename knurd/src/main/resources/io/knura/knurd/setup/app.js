// Knura setup page. Served by knurd on the admin port; talks only to knurd's admin API (docs/setup-api.md).
// No libraries and nothing from the internet. Every request carries X-Knura-Request: 1.
"use strict";
(function () {
const P = {
  home:'M4 10.5L12 4l8 6.5V19a1.5 1.5 0 0 1-1.5 1.5H15v-6H9v6H5.5A1.5 1.5 0 0 1 4 19z',
  tag:'M3.5 12V5.5a2 2 0 0 1 2-2H12l8.5 8.5-8.5 8.5zM8 8h.01',
  plug:'M9 2v6M15 2v6M6 8h12v4a6 6 0 0 1-12 0zM12 18v4',
  card:'M5 5h14a2.5 2.5 0 0 1 2.5 2.5v9A2.5 2.5 0 0 1 19 19H5a2.5 2.5 0 0 1-2.5-2.5v-9A2.5 2.5 0 0 1 5 5zM2.5 10h19',
  shield:'M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6zM8.8 12l2.2 2.2 4.2-4.4',
  out:'M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4M9 8l-4 4 4 4M5 12h10',
  bell:'M6 9a6 6 0 0 1 12 0c0 6 2.5 7.5 2.5 7.5h-17S6 15 6 9M10 20a2.2 2.2 0 0 0 4 0',
  sun:'M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6L7 7M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
  moon:'M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z',
  gear:'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z', chev:'M9 6l6 6-6 6', help:'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.6M12 17h.01',
  plus:'M12 5v14M5 12h14', check:'M5 12.5l4.5 4.5L19 7.5', back:'M15 18l-6-6 6-6', x:'M6 6l12 12M18 6L6 18',
  phone:'M8.5 2h7A2.5 2.5 0 0 1 18 4.5v15a2.5 2.5 0 0 1-2.5 2.5h-7A2.5 2.5 0 0 1 6 19.5v-15A2.5 2.5 0 0 1 8.5 2zM11 18h2',
  house:'M3 11l9-7 9 7M5 10v10h14V10',
  link:'M10 14a3.5 3.5 0 0 0 5 0l3-3a3.5 3.5 0 0 0-5-5l-.5.5M14 10a3.5 3.5 0 0 0-5 0l-3 3a3.5 3.5 0 0 0 5 5l.5-.5',
  bolt:'M13 3L5 13.5h6L10 21l8-10.5h-6z', matter:'M12 3v6M12 15v6M3 12h6M15 12h6',
  key:'M8 11a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9zM11.5 11.5L21 2M17 6l3 3M14.5 8.5l2 2',
  copy:'M9 9h11v11H9zM5 15H4V4h11v1', download:'M12 3v12M7 10l5 5 5-5M4 21h16',
  search:'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4',
  nfc:'M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17zM9 9.5a4 4 0 0 1 0 5M6.8 7.3a7 7 0 0 1 0 9.4',
  edit:'M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4',
  door:'M5 21V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16M3 21h18M15 12h.01',
  garage:'M3 21V9l9-5 9 5v12M7 21v-8h10v8M7 16h10',
  office:'M4 6h16v10H4zM9 20h6M12 16v4',
  pantry:'M5 3h14v18H5zM5 12h14M9 7.5h.01M9 16.5h.01',
  lamp:'M9 21h6M12 21v-6M7 9l5-6 5 6zM12 9v6',
  car:'M5 16h14l-1.5-6h-11zM7 16v3M17 16v3M7.5 13h.01M16.5 13h.01',
  bed:'M3 18V8M3 14h18v4M21 18v-6a2 2 0 0 0-2-2h-8v4',
  gate:'M4 21V5M20 21V5M4 9h16M4 15h16M9 9v6M15 9v6',
  mail:'M3 6h18v12H3zM3 7l9 6 9-6',
  lock:'M6 11h12v10H6zM8.5 11V8a3.5 3.5 0 0 1 7 0v3',
  eye:'M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z',
  eyeoff:'M3 3l18 18M10.6 5.1A10.6 10.6 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.2 4M6.6 6.6A17 17 0 0 0 2 12s3.6 7 10 7a10 10 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2',
  finger:'M12 11v4M8.2 8.6A5 5 0 0 1 17 11.5v1.5a10 10 0 0 1-1 4.5M7 12v1a8 8 0 0 1-1.5 4.5M12 3a8 8 0 0 0-8 8v2M20 11.5V13M12 19a12 12 0 0 0 1-4',
  coffee:'M4 9h13v5a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5zM17 11h1.5a2.5 2.5 0 0 1 0 5H17M8 3v3M12 3v3'
};
P.globe = 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z';
P.usb = 'M12 2v14M9 5l3-3 3 3M7 9v3l5 3 5-3V9M12 16a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z';
P.file = 'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M9 13h6M9 17h4';
P.next = 'M9 18l6-6-6-6'; P.reset = 'M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4';
P.kitchen = 'M7 3v8M5 3v4a2 2 0 0 0 4 0V3M7 11v10M17 3c-2 1.5-3 4-3 7h3v11';
P.sofa = 'M4 11V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v3M2 13a2 2 0 0 1 4 0v2h12v-2a2 2 0 0 1 4 0v5H2zM5 18v2M19 18v2';
P.box = 'M3 7l9-4 9 4v10l-9 4-9-4zM3 7l9 4 9-4M12 11v10';
P.info = 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v5M12 8h.01';
P.warn = 'M12 3L2 20h20zM12 10v4M12 17h.01';
P.web = 'M4 6h16v12H4zM4 9h16M8 13l-2 2 2 2M16 13l2 2-2 2';
P.ha = 'M12 3L3 11v10h18V11zM12 21v-6M12 15l-3-3M12 15l3-2.5';
P.restore = 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 8v4l3 2';
P.cloud = 'M7 18a4.5 4.5 0 0 1-.6-9A6 6 0 0 1 18 9.5a4 4 0 0 1-.5 8.5z';
P.terminal = 'M4 5h16v14H4zM8 10l2 2-2 2M12 14h4';

const ico = (n, s = 20, w = 1.8) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${P[n] || P.info}"/></svg>`;
const MARK = (s = 30, anim = false) => `<svg class="${anim ? 'mk' : ''}" width="${s}" height="${s}" viewBox="0 0 32 32" role="img" aria-label="Knura"><rect x="2" y="2" width="28" height="28" rx="8" fill="none" stroke="var(--accent)" stroke-width="2.2"/><rect x="8.5" y="8.5" width="15" height="15" rx="4.5" fill="none" stroke="var(--accent)" stroke-width="2"/><rect x="12.75" y="12.75" width="6.5" height="6.5" rx="1.8" fill="var(--fg)"/></svg>`;
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const DOCS = 'https://docs.knura.io';

const STEPS = ['Welcome', 'Recovery kit', 'Reach from phones', 'Phone or reader', 'First tag', 'First action', 'All set'];
const ICONS = [['door', 'Front door'], ['kitchen', 'Kitchen'], ['sofa', 'Living room'], ['bed', 'Bedroom'], ['garage', 'Garage'], ['office', 'Office'], ['pantry', 'Pantry'], ['car', 'Car'], ['box', 'Storage'], ['lamp', 'Lamp'], ['mail', 'Mailbox'], ['gate', 'Gate']];

// ---------- state ----------
let S = {
  screen: 'loading', start: 'new', session: null, settings: null, status: null, tags: [], connections: [], reach: null,
  name: '', pw: '', pw2: '', showpw: false, busy: false, err: '',
  priorKit: null, words: [], fileDone: false, sheetDone: false, c2: '', c5: '', redoBackup: false,
  dev: null,
  tagSince: null, tag: null, tagname: 'Front door', tagicon: 'door', tapped: false,
  act: null, actUrl: '', secret: null, connId: null, tested: false,
};
let timers = [], seen = new Set(), dir = 'fwd', lastKey = null;
const store = {
  get(k) { try { return localStorage.getItem('knura.setup.' + k); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem('knura.setup.' + k, v); } catch (e) { /* private window */ } },
};
function an(key, cls = 'rise') { if (seen.has(key)) return ''; seen.add(key); return ' ' + cls; }
function later(fn, ms) { const t = setTimeout(fn, ms); timers.push(t); return t; }
function clearTimers() { timers.forEach(clearTimeout); timers = []; }
function toast(t) { const el = document.getElementById('toast'); el.textContent = t; el.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.remove('show'), 3000); }

// ---------- API ----------
async function api(method, path, body) {
  const opts = { method, credentials: 'same-origin', headers: { 'X-Knura-Request': '1' } };
  if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
  let r;
  try { r = await fetch(path, opts); } catch (e) { return { ok: false, status: 0, json: { detail: 'Knura isn’t answering. Is it still running?' } }; }
  let json = null;
  if ((r.headers.get('Content-Type') || '').includes('json')) { try { json = await r.json(); } catch (e) { /* empty */ } }
  if (r.status === 401 && typeof S.screen === 'number' && path !== '/v1/session' && path !== '/v1/password') { S.screen = 'signin'; render(); }
  return { ok: r.ok, status: r.status, json, res: r };
}
const why = r => (r.json && r.json.detail) ? r.json.detail.charAt(0).toUpperCase() + r.json.detail.slice(1) + '.' : 'Something went wrong (' + r.status + ').';

async function download(path, body, fallbackName) {
  const r = await api('POST', path, body);
  if (!r.ok) return r;
  const blob = await r.res.blob();
  const cd = r.res.headers.get('Content-Disposition') || '';
  const m = /filename="([^"]+)"/.exec(cd);
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = m ? m[1] : fallbackName;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 30000);
  return { ok: true, name: a.download };
}

async function loadAll() {
  const [st, set, tg, cn] = await Promise.all([api('GET', '/v1/status'), api('GET', '/v1/settings'), api('GET', '/v1/tags'), api('GET', '/v1/connections')]);
  if (st.ok) S.status = st.json;
  if (set.ok) { S.settings = set.json; if (!S.name) S.name = set.json.name || 'Knura home'; }
  if (tg.ok) S.tags = tg.json.tags;
  if (cn.ok) S.connections = cn.json.connections;
}

function pick6() {
  const list = window.KNURA_WORDS, out = [], b = new Uint16Array(6);
  crypto.getRandomValues(b);
  for (let i = 0; i < 6; i++) out.push(list[b[i] & 2047]); // 65536 is a multiple of 2048, so every word is equally likely
  return out;
}
const backupDone = () => S.status && S.status.backup && S.status.backup.file && S.status.backup.sheet;
const day = iso => iso ? new Date(iso).toLocaleDateString() : '';

// ---------- screens ----------
function opt(field, val, icon, title, sub, badge) {
  const on = S[field] === val;
  return `<button class="opt" data-set="${field}" data-val="${val}" aria-pressed="${on}"><div class="tile${on ? '' : ' plain'}">${ico(icon, 22)}</div><div class="txt"><b>${title}${badge ? ` <span class="pill acc">${badge}</span>` : ''}</b><span>${sub}</span></div></button>`;
}
const errLine = () => S.err ? `<p class="err" role="alert">${esc(S.err)}</p>` : '';

function hello() {
  return `<div class="hello g${an('hello', 'enter')}">
  <div class="mk">${MARK(54, true)}</div>
  <div class="head"><div class="kick">Knura on this computer</div><h1>Let’s set up Knura</h1><p>Knura checks every tap on your tags and makes things happen at home. This takes about 20 minutes, and you can stop and come back at any time.</p></div>
  <div class="opts">
    ${opt('start', 'new', 'tag', 'Set up a new Knura', 'Start fresh with new tags.', 'Most people')}
    ${opt('start', 'restore', 'restore', 'Restore from a recovery kit', 'On a new computer, or after a crash.')}
    ${opt('start', 'hosted', 'cloud', 'I’d rather not keep a computer on', 'A hosted account checks taps for you.')}
  </div>
  ${S.start === 'restore' ? `<div class="note${an('rest')}">${ico('terminal', 18)}<div>Restoring from this page is coming soon. For now, stop Knura, then in an empty folder run <code>knurctl backup restore FILE</code> with your backup file, or <code>knurctl backup restore --sheet</code> with your recovery sheet. <a href="${DOCS}/home/keys-backup" target="_blank" rel="noopener">How to restore</a></div></div>` : ''}
  ${S.start === 'hosted' ? `<div class="note${an('host')}">${ico('cloud', 18)}<div>A hosted account needs no computer at home. <a href="${DOCS}/start-here/at-home-or-hosted" target="_blank" rel="noopener">At home or hosted?</a></div></div>` : ''}
  <button class="btn primary" data-act="begin" style="align-self:flex-start" ${S.start === 'new' ? '' : 'disabled'}>Get started ${ico('next', 18, 2)}</button>
</div>`;
}

function signin() {
  return `<div class="hello g signin${an('signin', 'enter')}">
  <div class="mk">${MARK(46, true)}</div>
  <div class="head"><h1>Sign in to Knura</h1><p>Use the password you made when you set Knura up.</p></div>
  <form id="signinForm" class="opts" novalidate>
    <div><label class="fl" for="spw">Password</label><input id="spw" class="in" type="password" data-in="pw" autocomplete="current-password" autofocus></div>
    ${errLine()}
    <button class="btn primary" type="submit" style="align-self:flex-start" ${S.busy ? 'disabled' : ''}>Sign in</button>
  </form>
  <p class="small muted">Forgot it? On the Knura computer, delete <code>knurd-admin-password</code> from the settings folder and open this page there to make a new one.</p>
</div>`;
}

function strength(p) {
  if (!p) return { w: 0, c: 'var(--track)', t: 'At least 8 characters.' };
  const s = Math.min(p.length, 16) / 16 * 70 + (/[0-9]/.test(p) ? 10 : 0) + (/[^A-Za-z0-9]/.test(p) ? 10 : 0) + (/[A-Z]/.test(p) && /[a-z]/.test(p) ? 10 : 0);
  if (p.length < 8) return { w: 20, c: 'var(--bad)', t: 'Too short. Use at least 8 characters.' };
  if (s < 60) return { w: s, c: 'var(--warn)', t: 'Okay. A longer one is better.' };
  return { w: Math.min(s, 100), c: 'var(--ok)', t: 'Strong.' };
}

function s1() {
  const needPw = !S.session.passwordSet, st = strength(S.pw);
  return `
<div class="head"><div class="kick">Step 1 of 7</div><h1>${needPw ? 'Name your Knura and protect this page' : 'Name your Knura'}</h1><p>The name shows in the Knura app on your phone.${needPw ? ' The password stops anyone else on your Wi-Fi from changing your settings.' : ''}</p></div>
<div><label class="fl" for="name">Name</label><input id="name" class="in" data-in="name" value="${esc(S.name)}" maxlength="40" autocomplete="off"><p class="small muted" style="margin-top:6px">Like “Our house” or “Lake cabin”.</p></div>
${needPw ? `<div class="grid2">
  <div><label class="fl" for="pw">Password for this page</label><input id="pw" class="in" type="${S.showpw ? 'text' : 'password'}" data-in="pw" value="${esc(S.pw)}" autocomplete="new-password">
    <div class="meter"><div id="pwm" style="width:${st.w}%;background:${st.c}"></div></div><p id="pwt" class="small muted" style="margin-top:6px">${st.t}</p></div>
  <div><label class="fl" for="pw2">Type it again</label><input id="pw2" class="in" type="${S.showpw ? 'text' : 'password'}" data-in="pw2" value="${esc(S.pw2)}" autocomplete="new-password"><p id="pw2t" class="small muted" style="margin-top:6px">${S.pw2 ? (S.pw === S.pw2 ? 'Matches' : 'Doesn’t match yet') : '&nbsp;'}</p></div>
</div>
<button class="btn sm ghost" data-act="showpw" style="align-self:flex-start">${ico(S.showpw ? 'eyeoff' : 'eye', 16)} ${S.showpw ? 'Hide' : 'Show'} password</button>` : ''}
${errLine()}`;
}

function s2() {
  const prior = S.priorKit && !S.redoBackup;
  if (prior) return `
<div class="head"><div class="kick">Step 2 of 7</div><h1>Your recovery kit</h1><p>Every tag is locked with a key that only this Knura has. You already have a copy of it.</p></div>
<div class="note ok${an('prior')}">${ico('check', 18, 2.2)}<div><b>Backed up.</b> Backup file made ${esc(day(S.status.backup.file))}, recovery sheet made ${esc(day(S.status.backup.sheet))}. Keep them somewhere other than this computer.</div></div>
<button class="btn sm" data-act="redo" style="align-self:flex-start">${ico('reset', 15)} Make a new kit</button>`;
  const ok2 = S.c2.trim().toLowerCase() === S.words[1], ok5 = S.c5.trim().toLowerCase() === S.words[4];
  return `
<div class="head"><div class="kick">Step 2 of 7</div><h1>Save your recovery kit</h1><p>Every tag is locked with a key that only this Knura has. If this computer breaks and there’s no copy, your tags can’t be used again. This kit is that copy.</p></div>
<div class="keyart${an('k2')}"><div class="kbox">${ico('key', 28, 2)}</div><div><b>Your master key is ready</b><p class="small muted">It never leaves this computer except inside your locked backup and on the sheet you print.</p></div></div>
<div style="display:flex;flex-direction:column;gap:12px">
  <div class="row" style="justify-content:space-between;flex-wrap:wrap"><h2 style="font-size:17px;font-weight:600">1. Your passphrase</h2>
    <button class="btn sm" data-act="newwords" ${S.fileDone ? 'disabled' : ''}>${ico('reset', 15)} New words</button></div>
  <p class="small muted">It locks the backup file. Write these six words on paper now, in order. You’ll need them to restore.</p>
  <div class="words">${S.words.map((w, i) => `<div class="word${an('w' + S.words.join() + i, 'flip')}" style="animation-delay:${i * 90}ms"><i>${i + 1}</i>${esc(w)}</div>`).join('')}</div>
</div>
<div style="display:flex;flex-direction:column;gap:12px">
  <h2 style="font-size:17px;font-weight:600">2. Keep two copies somewhere else</h2>
  <div class="grid2">
    <div class="sub save">
      <div class="row"><div class="tile${S.fileDone ? ' ok' : ''}">${ico(S.fileDone ? 'check' : 'download', 22)}</div><div><b>Backup file</b><p class="small muted">Locked with your passphrase.</p></div></div>
      ${S.fileDone ? `<p class="small${an('fdone')}"><span class="pill ok">${ico('check', 13, 2.4)} Downloaded</span> <span class="mono muted">${esc(S.fileDone)}</span></p><p class="small muted">Copy it to a USB stick or to Google Drive or iCloud, not only this computer.</p>`
      : `<button class="btn sm primary" data-act="savefile" ${S.busy ? 'disabled' : ''}>${S.busy === 'file' ? '<span class="spin"></span> Locking…' : ico('download', 16) + ' Download backup'}</button>`}
    </div>
    <div class="sub save">
      <div class="row"><div class="tile${S.sheetDone ? ' ok' : ''}">${ico(S.sheetDone ? 'check' : 'file', 22)}</div><div><b>Recovery sheet</b><p class="small muted">One page to print and keep with important papers.</p></div></div>
      ${S.sheetDone ? `<p class="small${an('sdone')}"><span class="pill ok">${ico('check', 13, 2.4)} Downloaded</span> <span class="muted">Open it, print it, then delete the file.</span></p>`
      : `<button class="btn sm" data-act="sheet" ${S.busy ? 'disabled' : ''}>${ico('file', 16)} Download recovery sheet</button>`}
    </div>
  </div>
</div>
<div style="display:flex;flex-direction:column;gap:12px">
  <h2 style="font-size:17px;font-weight:600">3. Check you wrote them down</h2>
  <p class="small muted">Look at your paper, not the screen.</p>
  <div class="confirm">
    <div><label class="fl" for="c2">Word 2</label><input id="c2" class="in${ok2 ? ' okk' : ''}" data-in="c2" value="${esc(S.c2)}" autocomplete="off" autocapitalize="off" spellcheck="false"></div>
    <div><label class="fl" for="c5">Word 5</label><input id="c5" class="in${ok5 ? ' okk' : ''}" data-in="c5" value="${esc(S.c5)}" autocomplete="off" autocapitalize="off" spellcheck="false"></div>
  </div>
</div>
${errLine()}`;
}

function s3() {
  const r = S.reach, live = r && r.reachable;
  return `
<div class="head"><div class="kick">Step 3 of 7</div><h1>Let phones reach your Knura</h1><p>Each tag holds a link. Wherever someone taps it, their phone needs to reach this computer through the internet.</p></div>
<div class="route" aria-hidden="true"><div class="node">${ico('phone', 16)} Phone</div><div class="wire${live ? '' : ' idle'}"></div>${r && r.resolverOwner ? `<div class="node">${ico('globe', 16)} tap.knura.io</div><div class="wire${live ? '' : ' idle'}"></div>` : ''}<div class="node">${ico('house', 16)} ${esc(S.name || 'Knura')}</div></div>
${!r ? `<div class="row"><div class="spin"></div><span class="muted">Checking…</span></div>`
  : !r.publicUrl ? `<div class="note warn${an('nourl')}">${ico('info', 18)}<div><b>Phones can’t reach this Knura yet.</b> It needs a web address phones can open. Guided setup with Tailscale is coming to this page soon; until then, follow <a href="${DOCS}/start-here/first-verified-tap" target="_blank" rel="noopener">Let phones reach knurd</a> and come back. You can carry on meanwhile; taps from your own Wi-Fi need it too.</div></div>`
  : `<div style="display:flex;flex-direction:column;gap:10px">
  <label class="fl" style="margin:0">Your Knura’s address</label>
  <div class="urlbox"><span class="t">${esc(r.publicUrl)}</span>${live ? '<span class="pill ok">Live</span>' : ''}</div>
  ${live ? `<div class="note ok${an('live')}">${ico('check', 18, 2.2)}<div><b>Your address answers.</b> Phones can reach your Knura.${r.resolverOwner ? ' Your tags use tap.knura.io links, so they keep working if Knura moves.' : ''}</div></div>`
    : `<div class="note warn">${ico('info', 18)}<div><b>Your address doesn’t answer yet.</b> A new Tailscale address can take up to 15 minutes. You can keep going and check again later.</div><div class="dotwait" style="margin-top:6px"></div></div>`}
</div>`}
${r ? `<button class="btn sm" data-act="reach" style="align-self:flex-start">${ico('reset', 15)} Check again</button>` : ''}`;
}

function s4() {
  const phones = (S.settings && S.settings.phones) || [];
  return `
<div class="head"><div class="kick">Step 4 of 7</div><h1>Choose how you’ll set up tags</h1><p>Setting up a tag locks it to this Knura. You can do it with an Android phone or a USB NFC reader plugged into this computer.</p></div>
<div class="opts two">
  ${opt('dev', 'phone', 'phone', 'My Android phone', 'Uses the phone’s own NFC. Nothing else to buy.', 'No extra hardware')}
  ${opt('dev', 'reader', 'usb', 'A USB reader', 'Like the ACS ACR122U, with knurctl.')}
</div>
${S.dev === 'phone' ? (phones.length ? `<div class="stage${an('paired')}" style="gap:28px;flex-wrap:wrap;padding:24px">
    <div class="phone pop"><div class="tile ok" style="width:38px;height:38px">${ico('check', 20, 2.4)}</div><b style="font-size:13px">Paired</b></div>
    <div style="max-width:280px;display:flex;flex-direction:column;gap:6px"><span class="pill ok" style="align-self:flex-start">${ico('check', 13, 2.4)} Paired</span><b style="font-size:17px">${phones.map(esc).join(', ')}</b><p class="small muted">This phone can set up tags with your Knura over your home Wi-Fi.</p></div></div>`
  : `<div class="sub" style="padding:18px;display:flex;flex-direction:column;gap:12px">
    <b>Pair your phone</b>
    <ol class="small muted" style="margin:0;padding-left:18px;display:grid;gap:6px">
      <li>Install the Knura app on your phone. <a href="${DOCS}/tags/set-up-with-phone" target="_blank" rel="noopener">Get the app</a></li>
      <li>On this computer, run <code>knurctl admin pair-phone my-phone</code> in your settings folder, restart Knura, and scan the code it shows with the app.</li>
    </ol>
    <div class="row small muted"><div class="spin"></div>Waiting for a phone to pair. This page notices by itself.</div>
    <p class="small muted">Pairing straight from this page comes with the Knura installer.</p>
  </div>`)
  : S.dev === 'reader' ? `<div class="sub" style="padding:18px;display:flex;flex-direction:column;gap:10px${an('rd') ? '' : ''}">
    <div class="row"><div class="tile plain">${ico('usb', 22)}</div><div><b>Plug in your reader</b><p class="small muted">You’ll set each tag up with <code>knurctl provision</code> in your settings folder, then come back here.</p></div></div></div>`
  : `<p class="small muted">Using an iPhone? The iPhone app isn’t ready yet, so use a USB reader for now.</p>`}`;
}

function s5() {
  const known = S.tags.filter(t => t.lastCounter != null);
  if (!S.tag) return `
<div class="head"><div class="kick">Step 5 of 7</div><h1>Add your first tag</h1><p>Knura tags use NTAG 424 DNA chips. Every tap makes a new link that works only once, so a copied link is refused.</p></div>
<div class="sub" style="padding:18px;display:flex;flex-direction:column;gap:10px">
  <b>1. Set the tag up</b>
  <p class="small muted">${S.dev === 'reader' ? 'Put a blank tag on the reader and run <code>knurctl provision</code>. Got a tag with a claim card? Run <code>knurctl provision --from claim:YOUR-CODE</code> instead.' : 'In the Knura app, tap <b>Set up a tag</b> and hold a blank tag to the back of the phone. Got a tag with a claim card? Tap <b>Claim a tag</b> and scan the card.'}</p>
  <b>2. Tap it with your phone</b>
  <p class="small muted">Like anyone would: unlock the phone and hold it to the tag. No app needed.</p>
</div>
<div class="tapzone">
  <div class="waiting">${ico('nfc', 34)}</div><div><b>Waiting for a tap from a new tag…</b>${S.reach && !S.reach.reachable ? '<p class="small muted" style="margin-top:4px">Phones can’t reach your Knura yet (step 3), so the tap may not arrive.</p>' : ''}</div>
</div>
${known.length ? `<div class="small muted">Or use a tag you already have: ${known.map(t => `<button class="btn sm" data-uid="${esc(t.uid)}">${esc(t.label || t.uid)}</button>`).join(' ')}</div>` : ''}`;
  return `
<div class="head"><div class="kick">Step 5 of 7</div><h1>${S.tapped ? 'It works' : 'Name your tag'}</h1><p>${S.tapped ? 'Your Knura checked the tap and knew it was real.' : 'Give it a name and pick where it goes.'}</p></div>
${S.tapped ? `<div class="tapzone" style="min-height:200px">
  <div class="burst"><svg width="46" height="46" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="${P.check}"/></svg></div>
  <div class="rise" style="animation-delay:.3s"><b style="font-size:20px">Tap verified</b><p class="muted mono small">${esc(S.tag)}</p></div></div>
<div class="note${an('refresh')}" style="animation-delay:600ms">${ico('info', 18)}<div><b>Try this:</b> refresh the page on your phone. It says <b>Link already used</b>, because each tap’s link works once. That’s what stops copied links.</div></div>` : ''}
<div><label class="fl" for="tagname">Name</label><input id="tagname" class="in" data-in="tagname" value="${esc(S.tagname)}" maxlength="40"></div>
<div><label class="fl">Icon</label><div class="icons">${ICONS.map(([k, l]) => `<button data-icon="${k}" data-label="${l}" aria-pressed="${S.tagicon === k}" title="${l}">${ico(k, 22)}<span>${l.split(' ')[0]}</span></button>`).join('')}</div></div>
${errLine()}`;
}

const CHOICES = { start: ['new', 'restore', 'hosted'], dev: ['phone', 'reader'], act: ['ha', 'matter', 'web', 'none'] };
const TYPE_OF = { ha: 'homeassistant', web: 'webhook', matter: 'matter' };
const ID_OF = { ha: 'home-assistant', web: 'web-service', matter: 'matter' };
function s6() {
  const tn = esc(S.tagname || 'your tag');
  return `
<div class="head"><div class="kick">Step 6 of 7</div><h1>Make ${tn} do something</h1><p>Pick what happens when it’s tapped. You can add more later.</p></div>
<div class="opts two">
  ${opt('act', 'ha', 'ha', 'Home Assistant', 'Turn on lights, start a scene, send a notification.')}
  ${opt('act', 'matter', 'matter', 'Google Home, Apple Home or Alexa', 'Through the Knura Matter bridge.')}
  ${opt('act', 'web', 'web', 'A web service', 'IFTTT, Zapier, Node-RED or your own code.')}
  ${opt('act', 'none', 'x', 'Not now', 'The tag still checks taps.')}
</div>
${S.act && S.act !== 'none' ? actPanel() : ''}
${errLine()}
${S.act && S.act !== 'none' ? `<div class="note small">${ico('lock', 18)}<div>Don’t use taps for locks, garage doors or alarms yet. A tap proves the tag is real, not who tapped it.</div></div>` : ''}`;
}
function actPanel() {
  const a = S.act;
  const label = a === 'ha' ? 'Home Assistant’s address' : a === 'web' ? 'The service’s webhook address' : 'The Matter bridge’s address';
  const ph = a === 'ha' ? 'http://192.168.1.50:8123' : a === 'web' ? 'https://maker.ifttt.com/trigger/…' : 'http://127.0.0.1:5541/tap';
  const hint = a === 'ha' ? 'Use its IP address, not homeassistant.local. You’ll find it in Home Assistant under Settings → System → Network.'
    : a === 'web' ? 'It must start with https://.' : `The bridge runs on this computer. <a href="${DOCS}/actions/matter" target="_blank" rel="noopener">Set up the Matter bridge</a>`;
  if (!S.connId) return `
<div><label class="fl" for="actUrl">${label}</label><div class="copyrow"><input id="actUrl" class="in" style="flex:1;min-width:220px" data-in="actUrl" placeholder="${ph}" value="${esc(S.actUrl)}" spellcheck="false"><button class="btn primary" data-act="connect" ${S.busy ? 'disabled' : ''}>${S.busy ? '<span class="spin"></span>' : ''} Connect</button></div><p class="small muted" style="margin-top:6px">${hint}</p></div>`;
  let extra = '';
  if (a === 'ha') {
    const yaml = `alias: ${S.tagname || 'Knura tag'} tap from Knura\ntriggers:\n  - trigger: webhook\n    webhook_id: ${S.secret || 'YOUR_EXISTING_WEBHOOK_ID'}\n    allowed_methods:\n      - POST\n    local_only: true\nactions:\n  - action: light.toggle\n    target:\n      entity_id: light.living_room`;
    extra = `<div style="display:flex;flex-direction:column;gap:10px">
  <b>Add this automation in Home Assistant</b>
  <p class="small muted">${S.secret ? 'Knura made a secret webhook id for it, shown only now.' : 'This connection already existed, so use the webhook id you saved then.'} In Home Assistant, go to <b>Settings → Automations & scenes → Create automation</b>, choose <b>Edit in YAML</b> from the ⋮ menu, and paste this. Change the light to the one you want.</p>
  <pre class="term" id="yaml">${esc(yaml)}</pre>
  <div class="copyrow"><button class="btn sm" data-copy="yaml">${ico('copy', 15)} Copy</button>${/^https?:\/\//i.test(S.actUrl) ? `<a class="btn sm" href="${esc(S.actUrl)}" target="_blank" rel="noopener">${ico('ha', 15)} Open Home Assistant</a>` : ''}</div></div>`;
  } else if (S.secret) {
    extra = `<div class="note warn">${ico('key', 18)}<div><b>Signing secret, shown once:</b> <span class="mono" id="sec" style="word-break:break-all">${esc(S.secret)}</span> <button class="btn sm" data-copy="sec" style="margin-left:6px">Copy</button><br><span class="small">${a === 'matter' ? 'Put it in the Matter bridge’s settings.' : 'Only needed by services that check signatures. Most can ignore it.'}</span></div></div>`;
  }
  const target = a === 'ha' ? 'Home Assistant' : a === 'matter' ? 'The Matter bridge' : 'Your web service';
  return `
<div class="sub row${an('conn' + a)}" style="padding:14px 16px;flex-wrap:wrap"><div class="tile ok">${ico(a === 'ha' ? 'ha' : a === 'web' ? 'web' : 'matter', 22)}</div><div style="flex:1;min-width:180px"><b>Connected</b><p class="small muted mono" style="word-break:break-all">${esc(S.actUrl)}</p></div><span class="pill ok">${ico('check', 13, 2.4)} ${esc(S.tagname)} uses it</span></div>
${extra}
<div class="sub" style="padding:18px;display:flex;flex-direction:column;gap:14px;align-items:center;text-align:center">
  <div class="flowline">${ico(S.tagicon, 22)}<div class="wire" style="${S.tested ? '' : 'animation:none;opacity:.35'}"></div><div class="lamp${S.tested ? ' on' : ''}">${ico(a === 'ha' ? 'lamp' : a === 'matter' ? 'house' : 'web', 34)}</div></div>
  ${S.tested ? `<div class="rise"><b>Sent to ${target.charAt(0).toLowerCase() + target.slice(1)}</b><p class="small muted">${a === 'ha' ? 'Did your light change? If not, check the automation’s trace in Home Assistant.' : 'Check that it arrived.'} Tap ${esc(S.tagname)} for real any time.</p></div>` : `<p class="small muted">Send a pretend tap to check it all works.</p>`}
  <button class="btn sm${S.tested ? '' : ' primary'}" data-act="test">${ico('bolt', 15)} ${S.tested ? 'Send another test' : 'Send a test'}</button>
</div>`;
}

function s7() {
  const phones = (S.settings && S.settings.phones) || [], live = S.reach && S.reach.reachable;
  const named = S.tags.filter(t => t.lastCounter != null || t.label);
  const first = named[0];
  const items = [
    ['key', backupDone() ? 'Recovery kit saved' : 'No recovery kit yet', backupDone() ? 'Backup file and recovery sheet' : 'Make one in step 2', backupDone()],
    ['globe', live ? 'Phones can reach your Knura' : 'Phones can’t reach your Knura yet', live ? S.reach.publicUrl : 'See step 3', live],
    [S.dev === 'reader' ? 'usb' : 'phone', phones.length ? 'Phone paired' : S.dev === 'reader' ? 'USB reader' : 'No phone paired', phones.length ? phones.join(', ') : S.dev === 'reader' ? 'Set tags up with knurctl provision' : 'See step 4', phones.length > 0 || S.dev === 'reader'],
    [first && first.icon || 'tag', named.length + (named.length === 1 ? ' tag' : ' tags') + (first ? ' · ' + (first.label || first.uid) : ''), first && first.actions && first.actions.length ? 'Runs ' + first.actions.join(', ') : 'No action yet', named.length > 0],
  ];
  return `
<div class="done-hero"><div class="tile ok pop" style="width:64px;height:64px;border-radius:20px">${ico('check', 34, 2.4)}</div><div class="head"><div class="kick">Step 7 of 7</div><h1>${esc(S.name || 'Knura')} is set up</h1><p>Knura keeps checking taps as long as it’s running on this computer.</p></div></div>
<div class="sub summary" style="padding:6px 16px"><div class="checks">${items.map(([i, t, s, ok], n) => `<div class="chk" style="animation-delay:${200 + n * 160}ms"><div class="tile${ok ? ' ok' : ''}" style="width:38px;height:38px;border-radius:12px">${ico(i, 18)}</div><div class="txt"><b>${esc(t)}</b><span>${esc(s)}</span></div><div class="state ${ok ? 'ok' : ''}">${ok ? `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="${P.check}" style="animation-delay:${400 + n * 160}ms"/></svg>` : '<div class="dotwait"></div>'}</div></div>`).join('')}</div></div>
<div class="note rise" style="animation-delay:900ms">${ico('info', 18)}<div>Taps only work while this computer is on and awake. If it sleeps, change that in its power settings.</div></div>
<div class="row" style="flex-wrap:wrap;gap:10px"><button class="btn primary" data-act="finish">Done ${ico('check', 16, 2)}</button><button class="btn" data-act="another">${ico('plus', 16)} Add another tag</button></div>`;
}

// ---------- requirements ----------
function canNext() {
  switch (S.screen) {
    case 1: return S.name.trim() && (S.session.passwordSet || (S.pw.length >= 8 && S.pw === S.pw2));
    case 2: return S.priorKit && !S.redoBackup || (S.fileDone && S.sheetDone && S.c2.trim().toLowerCase() === S.words[1] && S.c5.trim().toLowerCase() === S.words[4]);
    case 3: return !!S.reach;
    case 4: return S.dev === 'reader' || (S.dev === 'phone' && ((S.settings && S.settings.phones) || []).length > 0);
    case 5: return !!S.tag && S.tagname.trim();
    case 6: return S.act === 'none' || (S.connId && S.tested);
    default: return true;
  }
}
function whyNot() {
  switch (S.screen) {
    case 1: return S.session.passwordSet ? 'Add a name' : 'Add a password to continue';
    case 2: return 'Download both copies and check two words';
    case 4: return S.dev ? 'Pair a phone to continue' : 'Pick one';
    case 5: return S.tag ? 'Add a name' : 'Tap a tag to continue';
    case 6: return S.act ? (S.connId ? 'Send a test to continue' : 'Connect it first') : 'Pick one, or Not now';
    default: return '';
  }
}

// ---------- render ----------
function render() {
  const app = document.getElementById('app');
  const key = S.screen + '|' + (S.tag ? 1 : 0);
  const changed = lastKey !== key;
  const ae = document.activeElement, focusId = ae && ae.id, sel = ae && ae.selectionStart;
  if (S.screen === 'loading') { app.innerHTML = topbar() + '<p class="muted">Loading…</p>'; }
  else if (S.screen === 'error') { app.innerHTML = topbar() + `<div class="hello g"><h1>Knura isn’t answering</h1><p class="muted">${esc(S.err)}</p><button class="btn" data-act="reload">Try again</button></div>`; }
  else if (S.screen === 'hello') { app.innerHTML = topbar() + hello(); }
  else if (S.screen === 'signin') { app.innerHTML = topbar() + signin(); }
  else {
    const n = S.screen, body = [s1, s2, s3, s4, s5, s6, s7][n - 1]();
    const ok = canNext();
    app.innerHTML = topbar() + `<div class="layout">${rail()}<div class="main">${mbar()}<div class="card g${changed ? ' enter' + (dir === 'back' ? ' back' : '') : ''}" id="card">${body}</div>
  ${n < 7 ? `<div class="foot"><button class="btn ghost" data-act="back" ${n === 1 ? 'disabled' : ''}>${ico('back', 16, 2)} Back</button><div class="sp"></div><span class="why" id="why" style="visibility:${ok ? 'hidden' : 'visible'}">${whyNot()}</span><button class="btn primary" id="next" data-act="next" ${ok && !S.busy ? '' : 'disabled'}>${S.screen === 3 && !(S.reach && S.reach.reachable) ? 'Skip for now' : 'Continue'} ${ico('next', 16, 2)}</button></div>` : ''}</div></div>`;
  }
  lastKey = key;
  if (changed) window.scrollTo({ top: 0 });
  if (focusId && !changed) { const el = document.getElementById(focusId); if (el) { el.focus(); try { el.setSelectionRange(sel, sel); } catch (e) { /* not text */ } } }
  const f = document.getElementById('signinForm'); if (f) f.onsubmit = e => { e.preventDefault(); doSignIn(); };
}
function topbar() {
  const inApp = typeof S.screen === 'number';
  return `<div class="top"><div class="brand">${MARK(28)} Knura <small>setup</small></div><span class="addr">${ico('lock', 12)} ${esc(location.host)}</span><div class="sp"></div>${inApp ? `<span class="saved">${ico('check', 14, 2.2)} Saved on this computer</span>${S.session && S.session.signedIn ? `<button class="btn sm ghost" data-act="signout">Sign out</button>` : ''}` : ''}</div>`;
}
function rail() {
  const max = +(store.get('max') || 1);
  return `<nav class="rail g" aria-label="Setup steps">${STEPS.map((t, i) => {
    const n = i + 1, cur = S.screen === n;
    return `<button class="${cur ? 'on' : ''}${n < S.screen ? ' done' : ''}" data-go="${n}" ${n <= max && !cur ? '' : 'disabled'} ${cur ? 'aria-current="step"' : ''}><span class="n">${n < S.screen ? ico('check', 13, 2.6) : n}</span>${t}</button>`;
  }).join('')}<div class="hint">You can close this page. Knura keeps your place.</div></nav>`;
}
function mbar() {
  return `<div class="mbar g"><div class="row"><b>${STEPS[S.screen - 1]}</b><span class="muted">Step ${S.screen} of 7</span></div><div class="segs">${STEPS.map((_, i) => `<i class="${i + 1 < S.screen ? 'd' : i + 1 === S.screen ? 'c' : ''}"></i>`).join('')}</div></div>`;
}

// ---------- step changes ----------
function go(n) {
  clearTimers();
  dir = (typeof n === 'number' ? n : 0) < (typeof S.screen === 'number' ? S.screen : 0) ? 'back' : 'fwd';
  S.screen = n; S.err = '';
  if (typeof n === 'number') store.set('max', Math.max(+(store.get('max') || 1), n));
  onEnter(); render();
}
function onEnter() {
  if (S.screen === 2) { if (!S.words.length) S.words = pick6(); if (S.priorKit == null) S.priorKit = !!backupDone(); }
  if (S.screen === 3 || S.screen === 5 || S.screen === 7) checkReach();
  if (S.screen === 4) watchPhones();
  if (S.screen === 5 && !S.tag) watchTaps();
  if (S.screen === 7) loadAll().then(render);
}
async function checkReach() {
  const r = await api('GET', '/v1/reach');
  if (r.ok) { S.reach = r.json; if (S.screen === 3 || S.screen === 7) render(); }
}
function watchPhones() {
  const tick = async () => {
    if (S.screen !== 4) return;
    if (S.dev === 'phone' && !((S.settings && S.settings.phones) || []).length) {
      const r = await api('GET', '/v1/settings');
      if (r.ok && r.json.phones.length) { S.settings = r.json; render(); toast(r.json.phones[0] + ' is paired'); return; }
    }
    later(tick, 3000);
  };
  later(tick, 1500);
}
function watchTaps() {
  if (!S.tagSince) S.tagSince = new Date(Date.now() - 5000).toISOString();
  const tick = async () => {
    if (S.screen !== 5 || S.tag) return;
    const r = await api('GET', '/v1/taps?limit=20');
    if (r.ok) {
      const hit = r.json.taps.find(t => t.status === 'ok' && t.time >= S.tagSince);
      if (hit) { chooseTag(hit.uid, true); return; }
    }
    later(tick, 2000);
  };
  tick();
}
function chooseTag(uid, tapped) {
  S.tag = uid; S.tapped = tapped;
  const t = S.tags.find(x => x.uid === uid);
  if (t && t.label) { S.tagname = t.label; S.tagicon = t.icon || S.tagicon; }
  render();
}

async function next() {
  if (!canNext() || S.busy) return;
  S.err = ''; S.busy = true; render();
  try {
    if (S.screen === 1) {
      if (!S.session.passwordSet) {
        const r = await api('POST', '/v1/password', { password: S.pw });
        if (!r.ok) { S.err = why(r); return; }
        S.session = { passwordSet: true, signedIn: true }; S.pw = S.pw2 = '';
      }
      const r = await api('PUT', '/v1/settings', { name: S.name.trim() });
      if (!r.ok) { S.err = why(r); return; }
      S.settings = r.json;
      await loadAll();
    }
    if (S.screen === 5) {
      const r = await api('PUT', '/v1/tags/' + S.tag, { label: S.tagname.trim(), icon: S.tagicon });
      if (!r.ok) { S.err = why(r); return; }
    }
    if (S.screen === 6) { await loadAll(); }
    S.busy = false;
    go(S.screen + 1);
    return;
  } finally {
    S.busy = false; render();
  }
}

async function doSignIn() {
  S.busy = true; S.err = ''; render();
  const r = await api('POST', '/v1/session', { password: S.pw });
  S.busy = false; S.pw = '';
  if (!r.ok) { S.err = r.status === 401 ? 'That password is wrong.' : why(r); render(); return; }
  S.session = { passwordSet: true, signedIn: true };
  await loadAll();
  go(resumeAt());
}
function resumeAt() {
  if (store.get('done')) return 7;
  return Math.min(Math.max(+(store.get('max') || 1), 1), 7);
}

async function connect() {
  const a = S.act, url = S.actUrl.trim();
  if (!url) { S.err = 'Add the address first.'; render(); return; }
  S.busy = true; S.err = ''; render();
  const id = ID_OF[a], type = TYPE_OF[a];
  const r = await api('PUT', `/v1/connections/${type}/${id}`, { url });
  if (!r.ok) { S.busy = false; S.err = why(r); render(); return; }
  S.secret = r.json.secret;
  const t = S.tags.find(x => x.uid === S.tag);
  const ids = Array.from(new Set([...(t && t.actions) || [], id]));
  const r2 = await api('PUT', `/v1/tags/${S.tag}/actions`, { actions: ids.join(',') });
  S.busy = false;
  if (!r2.ok) { S.err = why(r2); render(); return; }
  S.connId = id;
  render();
}

async function copyFrom(id) {
  const el = document.getElementById(id); if (!el) return;
  const text = el.textContent;
  try { await navigator.clipboard.writeText(text); toast('Copied'); }
  catch (e) { const r = document.createRange(); r.selectNodeContents(el); const s = getSelection(); s.removeAllRanges(); s.addRange(r); toast('Selected. Press Ctrl+C to copy.'); }
}

// ---------- events ----------
document.addEventListener('click', async e => {
  const t = e.target.closest('[data-act],[data-set],[data-go],[data-icon],[data-uid],[data-copy]');
  if (!t || t.disabled) return;
  if (t.dataset.set) {
    const f = t.dataset.set, v = CHOICES[f] && CHOICES[f].find(c => c === t.dataset.val);
    if (!v || S[f] === v) return;
    S[f] = v; S.err = '';
    if (f === 'act') { S.connId = null; S.secret = null; S.tested = false; S.actUrl = ''; const c = S.connections.find(x => x.type === TYPE_OF[v] && x.id === ID_OF[v]); if (c) S.actUrl = c.url; }
    if (f === 'dev') { clearTimers(); watchPhones(); }
    render(); return;
  }
  if (t.dataset.go) { const n = Math.trunc(+t.dataset.go); if (n >= 1 && n <= 7) go(n); return; }
  if (t.dataset.uid) { const tag = S.tags.find(x => x.uid === t.dataset.uid); if (tag) chooseTag(tag.uid, false); return; }
  if (t.dataset.copy) { copyFrom(t.dataset.copy); return; }
  if (t.dataset.icon) {
    const pick = ICONS.find(x => x[0] === t.dataset.icon); // only our own icons, never text from the page
    if (!pick) return;
    if (ICONS.some(x => x[1] === S.tagname)) S.tagname = pick[1];
    S.tagicon = pick[0]; render(); return;
  }
  switch (t.dataset.act) {
    case 'begin': go(1); break;
    case 'next': next(); break;
    case 'back':
      if (S.screen === 5 && S.tag) { S.tag = null; S.tapped = false; S.tagSince = new Date().toISOString(); dir = 'back'; render(); watchTaps(); }
      else if (S.screen > 1) go(S.screen - 1);
      break;
    case 'showpw': S.showpw = !S.showpw; render(); break;
    case 'redo': S.redoBackup = true; S.words = pick6(); render(); break;
    case 'newwords': S.words = pick6(); S.c2 = S.c5 = ''; render(); break;
    case 'savefile': {
      S.busy = 'file'; S.err = ''; render();
      const r = await download('/v1/backup/file', { passphrase: S.words.join(' ') }, 'knura-backup.knurabackup');
      S.busy = false;
      if (!r.ok) S.err = why(r); else { S.fileDone = r.name; const st = await api('GET', '/v1/status'); if (st.ok) S.status = st.json; }
      render(); break;
    }
    case 'sheet': {
      S.busy = 'sheet'; render();
      const r = await download('/v1/backup/sheet', undefined, 'knura-recovery-sheet.html');
      S.busy = false;
      if (!r.ok) S.err = why(r); else { S.sheetDone = true; toast('Open the file, print it, then delete it'); const st = await api('GET', '/v1/status'); if (st.ok) S.status = st.json; }
      render(); break;
    }
    case 'reach': S.reach = null; render(); checkReach(); break;
    case 'connect': connect(); break;
    case 'test': {
      S.tested = false; render();
      const r = await api('POST', `/v1/tags/${S.tag}/test`);
      if (!r.ok) { S.err = why(r); render(); break; }
      if (!r.json.started) { S.err = 'Nothing ran. Check the connection and try again.'; render(); break; }
      later(() => { S.tested = true; render(); }, 600); break;
    }
    case 'another': S.tag = null; S.tapped = false; S.tagSince = null; S.tagname = 'Kitchen'; S.tagicon = 'kitchen'; S.act = null; S.connId = null; S.tested = false; store.set('done', ''); go(5); break;
    case 'finish': store.set('done', '1'); toast('All done. You can close this page.'); break;
    case 'signout': await api('DELETE', '/v1/session'); S.session.signedIn = false; S.screen = 'signin'; render(); break;
    case 'reload': location.reload(); break;
  }
});
document.addEventListener('input', e => {
  const t = e.target; if (!t.dataset || !t.dataset.in) return;
  const v = String(t.value);
  switch (t.dataset.in) { // the only fields a text box may set
    case 'name': S.name = v; break; case 'pw': S.pw = v; break; case 'pw2': S.pw2 = v; break;
    case 'c2': S.c2 = v; break; case 'c5': S.c5 = v; break;
    case 'tagname': S.tagname = v; break; case 'actUrl': S.actUrl = v; break;
    default: return;
  }
  if (t.dataset.in === 'pw' || t.dataset.in === 'pw2') {
    const st = strength(S.pw), m = document.getElementById('pwm'), pt = document.getElementById('pwt'), p2 = document.getElementById('pw2t');
    if (m) { m.style.width = st.w + '%'; m.style.background = st.c; }
    if (pt) pt.textContent = st.t;
    if (p2) p2.innerHTML = S.pw2 ? (S.pw === S.pw2 ? 'Matches' : 'Doesn’t match yet') : '&nbsp;';
  }
  if (t.dataset.in === 'c2') t.classList.toggle('okk', S.c2.trim().toLowerCase() === S.words[1]);
  if (t.dataset.in === 'c5') t.classList.toggle('okk', S.c5.trim().toLowerCase() === S.words[4]);
  const nb = document.getElementById('next');
  if (nb) { const ok = canNext(); nb.disabled = !ok || !!S.busy; const w = document.getElementById('why'); if (w) w.style.visibility = ok ? 'hidden' : 'visible'; }
});
document.addEventListener('keydown', e => {
  if (e.key === 'Enter' && e.target.dataset && e.target.dataset.in && typeof S.screen === 'number') {
    if (e.target.id === 'actUrl') connect(); else next();
  }
});

// ---------- start ----------
async function boot() {
  const r = await api('GET', '/v1/session');
  if (!r.ok) { S.screen = 'error'; S.err = r.status === 404 ? 'This Knura doesn’t have the setup page turned on.' : why(r); render(); return; }
  S.session = r.json;
  if (!r.json.passwordSet) { S.screen = 'hello'; render(); return; }
  if (!r.json.signedIn) {
    const probe = await api('GET', '/v1/settings'); // plaintext mode needs no sign-in
    if (!probe.ok) { S.screen = 'signin'; render(); return; }
  }
  await loadAll();
  go(resumeAt());
}
boot();
})();
