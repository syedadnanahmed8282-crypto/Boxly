const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];
const B = window.Boxly || { pickZip(){}, pickIcon(){}, clearIcon(){}, build(){}, history: () => '[]', clearHistory(){}, install(){}, share(){} };
const T = {
  'index.html': '<!DOCTYPE html>\n<html lang="en">\n<head>\n  <meta charset="utf-8">\n  <meta name="viewport" content="width=device-width,initial-scale=1">\n  <link rel="stylesheet" href="style.css">\n</head>\n<body>\n  <h1>Hello, World!</h1>\n  <button id="b">Tap me</button>\n  <script src="script.js"><\/script>\n</body>\n</html>',
  'style.css': 'body{font-family:sans-serif;text-align:center;padding:60px 20px}\nbutton{font-size:18px;padding:12px 24px}',
  'script.js': "document.getElementById('b').onclick = () => {\n  if (window.Android) Android.toast('Hello from Boxly!');\n};"
};
let cur = 'index.html', mode = 'code', zipOk = false, last = null, iconUrl = null, shown = 0, raf = 0;
const defIcon = $('#iprev').innerHTML;
$('#ed').value = T[cur];

/* ---------- navigation ---------- */
const NAV = { home: 0, history: 1, settings: 2, building: 0, done: 0 };
function show(id) {
  $$('.scr').forEach(e => {
    const on = e.id === id;
    if (on && !e.classList.contains('on')) [...e.children].forEach((c, i) => c.style.setProperty('--i', i));
    e.classList.toggle('on', on);
  });
  $('#nav').hidden = id === 'building' || id === 'done';
  $('#nav').style.setProperty('--k', NAV[id]);
  ['home', 'history', 'settings'].forEach(n => $('#n-' + n).classList.toggle('on', n === id));
  if (id === 'history') renderHistory();
}
['home', 'history', 'settings'].forEach(n => $('#n-' + n).onclick = () => show(n));
$('#again').onclick = () => show('home');
$('#back').onclick = () => show('home');

/* ---------- ripple on every pressable ---------- */
document.addEventListener('pointerdown', e => {
  const t = e.target.closest('.btn,.seg button,.nav button,.tag,.files button,.drop');
  if (!t) return;
  let w = t.querySelector(':scope > .rw2');
  if (!w) { w = document.createElement('span'); w.className = 'rw2'; t.appendChild(w); }
  const r = t.getBoundingClientRect(), d = Math.max(r.width, r.height) * 2;
  const s = document.createElement('span'); s.className = 'rip';
  s.style.cssText = 'width:' + d + 'px;height:' + d + 'px;left:' + (e.clientX - r.left - d / 2) + 'px;top:' + (e.clientY - r.top - d / 2) + 'px';
  w.appendChild(s); setTimeout(() => s.remove(), 650);
});

/* ---------- editor / modes ---------- */
$$('.files button').forEach(b => b.onclick = () => {
  T[cur] = $('#ed').value; cur = b.dataset.f; $('#ed').value = T[cur];
  $$('.files button').forEach(x => x.classList.toggle('on', x === b));
  swap($('#ed'));
});
function swap(el) { el.classList.remove('swap'); void el.offsetWidth; el.classList.add('swap'); }
function setMode(m) {
  mode = m;
  $('#tab-code').classList.toggle('on', m === 'code'); $('#tab-zip').classList.toggle('on', m === 'zip');
  $('#spill').style.transform = m === 'zip' ? 'translateX(calc(100% + 4px))' : 'none';
  $('#pane-code').hidden = m !== 'code'; $('#pane-zip').hidden = m !== 'zip';
  swap($(m === 'zip' ? '#pane-zip' : '#pane-code'));
}
$('#tab-code').onclick = () => setMode('code'); $('#tab-zip').onclick = () => setMode('zip');
$('#pick').onclick = () => B.pickZip();
window.onZipPicked = (n, c, kb, hasIndex) => {
  zipOk = hasIndex;
  $('#zipinfo').textContent = hasIndex ? n + ' · ' + c + ' files · ' + kb + ' KB' : n + ': index.html not found';
};
window.onZipError = m => { zipOk = false; $('#zipinfo').textContent = m; };

/* ---------- custom icon ---------- */
$('#ipick').onclick = () => B.pickIcon();
window.onIconPicked = url => {
  iconUrl = url;
  const p = $('#iprev'); p.innerHTML = '<img alt="App icon preview" src="' + url + '">';
  p.classList.remove('pop'); void p.offsetWidth; p.classList.add('pop');
  $('#ihint').textContent = 'Custom icon selected'; $('#iclr').hidden = false;
};
window.onIconError = m => { $('#ihint').textContent = m; };
$('#iclr').onclick = () => {
  B.clearIcon(); iconUrl = null; $('#iprev').innerHTML = defIcon;
  $('#ihint').textContent = 'Default Boxly icon'; $('#iclr').hidden = true;
};

