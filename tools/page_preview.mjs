// A local preview server for AscensionLib's web pages: serves them with a mock window.webgui that answers each page like the real server would
// (fixture data), so the pages can be opened and clicked in any browser without Minecraft.
// usage: node tools/page_preview.mjs [port]      then open http://127.0.0.1:<port>/
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const pagesDir = path.resolve(here, '../ascensionlib/src/main/resources/web/ascensionlib');
const port = Number(process.argv[2] || 8750);
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png' };

const MOCK = `<script>
(function () {
  var handlers = {}, log = [];
  var PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAFUlEQVR42mP8z8Dwn4EIwDiqkL4KAfE8Bv9hHFSfAAAAAElFTkSuQmCC';
  var price = { dust: 20, facets: 2, cores: 0 };
  var slots = [
    { slotId: 'p1', category: 'prefix', name: 'Smoldering', condition: 'Burns you inflict deal 50% more damage.', rank: 2, value: 12, bandMin: 10, bandMax: 14, nextMin: 15, nextMax: 19, upgradeBlock: '', refineBlock: '', reforgeBlock: '', reforgePool: 4, perfectBlock: 'Needs rank V', perfectCost: 0, floor: 6 },
    { slotId: 'p2', category: 'prefix', name: 'Physical Force', condition: '', rank: 1, value: 8, bandMin: 6, bandMax: 9, nextMin: 10, nextMax: 13, upgradeBlock: '', refineBlock: '', reforgeBlock: '', reforgePool: 4, perfectBlock: 'Needs rank V', perfectCost: 0, floor: 6 },
    { slotId: 's1', category: 'suffix', name: 'Stubborn', condition: '', rank: 5, value: 30, bandMin: 28, bandMax: 32, nextMin: -1, nextMax: -1, upgradeBlock: 'Already at the top rank', refineBlock: '', reforgeBlock: '', reforgePool: 3, perfectBlock: '', perfectCost: 40, floor: 29 } ];
  var view = { pokemonId: 'mew2', name: 'Mewtwo', speciesId: 'cobblemon:mewtwo', aspects: [], level: 52, rarityId: 'mythical', uniqueName: '777', pending: 2, nextMilestone: 60,
    profileRevision: 7, walletRevision: 8, dust: 1234, facets: 40, cores: 3, scouters: 5, marks: 6, refine: price, reforge: price,
    promotion: { toRarity: '', price: price, attunement: 90, attunementNeeded: 0, block: 'Already Ascended' },
    unique: { currentId: '777', catalysts: 1, fragments: 3, fragmentsNeeded: 5, block: '', options: [
      { id: '777', name: '777', benefit: 'More item rewards.', drawback: 'Lower defence.', current: true },
      { id: 'fury', name: 'Fury', benefit: 'Hits harder.', drawback: 'Takes more damage.', current: false } ] },
    respec: { hourglasses: 1, prisms: 1, marks: 6, price: 90, spent: 9, hourglassBlock: '', prismBlock: '', resets: [{ name: 'Smoldering', rank: 3, floor: 0 }, { name: 'Stubborn', rank: 5, floor: 29 }] },
    slots: slots, message: '', open: false };
  var E = function (n, id, rar, party, canHost, block) { return { id: id, name: n, speciesId: 'cobblemon:' + n.toLowerCase(), rarityId: rar, uniqueName: 'Overmind', inParty: party, canHost: canHost, block: block }; };
  var detail = function (rar) { return { level: 40, types: ['fire', 'flying'], baseStats: [90, 100, 80, 110, 80, 95], rarityId: rar, uniqueName: '777',
    slots: [{ category: 'prefix', name: 'Smoldering', rank: 3, value: 14, min: 12, max: 16, condition: 'Burns you inflict deal 50% more damage.' }, { category: 'suffix', name: 'Stubborn', rank: 1, value: 6, min: 5, max: 8, condition: '' }] }; };
  var scouted = {};
  function scoutState() { return { encounterId: 'enc-1', scouters: 3, entries: [{ subjectId: 's1', name: 'Growlithe', detail: scouted.s1 ? detail('rare') : undefined }, { subjectId: 's2', name: 'Houndoom', detail: detail('epic') }] }; }
  function emit(e, d) { setTimeout(function () { (handlers[e] || []).forEach(function (fn) { fn(Math.random() < .5 ? d : JSON.stringify(d)); }); }, 60); }
  var page = location.pathname.split('/').pop();
  window.webgui = {
    isHud: page === 'hud.html',
    on: function (e, fn) { (handlers[e] = handlers[e] || []).push(fn); },
    postToGame: function (m) {
      log.push(m); console.log('[page -> server]', JSON.stringify(m)); showLog();
      switch (m.channel) {
        case 'ascension_craft_ready': emit('sprites', { mewtwo: PNG }); emit('craftView', view); break;
        case 'ascension_craft_confirm':
          emit('craftDone', { operationId: m.operationId, ok: true, message: '', slotId: m.slotId, oldName: 'Smoldering', oldRank: 2, oldValue: 12, newName: 'Smoldering', newRank: 3, newValue: 16, replayed: false });
          view.pending = Math.max(0, view.pending - 1); emit('craftView', view); break;
        case 'ascension_fusion_open': location.href = 'fusion.html'; break;
        case 'ascension_craft_open': location.href = 'craft.html'; break;
        case 'ascension_fusion_ready': case 'ascension_fusion_refresh': emit('sprites', { mewtwo: PNG, mew: PNG }); emit('fusionCandidates', { host: E('Mewtwo', 'h1', 'mythical', true, true, ''), hostBlock: '' }); break;
        case 'ascension_fusion_pick':
          emit('fusionPickList', { hostRole: m.hostRole, entries: m.hostRole ? [E('Mewtwo', 'h1', 'mythical', true, true, ''), E('Mew', 'h2', 'mythical', false, true, '')]
            : [E('Mew', 'd1', 'epic', true, true, ''), E('Ditto', 'd2', 'common', true, false, 'Needs Epic rarity or better.'), E('Arcanine', 'd3', 'legendary', false, false, '')] }); break;
        case 'ascension_fusion_preview':
          emit('fusionDetail', { hostId: m.hostId, donorId: m.donorId, block: '', name: 'Mewmew', fromUniques: 'Overmind + Overmind', harmony: 'mild', benefit: 70, drawback: 30, blurb: 'Two minds, one.',
            core: 'Psychic pulses hit twice.', clutch: 'Heals when low.', drawbackText: 'Slower.', twistName: 'Echo', twistText: 'Repeats the first move.', riderText: 'Smoldering +12%', hostType: 'psychic', donorType: 'psychic',
            price: [600, 30, 10, 1, 5], balances: [1200, 40, 12, 2, 6], hostRevision: 3, donorRevision: 4, walletRevision: 5 }); break;
        case 'ascension_fusion_confirm': emit('fusionResult', { operationId: m.operationId, ok: true, message: '', name: 'Mewmew' }); break;
        case 'ascension_inspect_ready':
          emit('sprites', { arcanine: PNG });
          if (location.search.indexOf('enemy') >= 0) emit('inspectScout', scoutState());
          else emit('inspectOwned', { pokemonId: 'a1', name: 'Arcanine', species: 'Arcanine', pending: 2, attunement: 9, detail: detail('epic'),
            extra: { speciesId: 'cobblemon:arcanine', aspects: [], origin: 'wild_capture', initialRarity: 'rare', highestLevel: 40, milestones: 3, prefixCap: 2, suffixCap: 2, craftLocked: false } });
          break;
        case 'ascension_inspect_use': scouted[m.subjectId] = true; emit('inspectScout', scoutState()); emit('hudScout', scoutState()); break;
        case 'ascension_inspect_open': location.href = 'inspect.html' + (m.kind === 'enemy' ? '?enemy' : ''); break;
        case 'ascension_wallet_ready': case 'ascension_wallet_deposit': case 'ascension_wallet_withdraw':
          if (m.channel !== 'ascension_wallet_ready') emit('walletResult', { ok: true, message: m.channel === 'ascension_wallet_withdraw' ? 'Withdrew ' + m.amount + ' as voucher items.' : 'Deposited 64 Facet.' });
          emit('walletView', { freeSlots: 11, lines: [['resonance_dust', 'Resonance Dust', '#d9a441', 1234, 0], ['facet', 'Facet', '#4f9ad9', 40, 64], ['ascension_core', 'Ascension Core', '#c9694f', 3, 0],
            ['unique_fragment', 'Unique Fragment', '#8c63c7', 7, 0], ['unique_catalyst', 'Unique Catalyst', '#b04fa0', 1, 0], ['scouter', 'Scouter', '#6f9a52', 5, 0], ['ascension_sigil', 'Ascension Sigil', '#ffd437', 0, 0]]
            .map(function (a) { return { id: a[0], name: a[1], color: a[2], amount: a[3], carried: a[4] }; }) }); break;
        case 'ascension_wallet_open': location.href = 'wallet.html'; break;
        case 'ascension_hud_ready':
          emit('sprites', { arcanine: PNG });
          emit('hudReveal', { pokemonId: 'a1', name: 'Arcanine', speciesId: 'cobblemon:arcanine', aspects: [], detail: detail('epic') });
          emit('hudScout', scoutState());
          emit('hudTeam', [{ id: 'a1', name: 'Arcanine', speciesId: 'cobblemon:arcanine', rarityId: 'epic', level: 40 }, { id: 'a2', name: 'Mewtwo', speciesId: 'cobblemon:mewtwo', rarityId: 'mythical', level: 52 }]);
          break;
      }
    }
  };
  function showLog() {
    var box = document.getElementById('__log');
    if (!box) { box = document.createElement('pre'); box.id = '__log';
      box.style.cssText = 'position:fixed;left:6px;bottom:6px;max-width:46vw;max-height:30vh;overflow:auto;margin:0;padding:6px;background:#111c;color:#9f9;font:11px monospace;z-index:99999;pointer-events:none';
      document.addEventListener('DOMContentLoaded', function () { document.body.appendChild(box); }); if (document.body) document.body.appendChild(box); }
    box.textContent = log.slice(-6).map(function (m) { return 'page -> server  ' + JSON.stringify(m); }).join('\\n');
  }
})();
</script>`;

