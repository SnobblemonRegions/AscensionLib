// Drives AscensionLib's web pages in a headless Chromium with a mock window.webgui, so the pages can be tested without Minecraft.
// usage: node tools/page_harness.mjs [--shots <dir>]
// Needs playwright-core (found in ../CobbleTowers/web/node_modules) and the headless shell in work/preview-browsers.
import { createRequire } from 'node:module';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const pagesDir = path.join(root, 'ascensionlib/src/main/resources/web/ascensionlib');
// playwright-core is found in PLAYWRIGHT_DIR (a folder that has node_modules/playwright-core) when set, else in the sibling CobbleTowers checkout.
const require = createRequire(path.resolve(process.env.PLAYWRIGHT_DIR || path.join(root, '../../CobbleTowers/web/')) + path.sep);
const { chromium } = require('playwright-core');
const shotsDir = process.argv.includes('--shots') ? process.argv[process.argv.indexOf('--shots') + 1] : null;
if (shotsDir) fs.mkdirSync(shotsDir, { recursive: true });

function findShell() {
  const base = path.join(root, 'work/preview-browsers');
  for (const d of fs.readdirSync(base)) {
    const exe = path.join(base, d, 'chrome-headless-shell-win64', 'chrome-headless-shell.exe');
    if (fs.existsSync(exe)) return exe;
  }
  throw new Error('no headless shell in ' + base);
}

