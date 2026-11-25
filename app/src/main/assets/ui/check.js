(function () {
  if (window.__boxlyCheck) return;
  window.__boxlyCheck = true;
  function run() {
    var W = window.innerWidth, H = window.innerHeight, issues = [];
    function nm(el) { var s = el.tagName.toLowerCase(); if (el.id) return s + '#' + el.id; if (el.classList && el.classList.length) return s + '.' + el.classList[0]; return s; }
    function add(title, fix) { issues.push({ title: title, fix: fix }); }

    // 1. viewport tag
    if (!document.querySelector('meta[name=viewport]')) add('No viewport tag.', 'Turn on Mobile fix, or add <meta name="viewport" content="width=device-width, initial-scale=1">.');

    // 2. content wider than the screen (starts on screen, ends beyond it)
    var wide = [], set = new Set();
    document.querySelectorAll('body *').forEach(function (el) {
      var r = el.getBoundingClientRect();
      if (r.width < 20 || r.left < -2 || r.right <= W + 2) return;
      for (var p = el.parentElement; p && p !== document.body; p = p.parentElement) if (getComputedStyle(p).overflowX !== 'visible') return;
      wide.push(el); set.add(el);
    });
    wide.filter(function (el) { return !set.has(el.parentElement); }).slice(0, 2).forEach(function (el) {
      add(nm(el) + ' is ' + Math.round(el.getBoundingClientRect().width) + 'px wide, screen is ' + W + 'px.', 'Use max-width:100% and avoid fixed pixel widths.');
    });

    // 3. fixed / sticky position overridden by another rule
    var seen = {};
    function walk(rules) {
      for (var i = 0; i < rules.length; i++) {
        var r = rules[i];
        if (r.type === 1 && r.style) {
          var pos = r.style.position;
          if (pos === 'fixed' || pos === 'sticky') {
            try {
              document.querySelectorAll(r.selectorText).forEach(function (el) {
                var cp = getComputedStyle(el).position;
                if (cp !== pos && !seen[r.selectorText]) { seen[r.selectorText] = 1; add('"' + r.selectorText + '" should be position:' + pos + ' but is ' + cp + '.', 'Another rule (like .card or .glass) overrides it. Raise specificity, e.g. header.glass{position:' + pos + '}.'); }
              });
            } catch (e) {}
          }
        } else if (r.type === 4 && window.matchMedia(r.conditionText || r.media.mediaText).matches) walk(r.cssRules);
      }
    }
    for (var s = 0; s < document.styleSheets.length; s++) { try { walk(document.styleSheets[s].cssRules); } catch (e) {} }

    // 4. icon / image much bigger than its width attribute
    var big = null;
    document.querySelectorAll('svg,img').forEach(function (el) {
      var a = parseFloat(el.getAttribute('width')), w = el.getBoundingClientRect().width;
      if (!big && a && a <= 64 && w >= a * 3 && w >= 60) big = { el: el, a: a, w: Math.round(w) };
    });
    if (big) add('An icon set to ' + big.a + 'px renders ' + big.w + 'px wide (' + nm(big.el.parentElement || big.el) + ').', 'A broad selector or @media rule is enlarging it. Scope it, e.g. .hero>svg instead of .hero svg.');

    // 5. scrolling blocked
    var de = document.documentElement, o = getComputedStyle(de).overflowY;
    if (o === 'visible') o = getComputedStyle(document.body).overflowY;
    if (o === 'hidden' && de.scrollHeight > H + 8) add('Content is cut off: page is ' + de.scrollHeight + 'px tall but cannot scroll.', 'Remove overflow:hidden from html/body, or use overflow-y:auto.');

    window.__boxlyIssues = issues;
    show(issues);
  }

  function show(issues) {
    var host = document.createElement('div');
    host.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;pointer-events:none';
    document.documentElement.appendChild(host);
    var sh = host.attachShadow({ mode: 'open' });
    var st = document.createElement('style');
    st.textContent = '.c{pointer-events:auto;margin:10px;padding:12px 14px;border-radius:18px;background:rgba(30,27,58,.96);color:#fff;font:13px/1.45 system-ui,sans-serif;box-shadow:0 10px 30px rgba(0,0,0,.35);max-height:34vh;overflow:auto}' +
      '.h{display:flex;align-items:center;justify-content:space-between;font-weight:800;margin-bottom:6px}.ok{background:linear-gradient(100deg,#2BC9A0,#4C8BFF);text-align:center;font-weight:700}' +
      '.i{margin:6px 0;padding-left:10px;border-left:3px solid #FF7AAE}.i b{display:block}.i{cursor:pointer}.i span{opacity:.8;display:none;margin-top:2px}.i.o span{display:block}.t{opacity:.7;font-size:11.5px;margin:2px 0 4px}' +
      'button{font:inherit;border:0;border-radius:12px;padding:9px 14px;font-weight:700;color:#fff;background:linear-gradient(100deg,#6A44F0,#C92F78)}button.x{background:none;padding:2px 8px;font-size:18px}';
    sh.appendChild(st);
    var c = document.createElement('div'); c.className = 'c'; sh.appendChild(c);
    if (!issues.length) {
      c.className = 'c ok'; c.textContent = '\u2713 Preview check: looks good';
      setTimeout(function () { host.remove(); }, 2400);
      return;
    }
    var h = document.createElement('div'); h.className = 'h';
    var t = document.createElement('span'); t.textContent = 'Preview check: ' + issues.length + ' issue' + (issues.length > 1 ? 's' : '');
    var x = document.createElement('button'); x.className = 'x'; x.textContent = '\u00d7'; x.setAttribute('aria-label', 'Close');
    x.onclick = function () { host.remove(); };
    h.appendChild(t); h.appendChild(x); c.appendChild(h);
    var tip = document.createElement('div'); tip.className = 't'; tip.textContent = 'Tap an issue to see the fix.'; c.appendChild(tip);
    issues.forEach(function (it) {
      var d = document.createElement('div'); d.className = 'i';
      var b = document.createElement('b'); b.textContent = it.title;
      var s = document.createElement('span'); s.textContent = it.fix;
      d.onclick = function () { d.classList.toggle('o'); };
      d.appendChild(b); d.appendChild(s); c.appendChild(d);
    });
    var cp = document.createElement('button'); cp.textContent = 'Copy fix request'; cp.style.marginTop = '8px';
    cp.onclick = function () {
      var txt = 'Please fix these mobile layout problems in my HTML/CSS. It runs in an Android WebView about ' + window.innerWidth + 'px wide. Return the full corrected file(s).\n' +
        issues.map(function (it, k) { return (k + 1) + '. ' + it.title + ' ' + it.fix; }).join('\n');
      if (window.Android && Android.copyText) Android.copyText(txt);
      else if (navigator.clipboard) navigator.clipboard.writeText(txt);
      cp.textContent = 'Copied';
    };
    c.appendChild(cp);
  }

  setTimeout(run, 900);
})();