/* ---------- build ---------- */
$('#build').onclick = () => {
  T[cur] = $('#ed').value;
  if (mode === 'zip' && !zipOk) { $('#zipinfo').textContent = 'Choose a ZIP that contains index.html'; return; }
  $('#err').hidden = true; shown = 0; setP(0, 5); show('building');
  B.build($('#n').value, mode === 'zip' ? 'zip' : JSON.stringify(T));
};
function tween(to) {
  cancelAnimationFrame(raf);
  const from = shown, t0 = performance.now();
  (function f(t) {
    const k = Math.min(1, (t - t0) / 500), e = 1 - Math.pow(1 - k, 3);
    shown = Math.round(from + (to - from) * e);
    $('#pct').textContent = shown + '%';
    $('#bar').style.transform = 'scaleX(' + shown / 100 + ')';
    if (k < 1) raf = requestAnimationFrame(f);
  })(t0);
}
function setP(i, p) {
  tween(p);
  $$('.stp').forEach((e, k) => { const c = 'sdot stp' + (k < i ? ' ok' : k === i ? ' go' : ''); if (e.className !== c) e.className = c; });
}
window.onProgress = setP;
const kb = b => b > 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.round(b / 1024) + ' KB';
window.onDone = info => {
  last = info; setP(4, 100);
  $('#dn').textContent = info.name;
  $('#dm').textContent = kb(info.size) + ' · ' + info.pkg + (iconUrl && !info.iconApplied ? ' · icon not applied' : '');
  $('#dlogo').innerHTML = iconUrl && info.iconApplied ? '<img alt="" src="' + iconUrl + '">' : '<svg class="i"><use href="#i-box"/></svg>';
  setTimeout(() => { show('done'); burst(); }, 700);
};
window.onError = m => { $('#errmsg').textContent = m; $('#err').hidden = false; };
$('#inst').onclick = () => last && B.install(last.path);
$('#shr').onclick = () => last && B.share(last.path);

function burst() {
  const box = $('#cf'), cols = ['#7C5CFF', '#E0457B', '#FF9A5C', '#2BC9A0', '#FFD36B']; box.innerHTML = '';
  for (let i = 0; i < 32; i++) {
    const p = document.createElement('i'), a = Math.random() * Math.PI * 2, d = 90 + Math.random() * 140;
    p.style.cssText = '--x:' + (Math.cos(a) * d).toFixed(0) + 'px;--y:' + (Math.sin(a) * d - 50).toFixed(0) + 'px;--r:' + (Math.random() * 720 - 360).toFixed(0) + 'deg;--c:' + cols[i % 5] + ';animation-delay:' + (Math.random() * 150).toFixed(0) + 'ms';
    box.appendChild(p);
  }
}

/* ---------- history ---------- */
function renderHistory() {
  const h = JSON.parse(B.history() || '[]'), cs = ['c1', 'c2', 'c3', 'c4'];
  $('#hl').innerHTML = h.length ? '' : '<div class="it"><div class="grow mut">No builds yet.</div></div>';
  h.forEach((x, i) => {
    const d = document.createElement('div'); d.className = 'it'; d.style.setProperty('--i', i);
    d.innerHTML = '<div class="ico ' + cs[i % 4] + '"><svg class="i"><use href="#i-box"/></svg></div><div class="grow"><b></b><span class="mut"></span></div><button class="tag">Install</button>';
    d.querySelector('b').textContent = x.name;
    d.querySelector('.mut').textContent = kb(x.size) + ' · ' + new Date(x.time).toLocaleString();
    d.querySelector('button').onclick = () => B.install(x.path);
    $('#hl').appendChild(d);
  });
}
$('#clr').onclick = () => { B.clearHistory(); renderHistory(); };

/* ---------- ambient sparkles + splash ---------- */
for (let i = 0; i < 10; i++) {
  const s = document.createElement('i'), z = 3 + Math.random() * 4;
  s.className = 'sp';
  s.style.cssText = 'left:' + (Math.random() * 100).toFixed(0) + '%;top:' + (10 + Math.random() * 80).toFixed(0) + '%;width:' + z + 'px;height:' + z + 'px;--d:' + (4 + Math.random() * 4).toFixed(1) + 's;--dl:' + (Math.random() * 4).toFixed(1) + 's';
  $('.app').insertBefore(s, $('.b3').nextSibling);
}
setTimeout(() => show('home'), 1500);
setTimeout(() => { $('#splash').hidden = true; }, 2300);
