// Knura dashboard. Served by knurd at / on the admin port; talks only to knurd's admin API (docs/setup-api.md).
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
  wifi:'M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01',
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
const ico = (n, s = 20, w = 1.8) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${P[n]}"/></svg>`;
const MARK = (s = 30) => `<svg width="${s}" height="${s}" viewBox="0 0 32 32" role="img" aria-label="Knura"><rect x="2" y="2" width="28" height="28" rx="8" fill="none" stroke="var(--accent)" stroke-width="2.2"/><rect x="8.5" y="8.5" width="15" height="15" rx="4.5" fill="none" stroke="var(--accent)" stroke-width="2"/><rect x="12.75" y="12.75" width="6.5" height="6.5" rx="1.8" fill="var(--fg)"/></svg>`;
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
// ---------- charts ----------
function niceStep(max) {
  const raw = Math.max(1, max) / 2, mag = Math.pow(10, Math.floor(Math.log10(raw)));
  for (const m of [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) { const v = m * mag; if (v >= raw && Number.isInteger(v)) return v; }
  return Math.ceil(raw);
}
function smooth(vals, W, H, top, bot, padX, max) {
  const n = vals.length;
  const pts = vals.map((v, i) => [padX + (W - 2 * padX) * (n === 1 ? 1 : i / (n - 1)), bot - (bot - top) * (v / max)]);
  let d = `M${pts[0][0].toFixed(1)},${pts[0][1].toFixed(1)}`;
  for (let i = 1; i < n; i++) {
    const [x0, y0] = pts[i - 1], [x1, y1] = pts[i], dx = (x1 - x0) / 2;
    d += ` C${(x0 + dx).toFixed(1)},${y0.toFixed(1)} ${(x1 - dx).toFixed(1)},${y1.toFixed(1)} ${x1.toFixed(1)},${y1.toFixed(1)}`;
  }
  return { d, pts };
}
// y axis: grid lines drawn in the SVG, numbers as HTML so they stay a readable size at any width
function withY(svg, ticks, H) {
  const labels = ticks.map(([v, y]) => `<span style="position:absolute;right:calc(100% - 26px);top:${(y / H * 100).toFixed(2)}%;transform:translateY(-50%);font-size:11px;color:var(--muted);font-variant-numeric:tabular-nums">${v}</span>`).join('');
  return `<div class="ychart" style="position:relative;padding-left:34px"><div aria-hidden="true" style="position:absolute;left:0;top:0;bottom:0;width:30px">${labels}</div>${svg}</div>`;
}
const grid = (ticks, W) => ticks.map(([, y]) => `<line x1="0" y1="${y}" x2="${W}" y2="${y}" stroke="var(--line)" stroke-width="1" vector-effect="non-scaling-stroke"${y < ticks[0][1] ? ' stroke-dasharray="3 5"' : ''}/>`).join('');
let gid = 0;
function lineChart(vals, label, W = 1000, H = 120, stops = []) {
  const step = niceStep(Math.max(...vals)), max = step * 2;
  const id = 'c' + (++gid), top = 12, B = H - 8, { d, pts } = smooth(vals, W, H, top, B, 15, max), last = pts[pts.length - 1];
  const ticks = [[0, B], [step, (B + top) / 2], [max, top]];
  const bad = stops.map((i, k) => `<circle class="pulse${k % 2 ? ' late' : ''}" cx="${pts[i][0]}" cy="${pts[i][1]}" r="6" fill="var(--bad)" opacity=".6"/><circle cx="${pts[i][0]}" cy="${pts[i][1]}" r="4.5" fill="var(--bad)"/>`).join('');
  return withY(`<svg viewBox="0 0 ${W} ${H}" width="100%" role="img" aria-label="${esc(label)}, scale 0 to ${max} taps" style="display:block;overflow:visible">
    <defs><linearGradient id="${id}a" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#F28C3A" stop-opacity=".32"/><stop offset="1" stop-color="#F28C3A" stop-opacity="0"/></linearGradient>
    <filter id="${id}f" x="-200%" y="-200%" width="500%" height="500%"><feGaussianBlur stdDeviation="4"/></filter></defs>
    ${grid(ticks, W)}
    <path d="${d} L${last[0]},${B} L15,${B} Z" fill="url(#${id}a)"/>
    <path d="${d}" fill="none" stroke="var(--accent-2)" stroke-width="2" stroke-linecap="round" vector-effect="non-scaling-stroke"/>
    ${bad}
    <circle cx="${last[0]}" cy="${last[1]}" r="9" fill="var(--accent-2)" filter="url(#${id}f)"/>
    <circle class="pulse" cx="${last[0]}" cy="${last[1]}" r="6" fill="none" stroke="var(--accent-2)" stroke-width="2"/>
    <circle cx="${last[0]}" cy="${last[1]}" r="4.5" fill="var(--dot)" stroke="var(--accent)" stroke-width="1.5"/></svg>`, ticks, H);
}
function barChart(vals, stops, label) {
  const W = 300, H = 120, n = vals.length, step = niceStep(Math.max(...vals)), max = step * 2, top = 30, B = 112, gap = (W - 30) / Math.max(1, n - 1);
  const ticks = [[0, B], [step, (B + top) / 2], [max, top]];
  let out = grid(ticks, W);
  vals.forEach((v, i) => {
    const x = 15 + gap * i, h = Math.max(3, (B - top) * v / max);
    out += `<rect x="${(x - 4).toFixed(1)}" y="${(B - h).toFixed(1)}" width="8" height="${h.toFixed(1)}" rx="4" fill="var(--bars)"/>`;
  });
  stops.forEach((i, k) => {
    const x = 15 + gap * i;
    out += `<circle class="pulse${k % 2 ? ' late' : ''}" cx="${x.toFixed(1)}" cy="12" r="6" fill="var(--bad)" opacity=".6"/><circle cx="${x.toFixed(1)}" cy="12" r="4.5" fill="var(--bad)"/>`;
  });
  return withY(`<svg viewBox="0 0 ${W} ${H}" width="100%" role="img" aria-label="${esc(label)}, scale 0 to ${max} taps" style="display:block;overflow:visible">${out}</svg>`, ticks, H);
}
const narrow = () => window.innerWidth < 760;
const axis = labels => `<div class="axis" style="padding-left:36px">${labels.map(l => `<span>${esc(l)}</span>`).join('')}</div>`;


// ---------- toast & modal ----------
let tt;
function toast(msg) { const el = document.getElementById('toast'); el.textContent = msg; el.classList.add('show'); clearTimeout(tt); tt = setTimeout(() => el.classList.remove('show'), 2600); }
function modal(html, onMount) {
  const root = document.getElementById('modal-root');
  root.innerHTML = `<div class="modal-bg" data-close><div class="g modal" role="dialog" aria-modal="true">${html}</div></div>`;
  const bg = root.firstElementChild;
  bg.addEventListener('click', e => { if (e.target === bg || e.target.closest('[data-x]')) closeModal(); });
  onMount && onMount(bg);
  const f = bg.querySelector('input,button'); f && f.focus();
}
function closeModal() { document.getElementById('modal-root').innerHTML = ''; }
document.addEventListener('keydown', e => {
  if (e.key === 'Backspace' && e.target.dataset && e.target.dataset.digit !== undefined && !e.target.value) { const p = document.querySelector(`[data-digit="${+e.target.dataset.digit - 1}"]`); p && p.focus(); } if (e.key === 'Escape') { closeModal(); if (S.notif) { S.notif = false; render(); } } });
function copy(text, what) { try { navigator.clipboard.writeText(text).then(() => toast(what + ' copied'), () => toast(what + ' copied')); } catch (e) { toast(what + ' copied'); } }

Object.assign(P, {
  list:'M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01',
  usb:'M12 2v14M9 5l3-3 3 3M7 9v3l5 3 5-3V9M12 16a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z',
  warn:'M12 4l9 16H3zM12 10v4M12 17h.01',
  refresh:'M20 11a8 8 0 0 0-14.6-4.5M4 4v4h4M4 13a8 8 0 0 0 14.6 4.5M20 20v-4h-4',
  play:'M7 4.5v15l12-7.5z',
  globe:'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c2.5 2.5 3.5 5.5 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-5.5-3.5-9s1-6.5 3.5-9z',
  server:'M4 4h16v7H4zM4 13h16v7H4zM8 7.5h.01M8 16.5h.01',
  kitchen:'M7 3v8M5 3v4a2 2 0 0 0 4 0V3M7 11v10M17 3c-2 1.5-3 4-3 7h3v11',
  sofa:'M4 11V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v3M2 13a2 2 0 0 1 4 0v2h12v-2a2 2 0 0 1 4 0v5H2zM5 18v2M19 18v2',
  box:'M3 7l9-4 9 4v10l-9 4-9-4zM3 7l9 4 9-4M12 11v10',
  wand:'M4 20L15 9M14 4v2M19 9h2M17.5 6.5l1.5-1.5M10 4.5l.5 1.5M18 13l1.5.5'
});
// The same icons the setup page offers, plus a few more.
const ICONS = [['door','Front door'],['kitchen','Kitchen'],['sofa','Living room'],['bed','Bedroom'],['garage','Garage'],['office','Office'],['pantry','Pantry'],['car','Car'],['box','Storage'],['lamp','Lamp'],['mail','Mailbox'],['gate','Gate'],['coffee','Coffee'],['lock','Lock'],['house','House']];
// Only our own icon names ever reach the page, never text from knurd or the page itself.
const iconOf = t => (t && ICONS.some(x => x[0] === t.icon)) ? t.icon : 'tag';

// ---------- state ----------
const S = {
  screen: 'loading', session: null, status: null, settings: null, tags: [], conns: [], taps: [], days: null, reach: null,
  range: 'week', notif: false, words: null, ppSaved: false, logs: null, logFilter: 'all', logQ: '', logLive: false, q: '', tapRes: 'all', tapTag: 'all', tapWhen: '7', renaming: false, authErr: '', busy: false
};
const store = { get(k){ try { return localStorage.getItem('knura.dashboard.' + k); } catch(e){ return null; } }, set(k,v){ try { localStorage.setItem('knura.dashboard.' + k, v); } catch(e){} } };
const curTheme = () => document.documentElement.dataset.theme || (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
const setTheme = t => { document.documentElement.dataset.theme = t; store.set('theme', t); };
{ const t = store.get('theme'); if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t; }

// ---------- API ----------
async function api(method, path, body) {
  const opts = { method, credentials: 'same-origin', headers: { 'X-Knura-Request': '1' } };
  if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
  let r;
  try { r = await fetch(path, opts); } catch (e) { return { ok: false, status: 0, json: { detail: 'Knura isn’t answering. Is it still running?' } }; }
  let json = null;
  if ((r.headers.get('Content-Type') || '').includes('json')) { try { json = await r.json(); } catch (e) { /* empty */ } }
  if (r.status === 401 && S.screen !== 'signin' && path !== '/v1/session' && path !== '/v1/password') { S.screen = 'signin'; render(); }
  return { ok: r.ok, status: r.status, json };
}
const why = r => (r.json && r.json.detail) ? r.json.detail.charAt(0).toUpperCase() + r.json.detail.slice(1) + (/[.!?]$/.test(r.json.detail) ? '' : '.') : 'Something went wrong (' + r.status + ').';

async function loadAll() {
  const [st, set, tg, cn, tp, dy] = await Promise.all([api('GET', '/v1/status'), api('GET', '/v1/settings'), api('GET', '/v1/tags'),
    api('GET', '/v1/connections'), api('GET', '/v1/taps?limit=1000'), api('GET', '/v1/taps/days?days=14')]);
  if (st.ok) S.status = st.json;
  if (set.ok) S.settings = set.json;
  if (tg.ok) S.tags = tg.json.tags;
  if (cn.ok) S.conns = cn.json.connections;
  if (tp.ok) S.taps = tp.json.taps;
  if (dy.ok) S.days = dy.json;
  const tk = await api('GET', '/v1/tokens');
  S.tokens = tk.ok && Array.isArray(tk.json.tokens) ? tk.json.tokens.filter(t => typeof t === 'string') : null;
  const od = await api('GET', '/v1/settings/other-devices');
  S.lan = od.ok ? od.json : null;
  if (location.hash === '#logs') await loadLogs();
}
async function loadReach() {
  const r = await api('GET', '/v1/reach');
  S.reach = r.ok ? r.json : { reachable: false, detail: why(r) };
  if (S.screen === 'app' && !S.modal) render();
}

// ---------- words ----------
const tagName = t => t ? (t.label || 'Tag ' + t.uid.slice(-4)) : 'Unknown tag';
const tagByUid = uid => S.tags.find(t => t.uid === uid);
const RESULT = { ok: ['ok', 'Genuine'], replay: ['bad', 'Copied link'], rejected: ['bad', 'Not genuine'], malformed: ['warn', 'Broken link'] };
const resultPill = s => { const [tone, word] = RESULT[s] || ['mut', s]; return `<span class="pill ${tone}">${word}</span>`; };
const tapWhat = e => e.status === 'ok' ? (e.actions ? 'Ran ' + e.actions + (e.actions === 1 ? ' action' : ' actions') : 'No actions to run')
  : e.status === 'replay' ? 'Someone reused an old tap. Nothing ran.' : e.status === 'rejected' ? 'The signature didn’t match. Nothing ran.' : 'The link was broken or cut short. Nothing ran.';
function ago(iso) {
  if (!iso) return 'never';
  const s = Math.max(0, (Date.now() - Date.parse(iso)) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return Math.floor(s / 60) + ' min ago';
  if (s < 86400) return Math.floor(s / 3600) + ' h ago';
  if (s < 172800) return 'yesterday';
  return Math.floor(s / 86400) + ' days ago';
}
function when(iso) {
  const d = new Date(iso), now = new Date(), y = new Date(now); y.setDate(now.getDate() - 1);
  const t = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  if (d.toDateString() === now.toDateString()) return 'Today ' + t;
  if (d.toDateString() === y.toDateString()) return 'Yesterday ' + t;
  return d.toLocaleDateString([], { month: 'short', day: 'numeric' }) + ', ' + t;
}
function uptime(sec) {
  if (sec < 3600) return Math.max(1, Math.floor(sec / 60)) + ' min';
  if (sec < 172800) return Math.floor(sec / 3600) + ' h ' + Math.floor(sec % 3600 / 60) + ' min';
  return Math.floor(sec / 86400) + ' days';
}
const dayLabel = iso => { const d = new Date(iso + 'T12:00:00'); return d.toLocaleDateString([], { month: 'short', day: 'numeric' }); };
const weekday = iso => new Date(iso + 'T12:00:00').toLocaleDateString([], { weekday: 'short' });
const localDay = iso => { const d = new Date(iso); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
const sum = a => a.reduce((x, y) => x + y, 0);

// ---------- health checks ----------
function checks() {
  const st = S.status || {}, out = [];
  out.push({ tone: 'ok', icon: 'server', t: 'Knura is running', s: 'Up ' + uptime(st.uptimeSeconds || 0) + ' · checking taps on this computer' });
  const r = S.reach;
  if (!r) out.push({ tone: 'mut', icon: 'globe', t: 'Checking whether phones can reach this computer…', s: 'This takes a few seconds' });
  else if (!r.publicUrl) out.push({ tone: 'bad', icon: 'globe', t: 'Phones can’t reach this computer yet', s: 'There’s no public address, so taps away from this computer have nowhere to go.', fix: ['/setup', 'Set up an address'] });
  else if (r.restart) out.push({ tone: 'warn', icon: 'globe', t: 'Restart Knura to use the new address', s: 'The address changed to ' + r.publicUrl.replace(/^https?:\/\//, '') + '. Phone pairing and tap.knura.io use the old one until Knura restarts.' });
  else if (r.reachable) out.push({ tone: 'ok', icon: 'globe', t: 'Phones can reach this computer', s: 'At ' + r.publicUrl.replace(/^https?:\/\//, '') });
  else out.push({ tone: 'bad', icon: 'globe', t: 'Phones can’t reach this computer', s: r.publicUrl.replace(/^https?:\/\//, '') + ' didn’t answer (' + (r.detail || 'no answer') + '). If you use Tailscale, check that it’s running.', fix: ['recheck', 'Check again'] });
  if (st.resolverOwner) out.push({ tone: 'ok', icon: 'link', t: 'tap.knura.io forwards your taps', s: 'Tags made with the tap.knura.io link reach you here' });
  const used = S.tags.filter(t => t.actions && t.actions.length).length;
  if (!S.conns.length) out.push({ tone: 'warn', icon: 'bolt', t: 'No actions yet', s: 'Taps are checked, but nothing happens when a tag is tapped.', fix: ['#actions', 'Add an action'] });
  else if (S.conns.some(c => connState(c)[0] === 'bad')) { const bad = S.conns.filter(c => connState(c)[0] === 'bad'); out.push({ tone: 'bad', icon: 'bolt', t: bad.map(c => c.id).join(', ') + (bad.length === 1 ? ' isn’t answering' : ' aren’t answering'), s: connState(bad[0])[2], fix: ['#actions', 'See actions'] }); }
  else out.push({ tone: 'ok', icon: 'bolt', t: S.conns.length + (S.conns.length === 1 ? ' action' : ' actions') + ' ready', s: S.conns.map(c => c.id).join(', ') + ' · used by ' + used + (used === 1 ? ' tag' : ' tags') });
  if (st.backup) {
    const b = st.backup, last = [b.file, b.sheet].filter(Boolean).sort().pop();
    if (!last) out.push({ tone: 'bad', icon: 'key', t: 'Your master key has no backup', s: 'Every tag’s keys come from it. If this computer dies without a backup, your tags stop working.', fix: ['#backup', 'Make a backup'] });
    else if (!b.file) out.push({ tone: 'warn', icon: 'key', t: 'Only a recovery sheet so far', s: 'Printed ' + ago(b.sheet) + '. A backup file also keeps your tags’ names and actions.', fix: ['#backup', 'Make a backup file'] });
    else out.push({ tone: 'ok', icon: 'key', t: 'Your master key is backed up', s: 'Backup file made ' + ago(b.file) + (b.sheet ? ' · recovery sheet printed ' + ago(b.sheet) : '') });
  }
  if (S.settings && S.settings.codeClone) out.push({ tone: 'bad', icon: 'folder', t: 'Your settings are inside the code folder', s: 'Deleting ' + S.settings.codeClone + ' to download Knura again would delete your master key too. Move the settings folder somewhere else.', fix: ['#backup', 'How to fix it'] });
  const phones = (S.settings && S.settings.phones) || [];
  out.push(phones.length ? { tone: 'ok', icon: 'phone', t: phones.length === 1 ? 'A phone is paired' : phones.length + ' phones are paired', s: phones.join(', ') + ' can add tags' }
    : { tone: 'mut', icon: 'phone', t: 'No phone paired', s: 'Add tags with a USB reader and knurctl, or pair a phone.', fix: ['#phones', 'Pair a phone'] });
  return out;
}
const toneIcon = t => t === 'ok' ? 'check' : t === 'warn' || t === 'bad' ? 'warn' : 'info';
P.info = 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v5M12 8h.01';
const needs = () => checks().filter(c => c.tone === 'warn' || c.tone === 'bad');
const fixBtn = (c, primary) => !c.fix ? '' : /^[/#]/.test(c.fix[0]) ? `<a class="btn sm${primary ? ' primary' : ''}" href="${c.fix[0]}">${c.fix[1]}</a>` : `<button class="btn sm${primary ? ' primary' : ''}" data-act="${c.fix[0]}">${c.fix[1]}</button>`;

// ---------- shell ----------
const NAV = [['home','Home','home'],['tags','Tags','tag'],['taps','Taps','list'],['actions','Actions','bolt'],['phones','Phones and readers','phone'],['backup','Keys and backup','key','desk'],['logs','Logs','logs','desk'],['config','Settings','gear','end']];
const greet = () => { const h = new Date().getHours(); return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'; };
const homeName = () => (S.settings && S.settings.name) || (S.status && S.status.name) || 'Knura';
function shell(route, title, body, extra = '') {
  const sec = route === 'tag' ? 'tags' : route;
  const nav = NAV.map(([r, l, i, d]) => `<a href="#${r}" class="${sec === r ? 'on' : ''} ${d || ''}" aria-label="${l}" ${sec === r ? 'aria-current="page"' : ''}>${ico(i, 22)}<span class="tip">${l}</span></a>`).join('');
  const n = needs();
  const notes = S.notif ? `<div class="g pop" role="dialog" aria-label="Needs a look"><div style="display:flex;justify-content:space-between;align-items:center;padding:6px 8px 10px"><b style="font-size:15px">Needs a look</b><span class="muted" style="font-size:12.5px">${n.length || 'Nothing'}</span></div>
    <div style="display:flex;flex-direction:column;gap:6px">${n.length ? n.map(c => `<div class="g2 row" style="padding:12px"><span class="tile ${c.tone}" style="width:38px;height:38px;border-radius:12px">${ico(toneIcon(c.tone), 18, 2.2)}</span><div style="min-width:0;flex:1"><div style="font-size:14px;font-weight:600">${esc(c.t)}</div><div class="muted" style="font-size:12.5px">${esc(c.s)}</div></div></div>`).join('') : '<div class="g2" style="padding:14px;font-size:14px">Everything is working.</div>'}</div></div>` : '';
  return `<div class="shell">
  <nav class="g rail" aria-label="Sections"><a href="#home" class="logo" aria-label="Knura home">${MARK(30)}</a>${nav}</nav>
  <main class="page">
    <header class="top">
      <div class="title"><span class="muted" style="font-size:13.5px">${esc(homeName())} · <span class="mono" style="font-size:12.5px">${esc(location.host)}</span></span><h1>${title}</h1></div>
      ${extra}
      <div class="g me">
        <button class="iconbtn" data-act="notif" aria-label="Needs a look" aria-expanded="${S.notif}">${ico('bell', 21)}${n.length ? '<span class="badge"></span>' : ''}</button>
        <a class="avatar" href="#config" aria-label="Settings" style="text-decoration:none;color:inherit">${ico('gear', 20, 2)}</a>${notes}
      </div>
    </header>
    <div class="view">${body}</div>
  </main></div>`;
}

// ---------- screens ----------
function vSignin() {
  const dark = curTheme() === 'dark';
  return `<div class="auth"><button class="g iconbtn themebtn" data-act="theme" aria-label="Switch to ${dark ? 'light' : 'dark'} theme">${ico(dark ? 'sun' : 'moon', 20)}</button>
    <form class="g auth-card" data-form="signin" novalidate>
      <div class="auth-mark">${MARK(38)}</div>
      <h1>${esc(homeName())}</h1><p class="lead">Knura on <span class="mono">${esc(location.host)}</span>. Sign in with the page password you made during setup.</p>
      ${S.authErr ? `<div class="err" role="alert">${ico('shield', 18)}<span>${esc(S.authErr)}</span></div>` : ''}
      <div><label class="fl" for="pw">Page password</label><div class="field"><span class="fi">${ico('lock', 18)}</span><input id="pw" type="password" placeholder="Your page password" autocomplete="current-password"><button type="button" class="iconbtn" data-act="eye" data-for="pw" aria-label="Show password">${ico('eye', 19)}</button></div></div>
      <button class="btn primary block"${S.busy ? ' disabled' : ''}>${S.busy ? 'Signing in…' : 'Sign in'}</button>
      <div class="sub" style="padding:12px 14px;font-size:13px;color:var(--muted);line-height:1.55">Forgot it? On the Knura computer, delete <span class="mono" style="color:var(--fg)">knurd-admin-password</span> from the settings folder, then open this page there to make a new one.</div>
    </form>
    <p class="auth-legal">A tap you can trust. · <a href="https://docs.knura.io" rel="noreferrer">docs.knura.io</a></p></div>`;
}
function rangeData() {
  const d = S.days ? S.days.days : [];
  if (S.range === 'today') {
    const start = new Date(); start.setHours(0, 0, 0, 0);
    const hours = new Array(new Date().getHours() + 1).fill(0);
    S.taps.forEach(e => { const t = new Date(e.time); if (e.status === 'ok' && t >= start) hours[t.getHours()]++; });
    const vals = hours.length < 2 ? [0, hours[0]] : hours;
    const labels = ['12 AM', vals.length > 12 ? '6 AM' : '', vals.length > 12 ? '12 PM' : '', 'Now'].filter(Boolean);
    return { vals, labels, total: sum(hours), stopped: S.taps.filter(e => e.status !== 'ok' && new Date(e.time) >= start).length };
  }
  const n = S.range === 'week' ? 7 : 14, part = d.slice(-n);
  const vals = part.map(x => x.ok);
  const labels = n === 7 ? part.map((x, i) => i === n - 1 ? 'Today' : weekday(x.day)) : [0, 4, 8, 13].map(i => part[i] ? (i === 13 ? 'Today' : dayLabel(part[i].day)) : '');
  return { vals: vals.length ? vals : [0, 0], labels, total: sum(vals), stopped: sum(part.map(x => x.stopped)) };
}
function vHome() {
  const r = rangeData(), cs = checks(), n = cs.filter(c => c.tone === 'warn' || c.tone === 'bad');
  const worst = n.some(c => c.tone === 'bad') ? 'bad' : n.length ? 'warn' : 'ok';
  const line = worst === 'ok' ? 'Everything is working.' : n.length === 1 ? 'One thing needs a look.' : n.length + ' things need a look.';
  const word = S.range === 'today' ? 'today' : S.range === 'week' ? 'this week' : 'in the last 14 days';
  const lead = worst !== 'ok' ? n.map(c => c.t).join('. ') + '.'
    : r.total ? r.total + (r.total === 1 ? ' genuine tap ' : ' genuine taps ') + word + (r.stopped ? ', and ' + r.stopped + (r.stopped === 1 ? ' copied or forged tap was' : ' copied or forged taps were') + ' stopped before they did anything.' : '.') : 'No taps ' + word + ' yet. Tap a tag to see it here.';
  const last = S.taps.find(e => e.uid), lt = last && tagByUid(last.uid);
  const seg = `<div class="g seg" role="tablist" aria-label="Time range">${[['today','Today'],['week','This week'],['14','14 days']].map(([k, l]) => `<button role="tab" aria-selected="${S.range === k}" data-range="${k}">${l}</button>`).join('')}</div>`;
  const hero = n[0] && n[0].fix ? fixBtn(n[0], true).replace('btn sm', 'btn') : `<button class="btn primary" data-act="add-tag">${ico('plus', 16, 2.2)}Add a tag</button>`;
  const shown = S.tags.slice().sort((a, b) => (b.lastCounter != null) - (a.lastCounter != null));
  const body = `
    <section class="g hero" aria-label="Status">
      <span class="tile ${worst}" style="width:64px;height:64px;border-radius:22px">${ico(worst === 'ok' ? 'check' : 'warn', 30, 2)}</span>
      <div style="flex:1 1 320px;min-width:0;display:flex;flex-direction:column;gap:6px"><span class="kick">Right now</span><h2>${line}</h2><p class="muted" style="font-size:14px;max-width:62ch">${esc(lead)}</p></div>
      ${hero}
    </section>
    <div class="two">
      <article class="g card" style="padding:20px 24px 16px">
        <div style="display:flex;justify-content:space-between;align-items:baseline;gap:12px"><h2>Genuine taps</h2><span class="muted" style="font-size:12.5px">${r.stopped ? r.stopped + ' stopped' : 'none stopped'}</span></div>
        <span class="big">${r.total}</span>
        ${lineChart(r.vals, 'Genuine taps over time', narrow() ? 420 : 760, 150)}
        ${axis(r.labels)}
      </article>
      ${last ? `<a class="g card lift" href="#tag-${last.uid}" style="text-decoration:none;color:inherit">
        <div style="display:flex;justify-content:space-between;align-items:baseline"><h2>Last tap</h2><span class="muted" style="font-size:12.5px">${ago(last.time)}</span></div>
        <div style="flex:1;display:flex;align-items:center;justify-content:center;position:relative;min-height:120px">
          <div aria-hidden="true" style="position:absolute;width:150px;height:150px;border-radius:50%;background:radial-gradient(circle,var(--accent-tint) 0%,transparent 70%)"></div>
          <span class="tile" style="position:relative;width:76px;height:76px;border-radius:24px">${ico(iconOf(lt), 36, 1.7)}</span></div>
        <div class="nm"><b style="font-size:16px">${esc(tagName(lt || { uid: last.uid }))}</b>${resultPill(last.status)}</div>
        <span class="muted" style="font-size:12.5px">${esc(tapWhat(last))}</span>
      </a>` : `<article class="g card empty-cta"><span class="tile" style="width:64px;height:64px;border-radius:20px">${ico('nfc', 30, 1.7)}</span><b>No taps yet</b><span class="muted small">Hold a phone to one of your tags. It shows up here straight away.</span></article>`}
    </div>
    <section class="g panel" aria-labelledby="hc">
      <div class="panel-head"><h2 id="hc">Health checks</h2><button class="btn sm" data-act="recheck">${ico('refresh', 15)}Check again</button></div>
      <div class="checks">${cs.map(c => `<div class="check-row"><span class="tile ${c.tone}" style="width:40px;height:40px;border-radius:12px">${ico(c.tone === 'ok' ? 'check' : c.tone === 'mut' ? c.icon : 'warn', 18, 2.2)}</span><span class="txt"><b>${esc(c.t)}</b><span>${esc(c.s)}</span></span>${fixBtn(c, c.tone === 'bad')}</div>`).join('')}</div>
    </section>
    <section style="display:flex;flex-direction:column;gap:12px">
      <div style="display:flex;justify-content:space-between;align-items:baseline;padding:0 4px"><h2 style="font-size:17px;font-weight:600">Your tags</h2><a href="#tags" style="font-size:13.5px;text-decoration:none">See all ${S.tags.length}</a></div>
      ${shown.length ? `<div class="chips">${shown.map(t => { const lt = S.taps.find(e => e.uid === t.uid); return `<a class="g lift chip" href="#tag-${t.uid}"><span class="tile">${ico(iconOf(t), 21)}</span><span style="flex:1;min-width:0;display:flex;flex-direction:column;gap:2px"><span class="nm"><b>${esc(tagName(t))}</b>${lt ? resultPill(lt.status) : ''}</span><span class="muted ell" style="font-size:12.5px">${lt ? ago(lt.time) : 'No taps yet'}</span></span></a>`; }).join('')}</div>`
        : `<div class="g empty-cta"><b>No tags yet</b><span class="muted small">Add your first tag with the Knura app or a USB reader.</span><button class="btn primary" data-act="add-tag">${ico('plus', 16, 2.2)}Add a tag</button></div>`}
    </section>`;
  return shell('home', greet(), body, seg);
}
function tagWeek(t) {
  const d = S.days && S.days.tags[t.uid];
  return d ? d.slice(-7) : [0, 0, 0, 0, 0, 0, 0];
}
function vTags() {
  const q = S.q.toLowerCase();
  const list = S.tags.filter(t => !q || tagName(t).toLowerCase().includes(q) || t.uid.toLowerCase().includes(q));
  const body = `
    <div class="row" style="flex-wrap:wrap">
      <div style="position:relative;flex:1 1 260px;max-width:420px"><span class="muted" style="position:absolute;left:14px;top:12px">${ico('search', 18)}</span><label class="sr" for="q">Search tags</label><input class="in" id="q" placeholder="Search by name or UID" value="${esc(S.q)}" style="padding-left:42px;border-radius:999px"></div>
      <span class="muted" style="font-size:13.5px">${S.tags.length} ${S.tags.length === 1 ? 'tag' : 'tags'}</span>
      <button class="btn primary" data-act="add-tag" style="margin-left:auto">${ico('plus', 16, 2.2)}Add a tag</button>
    </div>
    <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(min(100%,270px),1fr));gap:16px">
      ${list.map(t => { const wk = tagWeek(t), lt = S.taps.find(e => e.uid === t.uid); return `<a class="g lift" href="#tag-${t.uid}" style="padding:18px;display:flex;flex-direction:column;gap:14px;text-decoration:none;color:inherit">
        <div class="row"><span class="tile" style="width:52px;height:52px;border-radius:16px">${ico(iconOf(t), 24)}</span><div style="flex:1;min-width:0"><div class="nm"><b style="font-size:16px">${esc(tagName(t))}</b>${lt ? resultPill(lt.status) : ''}</div><div class="mono muted ell" style="font-size:12.5px">${t.uid}</div></div></div>
        <div>${lineChart(wk, tagName(t) + ' taps, last 7 days', 300, 96)}</div>
        <div style="display:flex;justify-content:space-between;font-size:13px;gap:8px"><span><b style="font-weight:600">${sum(wk)}</b> <span class="muted">this week</span></span><span class="muted ell">${lt ? 'Last tap ' + ago(lt.time) : 'No taps yet'}</span></div></a>`; }).join('')
        || `<div class="g empty" style="grid-column:1/-1">${S.tags.length ? 'No tags match “' + esc(S.q) + '”.' : 'No tags yet. Add one with the Knura app or a USB reader.'}</div>`}
    </div>`;
  return shell('tags', 'Tags', body);
}
function actionRow(t, c) {
  const on = (t.actions || []).includes(c.id);
  const kind = c.type === 'homeassistant' ? 'Home Assistant' : c.type === 'matter' ? 'Matter' : 'Webhook';
  const icon = c.type === 'homeassistant' ? 'bolt' : c.type === 'matter' ? 'matter' : 'link';
  return `<div class="row" style="padding:12px 14px;border-radius:18px;background:${on ? 'var(--accent-tint)' : 'var(--sub)'};border:1px solid ${on ? 'var(--accent-line)' : 'var(--sub-line)'};gap:14px">
    <span class="tile plain" style="width:42px;height:42px;border-radius:13px">${ico(icon, 20)}</span>
    <span style="flex:1;min-width:0;display:flex;flex-direction:column;gap:2px"><span class="mono" style="font-size:14.5px;font-weight:600">${esc(c.id)}</span><span class="muted ell" style="font-size:12.5px">${kind}${c.url ? ' · ' + esc(c.url) : ''}</span></span>
    <button class="sw" role="switch" aria-checked="${on}" aria-label="${esc(c.id)}" data-toggle="${esc(c.id)}"><span></span></button></div>`;
}
function vTag(uid) {
  const t = tagByUid(uid);
  if (!t) return shell('tag', 'Tags', `<div class="g empty">Knura doesn’t know a tag with the UID <span class="mono">${esc(uid)}</span>. <a href="#tags">See all tags</a></div>`);
  const hist = S.taps.filter(e => e.uid === t.uid);
  const d = (S.days && S.days.tags[t.uid]) || new Array(14).fill(0), days = S.days ? S.days.days : [];
  const stopIdx = days.map((x, i) => i).filter(i => S.taps.some(e => e.uid === t.uid && e.status !== 'ok' && localDay(e.time) === days[i].day));
  const stopped = hist.filter(e => e.status !== 'ok').length, week = sum(d.slice(-7));
  const head = S.renaming ? `<form data-form="rename" class="row" style="flex:1 1 260px;flex-wrap:wrap"><label class="sr" for="nn">Tag name</label><input class="in" id="nn" maxlength="40" value="${esc(t.label || '')}" placeholder="Front door" style="flex:1;min-width:180px;font-size:18px;font-weight:600"><button class="btn primary">Save</button><button type="button" class="btn" data-act="rename-cancel">Cancel</button></form>`
    : `<div style="flex:1 1 240px;min-width:0;display:flex;flex-direction:column;gap:4px"><div class="row" style="gap:10px;flex-wrap:wrap"><h2 style="font-size:30px;font-weight:700;letter-spacing:-.025em;overflow-wrap:anywhere">${esc(tagName(t))}</h2><span class="pill ok">${ico('check', 12, 3)}Signed tag</span></div><span class="mono muted" style="font-size:13px">${t.uid}${t.lastCounter != null ? ' · tapped ' + t.lastCounter + (t.lastCounter === 1 ? ' time' : ' times') : ' · not tapped yet'}</span></div>
      <div class="row" style="flex-wrap:wrap;gap:10px"><button class="btn" data-act="rename">Rename</button><button class="btn" data-act="icon">Change icon</button></div>`;
  const on = S.conns.filter(c => (t.actions || []).includes(c.id));
  const body = `
    <a href="#tags" class="muted" style="align-self:flex-start;display:inline-flex;align-items:center;gap:6px;font-size:14px;text-decoration:none;padding:4px 0">${ico('back', 16, 2)}All tags</a>
    <header class="g" style="padding:20px 22px;display:flex;flex-wrap:wrap;align-items:center;gap:18px">
      <span class="tile" style="width:72px;height:72px;border-radius:22px;box-shadow:0 0 40px -6px rgba(242,140,58,.55)">${ico(iconOf(t), 34, 1.7)}</span>${head}</header>
    <div class="split">
      <section class="g" style="padding:22px;display:flex;flex-direction:column;gap:12px" aria-labelledby="when">
        <div class="panel-head"><h2 id="when" style="font-size:18px;font-weight:600">When it’s tapped</h2><span class="muted" id="saved" style="font-size:12.5px">Changes save straight away</span></div>
        ${S.conns.length ? `<span class="label">Runs on this computer</span>${S.conns.map(c => actionRow(t, c)).join('')}`
          : `<div class="sub" style="padding:16px;display:flex;flex-direction:column;gap:8px"><b style="font-size:14px">No actions yet</b><span class="muted small">Connect Home Assistant, Matter or a webhook, then choose here what this tag does.</span><a class="btn sm" href="/setup" style="align-self:flex-start">Add an action in setup</a></div>`}
        <div class="row" style="flex-wrap:wrap;margin-top:auto;gap:10px"><button class="btn" data-act="test-tag"${on.length ? '' : ' disabled'}>${ico('play', 15)}Test these actions</button></div>
        <p class="muted" style="font-size:12.5px">A test runs the actions that are on, as if the tag were tapped, with tap number 0. It isn’t counted as a tap.</p>
      </section>
      <div style="display:flex;flex-direction:column;gap:20px">
        <div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px">
          ${[[week, 'genuine taps this week'], [t.lastCounter == null ? 0 : t.lastCounter, "the tag’s own count"], [stopped, stopped === 1 ? 'tap stopped' : 'taps stopped']].map(([n, l]) => `<div class="g" style="border-radius:22px;padding:16px 18px"><div class="big" style="font-size:32px">${n}</div><div class="muted" style="font-size:12.5px">${l}</div></div>`).join('')}
        </div>
        <section class="g" style="flex:1;padding:20px 22px 16px;display:flex;flex-direction:column;gap:10px" aria-labelledby="perday">
          <div class="panel-head"><h2 id="perday" style="font-size:15px;font-weight:600">Genuine taps per day</h2><span class="muted" style="font-size:12.5px">Last 14 days</span></div>
          <div style="margin-top:auto">${lineChart(d, 'Genuine taps per day over 14 days', 600, 260, stopIdx)}</div>
          ${axis(days.length ? [0, 4, 8, 13].map(i => i === 13 ? 'Today' : dayLabel(days[i].day)) : [])}
        </section>
      </div>
    </div>
    <section class="g" style="padding:20px 22px" aria-labelledby="hist">
      <div class="panel-head" style="padding-bottom:8px"><h2 id="hist" style="font-size:15px;font-weight:600">Recent taps</h2><a class="btn sm" href="#taps" data-act="taps-for" data-uid="${t.uid}">See all in Taps</a></div>
      <div class="list">${hist.slice(0, 8).map(e => `<div style="display:flex;flex-wrap:wrap;align-items:center;gap:8px 16px;min-height:52px"><span style="flex:0 0 160px;font-size:14px">${when(e.time)}</span>${resultPill(e.status)}<span class="muted" style="flex:1 1 200px;font-size:13.5px">${esc(tapWhat(e))}</span><span class="mono muted" style="font-size:12.5px">#${e.counter}</span></div>`).join('') || '<p class="muted" style="padding:12px 0;font-size:14px">No taps yet. Hold a phone to the tag to try it.</p>'}</div>
    </section>`;
  return shell('tag', 'Tags', body);
}
function tapRows() {
  const f = S.tapRes, tg = S.tapTag, days = S.tapWhen === 'all' ? null : +S.tapWhen;
  const since = days == null ? 0 : (() => { const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - (days - 1)); return d.getTime(); })();
  return S.taps.filter(e => (f === 'all' || (f === 'ok') === (e.status === 'ok')) && (tg === 'all' || e.uid === tg) && Date.parse(e.time) >= since);
}
function vTaps() {
  const rows = tapRows();
  const body = `
    <div class="filter">
      <div class="g seg" role="tablist" aria-label="Result">${[['all','All'],['ok','Genuine'],['stopped','Stopped']].map(([k, l]) => `<button role="tab" aria-selected="${S.tapRes === k}" data-res="${k}">${l}</button>`).join('')}</div>
      <label class="sr" for="tt">Tag</label><select id="tt"><option value="all">All tags</option>${S.tags.map(t => `<option value="${t.uid}"${S.tapTag === t.uid ? ' selected' : ''}>${esc(tagName(t))}</option>`).join('')}</select>
      <label class="sr" for="tw">When</label><select id="tw">${[['1','Today'],['7','Last 7 days'],['30','Last 30 days'],['all','Everything kept']].map(([v, l]) => `<option value="${v}"${S.tapWhen === v ? ' selected' : ''}>${l}</option>`).join('')}</select>
      <span style="margin-left:auto" class="row"><button class="btn sm" data-act="dl-csv"${rows.length ? '' : ' disabled'}>${ico('download', 15)}CSV</button><button class="btn sm" data-act="dl-json"${rows.length ? '' : ' disabled'}>${ico('download', 15)}JSON</button></span>
    </div>
    <section class="g" style="padding:8px 22px 12px">
      <div class="tw"><table class="t"><thead><tr><th>When</th><th>Tag</th><th>Result</th><th>What happened</th><th style="text-align:right">Tap #</th></tr></thead><tbody>
      ${rows.slice(0, 300).map(e => { const t = e.uid && tagByUid(e.uid); return `<tr><td style="white-space:nowrap">${when(e.time)}</td><td>${e.uid ? `<a href="#tag-${e.uid}" class="row" style="gap:8px;color:var(--fg);text-decoration:none;white-space:nowrap"><span style="color:var(--accent)">${ico(iconOf(t), 17)}</span>${esc(tagName(t || { uid: e.uid }))}</a>` : '<span class="muted">Unknown</span>'}</td><td>${resultPill(e.status)}</td><td style="font-size:13.5px;${e.status === 'ok' ? '' : 'color:var(--bad)'}">${esc(tapWhat(e))}</td><td class="mono muted" style="text-align:right;font-size:12.5px">${e.uid ? e.counter : '–'}</td></tr>`; }).join('') || '<tr><td colspan="5" class="empty">No taps match these filters.</td></tr>'}
      </tbody></table></div>
    </section>
    <p class="muted" style="font-size:12.5px;padding:0 4px">${rows.length > 300 ? 'Showing the newest 300 of ' + rows.length + '. The downloads have all of them. ' : ''}Knura keeps a year of taps on this computer, in <span class="mono">knurd-taps.log</span> in the settings folder. Stopped taps never run your actions.</p>`;
  return shell('taps', 'Taps', body);
}
function vConfig() {
  const dark = curTheme() === 'dark', st = S.status || {};
  const row = (icon, t, sub, end) => `<div class="set-row" style="cursor:default"><span class="tile">${ico(icon, 19)}</span><span class="txt"><b>${t}</b><span>${sub}</span></span><span class="end">${end}</span></div>`;
  const link = (href, icon, t, sub) => `<a class="set-row" href="${href}"${href.startsWith('http') ? ' rel="noreferrer"' : ''}><span class="tile">${ico(icon, 19)}</span><span class="txt"><b>${t}</b><span>${sub}</span></span><span class="end">${ico('chev', 18)}</span></a>`;
  const addr = (S.reach && S.reach.publicUrl) || st.publicUrl;
  const body = `<div class="set-wrap">
    <section class="g set-head"><span class="avatar" aria-hidden="true">${ico('house', 24, 2)}</span>
      <div style="min-width:0;flex:1"><div style="font-size:17px;font-weight:700;overflow-wrap:anywhere">${esc(homeName())}</div><div class="muted mono" style="font-size:12.5px">${esc(location.host)} · up ${uptime(st.uptimeSeconds || 0)}</div></div>
      <button class="btn sm" data-act="rename-home">Rename</button></section>
    <span class="label" style="padding:0 6px">Reach</span>
    <section class="g set-list" aria-label="Reach">
      ${row('globe', 'Public address', addr ? '<span class="mono">' + esc(addr.replace(/^https?:\/\//, '')) + '</span>' : 'Not set, so phones can’t reach this computer yet', '<button class="btn sm" data-act="change-url">Change</button>')}
      ${lanRows(row)}
      ${row('link', 'tap.knura.io', st.resolverOwner ? 'Forwards taps from tags with the tap.knura.io link' : 'Not used', '')}
    </section>
    <span class="label" style="padding:0 6px">Sign-in</span>
    <section class="g set-list" aria-label="Sign-in">
      ${row('lock', 'Page password', 'For this dashboard and the setup page', '<button class="btn sm" data-act="change-pw">Change</button>')}
      ${row('token', 'knurctl and app access', S.tokens ? (S.tokens.length ? S.tokens.map(t => '<span class="mono">' + esc(t) + '</span>').join(', ') : 'No tokens') : 'Tokens let knurctl and the Knura app in', '<span class="muted" style="font-size:12.5px">Add with <span class="mono">knurctl admin token add</span></span>')}
    </section>
    <span class="label" style="padding:0 6px">This computer</span>
    <section class="g set-list" aria-label="This computer">
      ${link('#backup', 'key', 'Keys and backup', backupState().h)}
      ${link('#logs', 'logs', 'Logs', 'What knurd has been doing')}
      ${row('folder', 'Settings folder', S.settings && S.settings.folder ? '<span class="mono">' + esc(S.settings.folder) + '</span>' + (S.settings.codeClone ? '<br><span style="color:var(--bad)">Inside the code folder. See Keys and backup.</span>' : '') : '', '')}
      ${row('logs', 'Log detail', 'How much knurd writes to its log. Used at once.', `<label class="sr" for="ll">Log detail</label><select id="ll" class="in" style="width:auto;height:36px;border-radius:999px;font-size:13px">${LEVELS.map(([v, l]) => `<option value="${v}"${(S.settings && S.settings.logLevel) === v ? ' selected' : ''}>${l}</option>`).join('')}</select>`)}
    </section>
    <span class="label" style="padding:0 6px">More</span>
    <section class="g set-list" aria-label="More">
      ${link('/setup', 'wand', 'Setup', 'The step-by-step setup again: address, Tailscale, phones and actions')}
      <button class="set-row" data-act="theme"><span class="tile">${ico(dark ? 'moon' : 'sun', 19)}</span><span class="txt"><b>Appearance</b><span>${dark ? 'Dark' : 'Light'} on this browser</span></span><span class="end" style="font-size:13px">Switch to ${dark ? 'light' : 'dark'}</span></button>
      ${link('https://docs.knura.io', 'help', 'Help', 'docs.knura.io')}
    </section>
    ${S.session && S.session.signedIn ? `<section class="g set-list">
      <button class="set-row signout" data-act="signout"><span class="tile">${ico('out', 19)}</span><span class="txt"><b>Sign out</b><span style="color:var(--muted)">Signs this browser out</span></span></button>
    </section>` : ''}
  </div>`;
  return shell('config', 'Settings', body);
}

// The certificate's SHA-256 fingerprint in pairs, as browsers show it: AB:12:...
function fingerprintPairs(f) {
  const hex = typeof f === 'string' ? f.replace(/[^0-9a-f]/gi, '').toUpperCase() : '';
  return hex.length === 64 ? hex.match(/../g) : null;
}
const onThisComputer = () => /^(127\.0\.0\.1|localhost|\[::1\])$/.test(location.hostname);
function lanRows(row) {
  const l = S.lan;
  if (!l) return '';
  const urls = Array.isArray(l.urls) ? l.urls.filter(u => typeof u === 'string' && /^https:\/\/[0-9a-z.:\[\]-]+$/i.test(u)) : [];
  const on = l.on === true && urls.length > 0;
  const sw = `<button class="sw" role="switch" aria-checked="${on}" aria-label="Open this page from other devices" data-act="lan"><span></span></button>`;
  const head = row('wifi', 'Open this page from other devices', on ? 'On your Wi-Fi at <span class="mono">' + esc(urls[0]) + '</span>' : 'Off. Only this computer can open it.', sw);
  if (!on) return head;
  const fp = fingerprintPairs(l.fingerprint);
  const qr = onThisComputer() ? qrSvg(l.qr, 'Code for opening this page on a phone', 168) : '';
  return head + `<div class="lan-more">
    <div class="lan-qr">${qr ? `<div>${qr}</div><span class="muted" style="font-size:12.5px">Scan with the phone’s camera</span>` : ''}</div>
    <div style="display:flex;flex-direction:column;gap:10px;min-width:0;flex:1">
      <p style="font-size:13.5px;line-height:1.55;margin:0">On a phone or another computer on the same Wi-Fi, open <span class="mono">${esc(urls[0])}</span>${urls.length > 1 ? ' (or ' + urls.slice(1).map(u => '<span class="mono">' + esc(u) + '</span>').join(', ') + ')' : ''} and sign in with the page password.</p>
      <p class="muted" style="font-size:13px;line-height:1.55;margin:0">The first time, the browser warns that the connection isn’t private. That’s because this computer made its own certificate, instead of buying one from a company. Before you continue, open the certificate details from the warning and check that its <b>SHA-256 fingerprint</b> starts like this. If it doesn’t, stop: you’re not talking to your Knura.</p>
      ${fp ? `<div class="term fp" aria-label="Certificate fingerprint"><b>${fp.slice(0, 8).join(':')}</b>:${fp.slice(8).join(':')}</div>` : ''}
      ${l.firewall ? `<p class="muted" style="font-size:12.5px;margin:0">Page doesn’t load? This computer’s firewall may block it. Run <span class="mono">${esc(l.firewall)}</span></p>` : ''}
      <p class="muted" style="font-size:12.5px;margin:0">Only devices on your home network can open it. Phones away from home still can’t.</p>
    </div></div>`;
}

// ---------- modals ----------
const mhead = t => `<div class="row" style="justify-content:space-between"><h2 style="font-size:20px;font-weight:700">${t}</h2><button class="iconbtn" data-x aria-label="Close">${ico('x', 18)}</button></div>`;
function addTagModal() {
  const phones = (S.settings && S.settings.phones) || [];
  modal(`${mhead('Add a tag')}<p class="muted" style="font-size:14px">The tag gets its own keys from this computer’s master key. Use a blank NTAG 424 DNA tag.</p>
    <div class="choice"><button data-way="phone"><span class="tile" style="width:40px;height:40px;border-radius:12px">${ico('phone', 20)}</span><b>With the Knura app</b><span>${phones.length ? 'On ' + esc(phones.join(', ')) : 'Pair a phone first'}</span></button>
    <button data-way="usb"><span class="tile" style="width:40px;height:40px;border-radius:12px">${ico('usb', 20)}</span><b>With a USB reader</b><span>Plugged into this computer</span></button></div>`, bg => {
    bg.addEventListener('click', ev => {
      const w = ev.target.closest('[data-way]'); if (!w) return;
      const box = bg.querySelector('.modal');
      box.innerHTML = w.dataset.way === 'phone'
        ? (phones.length ? `${mhead('Add a tag with the app')}<ol style="margin:0;padding-left:20px;display:flex;flex-direction:column;gap:10px;font-size:14.5px"><li>Open the Knura app on <span class="mono">${esc(phones[0])}</span>.</li><li>Tap <b>Add a tag</b> and hold a blank tag to the back of the phone.</li><li>Keep it still until the app says it’s done.</li><li>Tap it once. It shows up here under Tags, ready to name.</li></ol><button class="btn" data-x style="align-self:flex-start">Close</button>`
          : `${mhead('Pair a phone first')}<p class="muted" style="font-size:14px">The Knura app sets tags up for this computer once it’s paired. Pairing takes a minute.</p><div class="row"><a class="btn primary" href="#phones" data-x>Pair a phone</a><button class="btn" data-x>Close</button></div>`)
        : `${mhead('Add a tag with a USB reader')}<p class="muted" style="font-size:14px">Plug in the reader, then run this in a terminal, in Knura’s settings folder. Put a blank tag on the reader when it asks.</p><div class="term"><span class="p">$</span> knurctl provision</div><p class="muted" style="font-size:13px">Tap it once afterwards and it shows up here under Tags, ready to name.</p><div class="row"><button class="btn sm" data-act-m="copy-prov">${ico('copy', 14)}Copy</button><button class="btn sm" data-x>Close</button></div>`;
    });
  });
}

// ---------- actions ----------
Object.assign(P, {
  trash:'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6',
  reset:'M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4'
});
const KINDS = { homeassistant: ['Home Assistant', 'bolt'], matter: ['Matter bridge', 'matter'], webhook: ['Webhook', 'link'] };
// Only our own type names ever reach the page.
const kindOf = c => KINDS[Object.keys(KINDS).find(k => k === c.type)] || ['Action', 'bolt'];
const slug = v => String(v).toLowerCase().trim().replace(/[^a-z0-9_-]+/g, '-').replace(/^[-_]+|[-_]+$/g, '').slice(0, 32);
const connBy = (type, id) => S.conns.find(c => c.type === type && c.id === id);
// knurgN reports Java's names for network errors; say them in words.
const plain = d => !d ? '' : /ConnectTimeout|HttpTimeout|timed out/i.test(d) ? 'no answer in time. Is the address right, and is it running?'
  : /ConnectException|refused/i.test(d) ? 'the connection was refused. Is it running?'
  : /UnknownHost|UnresolvedAddress/i.test(d) ? 'that address doesn’t exist' : d;
const OUTCOME = { delivered: 'Delivered', refused: 'Refused', expired: 'Didn’t get through', failed: 'Didn’t get through' };
function connState(c) {
  const l = c.last;
  if (!l) return ['mut', 'Not used yet', 'No tap or test since Knura started'];
  const ok = l.result === 'delivered';
  const word = (l.test ? 'Test ' : 'Tap ') + (OUTCOME[Object.keys(OUTCOME).find(k => k === l.result)] || 'ended').toLowerCase();
  return ok ? ['ok', 'Working', word + ' ' + ago(l.time)] : ['bad', 'Not answering', word + ' ' + ago(l.time) + (l.detail ? ': ' + plain(l.detail) : '')];
}
function vActions() {
  const used = c => (c.usedBy || []).map(u => tagName(tagByUid(u) || { uid: u }));
  const body = `
    <section class="g flow" aria-label="Where a tap goes">
      <div style="display:flex;flex-direction:column;align-items:center;gap:8px;width:120px;text-align:center"><span style="width:60px;height:60px;border-radius:50%;background:var(--chip);border:1px solid var(--glass-line);display:flex;align-items:center;justify-content:center">${ico('nfc', 26)}</span><span style="font-size:13.5px;font-weight:600">A tag is tapped</span></div>
      <span class="ln" aria-hidden="true" style="background:linear-gradient(90deg,var(--track),var(--accent))"></span>
      <div style="display:flex;flex-direction:column;align-items:center;gap:8px;width:170px;text-align:center"><span style="width:72px;height:72px;border-radius:24px;background:var(--accent-tint);border:1px solid var(--accent-line);display:flex;align-items:center;justify-content:center;box-shadow:0 0 44px -8px rgba(242,140,58,.7)">${MARK(36)}</span><span style="font-size:13.5px;font-weight:600">Knura checks it is genuine</span></div>
      <span class="ln" aria-hidden="true" style="background:linear-gradient(90deg,var(--accent),var(--track))"></span>
      <div style="display:flex;flex-direction:column;gap:8px">${Object.values(KINDS).map(([n, i]) => `<span class="g row" style="padding:9px 14px;border-radius:16px;font-size:13.5px;gap:10px"><span style="color:var(--accent-text)">${ico(i, 17)}</span>${n}</span>`).join('')}</div>
    </section>
    <div class="row" style="justify-content:space-between;flex-wrap:wrap;padding:0 4px"><h2 style="font-size:17px;font-weight:600">Your actions</h2><button class="btn primary" data-act="add-conn">${ico('plus', 16, 2.2)}Add an action</button></div>
    ${S.conns.length ? `<div class="grid3">${S.conns.map((c, i) => {
      const [tone, word, sub] = connState(c), [kind, icon] = kindOf(c), u = used(c);
      return `<section class="g panel" aria-label="${esc(c.id)}">
        <div class="row"><span class="tile" style="width:46px;height:46px;border-radius:14px">${ico(icon, 22)}</span><span style="flex:1;min-width:0"><span class="mono" style="display:block;font-size:15px;font-weight:600">${esc(c.id)}</span><span class="muted" style="font-size:12.5px">${kind}</span></span>
        <span class="row" style="gap:7px;font-size:12.5px;font-weight:600;color:var(--${tone === 'ok' ? 'ok' : tone === 'bad' ? 'bad' : 'muted'})">${tone === 'ok' ? '<span class="dotlive"></span>' : tone === 'bad' ? '<span class="dotbad"></span>' : ''}${word}</span></div>
        <dl class="kv"><dt>Address</dt><dd class="mono" style="font-size:13px">${esc(c.url || '')}</dd><dt>Last</dt><dd style="font-size:13px;${tone === 'bad' ? 'color:var(--bad)' : ''}">${esc(sub)}</dd><dt>Tags</dt><dd style="font-size:13px">${u.length ? esc(u.join(', ')) : '<span class="muted">None yet. Turn it on from a tag’s page.</span>'}</dd></dl>
        <div class="row" style="flex-wrap:wrap;gap:8px;margin-top:auto"><button class="btn sm primary" data-conn="${i}" data-do="test">${ico('play', 14)}Send a test</button><button class="btn sm" data-conn="${i}" data-do="edit">${ico('edit', 14)}Edit</button>
        <button class="iconbtn" style="margin-left:auto" aria-label="Remove ${esc(c.id)}" data-conn="${i}" data-do="remove">${ico('trash', 18)}</button></div>
      </section>`; }).join('')}</div>`
      : `<div class="g empty">No actions yet. Add Home Assistant, a Matter bridge or a webhook, then turn it on for a tag.</div>`}
    <p class="muted" style="font-size:12.5px;padding:0 4px">Choose which tags run which actions on each tag’s page. A test sends a pretend tap (tap number 0) that only this action gets. Actions are kept in <span class="mono">knurgn.properties</span> on this computer, and their secrets in <span class="mono">knurgn-secrets.properties</span>.</p>`;
  return shell('actions', 'Actions', body);
}
function haYaml(secret, tag) {
  return `alias: ${JSON.stringify((tag || 'Knura tag') + ' tap from Knura')}\ntriggers:\n  - trigger: webhook\n    webhook_id: ${secret || 'YOUR_WEBHOOK_ID'}\n    allowed_methods:\n      - POST\n    local_only: true\nactions:\n  - action: persistent_notification.create\n    data:\n      title: Knura tap\n      message: "{{ trigger.json.data.name | default('A tag') }} was tapped"`;
}
const CONN_FORM = {
  homeassistant: { title: 'Home Assistant', id: 'home-assistant', label: 'Home Assistant’s address', ph: 'http://192.168.1.50:8123', hint: 'Use its IP address, not homeassistant.local. It’s in Home Assistant under Settings → System → Network.' },
  matter: { title: 'Matter bridge', id: 'matter', label: 'The Matter bridge’s address', ph: 'http://127.0.0.1:5541/tap', hint: 'The bridge runs on this computer and puts your tags in Apple Home, Google Home or Alexa as buttons.' },
  webhook: { title: 'webhook', id: 'web-service', label: 'The service’s webhook address', ph: 'https://maker.ifttt.com/trigger/…', hint: 'Works with IFTTT, Zapier, n8n, Node-RED or your own code. It must start with https://. Each tap is sent as signed JSON.' }
};
function connForm(type, c) {
  const f = CONN_FORM[Object.keys(CONN_FORM).find(k => k === type)];
  if (!f) return '';
  return `<form data-mform="conn" data-type="${type}" style="display:flex;flex-direction:column;gap:10px">${mhead(c ? 'Edit ' + esc(c.id) : 'Add ' + (type === 'webhook' ? 'a ' : '') + f.title)}
    <label class="fl" for="cn" style="margin:0">Name</label><input class="in mono" id="cn" maxlength="32" value="${esc(c ? c.id : f.id)}" ${c ? 'readonly' : ''} autocomplete="off" spellcheck="false">
    <label class="fl" for="cu" style="margin:0">${f.label}</label><input class="in mono" id="cu" value="${esc(c ? c.url : '')}" placeholder="${f.ph}" autocomplete="off" spellcheck="false">
    <p class="muted" style="font-size:12.5px">${f.hint}</p><div class="err" id="cerr" hidden></div>
    <div class="row"><button class="btn primary">${c ? 'Save' : 'Add'}</button><button type="button" class="btn" data-x>Cancel</button></div></form>`;
}
function addConnModal() {
  modal(`${mhead('Add an action')}<div class="choice">
    <button data-kind="homeassistant"><span class="tile" style="width:40px;height:40px;border-radius:12px">${ico('bolt', 20)}</span><b>Home Assistant</b><span>Run an automation when a tag is tapped</span></button>
    <button data-kind="matter"><span class="tile" style="width:40px;height:40px;border-radius:12px">${ico('matter', 20)}</span><b>Matter</b><span>Tags as buttons in Apple Home or Google Home</span></button>
    <button data-kind="webhook"><span class="tile" style="width:40px;height:40px;border-radius:12px">${ico('link', 20)}</span><b>Webhook</b><span>Send each tap to another service</span></button></div>`, bg => {
    bg.addEventListener('click', ev => {
      const k = ev.target.closest('[data-kind]'); if (!k) return;
      const box = bg.querySelector('.modal'); box.innerHTML = connForm(k.dataset.kind); const i = box.querySelector('#cu'); i && i.focus();
    });
  });
}
// After adding (or making a new secret): what to do with the secret, shown only now.
function secretModal(c, secret) {
  if (c.type === 'homeassistant') {
    const yaml = haYaml(secret);
    return modal(`${mhead('Add this automation in Home Assistant')}
      <p class="muted" style="font-size:13.5px">Knura made a secret webhook id for <span class="mono">${esc(c.id)}</span>, shown only now. In Home Assistant, go to <b>Settings → Automations & scenes → Create automation</b>, choose <b>Edit in YAML</b> from the ⋮ menu, and paste this. Change the action to whatever the tap should do.</p>
      <pre class="term" id="yaml" style="margin:0;white-space:pre-wrap">${esc(yaml)}</pre>
      <div class="row" style="flex-wrap:wrap"><button class="btn sm primary" data-m="copy-yaml">${ico('copy', 14)}Copy</button><button class="btn sm" data-x>Done</button></div>`, bg => {
      bg.addEventListener('click', ev => { if (ev.target.closest('[data-m="copy-yaml"]')) copy(yaml, 'Automation'); });
    });
  }
  modal(`${mhead('Signing secret')}
    <p class="muted" style="font-size:13.5px">Shown only now. ${c.type === 'matter' ? 'Put it in the Matter bridge’s settings.' : 'Only needed by services that check signatures. Most can ignore it.'}</p>
    <div class="term mono" style="word-break:break-all">${esc(secret)}</div>
    <div class="row"><button class="btn sm primary" data-m="copy-secret">${ico('copy', 14)}Copy</button><button class="btn sm" data-x>Done</button></div>`, bg => {
    bg.addEventListener('click', ev => { if (ev.target.closest('[data-m="copy-secret"]')) copy(secret, 'Secret'); });
  });
}
function editConnModal(c) {
  modal(connForm(c.type, c) + `<div class="sub" style="padding:12px 14px;display:flex;flex-direction:column;gap:8px;margin-top:12px">
    <b style="font-size:14px">${c.type === 'homeassistant' ? 'Lost the webhook id?' : 'Lost the signing secret?'}</b>
    <span class="muted" style="font-size:12.5px">${c.type === 'homeassistant' ? 'Make a new one, then paste the automation into Home Assistant again. The old id stops working.' : 'Make a new one, then give it to the service again. The old one stops working.'}</span>
    <button class="btn sm" data-m="new-secret" style="align-self:flex-start">${ico('reset', 14)}Make a new ${c.type === 'homeassistant' ? 'webhook id' : 'secret'}</button></div>`, bg => {
    bg.addEventListener('click', async ev => {
      if (!ev.target.closest('[data-m="new-secret"]')) return;
      if (!confirm('Make a new one? Whatever uses the old one stops working until you update it.')) return;
      const r = await api('POST', `/v1/connections/${c.type}/${c.id}/secret`);
      if (!r.ok) return toast(why(r));
      secretModal(c, r.json.secret);
    });
  });
}
async function testConn(c) {
  toast('Sending a test to ' + c.id + '…');
  const r = await api('POST', `/v1/connections/${c.type}/${c.id}/test`);
  if (!r.ok) return toast(why(r));
  const cn = await api('GET', '/v1/connections'); if (cn.ok) S.conns = cn.json.connections;
  render();
  toast(r.json.result === 'delivered' ? c.id + ' got the test' + (c.type === 'homeassistant' ? '. Check Home Assistant.' : '.') : c.id + ' didn’t get it' + (r.json.detail ? ': ' + plain(r.json.detail) : ''));
}
async function removeConn(c) {
  if ((c.usedBy || []).length) {
    return modal(`${mhead('Turn it off first')}<p class="muted" style="font-size:14px"><span class="mono">${esc(c.id)}</span> is still on for ${esc(c.usedBy.map(u => tagName(tagByUid(u) || { uid: u })).join(', '))}. Turn it off on ${c.usedBy.length === 1 ? 'that tag’s page' : 'those tags’ pages'}, then remove it.</p><button class="btn" data-x style="align-self:flex-start">OK</button>`);
  }
  if (!confirm('Remove ' + c.id + '? Its address and secret are deleted.')) return;
  const r = await api('DELETE', `/v1/connections/${c.type}/${c.id}`);
  if (!r.ok) return toast(why(r));
  S.conns = S.conns.filter(x => x !== c); render(); toast('Removed ' + c.id);
}

// ---------- phones and readers ----------
function qrSvg(rows, label = 'Pairing code for the Knura app', size = 200) {
  if (!Array.isArray(rows) || !rows.length || !rows.every(r => typeof r === 'string' && /^[01]+$/.test(r) && r.length === rows.length)) return '';
  const n = rows.length, q = 4;
  let d = '';
  rows.forEach((row, y) => { for (let x = 0; x < n; x++) if (row[x] === '1') d += `M${x + q} ${y + q}h1v1h-1z`; });
  return `<svg viewBox="0 0 ${n + 2 * q} ${n + 2 * q}" width="${size}" height="${size}" shape-rendering="crispEdges" role="img" aria-label="${esc(label)}" style="display:block;border-radius:12px"><rect width="100%" height="100%" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
}
function vPhones() {
  const phones = (S.settings && S.settings.phones) || [], p = S.pair;
  const others = p ? (p.addresses || []).filter(a => a !== p.address) : [];
  const body = `
    <div class="grid3" style="grid-template-columns:repeat(auto-fit,minmax(min(100%,380px),1fr))">
      <section class="g panel" aria-labelledby="ph">
        <div class="panel-head"><h2 id="ph">Phones</h2><span class="muted" style="font-size:12.5px">Add tags and check taps with the Knura app</span></div>
        ${phones.map((x, i) => `<div class="sub row" style="padding:12px 14px"><span class="tile plain" style="width:40px;height:40px;border-radius:12px">${ico('phone', 20)}</span><span style="flex:1;min-width:0"><span class="mono" style="display:block;font-size:14px">${esc(x)}</span><span class="muted" style="font-size:12.5px">Paired · can add tags</span></span><button class="iconbtn" aria-label="Remove ${esc(x)}" data-phone="${i}">${ico('trash', 18)}</button></div>`).join('') || '<p class="muted" style="font-size:13.5px">No phones yet.</p>'}
        ${p ? `<div style="padding:16px;border-radius:20px;background:var(--accent-tint);border:1px solid var(--accent-line);display:flex;flex-direction:column;align-items:center;gap:12px">
            <span style="align-self:flex-start;font-size:14px;font-weight:600">In the Knura app, tap <b>Scan pairing code</b> and point the camera at this. The phone will be called <span class="mono">${esc(p.name)}</span>.</span>
            ${qrSvg(p.qr)}
            <span class="row muted" style="font-size:12.5px;gap:8px"><span class="dotlive"></span>Waiting for the phone. This page notices by itself.</span>
            <span class="muted" style="font-size:12.5px;text-align:center">The code works once, for 15 minutes. The phone must be on the same Wi-Fi as this computer: the app connects to <span class="mono">${esc(p.url || '')}</span>.</span>
            ${p.firewall ? `<span class="muted" style="font-size:12.5px;align-self:flex-start">The app can’t connect? This computer’s firewall may be blocking it. Run this, then scan again:</span><div class="term" style="align-self:stretch">${esc(p.firewall)}</div>` : ''}
            ${others.length ? `<span class="muted" style="font-size:12.5px;align-self:flex-start">This computer has more than one network address. If the app can’t connect, try another:</span><div class="row" style="flex-wrap:wrap;gap:6px;align-self:flex-start">${others.map((a, i) => `<button class="btn sm" data-addr="${i}">${esc(a)}</button>`).join('')}</div>` : ''}
            <button class="btn sm" data-act="pair-cancel">Cancel</button></div>`
          : `<form data-form="pair" style="display:flex;flex-direction:column;gap:8px"><label class="fl" for="pn" style="margin:0">New phone’s name</label><div class="row" style="gap:8px"><input class="in mono" id="pn" maxlength="32" placeholder="my-phone" autocomplete="off" spellcheck="false"><button class="btn primary"${S.busy === 'pair' ? ' disabled' : ''}>${S.busy === 'pair' ? 'Getting ready…' : 'Show pairing code'}</button></div>
            <p class="muted" style="font-size:12.5px">The first time, Knura makes a security certificate and starts answering on your home Wi-Fi. That takes a few seconds.</p></form>`}
        <p class="muted" style="font-size:12.5px">A removed phone can’t reach this Knura or add tags any more. Tags it added keep working.</p>
      </section>
      <section class="g panel" aria-labelledby="rd">
        <div class="panel-head"><h2 id="rd">USB readers</h2><span class="muted" style="font-size:12.5px">For adding tags at the computer</span></div>
        <div class="sub row" style="padding:12px 14px;align-items:flex-start"><span class="tile plain" style="width:40px;height:40px;border-radius:12px">${ico('usb', 20)}</span><span style="flex:1;min-width:0;font-size:13.5px">Knura works with the ACR122U and other PC/SC readers. Plug one in, open a terminal in Knura’s settings folder, and use <span class="mono">knurctl</span>.</span></div>
        <dl class="kv"><dt class="mono">knurctl provision</dt><dd style="font-size:13px">Set up a blank tag on the reader</dd><dt class="mono">knurctl tap</dt><dd style="font-size:13px">Check a tag: prints the link it makes</dd><dt class="mono">knurctl info</dt><dd style="font-size:13px">Show what’s on a tag</dd></dl>
        <div class="row" style="flex-wrap:wrap;gap:8px"><button class="btn" data-act="add-tag">${ico('plus', 16, 2.2)}Add a tag</button></div>
      </section>
    </div>`;
  return shell('phones', 'Phones and readers', body);
}
let pairT;
function watchPair() {
  clearTimeout(pairT);
  const tick = async () => {
    if (!S.pair) return;
    const r = await api('GET', '/v1/settings');
    if (r.ok && (r.json.phones || []).includes(S.pair.name)) { const n = S.pair.name; S.pair = null; S.settings = r.json; render(); toast(n + ' is paired'); return; }
    pairT = setTimeout(tick, 3000);
  };
  pairT = setTimeout(tick, 3000);
}
async function pairPhone(name, address) {
  if (!name) return toast('Give the phone a name, like my-phone.');
  S.busy = 'pair'; render();
  const body = { name }; if (address) body.address = address;
  const r = await api('POST', '/v1/phones/pair', body);
  S.busy = false;
  if (!r.ok) { S.pair = null; render(); return toast(why(r)); }
  S.pair = r.json; render(); watchPair();
}
async function removePhone(name) {
  if (!confirm('Remove ' + name + '? It can’t reach this Knura any more. Tags it added keep working.')) return;
  const r = await api('DELETE', '/v1/phones/' + encodeURIComponent(name));
  if (!r.ok) return toast(why(r));
  const st = await api('GET', '/v1/settings'); if (st.ok) S.settings = st.json;
  render(); toast('Removed ' + name);
}

// ---------- keys and backup ----------
Object.assign(P, {
  print:'M6 9V3h12v6M6 18H4v-7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v7h-2M6 14h12v7H6z',
  logs:'M5 4h14v16H5zM8 8h8M8 12h8M8 16h5',
  folder:'M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
  token:'M4 7h16v10H4zM8 12h.01M12 12h.01M16 12h.01'
});
const OLD_BACKUP_DAYS = 90;
function pick6() {
  const list = window.KNURA_WORDS, out = [], b = new Uint16Array(6);
  if (!Array.isArray(list) || list.length < 2048) return null;
  crypto.getRandomValues(b);
  for (let i = 0; i < 6; i++) out.push(list[b[i] & 2047]); // 65536 is a multiple of 2048, so every word is equally likely
  return out;
}
// A download from knurd: the file is named by knurd's Content-Disposition when it gives one.
async function download(path, body, fallbackName) {
  let res;
  try {
    res = await fetch(path, { method: 'POST', credentials: 'same-origin', headers: { 'X-Knura-Request': '1', 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
  } catch (e) { return { ok: false, status: 0, json: { detail: 'Knura isn’t answering. Is it still running?' } }; }
  if (!res.ok) { let json = null; try { json = await res.json(); } catch (e) { /* empty */ } return { ok: false, status: res.status, json }; }
  const blob = await res.blob(), m = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') || '');
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = m ? m[1] : fallbackName;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 30000);
  return { ok: true, name: a.download };
}
function backupState() {
  const b = (S.status && S.status.backup) || null;
  if (!b) return { tone: 'mut', h: 'This Knura has no master key here.', p: 'Backups are for the master key every tag’s keys come from. This Knura doesn’t keep one in its settings folder.' };
  const days = b.file ? (Date.now() - Date.parse(b.file)) / 86400000 : null;
  if (!b.file && !b.sheet) return { tone: 'bad', h: 'Your master key has no backup.', p: 'Every tag’s keys come from the master key on this computer. If this computer dies or its folder is deleted, your tags stop working for good. Make a backup file now, and keep it somewhere else.' };
  if (!b.file) return { tone: 'warn', h: 'You have a recovery sheet, but no backup file.', p: 'The sheet brings back the master key. A backup file also keeps your tags’ names, actions and settings.' };
  if (days > OLD_BACKUP_DAYS) return { tone: 'warn', h: 'Your backup is ' + Math.floor(days) + ' days old.', p: 'It still brings back your master key, but names, actions and settings changed since then may be missing. Make a fresh one.' };
  return { tone: 'ok', h: 'Your master key is backed up.', p: 'Backup file made ' + ago(b.file) + (b.sheet ? ', recovery sheet printed ' + ago(b.sheet) : '') + '. Every tag’s keys come from the master key on this computer, so this is how you get them back if the computer dies.' };
}
function vBackup() {
  const st = backupState(), b = (S.status && S.status.backup) || {}, w = S.words, set = S.settings || {};
  const body = `
    ${set.codeClone ? `<div class="g" style="padding:16px 18px;display:flex;gap:14px;align-items:flex-start;border-color:var(--bad-tint)"><span class="tile bad" style="width:40px;height:40px;border-radius:12px;flex:none">${ico('warn', 20, 2.2)}</span><div style="font-size:14px;display:flex;flex-direction:column;gap:6px"><b>Your settings are inside the code folder</b><span class="muted">The settings folder <span class="mono">${esc(set.folder)}</span> is inside <span class="mono">${esc(set.codeClone)}</span>, where Knura’s code was downloaded. Deleting that folder to download Knura again deletes your master key too. Stop Knura, move the settings folder somewhere else (like <span class="mono">~/knura-home</span>), and start Knura with <span class="mono">knurd --home</span> and the new folder.</span></div></div>` : ''}
    <section class="g hero">
      <span class="tile ${st.tone}" style="width:64px;height:64px;border-radius:22px">${ico('key', 30, 1.9)}</span>
      <div style="flex:1 1 320px;min-width:0;display:flex;flex-direction:column;gap:6px"><span class="kick">Master key</span>
        <h2>${st.h}</h2><p class="muted" style="font-size:14px;max-width:64ch">${st.p}</p></div>
    </section>
    <div class="grid3" style="grid-template-columns:repeat(auto-fit,minmax(min(100%,380px),1fr))">
      <section class="g panel" aria-labelledby="bf">
        <div class="panel-head"><h2 id="bf">Backup file</h2><span class="muted" style="font-size:12.5px">${b.file ? 'Last made ' + esc(when(b.file)) : 'None yet'}</span></div>
        <p class="muted" style="font-size:14px">An encrypted copy of the settings folder (master key, tags’ names and actions, settings), locked with a passphrase. Save it off this computer, for example to a USB stick or your cloud drive, and never inside the folder Knura’s code is in.</p>
        ${w ? `<span class="label">Your passphrase</span><div class="term" style="font-size:15px;text-align:center">${w.map(esc).join(' ')}</div>
          <div class="row" style="flex-wrap:wrap;gap:8px"><button class="btn sm" data-act="new-words">${ico('refresh', 14)}Suggest others</button><button class="btn sm" data-act="copy-words">${ico('copy', 14)}Copy</button></div>
          <label class="row" style="gap:10px;font-size:14px;cursor:pointer"><input type="checkbox" id="saved-pp"${S.ppSaved ? ' checked' : ''}>I’ve saved the passphrase in my password manager</label>
          <div class="row" style="flex-wrap:wrap;gap:8px"><button class="btn primary" data-act="dl-backup"${S.ppSaved && S.busy !== 'file' ? '' : ' disabled'}>${ico('download', 16)}${S.busy === 'file' ? 'Making it…' : 'Download backup file'}</button><button class="btn" data-act="backup-cancel">Cancel</button></div>`
          : `<button class="btn primary" data-act="start-backup" style="align-self:flex-start"${S.status && S.status.backup ? '' : ' disabled'}>${ico('download', 16)}Make a backup file</button>`}
      </section>
      <section class="g panel" aria-labelledby="rs">
        <div class="panel-head"><h2 id="rs">Recovery sheet</h2><span class="muted" style="font-size:12.5px">${b.sheet ? 'Printed ' + esc(when(b.sheet)) : 'Not printed yet'}</span></div>
        <p class="muted" style="font-size:14px">One page with your master key written out, to keep with your important papers. It doesn’t change when you add tags, so you only need a new one if you make a new master key. Print it, then delete the file.</p>
        <button class="btn" data-act="sheet" style="align-self:flex-start"${S.status && S.status.backup ? '' : ' disabled'}>${ico('print', 16)}${S.busy === 'sheet' ? 'Making it…' : 'Download the sheet to print'}</button>
      </section>
      <section class="g panel" aria-labelledby="rt">
        <div class="panel-head"><h2 id="rt">Restore from a backup</h2><span class="muted" style="font-size:12.5px">On a new computer, or after something went wrong</span></div>
        <p class="muted" style="font-size:14px">Stop Knura, then run this in a terminal with the backup file. It asks for the passphrase and puts everything back in the folder you give it. Then start Knura with that folder.</p>
        <div class="term"><span class="p">$</span> knurctl backup restore knura-backup.knurabackup --to ~/knura-home</div>
        <p class="muted" style="font-size:12.5px">Only have the recovery sheet? <span class="mono">knurctl backup restore --sheet --to ~/knura-home</span> asks for its lines. Check a backup file still opens any time with <span class="mono">knurctl backup verify FILE</span>.</p>
      </section>
    </div>`;
  return shell('backup', 'Keys and backup', body);
}

// ---------- logs ----------
const LEVELS = [['error', 'Only problems', 'Errors'], ['warn', 'Problems and warnings', 'Warnings'], ['info', 'Normal', 'Normal'], ['debug', 'Everything', 'Everything']];
const lineLevel = l => { const m = /^\S+ \S+ (ERROR|WARN|INFO|DEBUG|TRACE)\b/.exec(l) || /^(ERROR|WARN|INFO|DEBUG|TRACE)\b/.exec(l); return m ? m[1] : ''; };
// For a bug report: tag UIDs, addresses and long secrets are hidden, so it's safe to post.
const scrub = l => l.replace(/\b[0-9A-F]{14}\b/g, '<tag>').replace(/https?:\/\/[^\s"']+/g, '<address>')
  .replace(/\b\d{1,3}(\.\d{1,3}){3}\b/g, '<ip>').replace(/[A-Za-z0-9_-]{32,}/g, '<secret>');
function logLines() {
  const lv = S.logFilter, q = S.logQ.toLowerCase();
  return (S.logs ? S.logs.lines : []).filter(l => {
    const v = lineLevel(l);
    if (lv === 'warn' && v !== 'WARN' && v !== 'ERROR') return false;
    if (lv === 'error' && v !== 'ERROR') return false;
    return !q || l.toLowerCase().includes(q);
  });
}
function vLogs() {
  const L = S.logs, lines = logLines();
  const body = !L ? '<div class="g empty">Reading the log…</div>' : !L.file ? `<div class="g empty">knurd isn’t writing a log file, so there’s nothing to show here. Add <span class="mono">log.file=logs/knurd.log</span> to knurd.properties and restart Knura.</div>` : `
    <div class="filter">
      <div class="g seg" role="tablist" aria-label="Level">${[['all', 'Everything'], ['warn', 'Warnings'], ['error', 'Errors']].map(([k, l]) => `<button role="tab" aria-selected="${S.logFilter === k}" data-lv="${k}">${l}</button>`).join('')}</div>
      <div style="position:relative;flex:1 1 220px;max-width:360px"><span class="muted" style="position:absolute;left:14px;top:12px">${ico('search', 18)}</span><label for="lq" class="sr">Search the log</label><input class="in" id="lq" placeholder="Search" value="${esc(S.logQ)}" style="padding-left:42px;border-radius:999px"></div>
      <span class="row" style="gap:10px;font-size:14px">Follow <button class="sw" role="switch" aria-checked="${S.logLive}" aria-label="Follow new lines" data-act="log-live"><span></span></button></span>
      <span style="margin-left:auto" class="row"><button class="btn sm" data-act="copy-logs">${ico('copy', 15)}Copy for a bug report</button><button class="btn sm" data-act="dl-logs">${ico('download', 15)}Download</button></span>
    </div>
    <div class="g term" role="log" aria-label="knurd log" id="logbox" style="max-height:62vh;overflow:auto;white-space:pre-wrap;font-size:12.5px">${lines.map(l => { const v = lineLevel(l); return `<div style="${v === 'ERROR' ? 'color:var(--bad)' : v === 'WARN' ? 'color:var(--warn)' : ''}">${esc(l)}</div>`; }).join('') || '<div class="muted">No lines match.</div>'}</div>
    <p class="muted" style="font-size:12.5px;padding:0 4px">The last ${L.lines.length} lines of <span class="mono">${esc(L.file)}</span>. Keys, tokens and passwords are never written to it, and “Copy for a bug report” also hides tag UIDs and addresses.</p>`;
  return shell('logs', 'Logs', body);
}
async function loadLogs() {
  const r = await api('GET', '/v1/logs?lines=1000');
  S.logs = r.ok ? r.json : { file: null, lines: [] };
}
let logT;
function followLogs() {
  clearTimeout(logT);
  if (!S.logLive) return;
  logT = setTimeout(async () => {
    if (!S.logLive || location.hash !== '#logs') return;
    const box = document.getElementById('logbox'), atEnd = box && box.scrollTop + box.clientHeight >= box.scrollHeight - 8;
    const a = document.activeElement;
    if (!(a && a.id === 'lq')) {
      await loadLogs(); render();
      const nb = document.getElementById('logbox'); if (nb && atEnd) nb.scrollTop = nb.scrollHeight;
    }
    followLogs();
  }, 3000);
}

// ---------- router ----------
let pollT;
function render() {
  const app = document.getElementById('app');
  if (S.screen === 'loading') { app.innerHTML = '<div class="loading">Loading…</div>'; return; }
  if (S.screen === 'error') { app.innerHTML = `<div class="center"><div class="g hello"><h1 style="font-size:22px">Can’t open the dashboard</h1><p class="muted">${esc(S.err)}</p><button class="btn" data-act="reload">Try again</button></div></div>`; return; }
  if (S.screen === 'signin') { app.innerHTML = vSignin(); const p = document.getElementById('pw'); p && p.focus(); return; }
  const route = (location.hash || '#home').slice(1);
  document.title = homeName() + ' · Knura';
  const uid = route.startsWith('tag-') && /^[0-9A-Fa-f]{14}$/.test(route.slice(4)) ? route.slice(4).toUpperCase() : null;
  app.innerHTML = route.startsWith('tag-') ? vTag(uid || '')
    : viewFor(route)();
}
// A plain switch, not a lookup by the page address, so an address like #constructor can't pick
// something that isn't a page.
function viewFor(route) {
  switch (route) {
    case 'tags': return vTags;
    case 'taps': return vTaps;
    case 'actions': return vActions;
    case 'phones': return vPhones;
    case 'backup': return vBackup;
    case 'logs': return vLogs;
    case 'config': return vConfig;
    default: return vHome;
  }
}
let lastRoute = '', wasNarrow = narrow();
window.addEventListener('resize', () => { if (narrow() !== wasNarrow) { wasNarrow = narrow(); render(); } });
window.addEventListener('hashchange', () => {
  if (location.hash !== lastRoute) { S.notif = false; S.renaming = false; window.scrollTo(0, 0); }
  lastRoute = location.hash; render();
  if (location.hash === '#logs') { loadLogs().then(() => { if (location.hash === '#logs') { render(); const nb = document.getElementById('logbox'); if (nb) nb.scrollTop = nb.scrollHeight; followLogs(); } }); }
});
// Taps arrive while the page is open: check every 15 seconds, without disturbing typing or a dialog.
function poll() {
  clearInterval(pollT);
  pollT = setInterval(async () => {
    if (S.screen !== 'app' || document.hidden || S.renaming) return;
    const a = document.activeElement;
    if (document.getElementById('modal-root').firstChild || (a && (a.tagName === 'INPUT' || a.tagName === 'SELECT'))) return;
    const before = S.taps.length && S.taps[0].time;
    const [tp, tg, st, dy, cn] = await Promise.all([api('GET', '/v1/taps?limit=1000'), api('GET', '/v1/tags'), api('GET', '/v1/status'), api('GET', '/v1/taps/days?days=14'), api('GET', '/v1/connections')]);
    if (cn.ok) S.conns = cn.json.connections;
    if (tp.ok) S.taps = tp.json.taps;
    if (tg.ok) S.tags = tg.json.tags;
    if (st.ok) S.status = st.json;
    if (dy.ok) S.days = dy.json;
    if (S.screen === 'app' && !document.getElementById('modal-root').firstChild) {
      const y = window.scrollY; render(); window.scrollTo(0, y);
      if (S.taps.length && S.taps[0].time !== before && before) { const e = S.taps[0]; toast((e.status === 'ok' ? 'New tap: ' : 'Stopped a tap: ') + tagName(tagByUid(e.uid) || (e.uid ? { uid: e.uid } : null))); }
    }
  }, 15000);
}

// ---------- events ----------
const app = document.getElementById('app');
const curTag = () => { const h = location.hash.slice(1); return h.startsWith('tag-') ? tagByUid(h.slice(4).toUpperCase()) || null : null; };
async function putLabel(t, label, icon) {
  const r = await api('PUT', '/v1/tags/' + t.uid, { label: label || '', icon: icon || '' });
  if (!r.ok) { toast(why(r)); return false; }
  t.label = label || null; t.icon = icon || null; return true;
}
app.addEventListener('click', async e => {
  const b = e.target.closest('[data-act],[data-range],[data-toggle],[data-res],[data-conn],[data-phone],[data-addr],[data-lv]');
  if (!b) { if (S.notif && !e.target.closest('.pop')) { S.notif = false; render(); } return; }
  const t = curTag();
  if (b.dataset.conn) {
    const c = S.conns[Math.trunc(+b.dataset.conn)]; if (!c) return; // only connectors knurd listed
    const what = ['test', 'edit', 'remove'].find(x => x === b.dataset.do);
    return what === 'test' ? testConn(c) : what === 'edit' ? editConnModal(c) : what === 'remove' ? removeConn(c) : undefined;
  }
  if (b.dataset.phone) { const n = ((S.settings && S.settings.phones) || [])[Math.trunc(+b.dataset.phone)]; return n ? removePhone(n) : undefined; }
  if (b.dataset.addr) { const a = S.pair && (S.pair.addresses || []).filter(x => x !== S.pair.address)[Math.trunc(+b.dataset.addr)]; return a ? pairPhone(S.pair.name, a) : undefined; }
  if (b.dataset.range) { S.range = ['today', 'week', '14'].find(x => x === b.dataset.range) || 'week'; return render(); }
  if (b.dataset.lv) { S.logFilter = ['all', 'warn', 'error'].find(x => x === b.dataset.lv) || 'all'; return render(); }
  if (b.dataset.res) { S.tapRes = ['all', 'ok', 'stopped'].find(x => x === b.dataset.res) || 'all'; return render(); }
  if (b.dataset.toggle && t) {
    const c = S.conns.find(x => x.id === b.dataset.toggle); if (!c) return; // only connectors knurd listed
    const id = c.id, cur = t.actions || [];
    const next = cur.includes(id) ? cur.filter(x => x !== id) : cur.concat(id);
    b.setAttribute('aria-checked', String(!cur.includes(id)));
    const r = await api('PUT', '/v1/tags/' + t.uid + '/actions', { actions: next.join(',') });
    if (!r.ok) { toast(why(r)); return render(); }
    t.actions = r.json.actions; render();
    const s = document.getElementById('saved'); if (s) { s.textContent = 'Saved'; s.style.color = 'var(--ok)'; }
    return;
  }
  switch (b.dataset.act) {
    case 'eye': { const i = document.getElementById(b.dataset.for), show = i.type === 'password'; i.type = show ? 'text' : 'password'; b.innerHTML = ico(show ? 'eyeoff' : 'eye', 19); b.setAttribute('aria-label', show ? 'Hide password' : 'Show password'); return; }
    case 'lan': {
      const on = !(S.lan && S.lan.on === true);
      if (!on && !onThisComputer() && !confirm('Turn it off? This browser isn’t on the Knura computer, so it will stop working here. You can turn it back on from the Knura computer.')) return;
      if (on) toast('Turning it on. The first time, Knura makes a certificate, which takes a few seconds.');
      const r = await api('PUT', '/v1/settings/other-devices', { on });
      if (!r.ok) return toast(why(r));
      S.lan = r.json; render();
      return toast(on ? 'Other devices on your Wi-Fi can open this page now' : 'Only this computer can open this page now');
    }
    case 'theme': setTheme(curTheme() === 'dark' ? 'light' : 'dark'); return render();
    case 'notif': S.notif = !S.notif; return render();
    case 'reload': location.reload(); return;
    case 'recheck': S.reach = null; render(); await Promise.all([loadAll(), loadReach()]); render(); return toast('Checked again just now');
    case 'add-tag': return addTagModal();
    case 'add-conn': return addConnModal();
    case 'start-backup': S.words = pick6(); S.ppSaved = false; if (!S.words) toast('The word list didn’t load. Reload the page.'); return render();
    case 'new-words': S.words = pick6(); S.ppSaved = false; return render();
    case 'copy-words': return S.words && copy(S.words.join(' '), 'Passphrase');
    case 'backup-cancel': S.words = null; S.ppSaved = false; return render();
    case 'dl-backup': {
      if (!S.words || !S.ppSaved) return;
      S.busy = 'file'; render();
      const r = await download('/v1/backup/file', { passphrase: S.words.join(' ') }, 'knura-backup.knurabackup');
      S.busy = false;
      if (!r.ok) { render(); return toast(why(r)); }
      S.words = null; S.ppSaved = false;
      const st = await api('GET', '/v1/status'); if (st.ok) S.status = st.json;
      render(); return toast('Saved ' + r.name + '. Move it off this computer.');
    }
    case 'sheet': {
      S.busy = 'sheet'; render();
      const r = await download('/v1/backup/sheet', undefined, 'knura-recovery-sheet.html');
      S.busy = false;
      if (!r.ok) { render(); return toast(why(r)); }
      const st = await api('GET', '/v1/status'); if (st.ok) S.status = st.json;
      render(); return toast('Open the file, print it, then delete it');
    }
    case 'log-live': S.logLive = !S.logLive; render(); return followLogs();
    case 'copy-logs': return copy(logLines().map(scrub).join('\n'), 'Log');
    case 'dl-logs': return save('knurd-log.txt', logLines().join('\n') + '\n', 'text/plain');
    case 'change-url': return modal(`<form data-mform="url" style="display:flex;flex-direction:column;gap:12px">${mhead('Public address')}<label class="fl" for="pu" style="margin:0">The https address phones use to reach this computer</label><input class="in mono" id="pu" value="${esc((S.settings && S.settings.publicUrl) || '')}" placeholder="https://my-computer.tail1234.ts.net" autocomplete="off" spellcheck="false"><p class="muted" style="font-size:12.5px">Using Tailscale? The setup page can set it up for you. New tags get links to this address, and Knura uses it fully after a restart.</p><div class="err" id="uerr" hidden></div><div class="row" style="flex-wrap:wrap"><button class="btn primary">Save</button><a class="btn" href="/setup">Set up with Tailscale</a><button type="button" class="btn" data-x>Cancel</button></div></form>`);
    case 'pair-cancel': S.pair = null; clearTimeout(pairT); return render();
    case 'test-tag': {
      const r = await api('POST', '/v1/tags/' + t.uid + '/test');
      return toast(r.ok ? (r.json.started ? 'Test sent to ' + (t.actions || []).join(', ') + '. Check that it arrived.' : 'Nothing to run: turn an action on first.') : why(r));
    }
    case 'taps-for': S.tapTag = t ? t.uid : 'all'; S.tapWhen = 'all'; return;
    case 'rename': S.renaming = true; render(); { const i = document.getElementById('nn'); i && (i.focus(), i.select()); } return;
    case 'rename-cancel': S.renaming = false; return render();
    case 'icon':
      return modal(`${mhead('Choose an icon')}<div class="icons">${ICONS.map(([o, l]) => `<button aria-label="${l}" title="${l}" aria-pressed="${t.icon === o}" data-pick="${o}">${ico(o, 24)}</button>`).join('')}</div>`, bg => {
        bg.addEventListener('click', async ev => {
          const p = ev.target.closest('[data-pick]'), pick = p && ICONS.find(x => x[0] === p.dataset.pick); // only our own icons
          if (pick && await putLabel(t, t.label, pick[0])) { closeModal(); render(); toast('Icon changed'); }
        });
      });
    case 'dl-csv': {
      const rows = tapRows(), q = v => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
      return save('knura-taps.csv', 'time,tag,uid,result,tap,actions\n' + rows.map(e => [e.time, q(e.uid ? tagName(tagByUid(e.uid) || { uid: e.uid }) : ''), e.uid || '', e.status, e.uid ? e.counter : '', e.actions].join(',')).join('\n') + '\n', 'text/csv');
    }
    case 'dl-json': return save('knura-taps.json', JSON.stringify(tapRows().map(e => ({ time: e.time, tag: e.uid ? (tagByUid(e.uid) || {}).label || null : null, uid: e.uid, result: e.status, tap: e.uid ? e.counter : null, actions: e.actions })), null, 2), 'application/json');
    case 'rename-home': return modal(`<form data-mform="home" style="display:flex;flex-direction:column;gap:12px">${mhead('Rename')}<label class="fl" for="hn" style="margin:0">Name</label><input class="in" id="hn" maxlength="60" value="${esc(homeName())}"><p class="muted" style="font-size:12.5px">Shown on this page, in setup and in the Knura app.</p><div class="row"><button class="btn primary">Save</button><button type="button" class="btn" data-x>Cancel</button></div></form>`);
    case 'change-pw': return modal(`<form data-mform="pw" style="display:flex;flex-direction:column;gap:12px">${mhead('Change page password')}<label class="fl" for="p0" style="margin:0">Current password</label><input class="in" id="p0" type="password" autocomplete="current-password"><label class="fl" for="p1" style="margin:0">New password</label><input class="in" id="p1" type="password" autocomplete="new-password" placeholder="At least 8 characters"><p class="muted" style="font-size:12.5px">Every other browser is signed out.</p><div class="err" id="pwerr" hidden></div><div class="row"><button class="btn primary">Change password</button><button type="button" class="btn" data-x>Cancel</button></div></form>`);
    case 'signout': await api('DELETE', '/v1/session'); S.session.signedIn = false; S.screen = 'signin'; render(); return;
  }
});
function save(name, text, type) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([text], { type })); a.download = name;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 30000);
  toast('Downloaded ' + name);
}
app.addEventListener('change', async e => {
  if (e.target.id === 'tt') { const tag = tagByUid(e.target.value); S.tapTag = tag ? tag.uid : 'all'; return render(); }
  if (e.target.id === 'saved-pp') { S.ppSaved = e.target.checked; return render(); }
  if (e.target.id === 'll') {
    const v = LEVELS.find(x => x[0] === e.target.value); if (!v) return;
    const r = await api('PUT', '/v1/settings', { logLevel: v[0] });
    if (!r.ok) { toast(why(r)); return render(); }
    S.settings = r.json; render(); return toast('Log detail: ' + v[1]);
  }
  if (e.target.id === 'tw') { S.tapWhen = ['1', '7', '30', 'all'].find(x => x === e.target.value) || '7'; return render(); }
});
app.addEventListener('input', e => {
  if (e.target.id === 'lq') { S.logQ = e.target.value; const pos = e.target.selectionStart; render(); const n = document.getElementById('lq'); n.focus(); n.setSelectionRange(pos, pos); return; }
  if (e.target.id === 'q') { S.q = e.target.value; const pos = e.target.selectionStart; render(); const n = document.getElementById('q'); n.focus(); n.setSelectionRange(pos, pos); }
});
app.addEventListener('submit', async e => {
  e.preventDefault();
  const f = e.target.dataset.form, t = curTag();
  if (f === 'signin') {
    const pw = document.getElementById('pw').value;
    if (!pw) { S.authErr = 'Enter your page password.'; return render(); }
    S.busy = true; render();
    const r = await api('POST', '/v1/session', { password: pw });
    S.busy = false;
    if (!r.ok) { S.authErr = r.status === 401 ? 'That password is wrong.' : why(r); return render(); }
    S.authErr = ''; S.session.signedIn = true; await start();
  }
  if (f === 'pair') return pairPhone(slug(document.getElementById('pn').value));
  if (f === 'rename' && t) {
    const v = document.getElementById('nn').value.trim();
    if (await putLabel(t, v, t.icon)) { S.renaming = false; render(); toast(v ? 'Saved. Taps now carry the name ' + v + '.' : 'Name cleared'); }
  }
});
const mroot = document.getElementById('modal-root');
mroot.addEventListener('click', e => {
  if (e.target.closest('[data-act-m="copy-prov"]')) copy('knurctl provision', 'Command');
});
mroot.addEventListener('submit', async e => {
  e.preventDefault();
  const f = e.target.dataset.mform;
  if (f === 'conn') {
    const type = Object.keys(CONN_FORM).find(k => k === e.target.dataset.type); if (!type) return;
    const id = slug(document.getElementById('cn').value), url = document.getElementById('cu').value.trim(), err = document.getElementById('cerr');
    const fail = m => { err.hidden = false; err.textContent = m; };
    if (!id) return fail('Give it a name, like living-room.');
    if (!url) return fail('Add the address first.');
    const existing = connBy(type, id), isEdit = document.getElementById('cn').readOnly;
    if (existing && !isEdit) return fail('There’s already an action called ' + id + '. Pick another name.');
    const r = await api('PUT', `/v1/connections/${type}/${id}`, { url });
    if (!r.ok) return fail(why(r));
    const cn = await api('GET', '/v1/connections'); if (cn.ok) S.conns = cn.json.connections;
    closeModal(); render();
    const c = connBy(type, id) || { type, id };
    if (r.json.secret) return secretModal(c, r.json.secret);
    return toast('Saved ' + id);
  }
  if (f === 'url') {
    const r = await api('PUT', '/v1/settings', { publicUrl: document.getElementById('pu').value.trim() });
    if (!r.ok) { const el = document.getElementById('uerr'); el.hidden = false; el.textContent = why(r); return; }
    S.settings = r.json; closeModal(); S.reach = null; render(); loadReach(); return toast('Saved. Restart Knura to use it everywhere.');
  }
  if (f === 'home') {
    const r = await api('PUT', '/v1/settings', { name: document.getElementById('hn').value.trim() });
    if (!r.ok) return toast(why(r));
    S.settings = r.json; closeModal(); render(); return toast('Renamed');
  }
  if (f === 'pw') {
    const r = await api('POST', '/v1/password', { current: document.getElementById('p0').value, password: document.getElementById('p1').value });
    if (!r.ok) { const el = document.getElementById('pwerr'); el.hidden = false; el.textContent = r.status === 401 ? 'The current password is wrong.' : why(r); return; }
    closeModal(); return toast('Password changed. Other browsers are signed out.');
  }
});

// ---------- start ----------
async function start() {
  S.screen = 'loading'; render();
  await loadAll();
  S.screen = 'app'; lastRoute = location.hash; render(); poll();
  loadReach();
}
async function boot() {
  const r = await api('GET', '/v1/session');
  if (!r.ok) { S.screen = 'error'; S.err = r.status === 404 ? 'This Knura doesn’t have the dashboard turned on. Set admin.listen.port in knurd.properties.' : why(r); render(); return; }
  S.session = r.json;
  if (!r.json.passwordSet) { location.replace('/setup'); return; }
  if (!r.json.signedIn) {
    const probe = await api('GET', '/v1/settings'); // plaintext mode needs no sign-in
    if (!probe.ok) { S.screen = 'signin'; render(); return; }
  }
  await start();
}
boot();
})();