const INDEX = `<!doctype html><meta charset="utf-8"><title>AscensionLib page preview</title>
<body style="font:15px system-ui;margin:40px;max-width:640px"><h2>AscensionLib page preview</h2>
<p>Each page below talks to a mock server that answers with sample data. Messages the page sends appear in the green box at the bottom left.</p>
<ul><li><a href="craft.html">craft.html</a> (upgrade, refine, reforge, promote, unique)</li>
<li><a href="fusion.html">fusion.html</a> (two chambers and picker)</li>
<li><a href="inspect.html">inspect.html</a> (own Pokémon) · <a href="inspect.html?enemy">enemy scouting</a></li>
<li><a href="wallet.html">wallet.html</a> (balances as item icons, withdraw and deposit)</li>
<li><a href="hud.html">hud.html</a> (overlay: capture card and scouting panel; the page is transparent, so it sits on a game-like backdrop here)</li></ul></body>`;

http.createServer((req, res) => {
  const url = decodeURIComponent(req.url.split('?')[0]);
  if (url === '/' || url === '/index.html') { res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); res.end(INDEX); return; }
  const file = path.join(pagesDir, url.replace(/^\/+/, ''));
  if (!file.startsWith(pagesDir) || !fs.existsSync(file)) { res.writeHead(404); res.end('not found'); return; }
  let body = fs.readFileSync(file);
  if (file.endsWith('.html')) {
    let html = body.toString('utf-8').replace('<head>', '<head>' + MOCK);
    if (file.endsWith('hud.html')) html = html.replace('<body class="hud">', '<body class="hud" style="background:linear-gradient(#5b8bd0,#8fbf6a)">');
    body = Buffer.from(html);
  }
  res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream', 'cache-control': 'no-store' });
  res.end(body);
}).listen(port, '127.0.0.1', () => console.log(`AscensionLib page preview on http://127.0.0.1:${port}/`));
