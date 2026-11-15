/* One centered reveal at a time. A stable server identity deduplicates delivery, including after a page reload. */
(function () {
  'use strict';
  var A = window.A, root = document.getElementById('capture-root');
  var SHOW_MS = 12000, HISTORY_LIMIT = 256;
  var HISTORY_KEY = 'ascensionlib.capture.seen', QUEUE_KEY = 'ascensionlib.capture.queue';
  var seen = [], queue = [], active = null, timer = 0, deadline = 0, remaining = SHOW_MS;
  var hovering = false, focused = false;
  var disabled = false, flip = null, flipping = false;
  var RARITIES = ['common', 'uncommon', 'rare', 'epic', 'legendary', 'mythical'];
  // Opacity, prism intensity, glow radius, secondary prism color: higher rarities grow more spectacular.
  var AURAS = [[.18,.04,3,'#bdf1ff'], [.32,.10,5,'#9affd5'], [.46,.16,8,'#a9eaff'], [.62,.24,11,'#65ecff'], [.76,.28,13,'#fff3b3'], [.88,.32,16,'#bd8bff']];
  var COLORS = ['#c1c5cf', '#1bd7e8', '#38ccef', '#af69ff', '#ffd437', '#ff3448'];
  function valid(r) { return r && typeof r.pokemonId === 'string' && r.pokemonId && r.detail && RARITIES.indexOf(r.detail.rarityId) >= 0 && Array.isArray(r.detail.slots); }
  function key(r) { return r.revealId || r.pokemonId; }
  try {
    var history = JSON.parse(sessionStorage.getItem(HISTORY_KEY) || '[]');
    if (Array.isArray(history)) seen = history.filter(function (id) { return typeof id === 'string'; }).slice(-HISTORY_LIMIT);
    var waiting = JSON.parse(sessionStorage.getItem(QUEUE_KEY) || '[]');
    if (Array.isArray(waiting)) queue = waiting.filter(valid);
  } catch (e) { /* a disabled or full web store must never prevent a reveal */ }
  function persist() {
    try { sessionStorage.setItem(HISTORY_KEY, JSON.stringify(seen)); sessionStorage.setItem(QUEUE_KEY, JSON.stringify(queue)); } catch (e) { /* memory still deduplicates */ }
  }
  function remember(id) { if (seen.indexOf(id) < 0) seen.push(id); if (seen.length > HISTORY_LIMIT) seen.shift(); }
  function alpha(hex, opacity) { return 'rgba(' + [1,3,5].map(function (i) { return parseInt(hex.slice(i, i + 2), 16); }).join(',') + ',' + opacity + ')'; }
  function append(parent, tag, cls, text) { var child = A.el(tag, cls, text); parent.appendChild(child); return child; }
  function decorative(el) { el.setAttribute('aria-hidden', 'true'); return el; }
  function fit() {
    if (!active) return;
    var card = root.firstElementChild;
    var preferred = Math.min(window.innerWidth / 1920, window.innerHeight / 1080) * A.pref / 100;
    var scale = Math.min(preferred, window.innerWidth * .94 / card.offsetWidth, window.innerHeight * .94 / card.offsetHeight, 3);
    root.style.setProperty('--capture-scale', Math.max(.1, scale).toFixed(3));
  }
  function pause() {
    if (!timer) return;
    remaining = Math.max(0, deadline - Date.now()); clearTimeout(timer); timer = 0;
  }
  function resume() {
    if (!active || timer || flipping || hovering || focused || document.hidden) return;
    deadline = Date.now() + remaining; timer = setTimeout(dismiss, remaining);
  }
  function dismiss() {
    var previous = active;
    if (flip) flip.cancel(); flip = null; flipping = false;
    clearTimeout(timer); timer = 0; active = null; hovering = focused = false;
    root.replaceChildren(); next();
    if (previous && A.captureReveal.onDismiss) A.captureReveal.onDismiss(previous);
    if (A.captureReveal.onIdle) A.captureReveal.onIdle();
  }
  function explore() {
    if (!active) return;
    var id = active.pokemonId, revealId = key(active);
    if (flip) flip.cancel(); flip = null; flipping = false;
    // Keep later catches for the HUD's return; do not briefly display the next card under the inspector.
    clearTimeout(timer); timer = 0; active = null; hovering = focused = false; root.replaceChildren(); persist();
    A.send('ascension_capture_open', { id: id, revealId: revealId });
  }
  function next() {
    if (disabled || active || !queue.length) return;
    active = queue.shift(); remember(key(active)); persist();
    var r = active, d = r.detail, index = RARITIES.indexOf(d.rarityId), aura = AURAS[index], color = COLORS[index];
    A.setBezel(d.rarityId);
    var card = append(root, 'section', 'capture-card');
    card.dataset.rarityIndex = index;
    card.setAttribute('aria-label', 'Caught ' + r.name + ', ' + A.rarityName(d.rarityId));
    card.style.setProperty('--capture-rarity', color); card.style.setProperty('--capture-rarity-bg', 'var(--' + d.rarityId + '-bg)');
    card.style.setProperty('--aura-strength', aura[0]); card.style.setProperty('--prism-strength', aura[1]); card.style.setProperty('--aura-radius', aura[2] + 'px');
    card.style.setProperty('--aura-secondary', aura[3]); card.style.setProperty('--aura-soft', alpha(color, .5)); card.style.setProperty('--aura-secondary-soft', alpha(aura[3], .18));
    var rotator = append(card, 'div', 'capture-rotator'), front = append(rotator, 'div', 'capture-front');
    var lcd = append(front, 'div', 'capture-lcd'), head = append(lcd, 'div', 'capture-head'), lamps = decorative(append(head, 'span', 'capture-lamps'));
    var disable = append(card, 'button', 'capture-disable', 'Disable'); disable.type = 'button'; disable.title = 'Disable future capture reveals. Re-enable with /ascend reveals on.';
    disable.addEventListener('click', function () { disabled = true; clear(); if (A.captureReveal.onDisable) A.captureReveal.onDisable(); });
    for (var i = 0; i < 4; i++) append(lamps, 'i');
    append(head, 'span', 'capture-head-label', 'CAUGHT');
    append(lcd, 'h2', 'capture-name', r.name);
    var portrait = append(lcd, 'div', 'capture-portrait');
    ['tl','tr','bl','br'].forEach(function (corner) { decorative(append(portrait, 'i', 'capture-corner ' + corner)); });
    decorative(append(portrait, 'div', 'capture-aura'));
    var facets = decorative(append(portrait, 'div', 'capture-facets'));
    for (i = 0; i < 6; i++) append(facets, 'i');
    decorative(append(portrait, 'div', 'capture-pixels'));
    var sprite = append(portrait, 'div', 'capture-sprite'), prism = decorative(A.el('div', 'capture-prism'));
    sprite.appendChild(A.sprite(r.speciesId, 4.5, function (img) {
      var mask = 'url("' + img.src.replace(/"/g, '%22') + '")';
      prism.style.webkitMaskImage = mask; prism.style.maskImage = mask; prism.classList.add('ready');
    }));
    sprite.appendChild(prism);
    append(lcd, 'div', 'capture-rarity', A.rarityName(d.rarityId).toUpperCase());
    // Rarity means trait slots (Common 1 to Ascended 6): say so where the badge is, in words a Cobblemon player can place.
    append(lcd, 'p', 'capture-meaning', (index + 1) + ' of 6 trait slots' + (index === 5 ? '' : ' · every Pokémon can reach 6'));
    var content = append(lcd, 'div', 'capture-content');
    var label = append(content, 'div', 'capture-label', 'TRAITS');
    var traits = append(content, 'div', 'capture-traits'), rows = Object.create(null);
    function trait(name, value, rank, unique, condition, category) {
      var row = append(traits, 'div', 'capture-trait');
      row.appendChild(A.affixIcon(name, unique));
      var copy = append(row, 'div');
      append(copy, 'div', 'capture-trait-name', name);
      append(copy, 'div', 'capture-trait-rank', unique ? 'Unique power' : (category === 'prefix' ? 'Attack' : 'Guard') + ' · Rank ' + (A.roman[rank] || rank));
      if (condition) row.title = condition;
      if (!unique) append(row, 'span', 'capture-trait-value', '+' + value + '%');
    }
    if (d.uniqueName) trait(d.uniqueName, 0, 0, true, '');
    d.slots.forEach(function (s) {
      var id = s.category + ':' + s.name;
      if (rows[id]) return;
      rows[id] = true; trait(s.name, s.value, s.rank, false, s.condition, s.category);
    });
    if (traits.childElementCount > 2) {
      label.textContent = 'TRAITS (' + traits.childElementCount + ')';
      append(label, 'span', 'capture-scroll-hint', 'SCROLL \u2193');
      traits.tabIndex = 0;
      traits.setAttribute('role', 'region');
      traits.setAttribute('aria-label', 'Pokémon traits. Scroll to view all ' + traits.childElementCount + '.');
    }
    var promise = append(content, 'div', 'capture-promise'), quote = append(promise, 'p', 'quote');
    quote.appendChild(document.createTextNode('IVs are what it was born with. ')); append(quote, 'span', '', 'Rarity is how many traits it can hold.');
    append(promise, 'p', 'sub', index === 5 ? 'An Ascended partner. Make it your own.' : 'Check IVs on the Summary screen as usual.');
    var path = append(content, 'div', 'capture-path');
    path.setAttribute('aria-label', A.rarityName(d.rarityId) + ' rarity, ' + (index + 1) + ' of 6');
    for (i = 0; i < 6; i++) decorative(append(path, 'i', i <= index ? 'on' : ''));
    var labels = append(content, 'div', 'capture-path-labels'); append(labels, 'span', '', 'COMMON'); append(labels, 'span', '', 'ASCENDED');
    var actions = append(content, 'div', 'capture-actions');
    var open = append(actions, 'button', 'primary', 'Inspect'); open.type = 'button'; open.addEventListener('click', explore);
    var keep = append(actions, 'button', 'capture-continue', 'Continue'); keep.type = 'button'; keep.addEventListener('click', dismiss);
    var foot = append(lcd, 'div', 'capture-foot'), brand = append(foot, 'span'), dex = append(brand, 'img'); dex.src = 'icons/ascension_dex.png'; dex.alt = '';
    brand.appendChild(document.createTextNode('ASCENSION DEX')); append(foot, 'span', '', 'PROFILE SAVED');
    card.addEventListener('mouseenter', function () { hovering = true; pause(); });
    card.addEventListener('mouseleave', function () { hovering = false; resume(); });
    card.addEventListener('focusin', function () { focused = true; pause(); });
    card.addEventListener('focusout', function (e) { focused = card.contains(e.relatedTarget); if (!focused) resume(); });
    remaining = SHOW_MS; fit();
    if (A.captureFlip) {
      var current = active;
      flipping = true; flip = A.captureFlip(card, rotator, front);
      flip.finished.then(function () { if (active !== current) return; flipping = false; flip = null; resume(); });
    } else resume();
    if (document.fonts) document.fonts.ready.then(fit);
  }
  function enqueue(r) {
    if (disabled || !valid(r)) return;
    var id = key(r);
    if ((active && key(active) === id) || queue.some(function (waiting) { return key(waiting) === id; })) return;
    if (seen.indexOf(id) >= 0) { if (!active && A.captureReveal.onDismiss) A.captureReveal.onDismiss(r); return; }
    queue.push(r); persist(); next();
  }
  function clear() {
    if (flip) flip.cancel(); flip = null; flipping = false;
    clearTimeout(timer); timer = 0; active = null; queue = []; hovering = focused = false; root.replaceChildren(); persist();
  }
  A.captureReveal = { busy: function () { return !!active || queue.length > 0; }, currentId: function () { return active ? key(active) : ''; }, clear: clear, onIdle: null, onDismiss: null, onDisable: null };
  A.on('captureReveal', enqueue);
  // The server re-syncs scouting when an inspector closes: resume any catches held while it was open.
  A.on('hudScout', next);
  A.on('uiScale', function (s) { if (s.calm && flip) flip.cancel(); fit(); });
  window.addEventListener('resize', fit);
  document.addEventListener('visibilitychange', function () { if (document.hidden) pause(); else resume(); });
  next();
})();
