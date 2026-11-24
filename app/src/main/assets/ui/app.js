const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];
const B = window.Boxly || { pickZip(){}, build(){}, history: () => '[]', clearHistory(){}, install(){}, share(){} };
const T = {
  'index.html': '<!DOCTYPE html>\n<html lang="en">\n<head>\n  <meta charset="utf-8">\n  <meta name="viewport" content="width=device-width,initial-scale=1">\n  <link rel="stylesheet" href="style.css">\n</head>\n<body>\n  <h1>Hello, World!</h1>\n  <button id="b">Tap me</button>\n  <script src="script.js"><\/script>\n</body>\n</html>',
  'style.css': 'body{font-family:sans-serif;text-align:center;padding:60px 20px}\nbutton{font-size:18px;padding:12px 24px}',
  'script.js': "document.getElementById('b').onclick = () => {\n  if (window.Android) Android.toast('Hello from Boxly!');\n};"
};
let cur = 'index.html', mode = 'code', zipOk = false, last = null;
$('#ed').value = T[cur];

function show(id) {
  $$('.scr').forEach(e => e.classList.toggle('on', e.id === id));
  $('#nav').hidden = id === 'building' || id === 'done';
  ['home', 'history', 'settings'].forEach(n => $('#n-' + n).classList.toggle('on', n === id));
  if (id === 'history') renderHistory();
}
['home', 'history', 'settings'].forEach(n => $('#n-' + n).onclick = () => show(n));
$('#again').onclick = () => show('home');
$('#back').onclick = () => show('home');

$$('.files button').forEach(b => b.onclick = () => {
  T[cur] = $('#ed').value; cur = b.dataset.f; $('#ed').value = T[cur];
  $$('.files button').forEach(x => x.classList.toggle('on', x === b));
});
function setMode(m) {
  mode = m;
  $('#tab-code').classList.toggle('on', m === 'code'); $('#tab-zip').classList.toggle('on', m === 'zip');
  $('#pane-code').hidden = m !== 'code'; $('#pane-zip').hidden = m !== 'zip';
}
$('#tab-code').onclick = () => setMode('code'); $('#tab-zip').onclick = () => setMode('zip');
$('#pick').onclick = () => B.pickZip();
window.onZipPicked = (n, c, kb, hasIndex) => {
  zipOk = hasIndex;
  $('#zipinfo').textContent = hasIndex ? n + ' · ' + c + ' files · ' + kb + ' KB' : n + ': index.html not found';
};
window.onZipError = m => { zipOk = false; $('#zipinfo').textContent = m; };

$('#build').onclick = () => {
  T[cur] = $('#ed').value;
  if (mode === 'zip' && !zipOk) { $('#zipinfo').textContent = 'Choose a ZIP that contains index.html'; return; }
  $('#err').hidden = true; setP(0, 5); show('building');
  B.build($('#n').value, mode === 'zip' ? 'zip' : JSON.stringify(T));
};
function setP(i, p) {
  $('#pct').textContent = p + '%';
  $('#bar').style.transform = 'scaleX(' + p / 100 + ')';
  $$('.stp').forEach((e, k) => e.className = 'sdot stp' + (k < i ? ' ok' : k === i ? ' go' : ''));
}
window.onProgress = setP;
const kb = b => b > 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.round(b / 1024) + ' KB';
window.onDone = info => {
  last = info; setP(4, 100);
  $('#dn').textContent = info.name; $('#dm').textContent = kb(info.size) + ' · ' + info.pkg;
  show('done');
};
window.onError = m => { $('#errmsg').textContent = m; $('#err').hidden = false; };
$('#inst').onclick = () => last && B.install(last.path);
$('#shr').onclick = () => last && B.share(last.path);

function renderHistory() {
  const h = JSON.parse(B.history() || '[]'), cs = ['c1', 'c2', 'c3', 'c4'];
  $('#hl').innerHTML = h.length ? '' : '<div class="it"><div class="grow mut">No builds yet.</div></div>';
  h.forEach((x, i) => {
    const d = document.createElement('div'); d.className = 'it';
    d.innerHTML = '<div class="ico ' + cs[i % 4] + '"><svg class="i"><use href="#i-box"/></svg></div><div class="grow"><b></b><span class="mut"></span></div><button class="tag" style="border:0;cursor:pointer">Install</button>';
    d.querySelector('b').textContent = x.name;
    d.querySelector('.mut').textContent = kb(x.size) + ' · ' + new Date(x.time).toLocaleString();
    d.querySelector('button').onclick = () => B.install(x.path);
    $('#hl').appendChild(d);
  });
}
$('#clr').onclick = () => { B.clearHistory(); renderHistory(); };
