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
const iconOf = t => (t && t.icon && P[t.icon]) ? t.icon : 'tag';

// ---------- state ----------
const S = {
  screen: 'loading', session: null, status: null, settings: null, tags: [], conns: [], taps: [], days: null, reach: null,
  range: 'week', notif: false, q: '', tapRes: 'all', tapTag: 'all', tapWhen: '7', renaming: false, authErr: '', busy: false
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
const why = r => (r.json && r.json.detail) ? r.json.detail.charAt(0).toUpperCase() + r.json.detail.slice(1) + '.' : 'Something went wrong (' + r.status + ').';

async function loadAll() {
  const [st, set, tg, cn, tp, dy] = await Promise.all([api('GET', '/v1/status'), api('GET', '/v1/settings'), api('GET', '/v1/tags'),
    api('GET', '/v1/connections'), api('GET', '/v1/taps?limit=1000'), api('GET', '/v1/taps/days?days=14')]);
  if (st.ok) S.status = st.json;
  if (set.ok) S.settings = set.json;
  if (tg.ok) S.tags = tg.json.tags;
  if (cn.ok) S.conns = cn.json.connections;
  if (tp.ok) S.taps = tp.json.taps;
  if (dy.ok) S.days = dy.json;
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
  if (!S.conns.length) out.push({ tone: 'warn', icon: 'bolt', t: 'No actions yet', s: 'Taps are checked, but nothing happens when a tag is tapped.', fix: ['/setup', 'Add an action'] });
  else out.push({ tone: 'ok', icon: 'bolt', t: S.conns.length + (S.conns.length === 1 ? ' action' : ' actions') + ' ready', s: S.conns.map(c => c.id).join(', ') + ' · used by ' + used + (used === 1 ? ' tag' : ' tags') });
  if (st.backup) {
    const b = st.backup, last = [b.file, b.sheet].filter(Boolean).sort().pop();
    if (!last) out.push({ tone: 'bad', icon: 'key', t: 'Your master key has no backup', s: 'Every tag’s keys come from it. If this computer dies without a backup, your tags stop working.', fix: ['/setup', 'Make a recovery kit'] });
    else if (!b.file) out.push({ tone: 'warn', icon: 'key', t: 'Only a recovery sheet so far', s: 'Printed ' + ago(b.sheet) + '. A backup file also keeps your tags’ names and actions.', fix: ['/setup', 'Make a backup file'] });
    else out.push({ tone: 'ok', icon: 'key', t: 'Your master key is backed up', s: 'Backup file made ' + ago(b.file) + (b.sheet ? ' · recovery sheet printed ' + ago(b.sheet) : '') });
  }
  const phones = (S.settings && S.settings.phones) || [];
  out.push(phones.length ? { tone: 'ok', icon: 'phone', t: phones.length === 1 ? 'A phone is paired' : phones.length + ' phones are paired', s: phones.join(', ') + ' can add tags' }
    : { tone: 'mut', icon: 'phone', t: 'No phone paired', s: 'Add tags with a USB reader and knurctl, or pair a phone in setup.', fix: ['/setup', 'Pair a phone'] });
  return out;
}
const toneIcon = t => t === 'ok' ? 'check' : t === 'warn' || t === 'bad' ? 'warn' : 'info';
P.info = 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v5M12 8h.01';
const needs = () => checks().filter(c => c.tone === 'warn' || c.tone === 'bad');
const fixBtn = (c, primary) => !c.fix ? '' : c.fix[0].startsWith('/') ? `<a class="btn sm${primary ? ' primary' : ''}" href="${c.fix[0]}">${c.fix[1]}</a>` : `<button class="btn sm${primary ? ' primary' : ''}" data-act="${c.fix[0]}">${c.fix[1]}</button>`;

// ---------- shell ----------
const NAV = [['home','Home','home'],['tags','Tags','tag'],['taps','Taps','list'],['config','Settings','gear','end']];
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
        <a class="avatar" href="#config" aria-label="Settings" style="text-decoration:none;color:inherit">${ico('house', 20, 2)}</a>${notes}
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
      ${row('globe', 'Public address', addr ? '<span class="mono">' + esc(addr.replace(/^https?:\/\//, '')) + '</span>' : 'Not set, so phones can’t reach this computer yet', '<a class="btn sm" href="/setup">Change in setup</a>')}
      ${row('link', 'tap.knura.io', st.resolverOwner ? 'Forwards taps from tags with the tap.knura.io link' : 'Not used', '')}
    </section>
    <span class="label" style="padding:0 6px">Sign-in</span>
    <section class="g set-list" aria-label="Sign-in">
      ${row('lock', 'Page password', 'For this dashboard and the setup page', '<button class="btn sm" data-act="change-pw">Change</button>')}
    </section>
    <span class="label" style="padding:0 6px">More</span>
    <section class="g set-list" aria-label="More">
      ${link('/setup', 'wand', 'Setup', 'Recovery kit, address, phones and actions, step by step')}
      <button class="set-row" data-act="theme"><span class="tile">${ico(dark ? 'moon' : 'sun', 19)}</span><span class="txt"><b>Appearance</b><span>${dark ? 'Dark' : 'Light'} on this browser</span></span><span class="end" style="font-size:13px">Switch to ${dark ? 'light' : 'dark'}</span></button>
      ${link('https://docs.knura.io', 'help', 'Help', 'docs.knura.io')}
    </section>
    ${S.session && S.session.signedIn ? `<section class="g set-list">
      <button class="set-row signout" data-act="signout"><span class="tile">${ico('out', 19)}</span><span class="txt"><b>Sign out</b><span style="color:var(--muted)">Signs this browser out</span></span></button>
    </section>` : ''}
  </div>`;
  return shell('config', 'Settings', body);
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
          : `${mhead('Pair a phone first')}<p class="muted" style="font-size:14px">The Knura app sets tags up for this computer once it’s paired. Pairing takes a minute in setup.</p><div class="row"><a class="btn primary" href="/setup">Pair a phone in setup</a><button class="btn" data-x>Close</button></div>`)
        : `${mhead('Add a tag with a USB reader')}<p class="muted" style="font-size:14px">Plug in the reader, then run this in a terminal, in Knura’s settings folder. Put a blank tag on the reader when it asks.</p><div class="term"><span class="p">$</span> knurctl provision</div><p class="muted" style="font-size:13px">Tap it once afterwards and it shows up here under Tags, ready to name.</p><div class="row"><button class="btn sm" data-act-m="copy-prov">${ico('copy', 14)}Copy</button><button class="btn sm" data-x>Close</button></div>`;
    });
  });
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
  app.innerHTML = route.startsWith('tag-') ? vTag(route.slice(4).toUpperCase())
    : ({ home: vHome, tags: vTags, taps: vTaps, config: vConfig }[route] || vHome)();
}
let lastRoute = '', wasNarrow = narrow();
window.addEventListener('resize', () => { if (narrow() !== wasNarrow) { wasNarrow = narrow(); render(); } });
window.addEventListener('hashchange', () => {
  if (location.hash !== lastRoute) { S.notif = false; S.renaming = false; window.scrollTo(0, 0); }
  lastRoute = location.hash; render();
});
// Taps arrive while the page is open: check every 15 seconds, without disturbing typing or a dialog.
function poll() {
  clearInterval(pollT);
  pollT = setInterval(async () => {
    if (S.screen !== 'app' || document.hidden || S.renaming) return;
    const a = document.activeElement;
    if (document.getElementById('modal-root').firstChild || (a && (a.tagName === 'INPUT' || a.tagName === 'SELECT'))) return;
    const before = S.taps.length && S.taps[0].time;
    const [tp, tg, st, dy] = await Promise.all([api('GET', '/v1/taps?limit=1000'), api('GET', '/v1/tags'), api('GET', '/v1/status'), api('GET', '/v1/taps/days?days=14')]);
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
const curTag = () => { const h = location.hash.slice(1); return h.startsWith('tag-') ? tagByUid(h.slice(4).toUpperCase()) : null; };
async function putLabel(t, label, icon) {
  const r = await api('PUT', '/v1/tags/' + t.uid, { label: label || '', icon: icon || '' });
  if (!r.ok) { toast(why(r)); return false; }
  t.label = label || null; t.icon = icon || null; return true;
}
app.addEventListener('click', async e => {
  const b = e.target.closest('[data-act],[data-range],[data-toggle],[data-res]');
  if (!b) { if (S.notif && !e.target.closest('.pop')) { S.notif = false; render(); } return; }
  const t = curTag();
  if (b.dataset.range) { S.range = b.dataset.range; return render(); }
  if (b.dataset.res) { S.tapRes = b.dataset.res; return render(); }
  if (b.dataset.toggle && t) {
    const id = b.dataset.toggle, cur = t.actions || [];
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
    case 'theme': setTheme(curTheme() === 'dark' ? 'light' : 'dark'); return render();
    case 'notif': S.notif = !S.notif; return render();
    case 'reload': location.reload(); return;
    case 'recheck': S.reach = null; render(); await Promise.all([loadAll(), loadReach()]); render(); return toast('Checked again just now');
    case 'add-tag': return addTagModal();
    case 'test-tag': {
      const r = await api('POST', '/v1/tags/' + t.uid + '/test');
      return toast(r.ok ? (r.json.started ? 'Test sent to ' + (t.actions || []).join(', ') + '. Check that it arrived.' : 'Nothing to run: turn an action on first.') : why(r));
    }
    case 'taps-for': S.tapTag = b.dataset.uid; S.tapWhen = 'all'; return;
    case 'rename': S.renaming = true; render(); { const i = document.getElementById('nn'); i && (i.focus(), i.select()); } return;
    case 'rename-cancel': S.renaming = false; return render();
    case 'icon':
      return modal(`${mhead('Choose an icon')}<div class="icons">${ICONS.map(([o, l]) => `<button aria-label="${l}" title="${l}" aria-pressed="${t.icon === o}" data-pick="${o}">${ico(o, 24)}</button>`).join('')}</div>`, bg => {
        bg.addEventListener('click', async ev => { const p = ev.target.closest('[data-pick]'); if (!p) return; if (await putLabel(t, t.label, p.dataset.pick)) { closeModal(); render(); toast('Icon changed'); } });
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
app.addEventListener('change', e => {
  if (e.target.id === 'tt') { S.tapTag = e.target.value; return render(); }
  if (e.target.id === 'tw') { S.tapWhen = e.target.value; return render(); }
});
app.addEventListener('input', e => {
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