const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png' };
const server = http.createServer((req, res) => {
  const file = path.join(pagesDir, decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, ''));
  if (!file.startsWith(pagesDir) || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
  res.end(fs.readFileSync(file));
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}/`;

const MOCK = `
  window.__sent = []; window.__handlers = {};
  window.webgui = {
    isHud: location.pathname.endsWith('hud.html'),
    postToGame(m) { window.__sent.push(m); },
    on(e, fn) { (window.__handlers[e] = window.__handlers[e] || []).push(fn); },
  };
  window.__emit = (e, data, asString) => { (window.__handlers[e] || []).forEach(fn => fn(asString ? JSON.stringify(data) : data)); };
`;

// a 4x4 solid PNG so the sprite path can be exercised
const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAFUlEQVR42mP8z8Dwn4EIwDiqkL4KAfE8Bv9hHFSfAAAAAElFTkSuQmCC';

const price = { dust: 20, facets: 2, cores: 0 };
const slots = [
  { slotId: 'p1', category: 'prefix', name: 'Smoldering', condition: 'Burns you inflict deal 50% more damage.', rank: 2, value: 12, bandMin: 10, bandMax: 14, nextMin: 15, nextMax: 19, upgradeBlock: '', refineBlock: '', reforgeBlock: '', reforgePool: 4, perfectBlock: 'Needs rank V', perfectCost: 0, floor: 10, overreachBlock: 'Needs rank V', sealed: false, overreachDust: 60, overreachFacets: 2, overreachChance: 45, overreachMin: 35, overreachMax: 40 },
  { slotId: 's1', category: 'suffix', name: 'Stubborn', condition: '', rank: 5, value: 32, bandMin: 28, bandMax: 32, nextMin: -1, nextMax: -1, upgradeBlock: 'Already at the top rank', refineBlock: 'Needs 20 Dust', reforgeBlock: '', reforgePool: 3, perfectBlock: '', perfectCost: 40, floor: 29, overreachBlock: '', sealed: false, overreachDust: 60, overreachFacets: 2, overreachChance: 45, overreachMin: 35, overreachMax: 40 },
];
const craftView = {
  pokemonId: '00000000-0000-0000-0000-000000000001', name: 'Mewtwo', speciesId: 'cobblemon:mewtwo', aspects: [], level: 52, rarityId: 'mythical', uniqueName: '777',
  pending: 1, nextMilestone: 60, profileRevision: 7, walletRevision: 8, dust: 1234, facets: 40, cores: 3, scouters: 5, marks: 6, refine: price, reforge: price,
  promotion: { toRarity: '', price, attunement: 90, attunementNeeded: 0, block: 'Already Ascended' },
  unique: { currentId: '777', catalysts: 1, fragments: 3, fragmentsNeeded: 5, block: '', options: [
    { id: '777', name: '777', benefit: 'More item rewards.', drawback: 'Lower defence.', current: true },
    { id: 'fury', name: 'Fury', benefit: 'Hits harder.', drawback: 'Takes more damage.', current: false } ] },
  respec: { hourglasses: 1, prisms: 1, marks: 6, price: 90, spent: 9, hourglassBlock: '', prismBlock: '', resets: [{ name: 'Smoldering', rank: 3, floor: 0 }, { name: 'Stubborn', rank: 5, floor: 29 }] },
  slots, message: '', open: false,
};
const entry = (n, id, rarity = 'mythical', party = true, canHost = true, block = '') =>
  ({ id, name: n, speciesId: 'cobblemon:' + n.toLowerCase(), rarityId: rarity, uniqueName: 'Overmind', inParty: party, canHost, block });
const detail = (slotsExtra = []) => ({ level: 40, types: ['fire', 'flying'], baseStats: [90, 100, 80, 110, 80, 95], rarityId: 'epic', uniqueName: '777',
  slots: [{ category: 'prefix', name: 'Keen Edge', rank: 5, value: 16, min: 12, max: 16, condition: 'Crit chance.', theme: 'Predator' },
          { category: 'prefix', name: 'Momentum', rank: 2, value: 5, min: 3, max: 6, condition: 'Stacks on KOs.', theme: 'Predator' },
          { category: 'suffix', name: 'Wardstone', rank: 1, value: 6, min: 5, max: 10, condition: 'Status ward.', theme: 'Vitality' }, ...slotsExtra],
  resonance: [{ theme: 'Predator', pieces: 2, bonusPercent: 15 }, { theme: 'Vitality', pieces: 1, bonusPercent: 0 }] });

const results = [];
let failed = 0;
async function check(name, fn) {
  try { await fn(); results.push(['ok', name]); } catch (e) { failed++; results.push(['FAIL', name + ' :: ' + (e && e.message || e)]); }
}
const eq = (a, b, what) => { if (JSON.stringify(a) !== JSON.stringify(b)) throw new Error(`${what}: expected ${JSON.stringify(b)}, got ${JSON.stringify(a)}`); };
const yes = (v, what) => { if (!v) throw new Error('expected: ' + what); };

const browser = await chromium.launch({ executablePath: findShell() });
async function open(page, file, w = 1100, h = 700) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h } });
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', e => errors.push('pageerror: ' + e.message));
  p.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await p.addInitScript(MOCK);
  await p.goto(base + file);
  p.errors = errors;
  return p;
}
// every page also asks for the player's page size on load; the page tests below are about their own messages
const sent = p => p.evaluate(() => window.__sent.filter(m => m.channel !== 'ascension_ui_get' && m.channel !== 'ascension_gui_beat'));
const sentAll = p => p.evaluate(() => window.__sent);
const emit = (p, e, d, s) => p.evaluate(([e, d, s]) => window.__emit(e, d, s), [e, d, s]);
// shots are taken over a game-like green so a transparent page shows what the game would show through it
const shot = async (p, n) => {
  if (!shotsDir) return;
  await p.evaluate(() => { document.documentElement.dataset.bg = document.documentElement.style.background; document.documentElement.style.background = 'linear-gradient(#3b6aa8,#4f8a45)'; });
  await p.screenshot({ path: path.join(shotsDir, n + '.png') });
  await p.evaluate(() => { document.documentElement.style.background = document.documentElement.dataset.bg || ''; });
};
const noErrors = p => eq(p.errors, [], 'page errors');

// ---------------------------------------------------------------- craft
await check('craft: asks for its data, renders, upgrade flow, wallet, tabs', async () => {
  const p = await open(null, 'craft.html');
  eq((await sent(p)).map(m => m.channel), ['ascension_craft_ready'], 'first message');
  await emit(p, 'sprites', { mewtwo: PNG });
  await emit(p, 'craftView', craftView);
  eq(await p.textContent('#name'), 'Mewtwo', 'name');
  eq(await p.textContent('#pend'), '1', 'pending');
  eq(await p.locator('.tab').count(), 8, 'tabs');
  eq(await p.locator('#list .row').count(), 2, 'affix rows');
  yes((await p.textContent('#wallet')).includes('1,234'), 'wallet shows dust with separators');
  eq(await p.locator('#wallet img.dot').count(), 9, 'the craft wallet column shows the item icons');
  yes(await p.locator('#art img').count() === 1, 'sprite image appears from the data URL');
  await shot(p, 'craft-upgrade');
  // Upgrade tab is selected (pending > 0): Review is enabled for Smoldering, then confirm sends one request
  await p.locator('#detail button.primary').click();
  yes(await p.locator('#scrim.on').count() === 1, 'review dialog opens');
  await p.click('#dOk');
  const m = (await sent(p)).find(x => x.channel === 'ascension_craft_confirm');
  yes(m, 'confirm was sent');
  eq([m.kind, m.slotId, m.profileRevision, m.walletRevision, m.pokemonId], ['upgrade', 'p1', 7, 8, craftView.pokemonId], 'confirm body');
  yes(/^[0-9a-f-]{36}$/.test(m.operationId), 'operation id is a uuid');
  await emit(p, 'craftDone', { operationId: m.operationId, ok: true, message: '', slotId: 'p1', oldName: 'Smoldering', oldRank: 2, oldValue: 12, newName: 'Smoldering', newRank: 3, newValue: 16, replayed: false }, true);
  yes((await p.textContent('.banner')).includes('Smoldering'), 'banner shows the result');
  yes(await p.locator('#scrim.on').count() === 0, 'dialog closed');
  // other tabs render without errors
  // Perfecting: only the rank V slot (Stubborn) can be polished; its price and the confirm request come from the server's numbers
  await p.locator('.tab', { hasText: 'Perfect' }).click();
  eq(await p.locator('#list .row.off').count(), 1, 'the rank II slot is dimmed for perfecting');
  await p.locator('#list .row', { hasText: 'Stubborn' }).click();
  yes((await p.textContent('#detail')).includes('+30%'), 'shows the new floor (29 -> 30)');
  yes((await p.textContent('#detail')).includes('40 Dust'), 'shows the server price');
  await p.locator('#detail button.primary').click();
  await p.click('#dOk');
  const pm = (await sent(p)).filter(x => x.channel === 'ascension_craft_confirm').pop();
  eq([pm.kind, pm.slotId], ['perfect', 's1'], 'perfect confirm');
  await emit(p, 'craftDone', { operationId: pm.operationId, ok: true, message: '', slotId: 's1', oldName: 'Stubborn', oldRank: 5, oldValue: 29, newName: 'Stubborn', newRank: 5, newValue: 30, replayed: false });
  yes((await p.textContent('.banner')).includes('Floor raised'), 'banner after perfecting');
  // Overreach: the gamble, offered only on a rank V slot at its band top; the risk is spelled out before the confirm
  await p.locator('.tab', { hasText: 'Overreach' }).click();
  eq(await p.locator('#list .row.off').count(), 1, 'the rank II slot is dimmed for overreach');
  await p.locator('#list .row', { hasText: 'Stubborn' }).click();
  yes((await p.textContent('#detail')).includes('45% to win'), 'shows the odds');
  yes((await p.textContent('#detail')).includes('+35–40%'), 'shows the range a win would give');
  await p.locator('#detail button.primary').click();
  yes((await p.textContent('#dBody')).includes('RISKY'), 'the dialog says it is risky');
  await p.click('#dOk');
  const om = (await sent(p)).filter(x => x.channel === 'ascension_craft_confirm').pop();
  eq([om.kind, om.slotId], ['overreach', 's1'], 'overreach confirm');
  await emit(p, 'craftDone', { operationId: om.operationId, ok: true, message: '', slotId: 's1', oldName: 'Stubborn', oldRank: 5, oldValue: 32, newName: 'Stubborn', newRank: 5, newValue: 37, replayed: false });
  yes((await p.textContent('.banner')).includes('Overreached'), 'a win is announced');
  await p.locator('.tab', { hasText: 'Overreach' }).click();
  await p.locator('#list .row', { hasText: 'Stubborn' }).click();
  await p.locator('#detail button.primary').click();
  await p.click('#dOk');
  const om2 = (await sent(p)).filter(x => x.channel === 'ascension_craft_confirm').pop();
  await emit(p, 'craftDone', { operationId: om2.operationId, ok: true, message: '', slotId: 's1', oldName: 'Stubborn', oldRank: 5, oldValue: 32, newName: 'Stubborn', newRank: 5, newValue: 20, replayed: false });
  yes((await p.textContent('.banner')).includes('failed'), 'a loss is announced');
  for (const t of ['Refine', 'Reforge', 'Promote', 'Unique']) { await p.locator('.tab', { hasText: t }).click(); }
  await shot(p, 'craft-unique');
  yes((await p.textContent('#detail')).includes('Benefit'), 'unique tab shows benefit');
  await p.locator('.tab', { hasText: 'Promote' }).click();
  yes((await p.textContent('#detail')).includes('highest rarity'), 'promote at top rarity says so');
  await p.click('#bank');
  yes((await sent(p)).some(x => x.channel === 'ascension_wallet_open'), 'the Wallet button opens the wallet page');
  await p.click('#fuse');
  yes((await sent(p)).some(x => x.channel === 'ascension_fusion_open' && x.hostId === craftView.pokemonId), 'Fuse opens fusion for this host');
  noErrors(p);
});

await check('craft respec: the Hourglass tab previews the reset, confirms it, shows the refusal for a sealed Pokemon, and buys one with Marks', async () => {
  const p = await open(null, 'craft.html', 1920, 1080);
  await emit(p, 'sprites', { mewtwo: PNG });
  await emit(p, 'craftView', craftView);
  await p.locator('.tab', { hasText: 'Respec' }).click();
  yes((await p.textContent('#detail')).includes('Hourglass of Undoing'), 'the Hourglass card');
  yes((await p.textContent('#detail')).includes('9 upgrade credits come back'), 'what comes back');
  yes((await p.textContent('#detail')).includes('Overreached') && (await p.textContent('#detail')).includes('never be respecced'), 'the Overreach warning is on the card');
  yes((await p.textContent('#list')).includes('Stubborn') && (await p.textContent('#list')).includes('floor +29% cleared'), 'the left list shows what resets, floors included');
  yes((await p.textContent('#detail')).includes('You hold 1 Hourglass'), 'holdings');
  eq(await p.locator('#detail button', { hasText: 'Buy for 90 Marks' }).isDisabled(), true, 'six Marks cannot buy one');
  await shot(p, 'craft-respec');
  await p.locator('#detail button', { hasText: 'Review' }).click();
  yes((await p.textContent('#dTitle')).includes('Confirm respec'), 'the dialog');
  yes((await p.textContent('#dBody')).includes('Reset 2 modifiers to rank I') && (await p.textContent('#dBody')).includes('Cost: 1 Hourglass of Undoing'), 'what it does and costs');
  await p.click('#dOk');
  const c = (await sent(p)).find(x => x.channel === 'ascension_craft_confirm' && x.kind === 'respec_ranks');
  eq([c.kind, c.slotId], ['respec_ranks', ''], 'it asks the server for a Rank Respec');
  await emit(p, 'craftDone', { operationId: c.operationId, ok: true, message: '', slotId: '', oldName: '', oldRank: 0, oldValue: 9, newName: 'Respec', newRank: 0, newValue: 0, replayed: false });
  yes((await p.textContent('.banner')).includes('9 upgrade credits back to spend'), 'the result line');
  // a sealed Pokemon: the server's reason is shown and Review is off
  await emit(p, 'craftView', Object.assign({}, craftView, { respec: Object.assign({}, craftView.respec, { hourglassBlock: 'An Overreached Pokemon is sealed for good and cannot be respecced' }) }));
  yes((await p.textContent('#detail')).includes('sealed for good'), 'the refusal reason shows');
  eq(await p.locator('#detail button', { hasText: 'Review' }).isDisabled(), true, 'and Review is off');
  // the Marks route
  await emit(p, 'craftView', Object.assign({}, craftView, { respec: Object.assign({}, craftView.respec, { marks: 95, hourglassBlock: '' }) }));
  const buy = p.locator('#detail button', { hasText: 'Buy for 90 Marks' });
  eq(await buy.isDisabled(), false, 'with 95 Marks the purchase is open');
  await buy.click();
  yes((await p.textContent('#dTitle')).includes('Confirm purchase') && (await p.textContent('#dBody')).includes('90 Tower Marks'), 'the purchase dialog');
  await p.click('#dOk');
  eq((await sent(p)).filter(x => x.channel === 'ascension_craft_confirm').pop().kind, 'buy_hourglass', 'it asks to buy');
  noErrors(p);
});

await check('modifier crests: a type-bound row ("Type Focus (fire)") gets its own type crest, an unknown type falls back to the plain one, a Unique has its own', async () => {
  const p = await open(null, 'craft.html', 1920, 1080);
  const sizes = await p.evaluate(async () => {
    const load = img => new Promise(r => { if (img.complete && img.naturalWidth) r(img.naturalWidth); else { img.addEventListener('load', () => r(img.naturalWidth)); img.addEventListener('error', () => setTimeout(() => r(img.complete && img.naturalWidth ? img.naturalWidth : 0), 300)); } });
    const out = {};
    for (const [key, name, unique] of [['plain', 'Smoldering', false], ['typed', 'Type Focus (fire)', false], ['unknown', 'Type Ward (zzz)', false], ['unique', 'Anything', true]]) {
      const img = A.affixIcon(name, unique); document.body.appendChild(img);
      out[key] = [await load(img), img.src.split('/').pop()];
    }
    return out;
  });
  yes(sizes.plain[0] === 32 && sizes.plain[1] === 'smoldering.png', 'a plain modifier uses its own crest: ' + JSON.stringify(sizes.plain));
  yes(sizes.typed[0] === 32 && sizes.typed[1] === 'type_focus_fire.png', 'a typed one uses its type crest: ' + JSON.stringify(sizes.typed));
  yes(sizes.unknown[0] === 32 && sizes.unknown[1] === 'type_ward.png', 'an unknown type falls back to the plain crest: ' + JSON.stringify(sizes.unknown));
  yes(sizes.unique[0] === 32 && sizes.unique[1] === 'unique.png', 'a Unique power has its own crest');
  await p.close();
});

await check('craft respec: the Unique tab offers the Unbinding Prism as a free swap, blocked with a reason when it cannot be used', async () => {
  const p = await open(null, 'craft.html', 1920, 1080);
  await emit(p, 'sprites', { mewtwo: PNG });
  await emit(p, 'craftView', craftView);
  await p.locator('.tab', { hasText: 'Unique' }).click();
  await p.locator('.uopt', { hasText: 'Fury' }).click();
  yes((await p.textContent('#detail')).includes('swap it free with an Unbinding Prism (you hold 1)'), 'the Prism row');
  await p.locator('#detail button', { hasText: 'Use Unbinding Prism' }).click();
  yes((await p.textContent('#dTitle')).includes('Confirm unbinding'), 'its own dialog');
  yes((await p.textContent('#dBody')).includes('Swap 777 for Fury') && (await p.textContent('#dBody')).includes('no Catalyst, no Tower Mark'), 'what it does and costs');
  await p.click('#dOk');
  const c = (await sent(p)).filter(x => x.channel === 'ascension_craft_confirm').pop();
  eq([c.kind, c.slotId], ['respec_unique', 'fury'], 'it asks for a Prism swap to the chosen Unique');
  await emit(p, 'craftDone', { operationId: c.operationId, ok: true, message: '', slotId: 'fury', oldName: '777', oldRank: 0, oldValue: 0, newName: 'Fury', newRank: 0, newValue: 0, replayed: false });
  yes((await p.textContent('.banner')).includes('Unique swapped: Fury'), 'the result line');
  // the normal Review still takes the Catalyst path
  await p.locator('.uopt', { hasText: 'Fury' }).click();
  await emit(p, 'craftView', craftView);
  await p.locator('.uopt', { hasText: 'Fury' }).click();
  await p.locator('#detail button', { hasText: 'Review' }).click();
  await p.click('#dOk');
  eq((await sent(p)).filter(x => x.channel === 'ascension_craft_confirm').pop().kind, 'unique', 'Review without the Prism is the Catalyst route');
  // no Prism held: disabled with the reason
  await emit(p, 'craftView', Object.assign({}, craftView, { respec: Object.assign({}, craftView.respec, { prisms: 0, prismBlock: 'You have no Unbinding Prism' }) }));
  await p.locator('.uopt', { hasText: 'Fury' }).click();
  eq(await p.locator('#detail button', { hasText: 'Use Unbinding Prism' }).isDisabled(), true, 'no Prism, no button');
  noErrors(p);
});

await check('craft tutorials: none before the server answers, one per unseen mechanic, dismissed for good, reopened with ?', async () => {
  const p = await open(null, 'craft.html');
  await emit(p, 'craftView', craftView);
  eq(await p.locator('.coach').count(), 0, 'no tutorial while the seen list is unknown');
  await emit(p, 'uiScale', { percent: 100, min: 60, max: 160, calm: false, seen: ['craft.refine'] });
  yes((await p.textContent('.coach')).includes('How Upgrade works'), 'the opening mechanic shows its tutorial');
  await p.locator('.coach button.primary').click();
  eq(await p.locator('.coach').count(), 0, 'Got it closes it');
  eq((await sentAll(p)).filter(m => m.channel === 'ascension_ui_set').pop().seen, 'craft.upgrade', 'and tells the server');
  await p.locator('.tab', { hasText: 'Refine' }).click();
  eq(await p.locator('.coach').count(), 0, 'an already-seen mechanic shows none');
  await p.locator('.tab', { hasText: 'Reforge' }).click();
  yes((await p.textContent('.coach')).includes('Reforge'), 'an unseen one does');
  await p.locator('.tab', { hasText: 'Refine' }).click();
  await p.locator('.dhead .help').click();
  yes((await p.textContent('.coach')).includes('How Refine works'), '? reopens a seen tutorial');
  eq(await p.locator('.tab').count(), 8, 'all eight mechanics stay on the rail');
  noErrors(p);
});

await check('craft: a server refusal shows and a stale wait recovers', async () => {
  const p = await open(null, 'craft.html');
  await emit(p, 'craftView', craftView);
  await p.locator('#detail button.primary').click();
  await p.click('#dOk');
  const m = (await sent(p)).find(x => x.channel === 'ascension_craft_confirm');
  await emit(p, 'craftDone', { operationId: m.operationId, ok: false, message: 'You do not have enough materials.', slotId: 'p1', oldName: '', oldRank: 0, oldValue: 0, newName: '', newRank: 0, newValue: 0, replayed: false });
  yes((await p.textContent('.banner')).includes('enough materials'), 'refusal is shown');
  // a Done for another operation is ignored
  await emit(p, 'craftDone', { operationId: 'someone-else', ok: true, message: '', slotId: '', oldName: '', oldRank: 0, oldValue: 0, newName: 'X', newRank: 1, newValue: 1, replayed: false });
  yes(!(await p.textContent('.banner')).includes('X'), 'foreign done ignored');
  noErrors(p);
});

await check('craft: text from the server is never parsed as HTML', async () => {
  const p = await open(null, 'craft.html');
  const evil = JSON.parse(JSON.stringify(craftView));
  evil.name = '<img src=x onerror="window.__pwned=1">'; evil.slots[0].name = '<b id=pwn>x</b>';
  await emit(p, 'craftView', evil);
  yes(!(await p.evaluate(() => window.__pwned)), 'no script ran');
  eq(await p.locator('#pwn').count(), 0, 'no element injected');
  eq(await p.textContent('#name'), evil.name, 'shown as plain text');
});

// ---------------------------------------------------------------- wallet
await check('wallet: balances with icons, withdraw and deposit requests, result banner', async () => {
  const p = await open(null, 'wallet.html');
  eq((await sent(p)).map(m => m.channel), ['ascension_wallet_ready'], 'first message');
  const lines = [['resonance_dust', 'Resonance Dust', '#d9a441', 1234, 0], ['facet', 'Facet', '#4f9ad9', 40, 64], ['ascension_core', 'Ascension Core', '#c9694f', 3, 0],
    ['unique_fragment', 'Unique Fragment', '#8c63c7', 0, 0], ['unique_catalyst', 'Unique Catalyst', '#b04fa0', 1, 0], ['scouter', 'Scouter', '#6f9a52', 5, 0], ['ascension_sigil', 'Ascension Sigil', '#ffd437', 0, 0]]
    .map(([id, name, color, amount, carried]) => ({ id, name, color, amount, carried }));
  await emit(p, 'walletView', { lines, freeSlots: 12 });
  eq(await p.locator('.line').count(), 7, 'a row per material');
  eq(await p.locator('.line img').count(), 7, 'an icon per material');
  yes((await p.textContent('.lines')).includes('1,234'), 'balance with separators');
  yes((await p.textContent('#free')).includes('12 free'), 'free slots shown');
  // the facet row: take 8
  const row = p.locator('.line', { hasText: 'Facet' }).first();
  await row.locator('input').fill('8');
  await row.locator('button', { hasText: 'Take' }).click();
  const w = (await sent(p)).find(x => x.channel === 'ascension_wallet_withdraw');
  eq([w.material, w.amount], ['facet', 8], 'withdraw request');
  await emit(p, 'walletResult', { ok: true, message: 'Withdrew 8 Facet as voucher items.' });
  yes((await p.textContent('.banner')).includes('Withdrew 8'), 'result shown');
  // only a row with vouchers offers Deposit; Deposit all is enabled
  eq(await p.locator('.line button', { hasText: 'Deposit' }).count(), 1, 'one deposit button');
  eq(await p.isDisabled('#depositAll'), false, 'deposit all enabled');
  await p.locator('.line button', { hasText: 'Deposit' }).click();
  const d = (await sent(p)).find(x => x.channel === 'ascension_wallet_deposit');
  eq(d.material, 'facet', 'deposit request names the material');
  await p.click('#depositAll');
  yes((await sent(p)).filter(x => x.channel === 'ascension_wallet_deposit').pop().material === undefined, 'deposit all names none');
  await emit(p, 'walletResult', { ok: false, message: 'You need 2 free inventory slots for that.' });
  yes((await p.textContent('.banner')).includes('free inventory'), 'a refusal is shown');
  noErrors(p);
});

await check('wallet: a bound material (Tower Mark) cannot be taken out', async () => {
  const p = await open(null, 'wallet.html');
  await emit(p, 'walletView', { feePercent: 3, catalystNeeded: 100, lines: [{ id: 'unique_fragment', name: 'Unique Fragment', color: '#8c63c7', amount: 63, carried: 0, tradable: true }, { id: 'facet', name: 'Facet', color: '#4f9ad9', amount: 9, carried: 0, tradable: true },
    { id: 'tower_mark', name: 'Tower Mark', color: '#e8e1c9', amount: 4, carried: 0, tradable: false }], freeSlots: 5 });
  const mark = p.locator('.line', { hasText: 'Tower Mark' }).first();
  eq(await mark.locator('button').first().isDisabled(), true, 'the Mark has no Take');
  yes((await mark.textContent()).includes('Bound'), 'it says Bound');
  eq(await p.locator('.line', { hasText: 'Facet' }).first().locator('button').first().isDisabled(), false, 'a tradable material can be taken');
  yes((await p.textContent('.cat')).includes('63 of 100'), 'the Catalyst progress shows fragments toward the next one');
  const fr = p.locator('.line', { hasText: 'Facet' }).first();
  await fr.locator('input').fill('9');
  yes((await fr.textContent()).includes('fee +1 = 10') && (await fr.textContent()).includes('you have 9'), 'the fee preview shows the total and warns when it is more than you have');
  await fr.locator('input').fill('5');
  yes((await fr.textContent()).includes('fee +1 = 6') && !(await fr.textContent()).includes('you have'), 'and no warning when it fits');
  noErrors(p);
});

// ---------------------------------------------------------------- completion
await check('completion: banner, pile counted up, crown rolled one at a time, collect closes', async () => {
  const p = await open(null, 'completion.html', 900, 700);
  eq((await sent(p)).map(m => m.channel), ['ascension_completion_ready'], 'asks for its data');
  const view = { tier: 'mythic', tierName: 'MYTHIC', score: 9, cycle: 1, cards: 5, flawless: true, attunement: 3, preview: false, tiers: [{ id: 'bronze', name: 'BRONZE', from: 0 }, { id: 'silver', name: 'SILVER', from: 2 }, { id: 'gold', name: 'GOLD', from: 5 }, { id: 'mythic', name: 'MYTHIC', from: 8 }],
    pile: [{ id: 'resonance_dust', name: 'Resonance Dust', color: '#d9a441', amount: 1800 }, { id: 'facet', name: 'Facet', color: '#4f9ad9', amount: 13 }, { id: 'unique_fragment', name: 'Unique Fragment', color: '#8c63c7', amount: 7 }, { id: 'scouter', name: 'Scouter', color: '#6f9a52', amount: 4 }, { id: 'tower_mark', name: 'Tower Mark', color: '#e8e1c9', amount: 5 }],
    crowns: [{ kind: 'core', id: 'ascension_core', label: 'Ascension Core', color: '#c9694f', amount: 1, big: true },
      { kind: 'armor', id: '', label: 'Tower armor piece', color: '#c9d4e0', amount: 1, big: false }] };
  await emit(p, 'completionView', view);
  yes((await p.textContent('#banner')).includes('MYTHIC COMPLETION'), 'tier banner');
  yes((await p.textContent('#sub')).includes('Score 9'), 'score line');
  await p.waitForSelector('.pl.on', { timeout: 4000 });
  await p.waitForFunction(() => document.querySelectorAll('.pl').length === 2, null, { timeout: 4000 });
  await p.waitForFunction(() => document.querySelector('.pl .amt') && document.querySelector('.pl .amt').textContent === '+1,800', null, { timeout: 4000 });
  await p.waitForFunction(() => document.querySelectorAll('.cr').length === 2 && !document.querySelector('.cr.spin'), null, { timeout: 9000 });
  yes((await p.textContent('#crowns')).includes('Ascension Core'), 'the core is revealed');
  eq(await p.locator('.cr.big').count(), 1, 'only the headline item glows');
  yes((await p.textContent('#crowns')).includes('Handed over by the Tower'), 'a Tower-owned item says who hands it over');
  await shot(p, 'completion');
  eq(await p.locator('#meter .tier').count(), 4, 'a meter segment per tier');
  eq(await p.locator('#meter .tier.on').count(), 4, 'a score of 9 lights them all');
  await p.click('#share');
  yes((await sent(p)).some(x => x.channel === 'ascension_completion_share'), 'Share asks the server for the chat line');
  eq(await p.isDisabled('#share'), true, 'and cannot be pressed twice');
  await p.click('#done');
  yes((await sent(p)).some(x => x.channel === 'close'), 'Collect closes the page');
  noErrors(p);
});

// ---------------------------------------------------------------- fusion
await check('fusion: pick, preview, review, confirm, result', async () => {
  const p = await open(null, 'fusion.html');
  eq((await sent(p)).map(m => m.channel), ['ascension_fusion_ready'], 'first message');
  await emit(p, 'sprites', { mewtwo: PNG });
  await emit(p, 'fusionCandidates', { host: entry('Mewtwo', 'h1'), hostBlock: '' });
  eq(await p.textContent('#hostName'), 'Mewtwo', 'host shown');
  yes((await p.textContent('#result')).includes('Choose a donor'), 'asks for a donor');
  await p.click('#changeDonor');
  const pick = (await sent(p)).find(x => x.channel === 'ascension_fusion_pick');
  eq([pick.hostRole, pick.hostId, pick.donorId], [false, 'h1', ''], 'pick request');
  await emit(p, 'fusionPickList', { hostRole: false, entries: [entry('Mew', 'd1', 'epic'), entry('Ditto', 'd2', 'common', false, false, 'Needs Epic rarity or better.')] });
  eq(await p.locator('.tile').count(), 1, 'party tab shows party entries only (d2 is in the PC)');
  await p.click('#tabPc');
  eq(await p.locator('.tile.off').count(), 1, 'PC tab shows the blocked one greyed');
  await p.click('#tabParty');
  await shot(p, 'fusion-picker');
  await p.locator('.tile').first().click();
  yes((await sent(p)).some(x => x.channel === 'ascension_fusion_preview' && x.donorId === 'd1'), 'picking asks for a preview');
  await emit(p, 'fusionDetail', { hostId: 'h1', donorId: 'd1', block: '', name: 'Mewmew', fromUniques: 'Overmind + Overmind', harmony: 'mild', benefit: 70, drawback: 30, blurb: 'b',
    core: 'c', clutch: 'cl', drawbackText: 'd', twistName: 'T', twistText: 'tt', riderText: 'r', hostType: 'psychic', donorType: 'psychic', price: [600, 30, 10, 1, 5], balances: [1, 2, 3, 4, 6],
    hostRevision: 3, donorRevision: 4, walletRevision: 5 }, true);
  await shot(p, 'fusion-preview');
  yes((await p.textContent('#result')).includes('Mewmew'), 'preview shown');
  await p.click('#fuse');                     // Review fusion
  yes((await p.textContent('#fuse')).includes('Fuse for good'), 'second step');
  await p.click('#fuse');
  const c = (await sent(p)).find(x => x.channel === 'ascension_fusion_confirm');
  eq([c.hostId, c.donorId, c.hostRevision, c.donorRevision, c.walletRevision], ['h1', 'd1', 3, 4, 5], 'confirm body');
  await emit(p, 'fusionResult', { operationId: c.operationId, ok: true, message: '', name: 'Mewmew' });
  yes((await p.textContent('#msg')).includes('became Mewmew'), 'result shown');
  yes((await sent(p)).some(x => x.channel === 'ascension_fusion_refresh'), 'host refreshed after');
  noErrors(p);
});

await check('fusion: a host that cannot host shows why, swap needs a valid host', async () => {
  const p = await open(null, 'fusion.html');
  await emit(p, 'fusionCandidates', { host: entry('Pidgey', 'h9', 'common', true, false), hostBlock: 'Only a Mythic Pokémon can be the host.' });
  yes((await p.textContent('#result')).includes('Only a Mythic'), 'reason shown');
  eq(await p.isDisabled('#swap'), true, 'swap disabled');
  noErrors(p);
});

// ---------------------------------------------------------------- inspect
await check('inspect: owned view, affixes, upgrade button; enemy view, scout', async () => {
  const p = await open(null, 'inspect.html');
  eq((await sent(p)).map(m => m.channel), ['ascension_inspect_ready'], 'first message');
  await emit(p, 'sprites', { arcanine: PNG });
  await emit(p, 'inspectOwned', { pokemonId: 'a1', name: 'Arcanine', species: 'Arcanine', pending: 2, attunement: 9, detail: detail(), extra: { speciesId: 'cobblemon:arcanine', aspects: [], origin: 'wild_capture', initialRarity: 'rare', highestLevel: 40, milestones: 3, prefixCap: 2, suffixCap: 2, craftLocked: false } });
  eq(await p.textContent('#name'), 'Arcanine', 'name');
  yes((await p.textContent('#grade')).includes('EPIC'), 'grade');
  yes((await p.textContent('#bottom')).includes('2 pending'), 'bottom line: the upgrades waiting');
  yes((await p.textContent('#rows')).includes('Next slot'), 'the slot the next rarity adds is shown');
  yes(await p.locator('#rows .row').count() >= 2, 'unique + affix rows');
  yes((await p.textContent('#reso')).includes('PREDATOR 2 · +15%'), 'resonance tier shown');
  yes((await p.textContent('#rows')).includes('MAX'), 'a perfected rank V slot is marked');
  yes((await p.textContent('#rows')).includes('Predator'), 'theme tag on the affix row');
  await shot(p, 'inspect-owned');
  yes((await p.textContent('#upgrade')).includes('Upgrade (2)'), 'upgrade label');
  await p.click('#upgrade');
  yes((await sent(p)).some(x => x.channel === 'ascension_craft_open' && x.pokemonId === 'a1'), 'upgrade opens craft');
  noErrors(p);
});

await check('inspect: a Pokemon with no profile offers an Ascension Sigil, armed by a first click and spent by a second', async () => {
  const p = await open(null, 'inspect.html');
  await emit(p, 'sprites', { arcanine: PNG });
  const extra = { speciesId: 'cobblemon:arcanine', aspects: [], origin: '', initialRarity: '', highestLevel: 0, milestones: 0, prefixCap: 0, suffixCap: 0, craftLocked: false };
  await emit(p, 'inspectOwned', { pokemonId: 'b2', name: 'Arcanine', species: 'Arcanine', pending: 0, attunement: 0, detail: null, extra, sigils: 0 });
  eq(await p.isVisible('#sigil'), true, 'the button shows for a bare Pokemon');
  eq(await p.isDisabled('#sigil'), true, 'and is disabled with no Sigil');
  eq(await p.isVisible('#upgrade'), false, 'no Upgrade without a profile');
  await emit(p, 'inspectOwned', { pokemonId: 'b2', name: 'Arcanine', species: 'Arcanine', pending: 0, attunement: 0, detail: null, extra, sigils: 2 });
  yes((await p.textContent('#sigil')).includes('(2)'), 'it shows how many Sigils you have');
  await p.click('#sigil');
  yes((await p.textContent('#sigil')).includes('Click again'), 'the first click only arms it');
  yes(!(await sent(p)).some(x => x.channel === 'ascension_inspect_sigil'), 'nothing is sent yet');
  await p.click('#sigil');
  const m = (await sent(p)).find(x => x.channel === 'ascension_inspect_sigil');
  eq(m && m.pokemonId, 'b2', 'the second click asks the server, naming the Pokemon');
  await emit(p, 'inspectOwned', { pokemonId: 'b2', name: 'Arcanine', species: 'Arcanine', pending: 0, attunement: 0, detail: detail(), extra, sigils: 1 });
  eq(await p.isVisible('#sigil'), false, 'once it has a profile the button is gone');
  noErrors(p);
});

await check('inspect: enemy scouting with a hidden and a revealed enemy', async () => {
  const p = await open(null, 'inspect.html');
  await emit(p, 'sprites', { growlithe: PNG });
  await emit(p, 'inspectScout', { encounterId: 'enc-1', scouters: 2, entries: [{ subjectId: 's1', name: 'Growlithe', speciesId: 'cobblemon:growlithe' }, { subjectId: 's2', name: 'Houndoom', detail: detail() }] });
  eq(await p.textContent('#name'), 'Growlithe', 'first enemy');
  yes(await p.locator('#art img').count() === 1, 'a scouted enemy shows its picture when the server sends the species');
  yes((await p.textContent('#grade')).includes('RARITY ?'), 'unscouted hides rarity');
  await p.click('#scout');
  const u = (await sent(p)).find(x => x.channel === 'ascension_inspect_use');
  eq([u.encounterId, u.subjectId], ['enc-1', 's1'], 'scout request');
  await p.click('#next');
  eq(await p.textContent('#name'), 'Houndoom', 'next enemy');
  yes((await p.textContent('#grade')).includes('EPIC'), 'revealed shows rarity');
  eq(await p.isDisabled('#scout'), true, 'already scouted');
  await shot(p, 'inspect-enemy');
  noErrors(p);
});

// ---------------------------------------------------------------- capture reveal
const capture = (id, rarity = 'common', extra = {}) => ({ pokemonId: id, revealId: 'capture-' + id, name: 'Pangoro', speciesId: 'cobblemon:pangoro', aspects: [],
  detail: { level: 28, types: ['fighting', 'dark'], rarityId: rarity, uniqueName: '', slots: [{ category: 'prefix', name: 'Defiant Force', rank: 1, value: 8, condition: 'Hits harder when challenged.' }] }, ...extra });

await check('capture: repeated notifications and repeated modifier rows never duplicate; two different catches queue', async () => {
  const p = await open(null, 'capture.html', 1920, 1080);
  const first = capture('first'); first.detail.slots.push({ ...first.detail.slots[0] });
  await emit(p, 'captureReveal', first);
  await emit(p, 'captureReveal', first, true);
  await emit(p, 'captureReveal', capture('second'));
  await emit(p, 'captureReveal', capture('second'), true);
  eq(await p.locator('.capture-card').count(), 1, 'a single card is visible');
  eq(await p.locator('.capture-trait').count(), 1, 'a repeated modifier has a single row');
  eq(await p.textContent('.capture-trait-name'), 'Defiant Force', 'one trait label');
  await p.click('.capture-continue');
  eq(await p.locator('.capture-card').count(), 1, 'the second catch replaces the first');
  await p.click('.capture-continue');
  eq(await p.locator('.capture-card').count(), 0, 'no duplicate was queued');
  await emit(p, 'captureReveal', first);
  eq(await p.locator('.capture-card').count(), 0, 'a dismissed capture cannot reappear');
  await p.reload();
  await emit(p, 'captureReveal', first);
  eq(await p.locator('.capture-card').count(), 0, 'duplicate remains suppressed after the HUD reloads');
  await emit(p, 'captureReveal', { ...first, revealId: 'explicit-admin-preview' });
  eq(await p.locator('.capture-card').count(), 1, 'an explicit operator preview can replay the same Pokemon');
  noErrors(p);
});

await check('capture: inspector uses the caught Pokemon and reveal identity without closing the native GUI first', async () => {
  const p = await open(null, 'capture.html', 1920, 1080);
  await emit(p, 'captureReveal', capture('inspect-me'));
  await p.click('.capture-content .primary');
  eq((await sent(p)).find(m => m.channel === 'ascension_capture_open')?.id, 'inspect-me', 'the caught ID is sent to the server');
  eq((await sent(p)).find(m => m.channel === 'ascension_capture_open')?.revealId, 'capture-inspect-me', 'the server can reject a stale inspector click');
  eq(await p.locator('.capture-card').count(), 0, 'no second card appears under the inspector');
  yes(!(await sent(p)).some(m => m.channel === 'close'), 'inspector transition preserves native GUI input');
  await p.reload();
  await emit(p, 'captureReveal', capture('waiting'));
  eq(await p.locator('.capture-card').count(), 1, 'the server can deliver the waiting catch after inspector return');
  await p.click('.capture-continue');
  await emit(p, 'captureReveal', capture('inspect-me'));
  eq(await p.locator('.capture-card').count(), 0, 'the inspected catch is not replayed');
  noErrors(p);
});

await check('capture: server advances the same GUI, then closes it exactly once to restore Minecraft input', async () => {
  const p = await open(null, 'capture.html', 1920, 1080);
  eq(await p.evaluate(() => window.webgui.isHud), false, 'capture uses WebGUI native GUI selection mode');
  yes((await sentAll(p)).some(m => m.channel === 'ascension_gui_beat'), 'capture participates in GUI presence');
  await emit(p, 'captureReveal', capture('first-gui'));
  await p.click('.capture-continue');
  eq((await sent(p)).find(m => m.channel === 'ascension_capture_dismiss')?.revealId, 'capture-first-gui', 'dismiss acknowledges only the active reveal');
  yes(!(await sent(p)).some(m => m.channel === 'close'), 'wait for the authoritative queue result');
  await emit(p, 'captureReveal', capture('held'));
  eq(await p.locator('.capture-card').count(), 1, 'the next reveal uses the same native GUI');
  await p.click('.capture-continue');
  eq(await p.locator('.capture-card').count(), 0, 'only the held capture was waiting');
  await emit(p, 'captureClosed', true);
  await emit(p, 'captureClosed', true);
  eq((await sent(p)).filter(m => m.channel === 'close').length, 1, 'native close fires once; no second close can toggle off the restored HUD');
  noErrors(p);
});

await check('capture: all rarities have matching sprite auras and prism masks, with no external assets', async () => {
  const p = await open(null, 'capture.html', 1920, 1080);
  await emit(p, 'sprites', { pangoro: PNG });
  await emit(p, 'uiScale', { percent: 100, min: 60, max: 160, calm: false });
  const rarities = ['common','uncommon','rare','epic','legendary','mythical'];
  const colors = ['#c1c5cf','#1bd7e8','#38ccef','#af69ff','#ffd437','#ff3448'];
  let previous = 0;
  for (let i = 0; i < rarities.length; i++) {
    await emit(p, 'captureReveal', capture('rarity-' + i, rarities[i]));
    await p.locator('.capture-prism.ready').waitFor();
    await p.waitForFunction(() => !document.querySelector('.capture-front').inert);
    const css = await p.locator('.capture-card').evaluate(el => ({ color: el.style.getPropertyValue('--capture-rarity'), intensity: Number(el.style.getPropertyValue('--aura-strength')) }));
    eq(css.color, colors[i], 'rarity color ' + rarities[i]);
    yes(css.intensity > previous, 'aura grows with rarity ' + rarities[i]); previous = css.intensity;
    yes((await p.locator('.capture-prism').evaluate(el => el.style.maskImage)).includes('data:image/png;base64'), 'prism uses the actual server sprite alpha');
    if (i === 0 || i === 5) await shot(p, 'capture-' + rarities[i]);
    await p.click('.capture-continue');
  }
  noErrors(p);
});

await check('capture: centered, transparent and fully on-screen even with all six modifiers and a Unique', async () => {
  for (const [w,h] of [[1920,1080],[1280,720],[854,480],[320,568],[3840,2160]]) {
    const p = await open(null, 'capture.html', w, h), payload = capture('fit-' + w, 'mythical');
    payload.name = 'A very long custom Pokémon nickname';
    payload.detail.uniqueName = 'A Unique Power';
    payload.detail.slots = Array.from({length:6}, (_, i) => ({ category: i < 3 ? 'prefix' : 'suffix', name: ['Defiant Force','Physical Force','Keen Edge','Iron Resolve','Stubborn','Special Bulwark'][i], rank: 5, value: 32 }));
    await emit(p, 'captureReveal', payload);
    await emit(p, 'uiScale', { percent: 160, min: 60, max: 160, calm: false });
    eq(await p.locator('.capture-trait').count(), 7, 'all six modifiers plus Unique, without truncation');
    const rect = await p.locator('.capture-card').evaluate(el => { const b = el.getBoundingClientRect(); return {left:b.left,top:b.top,right:b.right,bottom:b.bottom,cx:(b.left+b.right)/2,cy:(b.top+b.bottom)/2}; });
    yes(rect.left >= 0 && rect.top >= 0 && rect.right <= w + 1 && rect.bottom <= h + 1, 'fits ' + w + 'x' + h + ': ' + JSON.stringify(rect));
    yes(Math.abs(rect.cx - w/2) < 1 && Math.abs(rect.cy - h/2) < 1, 'centered at ' + w + 'x' + h);
    eq(await p.evaluate(() => [getComputedStyle(document.documentElement).backgroundColor, getComputedStyle(document.body).backgroundColor]), ['rgba(0, 0, 0, 0)','rgba(0, 0, 0, 0)'], 'the game remains visible around the card');
    if (w === 1280) await shot(p, 'capture-six-slots');
    noErrors(p);
  }
});

await check('capture: compact card scrolls all modifiers with wheel and keyboard without moving its actions', async () => {
  const p = await open(null, 'capture.html', 1920, 1080);
  await emit(p, 'captureReveal', capture('compact-one'));
  await p.evaluate(() => document.fonts.ready);
  const shortHeight = await p.locator('.capture-card').evaluate(el => el.offsetHeight);
  await p.click('.capture-continue');
  const payload = capture('compact-seven', 'mythical');
  payload.detail.uniqueName = 'A Unique Power';
  payload.detail.slots = Array.from({length:6}, (_,i) => ({category:i < 3 ? 'prefix':'suffix', name:['Defiant Force','Physical Force','Keen Edge','Iron Resolve','Stubborn','Special Bulwark'][i], rank:5, value:32}));
  await emit(p, 'captureReveal', payload);
  const fullHeight = await p.locator('.capture-card').evaluate(el => el.offsetHeight);
  yes(fullHeight <= 660 && fullHeight - shortHeight < 80, 'more modifiers stay in a compact, bounded card: ' + shortHeight + ' / ' + fullHeight);
  const list = p.locator('.capture-traits');
  yes(await list.evaluate(el => el.scrollHeight > el.clientHeight), 'the modifier region overflows and scrolls');
  eq(await p.textContent('.capture-scroll-hint'), 'SCROLL ↓', 'scrolling is discoverable');
  const actionY = (await p.locator('.capture-actions').boundingBox()).y;
  await list.hover();
  await p.mouse.wheel(0, 500);
  await p.waitForFunction(() => document.querySelector('.capture-traits').scrollTop > 0);
  await list.focus();
  await list.press('Home');
  await list.press('End');
  await p.waitForFunction(() => { const el = document.querySelector('.capture-traits'); return el.scrollTop + el.clientHeight >= el.scrollHeight - 1; });
  yes(await p.locator('.capture-trait').last().evaluate(el => el.getBoundingClientRect().bottom <= el.parentElement.getBoundingClientRect().bottom + 1), 'the last modifier is reachable');
  eq((await p.locator('.capture-actions').boundingBox()).y, actionY, 'only modifiers scroll; actions stay in place');
  eq(await p.locator('.capture-trait').count(), 7, 'all modifiers remain present exactly once');
  await shot(p, 'capture-compact-scrolled');
  noErrors(p);
});

await check('capture: calm and reduced motion, missing or late sprites, and server text are handled safely', async () => {
  const p = await open(null, 'capture.html', 1920, 1080);
  const payload = capture('safe'); payload.name = '<img src=x onerror=alert(1)>';
  await emit(p, 'captureReveal', payload);
  eq(await p.textContent('.capture-name'), payload.name, 'nickname is plain text');
  eq(await p.locator('.capture-name img').count(), 0, 'no server text becomes HTML');
  eq(await p.locator('.capture-prism.ready').count(), 0, 'no rectangular sheen before the sprite loads');
  await emit(p, 'sprites', { pangoro: PNG });
  await p.locator('.capture-prism.ready').waitFor({ state: 'attached' });
  await emit(p, 'uiScale', { percent: 100, min: 60, max: 160, calm: true });
  eq(await p.locator('.capture-aura').evaluate(el => getComputedStyle(el).display), 'none', 'calm removes aura');
  eq(await p.locator('.capture-prism').evaluate(el => getComputedStyle(el).display), 'none', 'calm removes prism');
  eq(await p.locator('.capture-sprite .spr').evaluate(el => getComputedStyle(el).filter), 'none', 'calm removes sprite glow');
  await emit(p, 'uiScale', { percent: 100, min: 60, max: 160, calm: false });
  await p.emulateMedia({ reducedMotion: 'reduce' });
  eq(await p.locator('.capture-prism').evaluate(el => getComputedStyle(el).animationName), 'none', 'reduced motion stops shimmer');
  noErrors(p);
});

await check('capture: timeout advances once, manual dismissal cancels the old timer, reading pauses expiry', async () => {
  const p = await open(null, 'capture.html', 1920, 1080);
  await p.clock.install();
  await emit(p, 'captureReveal', capture('timeout-first'));
  await emit(p, 'captureReveal', capture('timeout-second'));
  await p.clock.fastForward(12010);
  eq(await p.locator('.capture-card').count(), 1, 'timeout advances to the second catch');
  await p.locator('.capture-content .primary').focus();
  await p.clock.fastForward(20000);
  eq(await p.locator('.capture-card').count(), 1, 'keyboard reading pauses timeout');
  await p.click('.capture-continue');
  eq(await p.locator('.capture-card').count(), 0, 'manual dismissal removes it');
  await emit(p, 'captureReveal', capture('timeout-third'));
  await p.mouse.move(0, 0);
  await p.clock.fastForward(6000);
  eq(await p.locator('.capture-card').count(), 1, 'the previous card timer cannot remove a later catch');
  await p.clock.fastForward(6010);
  eq(await p.locator('.capture-card').count(), 0, 'the final card expires once');
  noErrors(p);
});

await check('capture: Disable works during the flip, cancels effects and queues, closes once and blocks late reveals', async () => {
  const p = await open(null, 'capture.html', 1920, 1080);
  await emit(p, 'uiScale', { percent:100,min:60,max:160,calm:false });
  await emit(p, 'captureReveal', capture('disable-now','mythical'));
  await emit(p, 'captureReveal', capture('queued'));
  yes(await p.locator('.capture-front').evaluate(el => el.inert), 'the flip is running');
  eq(await p.textContent('.capture-disable'), 'Disable', 'small labeled control');
  await p.click('.capture-disable');
  eq(await p.locator('.capture-card').count(), 0, 'current and queued cards disappear');
  eq((await sent(p)).filter(m => m.channel === 'ascension_capture_disable').length, 1, 'disable preference is sent once');
  eq((await sent(p)).filter(m => m.channel === 'close').length, 1, 'native GUI closes immediately');
  await emit(p, 'captureClosed', true);
  await emit(p, 'captureReveal', capture('late'));
  eq(await p.locator('.capture-card').count(), 0, 'a late notification cannot reopen a disabled card');
  eq((await sent(p)).filter(m => m.channel === 'close').length, 1, 'server acknowledgment cannot close the restored HUD');
  noErrors(p);
});

await check('capture: Prism Rupture flips once, counts reading time after the flip, and Calm cancels a running flip', async () => {
  const p = await open(null, 'capture.html', 1920, 1080);
  await emit(p, 'uiScale', { percent:100,min:60,max:160,calm:false });
  await emit(p, 'captureReveal', capture('animated','epic'));
  eq(await p.locator('.capture-back').count(), 1, 'one sealed back');
  yes(await p.locator('.capture-front').evaluate(el => el.inert), 'front actions cannot be clicked through the back');
  await p.waitForFunction(() => !document.querySelector('.capture-front').inert);
  eq(await p.locator('.capture-back').count(), 0, 'animation back is removed after the flip');
  eq(await p.locator('.capture-flip-fx').count(), 0, 'particles and light layers are cleaned up');
  await p.click('.capture-continue');
  await emit(p, 'captureReveal', capture('calm-mid-flip','legendary'));
  await emit(p, 'uiScale', { percent:100,min:60,max:160,calm:true });
  await p.waitForFunction(() => !document.querySelector('.capture-front').inert);
  eq(await p.locator('.capture-back').count(), 0, 'Calm immediately ends the running flip');
  eq(await p.locator('.capture-flip-fx').count(), 0, 'Calm clears the flare');
  noErrors(p);
});

await check('capture: Escape/page unload releases the active reveal without dismissing a later server card', async () => {
  const p = await open(null, 'capture.html', 1920, 1080);
  await emit(p, 'captureReveal', capture('escape'));
  await p.evaluate(() => window.dispatchEvent(new Event('pagehide')));
  eq((await sent(p)).find(m => m.channel === 'ascension_capture_closed')?.revealId, 'capture-escape', 'native close reports the active reveal identity');
  eq(await p.locator('.capture-card').count(), 0, 'unloading cancels timers and clears DOM');
  noErrors(p);
});

// ---------------------------------------------------------------- hud
await check('hud: scouting panel stays independent from the capture GUI', async () => {
  const p = await open(null, 'hud.html', 1000, 600);
  eq((await sent(p)).map(m => m.channel), ['ascension_hud_ready'], 'first message');
  await emit(p, 'sprites', { arcanine: PNG });
  await emit(p, 'hudScout', { encounterId: 'enc-1', scouters: 3, entries: [{ subjectId: 's1', name: 'Growlithe' }, { subjectId: 's2', name: 'Houndoom', detail: detail() }] });
  await emit(p, 'hudTeam', [{ id: 'a1', name: 'Arcanine', speciesId: 'cobblemon:arcanine', rarityId: 'epic', level: 40 }]);
  eq(await p.locator('#capture-root').count(), 0, 'the HUD cannot render a second capture card');
  yes(await p.locator('#panel.on').count() === 1, 'panel shown');
  await shot(p, 'hud');
  await p.locator('#opp button', { hasText: 'Scout' }).click();
  yes((await sent(p)).some(x => x.channel === 'ascension_inspect_use' && x.subjectId === 's1'), 'scout from the overlay');
  await p.locator('#team .n').click();
  yes((await sent(p)).some(x => x.channel === 'ascension_inspect_open' && x.kind === 'owned' && x.id === 'a1'), 'team row opens inspect');
  // the encounter ends: the panel hides; the toast leaves; then the page closes itself
  await emit(p, 'hudScout', { encounterId: '', scouters: 3, entries: [] });
  yes(await p.locator('#panel.on').count() === 0, 'panel hidden when the encounter ends');
  noErrors(p);
});

await check('hud: idle overlay stays transparent and never closes itself', async () => {
  const p = await open(null, 'hud.html');
  await emit(p, 'hudScout', { encounterId: '', scouters: 0, entries: [] });
  await p.waitForTimeout(3200);
  const ch = (await sent(p)).map(m => m.channel);
  yes(ch.includes('ascension_hud_idle') && !ch.includes('close'), 'idle overlay stays loaded: ' + ch.join(','));
});

await check('hud: bleed badges show side, stacks and turns, stay open while anyone bleeds, and clear', async () => {
  const p = await open(null, 'hud.html');
  await emit(p, 'hudBleed', [{ name: 'Growlithe', side: 'ally', stacks: 2, turnsLeft: 3 }, { name: 'Houndoom', side: 'foe', stacks: 1, turnsLeft: 1 }]);
  eq(await p.locator('.bleed').count(), 2, 'a badge per bleeding Pokemon');
  yes((await p.locator('.bleed').first().textContent()).includes('Growlithe') && (await p.locator('.bleed').first().textContent()).includes('BLEED x2'), 'name and stacks');
  yes((await p.locator('.bleed.foe').textContent()).includes('1 turn') && !(await p.locator('.bleed.foe').textContent()).includes('1 turns'), 'singular turn');
  await p.waitForTimeout(3200);
  yes(!(await sent(p)).some(m => m.channel === 'close'), 'the overlay stays open while a bleed is on');
  await emit(p, 'hudBleed', []);
  eq(await p.locator('.bleed').count(), 0, 'cleared');
  await p.waitForTimeout(3200);
  yes((await sent(p)).some(m => m.channel === 'ascension_hud_idle') && !(await sent(p)).some(m => m.channel === 'close'), 'then it idles without reopening the browser');
  noErrors(p);
});

await check('hud: Momentum stays as a badge, Wardstone and Ensnaring flash once, both sides, and the overlay closes after', async () => {
  const p = await open(null, 'hud.html');
  await emit(p, 'hudEffects', { momentum: [{ name: 'Arcanine', side: 'ally', stacks: 2 }, { name: 'Houndoom', side: 'foe', stacks: 1 }], procs: [{ name: 'Growlithe', side: 'ally', kind: 'wardstone' }, { name: 'Houndoom', side: 'foe', kind: 'ensnaring' }] });
  eq(await p.locator('.eff.mom').count(), 2, 'a Momentum badge per holder');
  yes((await p.locator('.eff.mom').first().textContent()).includes('MOMENTUM x2'), 'stacks shown');
  eq(await p.locator('.eff.mom.foe').count(), 1, 'the foe has its own colour');
  yes((await p.locator('.eff.flash.wardstone').textContent()).includes('status resisted'), 'the Wardstone flash');
  yes((await p.locator('.eff.flash.ensnaring.foe').textContent()).includes('Speed lowered'), 'the Ensnaring flash on the foe');
  await emit(p, 'hudEffects', { momentum: [{ name: 'Arcanine', side: 'ally', stacks: 3 }], procs: [] });
  eq(await p.locator('.eff.mom').count(), 1, 'the standing badges are replaced by the new list');
  yes((await p.locator('.eff.mom').textContent()).includes('x3'), 'and show the new stacks');
  eq(await p.locator('.eff.flash').count(), 2, 'a flash is not cleared by a later update');
  await p.waitForTimeout(3200);
  yes(!(await sent(p)).some(m => m.channel === 'close'), 'the overlay stays open while Momentum stands');
  await emit(p, 'hudEffects', { momentum: [], procs: [] });
  await p.waitForTimeout(4200);
  eq(await p.locator('.eff').count(), 0, 'the flashes ended by themselves');
  yes((await sent(p)).some(m => m.channel === 'ascension_hud_idle') && !(await sent(p)).some(m => m.channel === 'close'), 'then the overlay idles without closing');
  noErrors(p);
});

await check('every page scales its tablet to fill the window at any resolution, and stays inside it', async () => {
  for (const [w, h] of [[2560, 1440], [1920, 1080], [1280, 720], [3840, 2160]]) {
    for (const file of ['craft.html', 'inspect.html', 'wallet.html', 'completion.html']) {
      const p = await open(null, file, w, h);
      const r = await p.evaluate(() => { const b = document.querySelector('.frame').getBoundingClientRect(); return { l: b.left, t: b.top, w: b.width, h: b.height, iw: innerWidth, ih: innerHeight }; });
      yes(r.l >= 0 && r.t >= 0 && r.l + r.w <= r.iw + 1 && r.t + r.h <= r.ih + 1, `${file} at ${w}x${h} stays inside the window (${JSON.stringify(r)})`);
      yes(r.w >= r.iw * 0.6 || r.h >= r.ih * 0.6, `${file} at ${w}x${h} fills the window (${Math.round(r.w)}x${Math.round(r.h)})`);
      if (file === 'craft.html' && w === 2560) { await shot(p, 'craft-2560'); }
    }
  }
  const big = await open(null, 'craft.html', 2560, 1440), small = await open(null, 'craft.html', 1280, 720);
  const wid = pg => pg.evaluate(() => document.querySelector('.frame').getBoundingClientRect().width);
  yes((await wid(big)) > (await wid(small)) * 1.7, 'a bigger window gets a bigger tablet');
});

await check('page size: each tablet page asks for the player size, shows - and + in the header, and re-fits when the server answers', async () => {
  for (const file of ['craft.html', 'inspect.html', 'wallet.html', 'completion.html', 'fusion.html']) {
    const p = await open(null, file, 1920, 1080);
    yes((await sentAll(p)).some(m => m.channel === 'ascension_ui_get'), file + ' asks for its size');
    eq(await p.locator('.head .uisize button').count(), 2, file + ' has the - and + controls');
    noErrors(p);
  }
  const p = await open(null, 'craft.html', 1920, 1080);
  const width = () => p.evaluate(() => document.querySelector('.frame').getBoundingClientRect().width);
  const base = await width();
  await p.click('.uisize button:last-child');
  const set = (await sentAll(p)).find(m => m.channel === 'ascension_ui_set');
  eq(set && set.percent, 110, '+ asks for 10 percent more');
  await emit(p, 'uiScale', { percent: 140, min: 60, max: 160 });
  yes((await p.textContent('#uipct')) === '140%', 'the readout follows the server');
  yes((await width()) > base * 1.15, 'a bigger size makes the tablet bigger (' + Math.round(base) + ' to ' + Math.round(await width()) + ')');
  await emit(p, 'uiScale', { percent: 60, min: 60, max: 160 });
  yes((await width()) < base * 0.8, 'a smaller size makes it smaller');
  await emit(p, 'uiScale', { percent: 160, min: 60, max: 160 });
  const r = await p.evaluate(() => { const b = document.querySelector('.frame').getBoundingClientRect(); return b.left >= 0 && b.top >= 0 && b.right <= innerWidth && b.bottom <= innerHeight; });
  yes(r, 'even the largest size stays inside the window');
  await p.click('.uisize button:first-child');
  eq((await sentAll(p)).filter(m => m.channel === 'ascension_ui_set').pop().percent, 150, '- steps down from the server value');
  const hud = await open(null, 'hud.html', 1920, 1080);
  yes((await sentAll(hud)).some(m => m.channel === 'ascension_ui_get'), 'the overlay asks too');
  noErrors(p);
});

await check('party: six slots, rarity, health and pending upgrades; pick, inspect and upgrade send the existing messages', async () => {
  const p = await open(null, 'party.html', 1920, 1080);
  eq((await sent(p)).map(m => m.channel), ['ascension_party_ready'], 'asks for its data');
  await emit(p, 'sprites', { arcanine: PNG, growlithe: PNG });
  const mon = (slot, id, name, species, level, rarityId, pending, hp, extra) => Object.assign({ slot, id, name, speciesId: species, level, rarityId, pending, hp, fainted: hp === 0, shiny: false, locked: false }, extra || {});
  const empty = slot => ({ slot, id: '', name: '', speciesId: '', level: 0, rarityId: '', pending: 0, hp: 0, fainted: false, shiny: false, locked: false });
  await emit(p, 'partyView', { slots: [mon(0, 'a1', 'Arcanine', 'cobblemon:arcanine', 52, 'epic', 2, 80), mon(1, 'b2', 'Growlithe', 'cobblemon:growlithe', 20, '', 0, 30), mon(2, 'c3', 'Pikachu', 'cobblemon:pikachu', 10, 'rare', 0, 0, { shiny: true }), empty(3), empty(4), empty(5)] });
  eq(await p.locator('.card').count(), 6, 'six slots');
  eq(await p.locator('.card.empty').count(), 3, 'three are empty');
  yes((await p.textContent('.card')).includes('2 upgrades') && (await p.textContent('.card')).includes('EPIC') || (await p.textContent('.card')).includes('Epic'), 'rarity and pending upgrades on the card');
  yes((await p.locator('.card').nth(1).textContent()).includes('No profile'), 'a Pokemon with no profile says so');
  yes((await p.locator('.card').nth(2).textContent()).includes('★ Pikachu'), 'a shiny is starred');
  eq(await p.locator('.card.sel').count(), 1, 'the first Pokemon is selected');
  eq(await p.isDisabled('#upgrade'), false, 'a profiled Pokemon can be upgraded');
  await p.locator('.card').nth(1).click();
  eq(await p.isDisabled('#upgrade'), true, 'but one with no profile cannot (inspect it to use a Sigil)');
  await p.click('#inspect');
  const m = (await sent(p)).find(x => x.channel === 'ascension_inspect_open');
  eq([m.kind, m.id], ['owned', 'b2'], 'Inspect opens the owned inspector for the pick');
  await p.locator('.card').nth(0).click();
  await p.click('#upgrade');
  eq((await sent(p)).find(x => x.channel === 'ascension_craft_open').pokemonId, 'a1', 'Upgrade opens craft for the pick');
  eq(await p.isDisabled('#fuse'), false, 'Fuse is available with a Pokemon picked');
  await p.click('#fuse');
  eq((await sent(p)).find(x => x.channel === 'ascension_fusion_open').hostId, 'a1', 'Fuse opens the fusion screen with the pick as the host');
  await p.locator('.card').nth(3).click();
  eq(await p.locator('.card.sel').count() <= 1, true, 'an empty slot cannot be picked in');
  await p.locator('.card').nth(2).dblclick();
  eq((await sent(p)).filter(x => x.channel === 'ascension_inspect_open').pop().id, 'c3', 'double-click inspects');
  // the keyboard: arrows skip empty slots, Enter inspects
  await p.locator('.card').nth(0).click();
  await p.keyboard.press('ArrowRight');
  await p.keyboard.press('Enter');
  eq((await sent(p)).filter(x => x.channel === 'ascension_inspect_open').pop().id, 'b2', 'ArrowRight then Enter inspects the next Pokemon');
  await p.keyboard.press('ArrowRight'); await p.keyboard.press('ArrowRight');
  eq(await p.locator('.card.empty.sel').count(), 0, 'the arrows never land on an empty slot');
  await shot(p, 'party');
  noErrors(p);
});

await check('party: a Wallet tab at the bottom shows the wallet (fee preview, Take, Deposit) and switches back to the party', async () => {
  const p = await open(null, 'party.html', 1920, 1080);
  eq(await p.locator('#tabbar button').count(), 3, 'three tabs (Fuse is a footer button)');
  eq(await p.isVisible('#paneWallet'), false, 'the wallet is hidden at first');
  eq(await p.isVisible('#footParty'), true, 'the party buttons show');
  const box = async sel => p.evaluate(s => { const b = document.querySelector(s).getBoundingClientRect(); return { t: b.top, b: b.bottom }; }, sel);
  yes((await box('#tabbar')).t > (await box('.main')).t, 'the tab bar is below the content');
  await p.click('#tabWallet');
  eq(await p.isVisible('#paneWallet'), true, 'the wallet pane shows');
  eq(await p.isVisible('#paneParty'), false, 'and the party pane hides');
  eq(await p.isVisible('#footWallet'), true, 'with the wallet buttons');
  yes((await sent(p)).some(m => m.channel === 'ascension_wallet_ready'), 'opening it asks the server for the wallet');
  await emit(p, 'walletView', { feePercent: 3, catalystNeeded: 100, freeSlots: 7, lines: [
    { id: 'unique_fragment', name: 'Unique Fragment', color: '#8c63c7', amount: 40, carried: 0, tradable: true },
    { id: 'facet', name: 'Facet', color: '#4f9ad9', amount: 9, carried: 64, tradable: true },
    { id: 'tower_mark', name: 'Tower Mark', color: '#e8e1c9', amount: 4, carried: 0, tradable: false }] });
  eq(await p.locator('#paneWallet .line').count(), 3, 'a row per material');
  yes((await p.textContent('#info')).includes('7 free inventory slots'), 'free slots in the footer');
  yes((await p.textContent('#paneWallet .cat')).includes('40 of 100'), 'the Catalyst bar');
  eq(await p.isDisabled('#depositAll'), false, 'Deposit all is on when vouchers are carried');
  const row = p.locator('#paneWallet .line', { hasText: 'Facet' }).first();
  await row.locator('input').fill('9');
  yes((await row.textContent()).includes('fee +1 = 10'), 'the fee preview works in the tab too');
  await row.locator('input').fill('5');
  await row.locator('button', { hasText: 'Take' }).click();
  const w = (await sent(p)).find(x => x.channel === 'ascension_wallet_withdraw');
  eq([w.material, w.amount], ['facet', 5], 'Take sends the same withdraw message');
  await p.click('#depositAll');
  yes((await sent(p)).some(x => x.channel === 'ascension_wallet_deposit'), 'Deposit all sends the deposit message');
  await emit(p, 'walletResult', { ok: true, message: 'Withdrew 5 Facet as voucher items (fee 1).' });
  yes((await p.textContent('#paneWallet .banner')).includes('Withdrew 5'), 'the result line shows');
  await shot(p, 'party-wallet');
  await p.click('#tabParty');
  eq(await p.isVisible('#paneParty'), true, 'back to the party');
  eq(await p.isVisible('#footParty'), true, 'with the party buttons');
  noErrors(p);
});

await check('guide: topics on the left, the quick-start steps first, tables and tips drawn, next and previous move through, nothing unresolved', async () => {
  const p = await open(null, 'guide.html', 1920, 1080);
  const topics = await p.locator('.gp .topics button').count();
  yes(topics >= 9, 'a topic per button (' + topics + ')');
  yes((await p.textContent('.gp h2')).toLowerCase().includes('quick start'), 'Quick start is open first');
  eq(await p.locator('.gp ol li').count(), 6, 'six quick-start steps');
  yes((await p.textContent('.gp ol')).includes('Every 10 levels earns one upgrade credit (up to 10)'), 'numbers come from the rules');
  await p.locator('.gp .topics button', { hasText: 'Growing a Pokémon' }).click();
  yes((await p.locator('.gp table').count()) >= 2, 'the actions table and the promotion table');
  yes((await p.textContent('.gp')).includes('Overreach') && (await p.textContent('.gp')).includes('45%'), 'Overreach and its chance');
  await p.locator('.gp .nav button', { hasText: 'Uniques and Transcendents' }).click();
  yes((await p.textContent('.gp h2')).includes('Uniques'), 'Next moves on');
  yes((await p.textContent('.gp table')).includes('Ashen Heart') && (await p.textContent('.gp table')).includes('Titan'), 'the Unique table lists the Uniques');
  await p.locator('.gp .nav button', { hasText: 'Growing a Pokémon' }).click();
  yes((await p.textContent('.gp h2')).includes('Growing'), 'Previous moves back');
  await p.locator('.gp .topics button', { hasText: 'Towers' }).click();
  yes((await p.textContent('.gp')).includes('Mythic') && (await p.textContent('.gp')).includes('1800 dust'), 'the completion tiers table');
  yes((await p.textContent('.gp .warn')).includes('first 20 chests'), 'the daily limit warning');
  await shot(p, 'guide');
  // the whole guide: no placeholder or empty number is left anywhere
  const all = await p.evaluate(() => JSON.stringify(window.GUIDE));
  // a placeholder may remain only for a number a server can tune, and then it has a shipped default to show until the server answers
  const leftover = await p.evaluate(() => { const text = JSON.stringify(window.GUIDE.topics); const keys = (text.match(/\{[a-zA-Z]+\.[a-zA-Z]+\}/g) || []).map(k => k.slice(1, -1)); return keys.filter(k => !(k in window.GUIDE.facts)); });
  eq(leftover, [], 'every remaining placeholder has a shipped default');
  yes(!all.includes('undefined') && !all.includes('NaN'), 'no undefined or NaN');
  yes(!all.includes('"J key') && !all.includes('Press J'), 'no mention of the old client J key');
  await p.click('#close');
  yes((await sent(p)).some(m => m.channel === 'close'), 'Close closes');
  noErrors(p);
});

await check('guide: the numbers a server owner can tune come live from the server, so a tuned server shows its own', async () => {
  const p = await open(null, 'guide.html', 1920, 1080);
  yes((await sent(p)).some(m => m.channel === 'ascension_guide_facts'), 'the guide asks the server for its live numbers');
  await p.locator('.gp .topics button', { hasText: 'Towers' }).click();
  yes((await p.textContent('.gp .warn')).includes('first 20 chests'), 'the shipped default shows until the server answers');
  await emit(p, 'guideFacts', { facts: { 'budget.full': '8', 'budget.percent': '10', 'mini.dust': '300' }, tables: { tower_tier_table: { head: ['Tier', 'Reached at', 'Pile', 'Guaranteed pile', 'Crown'], rows: [['Bronze', 'score 0+', 'x1', '999 dust, 1 facets, 1 fragments, 1 scouters, 1 Marks', '1 crown roll (50% Core each)']] } } });
  yes((await p.textContent('.gp .warn')).includes('first 8 chests') && (await p.textContent('.gp .warn')).includes('10% of its dust'), 'the daily limit shows the live numbers');
  yes((await p.textContent('.gp')).includes('300 dust'), 'the mini chest shows its live dust');
  yes((await p.textContent('.gp table')).includes('999 dust'), 'the completion table is the one the server built');
  const all = await p.textContent('.gp');
  yes(!/\{[a-z]+\.[a-zA-Z]+\}/.test(all), 'no placeholder is left on screen');
  await p.locator('.gp .topics button', { hasText: 'Respec' }).click();
  yes((await p.textContent('.gp')).includes('90 Tower Marks'), 'a number the server did not send keeps its shipped default');
  noErrors(p);
});

await check('party: the Guide tab shows the same guide inside the AscensionDex', async () => {
  const p = await open(null, 'party.html', 1920, 1080);
  eq(await p.locator('#tabbar button').count(), 3, 'three tabs (Fuse is a footer button)');
  await p.click('#tabGuide');
  eq(await p.isVisible('#paneGuide'), true, 'the guide pane shows');
  eq(await p.isVisible('#paneParty'), false, 'and the party hides');
  yes((await p.textContent('#paneGuide h2')).toLowerCase().includes('quick start'), 'it opens at Quick start');
  await p.locator('#paneGuide .topics button', { hasText: 'Respec' }).click();
  yes((await p.textContent('#paneGuide')).includes('Hourglass of Undoing') && (await p.textContent('#paneGuide')).includes('90 Tower Marks'), 'the respec topic');
  await p.click('#tabParty');
  eq(await p.isVisible('#paneParty'), true, 'back to the party');
  noErrors(p);
});

await check('party: choosing a Pokemon only moves the highlight (the cards are not rebuilt)', async () => {
  const p = await open(null, 'party.html', 1920, 1080);
  const mon = (id, name) => ({ slot: 0, id, name, speciesId: 'cobblemon:' + name.toLowerCase(), level: 10, rarityId: 'rare', pending: 0, hp: 100, fainted: false, shiny: false, locked: false });
  await emit(p, 'partyView', { slots: [mon('a1', 'Arcanine'), mon('b2', 'Growlithe')] });
  await p.evaluate(() => { document.querySelectorAll('.card').forEach(c => { c.dataset.same = '1'; }); });
  await p.locator('.card').nth(1).click();
  eq(await p.locator('.card[data-same="1"]').count(), 2, 'the same card elements are still there');
  eq(await p.locator('.card.sel').count(), 1, 'one card is highlighted');
  yes((await p.locator('.card.sel').textContent()).includes('Growlithe'), 'the picked card is the highlighted one');
  yes((await p.textContent('#info')).includes('Growlithe'), 'the footer follows the pick');
  noErrors(p);
});

await check('perf: a page times its renders and reports them to the server in the bounds the server accepts', async () => {
  const p = await open(null, 'party.html', 1920, 1080);
  const mon = (id, name) => ({ slot: 0, id, name, speciesId: 'cobblemon:' + name.toLowerCase(), level: 10, rarityId: 'rare', pending: 0, hp: 100, fainted: false, shiny: false, locked: false });
  await emit(p, 'partyView', { slots: [mon('a1', 'Arcanine')] });
  await emit(p, 'partyView', { slots: [mon('a1', 'Arcanine')] });
  yes(!(await sentAll(p)).some(m => m.channel === 'ascension_perf'), 'nothing is sent while the page is open and quiet');
  await p.click('#close');
  const m = (await sentAll(p)).find(x => x.channel === 'ascension_perf');
  yes(m, 'closing sends the timings first');
  eq(m.page, 'party', 'the page name');
  eq(m.t['render.partyView'].length, 2, 'both renders were timed');
  yes(m.t.firstData.length === 1 && m.t.firstData[0] > 0, 'the first data time is reported once');
  yes(Object.keys(m.t).length <= 12 && Object.keys(m.t).every(k => /^[a-z][a-zA-Z0-9.]{0,23}$/.test(k)), 'every label passes the server rule');
  yes(Object.values(m.t).every(a => a.length <= 20 && a.every(v => typeof v === 'number' && v >= 0)), 'samples are bounded numbers');
  const order = (await sentAll(p)).map(x => x.channel);
  yes(order.indexOf('ascension_perf') < order.indexOf('close'), 'the report goes out before the close request');
  noErrors(p);
});

await check('craft: the Haunting row (Halloween 2026): hidden when the server hides it, rolls from its own detail, theme follows the server flag', async () => {
  const haunt = (extra = {}) => Object.assign({ show: true, essence: 4, id: '', name: '', rare: false, effect: '', rolls: 0, cost: 1, block: '',
    all: [{ id: 'wisp', name: 'Wisp', rare: false, effect: '20% chance on hit that the target flinches.', chance: 17.3 },
          { id: 'hex', name: 'Hex', rare: false, effect: 'Foes with a status lose 1/16.', chance: 15.4 },
          { id: 'spectral_veil', name: 'Spectral Veil', rare: false, effect: 'x', chance: 15.4 }, { id: 'pumpkin_lantern', name: 'Pumpkin Lantern', rare: false, effect: 'x', chance: 13.4 },
          { id: 'trick_or_treat', name: 'Trick or Treat', rare: false, effect: 'x', chance: 11.5 }, { id: 'cobweb', name: 'Cobweb', rare: false, effect: 'x', chance: 9.6 },
          { id: 'graveyard_chill', name: 'Graveyard Chill', rare: false, effect: 'x', chance: 7.7 }, { id: 'midnight_howl', name: 'Midnight Howl', rare: false, effect: 'x', chance: 5.8 },
          { id: 'headless_rider', name: 'Headless Rider', rare: true, effect: 'Survives a fatal hit once.', chance: 2.5 }, { id: 'reapers_toll', name: "Reaper's Toll", rare: true, effect: 'x', chance: 1.5 }] }, extra);
  const p = await open(null, 'craft.html');
  await emit(p, 'sprites', { mewtwo: PNG });
  await emit(p, 'uiScale', { percent: 100, min: 60, max: 160, calm: false, halloween: true });
  yes(await p.evaluate(() => document.documentElement.classList.contains('halloween')), 'the server flag turns the theme on');
  await emit(p, 'craftView', Object.assign({}, craftView, { haunt: haunt({ show: false }) }));
  eq(await p.locator('#list .row.haunt').count(), 0, 'no row while the server hides it');
  await emit(p, 'craftView', Object.assign({}, craftView, { haunt: haunt() }));
  eq(await p.locator('#list .row.haunt').count(), 1, 'the row shows');
  eq(await p.locator('#wallet img.dot').count(), 10, 'Spooky Essence joins the wallet column');
  await p.locator('#list .row.haunt').click();
  yes((await p.textContent('#detail')).includes('Not haunted') && (await p.textContent('#detail')).includes('Headless Rider'), 'the detail shows the empty state and the odds');
  await shot(p, 'craft-haunt-empty');
  await p.locator('#detail button.primary').click();
  yes((await p.textContent('#dTitle')) === 'Confirm haunting', 'the dialog names the action');
  await p.click('#dOk');
  const m = (await sent(p)).find(x => x.channel === 'ascension_craft_confirm');
  eq([m.kind, m.slotId, m.pokemonId], ['haunt', '', craftView.pokemonId], 'confirm body');
  await emit(p, 'craftDone', { operationId: m.operationId, ok: true, message: '', slotId: '', oldName: '', oldRank: 0, oldValue: 0, newName: 'Headless Rider', newRank: 1, newValue: 0, replayed: false }, true);
  await emit(p, 'craftView', Object.assign({}, craftView, { haunt: haunt({ id: 'headless_rider', name: 'Headless Rider', rare: true, effect: 'Survives a fatal hit once.', rolls: 1, cost: 3, essence: 3 }) }));
  yes((await p.textContent('.banner')).includes('Rare!'), 'the banner announces a Rare');
  yes((await p.textContent('#detail')).includes('RARE'), 'the detail tags it Rare');
  await shot(p, 'craft-haunt-rare');
  await emit(p, 'craftView', Object.assign({}, craftView, { haunt: haunt({ block: 'Needs 3 Spooky Essence', essence: 0 }) }));
  yes(await p.locator('#detail button.primary').isDisabled(), 'Review is disabled when the roll is blocked');
  await emit(p, 'uiScale', { percent: 100, min: 60, max: 160, calm: false, halloween: false });
  yes(!(await p.evaluate(() => document.documentElement.classList.contains('halloween'))), 'the theme turns off with the flag');
  noErrors(p);
});

await check('pages degrade without WebGUI (no window.webgui): no crash', async () => {
  const ctx = await browser.newContext(); const p = await ctx.newPage(); const errors = [];
  p.on('pageerror', e => errors.push(e.message));
  for (const f of ['craft.html', 'fusion.html', 'inspect.html', 'hud.html', 'capture.html', 'wallet.html', 'party.html', 'completion.html', 'guide.html']) await p.goto(base + f);
  eq(errors, [], 'errors');
});

await browser.close(); server.close();
for (const [s, n] of results) console.log(s === 'ok' ? '  ok   ' : '  FAIL ', n);
console.log(failed ? `\n${failed} FAILED` : `\nall ${results.length} page checks passed`);
process.exit(failed ? 1 : 0);
