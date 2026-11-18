/* The wallet, as a panel any page can mount: the balances with their icons, a fee preview and Take per currency, Deposit for the vouchers you carry, the
   Catalyst progress bar and a result line. Used by wallet.html and by the Wallet tab of the AscensionDex (party.html). It only asks the server (the
   ascension_wallet_* messages); the store decides. Needs ui.js first. */
(function () {
  'use strict';
  var A = window.A;
  var STYLE = [
    '.wp { flex: 1; min-height: 0; display: flex; flex-direction: column; gap: 8px; }',
    '.wp .hint { color: var(--muted); font-size: var(--fs-body); }',
    '.wp .lines { flex: 1; min-height: 0; overflow-y: auto; display: grid; gap: 6px; align-content: start; }',
    '.wp .line { display: grid; grid-template-columns: 40px 1fr 120px 150px 100px; gap: 10px; align-items: center; padding: 6px 8px; background: var(--sheen), var(--row); border: 2px solid var(--line); box-shadow: var(--raised); }',
    '.wp .line img { width: 32px; height: 32px; image-rendering: pixelated; background: var(--well); border: 2px solid var(--line); box-shadow: var(--sunk); padding: 2px; }',
    '.wp .line .nm { font-size: var(--fs-name); text-shadow: var(--glow); }',
    '.wp .line .amt { text-align: right; font-size: var(--fs-name); color: var(--acc); text-shadow: var(--glow); }',
    '.wp .line .car { font-size: var(--fs-meta); color: var(--muted); text-align: right; }',
    '.wp .line input { width: 74px; padding: 4px 6px; text-align: right; font-variant-numeric: tabular-nums; }',
    '.wp .withdraw { display: flex; gap: 6px; align-items: center; }',
    '.wp .fee { font-size: var(--fs-meta); color: var(--muted); text-align: right; min-height: 12px; white-space: nowrap; } .wp .fee.bad { color: var(--warn); }',
    '.wp .cat { padding: 6px 10px; background: var(--sheen), var(--row); border: 2px solid var(--line); box-shadow: var(--raised); display: flex; align-items: center; gap: 10px; font-size: var(--fs-body); }',
    '.wp .cat .bar { flex: 1; height: 10px; background: var(--well); border: 2px solid var(--line); box-shadow: inset 1px 1px 0 var(--lo); } .wp .cat .bar i { display: block; height: 100%; background: var(--acc); }',
    '.wp .banner { padding: 6px 10px; font-size: var(--fs-body); border: 2px solid var(--line); background: var(--row); box-shadow: var(--sunk); min-height: 28px; }',
    '.wp .banner.ok { border-color: var(--good); background: #0d2a1c; } .wp .banner.bad { border-color: var(--warn); background: #2b1612; }'
  ].join('\n');

  /**
   * Builds the panel inside `root` and starts listening. hooks.onView(view) runs after every redraw (the page uses it for its own footer);
   * the returned object can ask for fresh data and deposit everything the player carries.
   */
  function mount(root, hooks) {
    hooks = hooks || {};
    if (!document.getElementById('wp-style')) { var st = document.createElement('style'); st.id = 'wp-style'; st.textContent = STYLE; document.head.appendChild(st); }
    root.className = (root.className ? root.className + ' ' : '') + 'wp';
    root.innerHTML = '';
    root.appendChild(A.el('div', 'hint', 'Take currency out as items you can trade (a 3% fee, rounded up, is paid on top), then redeem them back with Deposit. Tower Marks are bound to you. Loot you pick up joins your wallet by itself.'));
    var catBox = A.el('div', 'cat'), lines = A.el('div', 'lines'), banner = A.el('div', 'banner');
    root.appendChild(catBox); root.appendChild(lines); root.appendChild(banner);
    var view = null, tries = 0;

    function say(ok, text) { banner.className = 'banner ' + (ok ? 'ok' : 'bad'); A.text(banner, text); }

    function render() {
      if (!view) return;
      lines.innerHTML = '';
      view.lines.forEach(function (l) {
        var row = A.el('div', 'line');
        var img = new Image(); img.alt = ''; img.src = 'icons/' + l.id + '.png'; row.appendChild(img);
        var nm = A.el('div', 'nm', l.name); nm.style.color = l.color; row.appendChild(nm);
        row.appendChild(A.el('div', 'amt', A.num(l.amount)));
        var wd = A.el('div', 'withdraw');
        var input = A.el('input'); input.type = 'number'; input.min = '1'; input.max = '2304'; input.value = String(Math.min(64, Math.max(1, l.amount))); wd.appendChild(input);
        var fee = A.el('div', 'fee');
        // The fee is taken on top, rounded up, so what you ask for is what the vouchers hold.
        var showFee = function () {
          var n = parseInt(input.value, 10);
          if (!(n > 0) || l.tradable === false) { A.text(fee, ''); fee.className = 'fee'; return; }
          var f = Math.floor((n * (view.feePercent || 0) + 99) / 100);
          var short = n + f > l.amount;
          A.text(fee, 'fee +' + A.num(f) + ' = ' + A.num(n + f) + (short ? ' (you have ' + A.num(l.amount) + ')' : ''));
          fee.className = 'fee' + (short ? ' bad' : '');
        };
        input.addEventListener('input', showFee); showFee();
        var go = A.el('button', '', l.tradable === false ? 'Bound' : 'Take'); go.disabled = l.amount < 1 || l.tradable === false;
        go.addEventListener('click', function () { var n = parseInt(input.value, 10); if (n > 0) A.send('ascension_wallet_withdraw', { material: l.id, amount: n }); });
        wd.appendChild(go); row.appendChild(wd);
        var dep = A.el('div', 'car');
        if (l.carried > 0) {
          dep.appendChild(A.el('div', '', 'carrying ' + A.num(l.carried)));
          var b = A.el('button', '', 'Deposit'); b.addEventListener('click', function () { A.send('ascension_wallet_deposit', { material: l.id }); });
          dep.appendChild(b);
        }
        row.appendChild(dep);
        lines.appendChild(row);
        if (l.tradable !== false) { var sub = A.el('div'); sub.style.gridColumn = '3 / 5'; sub.style.marginTop = '-6px'; sub.appendChild(fee); row.appendChild(sub); }
      });
      var frag = view.lines.filter(function (l) { return l.id === 'unique_fragment'; })[0], cata = view.lines.filter(function (l) { return l.id === 'unique_catalyst'; })[0];
      catBox.innerHTML = '';
      if (frag && view.catalystNeeded) {
        var have = Math.min(frag.amount, view.catalystNeeded);
        catBox.appendChild(A.el('span', '', 'Catalyst'));
        var bar = A.el('div', 'bar'), fill = A.el('i'); fill.style.width = Math.round(have / view.catalystNeeded * 100) + '%'; bar.appendChild(fill); catBox.appendChild(bar);
        catBox.appendChild(A.el('span', 'muted', A.num(frag.amount) + ' of ' + view.catalystNeeded + ' fragments' + (cata && cata.amount > 0 ? ' · ' + A.num(cata.amount) + ' ready' : '')));
      }
      if (hooks.onView) hooks.onView(view);
    }

    A.on('walletView', function (v) { view = v; tries = 99; render(); });
    A.on('walletResult', function (r) { say(!!r.ok, r.message); });

    function refresh() {
      if (!A.g) return;
      tries = 0;
      A.send('ascension_wallet_ready');
      var again = function () { if (!view && ++tries < 4) { A.send('ascension_wallet_ready'); setTimeout(again, 2000); } };
      setTimeout(again, 2000);
    }
    return {
      refresh: refresh,
      depositAll: function () { A.send('ascension_wallet_deposit', {}); },
      view: function () { return view; }
    };
  }

  A.wallet = { mount: mount };
})();
