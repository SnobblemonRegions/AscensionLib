/* Shared helpers for AscensionLib's pages. Server text is only ever put in with textContent, never as HTML. */
(function () {
  'use strict';
  var g = window.webgui;
  var A = window.A = {};
  A.g = g;
  A.$ = function (id) { return document.getElementById(id); };

  /**
   * The page's own stopwatch, for the server's performance log (UiMetrics.java): how long each server event took to draw, how long the first data took
   * to arrive, and the browser's long tasks. Samples are batched (at most 12 labels of 20) and sent every 30 s and when the page closes.
   */
  var perfPage = (location.pathname.split('/').pop() || 'page').replace(/\.html?$/, '').replace(/[^a-z]/g, '') || 'page';
  var perfBuf = {}, perfLabels = 0, perfSamples = 0;
  A.perf = {
    add: function (label, ms) {
      label = String(label).replace(/[^a-zA-Z0-9.]/g, '').slice(0, 24);
      var list = perfBuf[label];
      if (!list) { if (!label || perfLabels >= 12) return; list = perfBuf[label] = []; perfLabels++; }
      if (list.length < 20) { list.push(Math.round(ms * 10) / 10); perfSamples++; }
    },
    time: function (label, fn) { var t = performance.now(); try { return fn(); } finally { A.perf.add(label, performance.now() - t); } },
    flush: function () {
      if (!g || !perfSamples) return;
      var body = { page: perfPage, t: perfBuf };
      perfBuf = {}; perfLabels = 0; perfSamples = 0;
      A.send('ascension_perf', body);
    }
  };
  setInterval(A.perf.flush, 30000);
  window.addEventListener('pagehide', A.perf.flush);
  try {
    new PerformanceObserver(function (list) { list.getEntries().forEach(function (e) { A.perf.add('longtask', e.duration); }); }).observe({ entryTypes: ['longtask'] });
  } catch (e) { /* the browser does not report long tasks */ }

  /**
   * WebGUI draws a page at the screen's real pixel size, so a fixed 1040 px tablet is small on a large monitor. This scales the tablet (and the
   * dialogs and overlay that sit beside it) to fill FILL of the window, set as the CSS variable --ui-scale that ascension.css applies. The
   * text is vector and the icons are pixel art drawn with image-rendering: pixelated, so a bigger scale stays crisp, not blurry.
   */
  var FILL = 0.82, MIN_SCALE = 0.5, MAX_SCALE = 3;
  A.pref = 100;   // the player's own size, percent of the automatic fit (saved on the server, see UiPrefsWeb.java)
  A.prefRange = [60, 160];
  A.fit = function () {
    var frame = document.querySelector('.frame'), w = window.innerWidth || 1920, h = window.innerHeight || 1080, s;
    if (frame) {
      s = Math.min(w * FILL / frame.offsetWidth, h * FILL / frame.offsetHeight) * A.pref / 100;
      // however large the player asks, the tablet never grows past the window
      s = Math.min(s, w * 0.98 / frame.offsetWidth, h * 0.98 / frame.offsetHeight);
    } else s = Math.min(w / 1920, h / 1080) * A.pref / 100;   // the overlay: relative to a 1080p screen
    s = Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
    document.documentElement.style.setProperty('--ui-scale', s.toFixed(3));
  };
  A.fit();
  var fitQueued = false;
  window.addEventListener('resize', function () {
    if (fitQueued) return;
    fitQueued = true;
    requestAnimationFrame(function () { fitQueued = false; A.fit(); });   // at most one forced layout per frame while the window is dragged
  });
  sizeControl();
  calmControl();
  /** A header control to make the page bigger or smaller: the server keeps the choice and answers with the clamped value. */
  function sizeControl() {
    var head = document.querySelector('.frame .head');
    if (!head || head.querySelector('.uisize')) return;
    var box = document.createElement('span'); box.className = 'uisize'; box.title = 'Page size';
    var minus = document.createElement('button'), plus = document.createElement('button'), label = document.createElement('span');
    minus.className = plus.className = 'x'; minus.textContent = '\u2212'; plus.textContent = '+'; label.className = 'pct'; label.id = 'uipct';
    minus.title = 'Smaller'; plus.title = 'Larger';
    var step = function (d) { return function () { A.send('ascension_ui_set', { percent: Math.max(A.prefRange[0], Math.min(A.prefRange[1], A.pref + d)) }); }; };
    minus.addEventListener('click', step(-10)); plus.addEventListener('click', step(10));
    box.appendChild(minus); box.appendChild(label); box.appendChild(plus);
    var close = head.querySelector('.x:last-child'); head.insertBefore(box, close || null);
    label.textContent = A.pref + '%';
  }
  /** A header button for calm effects (no animation, flash or flare); the server keeps the choice and answers with it. */
  function calmControl() {
    var head = document.querySelector('.frame .head');
    if (!head || head.querySelector('.calmbtn')) return;
    var b = document.createElement('button'); b.className = 'x calmbtn'; b.textContent = 'Calm'; b.id = 'calmbtn';
    b.title = 'Calm effects: turn off animation, flashes and glow';
    b.addEventListener('click', function () { A.send('ascension_ui_set', { calm: !A.calm }); });
    var box = head.querySelector('.uisize'), close = head.querySelector('.x:last-child');
    head.insertBefore(b, box || close || null);
  }
  A.calm = false;
  // Every page starts calm and gets its effects only when the server answers that this player has not asked for calm, so a sensitive player never
  // sees an animated frame before their choice has arrived.
  document.documentElement.classList.add('calm');
  A.send = function (channel, body) {
    if (!g) return;
    if (channel === 'close') A.perf.flush();
    var o = body || {}; o.channel = channel; g.postToGame(o);
  };
  /** A full-screen page tells the server it is open: the overlay must not open over it or close it (see GuiPresence.java). The overlay itself does not beat. */
  if (g && perfPage !== 'hud') {
    A.send('ascension_gui_beat');
    setInterval(function () { A.send('ascension_gui_beat'); }, 2000);
  }
  var firstData = false;
  A.on = function (event, fn) {
    if (!g) return;
    g.on(event, function (data) {
      var t = performance.now();
      if (!firstData && event !== 'sprites' && event !== 'uiScale') { firstData = true; A.perf.add('firstData', t); }   // ms since the page began loading
      try { fn(typeof data === 'string' ? JSON.parse(data) : data); } catch (e) { if (window.console) console.error(event, e); }
      A.perf.add('render.' + event, performance.now() - t);
    });
  };
  /** Asks the server for the page's data, and asks again (up to 3 more times, 2 s apart) while loaded() is still false: the first ask can beat the page's listener. */
  A.ready = function (channel, loaded, notice) {
    if (!g) { A.text(notice, 'This page needs the WebGUI mod.'); return; }
    var asked = 0;
    (function ask() {
      A.send(channel);
      if (++asked < 4) setTimeout(function () { if (!loaded()) ask(); }, 2000);
    })();
  };
  A.text = function (el, value) { el.textContent = value == null ? '' : String(value); };
  A.cap = function (s) { return s ? s.charAt(0).toUpperCase() + s.slice(1).toLowerCase() : ''; };
  // The name players see for a stored rarity id. Legendary and Mythical are shown as Radiant and Ascended so they cannot be read as Cobblemon's
  // Legendary and Mythical species; the ids (and CSS classes) stay. Keep in step with Rarity.displayName() and tools/build_guide.py.
  var RARITY_NAMES = { legendary: 'Radiant', mythical: 'Ascended' };
  A.rarityName = function (id) { return id ? (RARITY_NAMES[id] || A.cap(id)) : ''; };
  A.roman = ['', 'I', 'II', 'III', 'IV', 'V'];
  // A modifier's crest (icons/affix/<id>.png, drawn by tools/affix_art.py). The id is the display name lower-cased with every non-alphanumeric as _; `unique` is a Unique power.
  // A type-bound modifier is named "Type Focus (fire)": it has a crest in its type's colour (type_focus_fire.png), falling back to the plain one.
  var iconUrl = {};   // the file that worked for each icon (or '' when none did), so later rows ask for it directly and the browser reuses the decoded picture
  A.affixIcon = function (name, unique, cls) {
    var m = unique ? null : /^(.*?)\s*\(([a-z]+)\)\s*$/.exec(String(name || ''));
    var base = unique ? 'unique' : (m ? m[1] : String(name || '')).toLowerCase().replace(/[^a-z0-9]/g, '_');
    var key = base + (m ? '_' + m[2] : '');
    var img = new Image(); img.alt = ''; img.className = 'afx' + (cls ? ' ' + cls : '');
    var known = iconUrl[key];
    if (known === '') { img.style.visibility = 'hidden'; return img; }
    var tried = false;
    // each row carries its own fallback: a handler is not copied by cloneNode, so a shared element would leave rows made before the error with the broken file
    img.onerror = function () {
      if (m && !tried) { tried = true; iconUrl[key] = 'icons/affix/' + base + '.png'; img.src = iconUrl[key]; }
      else { iconUrl[key] = ''; img.style.visibility = 'hidden'; }
    };
    img.src = known || 'icons/affix/' + key + '.png';
    return img;
  };
  A.num = function (n) { n = Number(n) || 0; return n >= 10000000 ? (n / 1000000).toFixed(1) + 'M' : n.toLocaleString('en-US'); };
  A.speciesPath = function (speciesId) { var i = (speciesId || '').indexOf(':'); return i >= 0 ? speciesId.slice(i + 1) : (speciesId || ''); };
  A.speciesName = function (speciesId) { return A.speciesPath(speciesId).replace(/_/g, ' '); };
  /** An element: tag, class, text. */
  A.el = function (tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  var sprites = {}, waiting = {};
  /** Pictures arrive from the server as data URLs, in batches; an image waits for its species and shows the name until then. */
  A.on('sprites', function (batch) {
    Object.keys(batch).forEach(function (path) {
      sprites[path] = batch[path];
      (waiting[path] || []).forEach(function (fill) { fill(batch[path]); });
      delete waiting[path];
    });
  });
  /** The species' card sprite (from CobblemonCards on the server), or its name when the server has none. */
  A.sprite = function (speciesId, size, onLoad) {
    var box = A.el('div', 'sprbox');
    var path = A.speciesPath(speciesId);
    var label = function () { box.innerHTML = ''; box.appendChild(A.el('div', 'sprfb', A.speciesName(speciesId))); };
    label();
    if (!/^[a-z0-9_]+$/.test(path)) return box;
    var fill = function (url) {
      var img = new Image();
      img.className = 'spr'; img.alt = '';
      img.style.width = (48 * (size || 2)) + 'px'; img.style.height = (32 * (size || 2)) + 'px';
      img.onload = function () { box.innerHTML = ''; box.appendChild(img); if (onLoad) onLoad(img); };
      img.src = url;
    };
    if (sprites[path]) fill(sprites[path]);
    else (waiting[path] = waiting[path] || []).push(fill);
    return box;
  };
  /** The tablet's shell colour follows the Pokemon's rarity (none: the classic red). Hard-edged 9-slice, stepped corners. */
  var SHELLS = {
    common: ['#6c7585', '#aeb6c4', '#3f4755'], uncommon: ['#14877a', '#4fd3c1', '#0a4a42'], rare: ['#1d6db0', '#5bb7ee', '#0f3f6e'],
    epic: ['#6a35b8', '#af69ff', '#341a66'], legendary: ['#b88a12', '#ffd437', '#6b4f08'], mythical: ['#8e1426', '#d0404f', '#620512']
  };
  var bezelFor = null;
  A.setBezel = function (rarityId) {
    if (bezelFor === (rarityId || '')) return;   // the rarity rarely changes; re-setting --bezel re-decodes the frame's border-image
    bezelFor = rarityId || '';
    var c = SHELLS[rarityId] || SHELLS.mythical;
    var cols = ['#1b120d', c[1], c[0], c[0], c[2], '#1b120d', '#cbd3df', '#eef2f7'], parts = '';
    cols.forEach(function (col, i) {
      var a = i + 0.5, k = Math.max(0, 3 - i);
      var p = [[a + k, a], [24 - a - k, a], [24 - a, a + k], [24 - a, 24 - a - k], [24 - a - k, 24 - a], [a + k, 24 - a], [a, 24 - a - k], [a, a + k]];
      parts += "<path d='M" + p.map(function (q) { return q[0] + ',' + q[1]; }).join(' L') + " Z' fill='none' stroke='" + col + "' stroke-width='1'/>";
    });
    var svg = "<svg xmlns='http://www.w3.org/2000/svg' width='24' height='24' viewBox='0 0 24 24' shape-rendering='crispEdges'>" + parts + '</svg>';
    document.documentElement.style.setProperty('--bezel', 'url("data:image/svg+xml,' + encodeURIComponent(svg).replace(/'/g, '%27') + '")');
  };
  A.setBezel('');
  A.newId = function () {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) { var r = Math.random() * 16 | 0; return (c === 'x' ? r : (r & 3 | 8)).toString(16); });
  };
  if (g && g.isHud) document.body.classList.add('hud');
  // The player's saved size: asked for once the page loads and again whenever it changes (the header buttons).
  A.on('uiScale', function (s) {
    A.pref = s.percent; A.prefRange = [s.min, s.max];
    A.calm = !!s.calm;
    document.documentElement.classList.toggle('calm', A.calm);
    document.documentElement.classList.toggle('halloween', !!s.halloween);   // the event theme: ascension.css, HalloweenEvent.java
    var cb = document.getElementById('calmbtn'); if (cb) cb.classList.toggle('on', A.calm);
    var label = document.getElementById('uipct'); if (label) label.textContent = s.percent + '%';
    A.fit();
  });
  if (g) A.send('ascension_ui_get');
})();
