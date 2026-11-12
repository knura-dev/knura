// Knura themes: holiday dates, everyday looks and the decorations, for the dashboard.
// Plain data and small functions with no outside files, so the same rules can later be copied to
// the Knura app and the hosted dashboard. Colors live in themes.css; this file only picks which
// theme is on and draws the small extras (particles, the strip under the header, the greeting).
(function () {
'use strict';

// The holidays, in calendar order. Each window is inclusive and never overlaps another.
const HOLIDAYS = [
  { id: 'valentine', name: 'Valentine’s Day', greet: 'Happy Valentine’s Day', from: [2, 7], to: [2, 14] },
  { id: 'july4', name: '4th of July', greet: 'Happy 4th of July', from: [6, 28], to: [7, 5] },
  { id: 'halloween', name: 'Halloween', greet: 'Happy Halloween', from: [10, 17], to: [11, 1] },
  { id: 'thanksgiving', name: 'Thanksgiving', greet: 'Happy Thanksgiving', rule: 'thanksgiving' },
  { id: 'christmas', name: 'Christmas', greet: 'Happy holidays', from: [12, 1], to: [12, 28] },
  { id: 'newyear', name: 'New Year’s', greet: 'Happy New Year', from: [12, 29], to: [1, 2] }
];
const LOOKS = [
  { id: 'default', name: 'Default', note: 'Dark and orange' },
  { id: 'midnight', name: 'Midnight', note: 'True black, always dark' },
  { id: 'mono', name: 'Mono', note: 'No glow, solid cards' },
  { id: 'custom', name: 'Your color', note: 'Pick the accent' }
];
const ACCENTS = [['orange', 'Orange', '#F28C3A'], ['gold', 'Gold', '#F2C14E'], ['pink', 'Pink', '#FF7EB6'], ['violet', 'Violet', '#A98BFF'], ['blue', 'Blue', '#5B9CFF'], ['ice', 'Ice', '#6CCFF6']];

const day = d => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const at = (y, m, dd) => new Date(y, m - 1, dd);
const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
// Thanksgiving (US) is the fourth Thursday of November; the theme runs 10 days before to 2 after.
function thanksgiving(y) {
  const first = at(y, 11, 1), firstThu = 1 + ((4 - first.getDay() + 7) % 7);
  const t = at(y, 11, firstThu + 21);
  return [addDays(t, -10), addDays(t, 2)];
}
// The window of a holiday that contains or follows the start of year y.
function windowsFor(h, y) {
  if (h.rule === 'thanksgiving') return [thanksgiving(y)];
  const [fm, fd] = h.from, [tm, td] = h.to;
  if (tm < fm) return [[at(y - 1, fm, fd), at(y, tm, td)], [at(y, fm, fd), at(y + 1, tm, td)]]; // wraps the new year
  return [[at(y, fm, fd), at(y, tm, td)]];
}
/** The holiday on a date, with the last day of its window, or null. */
function holidayOn(date) {
  const d = day(date);
  for (const h of HOLIDAYS) {
    for (const [a, b] of windowsFor(h, d.getFullYear())) {
      if (d >= a && d <= b) return { holiday: h, until: b };
    }
  }
  return null;
}
/** The next holiday that starts after a date, with its first day. */
function nextHoliday(date) {
  for (let i = 1; i <= 370; i++) {
    const d = addDays(day(date), i), on = holidayOn(d);
    if (on && !(holidayOn(addDays(d, -1)) || {}).holiday) return { holiday: on.holiday, from: d };
  }
  return null;
}
const byId = id => HOLIDAYS.find(h => h.id === id) || null;

/**
 * What to show for saved choices: { look, accent, dynamic } where look is an everyday look id or
 * a holiday id the person picked. With dynamic on, the holiday of the day wins; a holiday always
 * wins over an everyday look.
 */
function resolve(prefs, now) {
  const look = LOOKS.some(l => l.id === prefs.look) || byId(prefs.look) ? prefs.look : 'default';
  const accent = ACCENTS.some(a => a[0] === prefs.accent) ? prefs.accent : 'orange';
  const today = prefs.dynamic ? holidayOn(now || new Date()) : null;
  const season = today ? today.holiday : byId(look);
  return { look: season ? 'default' : look, accent, season, auto: !!today, until: today ? today.until : null };
}

let current = { look: 'default', accent: 'orange', season: null };

/** Sets the theme on the page. */
function apply(prefs, now) {
  current = resolve(prefs, now);
  const root = document.documentElement;
  if (current.look === 'default') delete root.dataset.look; else root.dataset.look = current.look;
  root.dataset.accent = current.accent;
  if (current.season) root.dataset.season = current.season.id; else delete root.dataset.season;
  const fx = document.getElementById('sfx'), id = current.season ? current.season.id : '';
  if (fx && fx.dataset.for !== id) { fx.dataset.for = id; fx.innerHTML = id ? particles(id) : ''; } // only redrawn when it changes
  return current;
}

// ---------- drawings ----------
const heart = w => `<svg width="${w}" height="${w}" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 21s-8-5-8-11.2A4.6 4.6 0 0 1 12 7a4.6 4.6 0 0 1 8 2.8C20 16 12 21 12 21z" fill="currentColor"/></svg>`;
const star = w => `<svg width="${w}" height="${w}" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2.5l2.8 6 6.5.8-4.8 4.5 1.3 6.5L12 17l-5.8 3.3 1.3-6.5-4.8-4.5 6.5-.8z" fill="currentColor"/></svg>`;
const jack = s => `<svg width="${(20 * s).toFixed(1)}" height="${(19 * s).toFixed(1)}" viewBox="0 0 18 17" aria-hidden="true"><path d="M9 4.2c.2-1.6 1-2.7 2.4-3.2" fill="none" stroke="var(--s-stem)" stroke-width="1.6" stroke-linecap="round"/><ellipse cx="9" cy="10.2" rx="8.2" ry="6.3" fill="var(--s-a)"/><path d="M6 4.6c-1.6 1.5-1.6 9.6 0 11.2M12 4.6c1.6 1.5 1.6 9.6 0 11.2" fill="none" stroke="var(--s-rib)" stroke-width=".9"/><path d="M4.4 8.6l2 -1.5.9 2.3zM13.6 8.6l-2-1.5-.9 2.3zM4.6 11.4c1.2 2.4 7.6 2.4 8.8 0l-1.6.6-.8-.9-1 .9-1-.9-1 .9-.8-.9z" fill="var(--s-face)"/></svg>`;
const pennant = i => { const c = ['--s-a', '--s-b', '--s-c'][i % 3]; return `<svg width="16" height="18" viewBox="0 0 14 16" aria-hidden="true"><path d="M0 0h14L7 16z" fill="var(${c})"/>${i % 3 === 0 ? '<path d="M7 3.2l.9 1.9 2 .3-1.5 1.4.4 2L7 7.8 5.2 8.8l.4-2-1.5-1.4 2-.3z" fill="var(--s-b)"/>' : ''}</svg>`; };
const burst = (r, c) => `<svg width="${r * 2}" height="${r * 2}" viewBox="-10 -10 20 20" aria-hidden="true">${Array.from({ length: 12 }, (_, i) => { const a = i / 12 * Math.PI * 2; return `<line x1="${(Math.cos(a) * 3).toFixed(2)}" y1="${(Math.sin(a) * 3).toFixed(2)}" x2="${(Math.cos(a) * 8.5).toFixed(2)}" y2="${(Math.sin(a) * 8.5).toFixed(2)}" stroke="var(${c})" stroke-width="1.3" stroke-linecap="round"/><circle cx="${(Math.cos(a) * 9.6).toFixed(2)}" cy="${(Math.sin(a) * 9.6).toFixed(2)}" r=".8" fill="var(${c})"/>`; }).join('')}</svg>`;
const spread = (n, f) => Array.from({ length: n }, (_, i) => f(i, ((i + .5) * 100 / n).toFixed(2))).join('');

function particles(id) {
  const moon = id === 'halloween' ? '<div class="moon"></div>' : '';
  return moon + Array.from({ length: 22 }, (_, i) => {
    const x = (i * 37 + 11) % 97, y = (i * 53 + 29) % 95, d = 14 + (i * 7) % 12, dl = -((i * 29) % 24);
    const inner = id === 'valentine' ? heart(8 + (i % 3) * 2) : id === 'july4' ? star(6 + (i % 3) * 2) : '';
    return `<i style="left:${x}%;--y:${y}vh;--d:${d}s;--dl:${dl}s">${inner}</i>`;
  }).join('');
}

/** The decoration under the page header for the holiday that's on, or ''. */
function strip() {
  const s = current.season && current.season.id;
  if (!s) return '';
  if (s === 'halloween') {
    const graves = [[6, 15], [19, 11], [33, 17], [47, 12], [61, 16], [76, 11], [90, 15]].map(([x, h]) => `<svg class="grave" style="left:${x}%" width="${(h * .7).toFixed(1)}" height="${h}" viewBox="0 0 10 14" aria-hidden="true"><path d="M1 14V5a4 4 0 0 1 8 0v9z" fill="var(--s-grave)"/></svg>`).join('');
    const ground = '<svg class="ground" viewBox="0 0 100 16" preserveAspectRatio="none" aria-hidden="true"><path d="M0 9C10 6 18 5 28 7S48 4 60 6 82 8 100 6V16H0z" fill="var(--s-dirt)"/><path d="M0 9C10 6 18 5 28 7S48 4 60 6 82 8 100 6" fill="none" stroke="var(--s-dirt-top)" stroke-width="1.4" vector-effect="non-scaling-stroke"/></svg>';
    const pumpkins = [[12, 1, 7], [22, .75, 8], [44, 1.15, 6], [53, .8, 8], [71, 1, 7], [86, .85, 8]].map(([x, sc, b]) => `<span class="pk" style="left:${x}%;bottom:${b}px">${jack(sc)}</span>`).join('');
    return `<div class="strip patch" aria-hidden="true">${graves}${ground}${pumpkins}</div>`;
  }
  if (s === 'newyear') return `<div class="strip fw" aria-hidden="true">${[[8, 11, '--s-a', 0], [24, 8, '--s-b', 1.2], [41, 13, '--s-c', 2.3], [58, 9, '--s-a', .6], [74, 12, '--s-b', 1.8], [91, 9, '--s-c', 2.9]].map(([x, r, c, d]) => `<span style="left:${x}%;animation-delay:-${d}s">${burst(r, c)}</span>`).join('')}</div>`;
  if (s === 'valentine') return `<div class="strip hang hearts" aria-hidden="true">${spread(18, (i, x) => `<span style="left:${x}%">${heart(i % 2 ? 12 : 15)}</span>`)}</div>`;
  if (s === 'july4') return `<div class="strip hang" aria-hidden="true">${spread(22, (i, x) => `<span style="left:${x}%">${pennant(i)}</span>`)}</div>`;
  if (s === 'thanksgiving') return `<div class="strip leaves" aria-hidden="true">${spread(30, (i, x) => `<span style="left:${x}%"></span>`)}</div>`;
  return `<div class="strip bulbs" aria-hidden="true">${spread(34, (i, x) => `<span style="left:${x}%"></span>`)}</div>`;
}

const fmt = d => d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
/** A plain line about the holiday themes for the Settings page. */
function status(now) {
  const n = now || new Date(), on = holidayOn(n);
  if (on) return { now: on.holiday, text: `${on.holiday.name} until ${fmt(on.until)}` };
  const next = nextHoliday(n);
  return { now: null, text: next ? `Next: ${next.holiday.name} from ${fmt(next.from)}` : '' };
}
/** The dates each holiday is on this year, for the Settings page. */
function windowText(h, now) {
  const y = (now || new Date()).getFullYear(), w = windowsFor(h, y), [a, b] = w[w.length - 1];
  return `${fmt(a)} to ${fmt(b)}`;
}

window.KnuraThemes = { HOLIDAYS, LOOKS, ACCENTS, holidayOn, nextHoliday, resolve, apply, strip, status, windowText,
  current: () => current, greeting: () => current.season ? current.season.greet : null };
})();
