#!/usr/bin/env node
// Tests ascension-fx.js (docs/BATTLE-ADAPTER-DESIGN.md) against the REAL Showdown simulator Cobblemon unbundles, with no
// Minecraft server.
//
//   node validation/showdown/ascension_fx_test.js --showdown-dir <path to an unbundled Showdown directory>
//       [--tower-fx L:/CobbleTowers/src/main/resources/assets/cobbletowers/showdown/tower-fx.js]
//
// Showdown is plain CommonJS, so the module is installed into it and driven through BattleStream with the same `>start` /
// `>player` lines Cobblemon writes. Each test asserts what the simulator actually did (damage dealt, HP healed), never what
// the module claims it asked for. Not part of CI: it needs the unbundled simulator from a server directory. Run it after
// touching ascension-fx.js and after a Cobblemon upgrade.

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const dirArg = args.indexOf('--showdown-dir');
const towerArg = args.indexOf('--tower-fx');
const SHOWDOWN = path.resolve(dirArg >= 0 ? args[dirArg + 1] : (process.env.SHOWDOWN_DIR || 'showdown'));
const TOWER_FX = path.resolve(towerArg >= 0 ? args[towerArg + 1] : path.join(__dirname, '../../../../CobbleTowers/src/main/resources/assets/cobbletowers/showdown/tower-fx.js'));
const FX_FILE = path.resolve(__dirname, '../../ascensionlib/src/main/resources/assets/ascensionlib/showdown/ascension-fx.js');
const AFFIXES = path.resolve(__dirname, '../../design/affixes.json');
if (!fs.existsSync(path.join(SHOWDOWN, 'sim', 'battle.js'))) {
  console.error('no Showdown simulator at ' + SHOWDOWN + ' (pass --showdown-dir)');
  process.exit(2);
}

globalThis.__ASCENSION_FX_TEST__ = true;
globalThis.__TOWER_FX_TEST__ = true;
const BS = require(path.join(SHOWDOWN, 'sim/battle-stream'));
const {Battle} = require(path.join(SHOWDOWN, 'sim/battle'));
const fx = require(FX_FILE);

/** The tests write effects as {id, pct, type}; the wire format is {i, p, t}. Anything that is not an object passes through. */
function compact(payload) {
  if (!payload || typeof payload !== 'object' || !payload.mons || typeof payload.mons !== 'object') return payload;
  const mons = {};
  for (const [uuid, list] of Object.entries(payload.mons)) {
    mons[uuid] = Array.isArray(list)
      ? list.map(e => (e && typeof e === 'object' && e.i === undefined ? {i: e.id, p: e.pct, t: e.type} : e))
      : list;
  }
  return Object.assign({}, payload, {mons});
}

// ================================ driving the real simulator ========================================================

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const U1 = '11111111-1111-1111-1111-111111111111';
const U2 = '22222222-2222-2222-2222-222222222222';

/** Cobblemon's packed team: name|species|uuid|currentHealth|status|statusDuration|item|ability|moves|pp|nature|... */
function pack(species, uuid, ability, moves, level, health = '', item = 'none') {
  return [species, species, uuid, health, '', '', item, ability, moves.join(','), moves.map(() => '16/16').join(','),
    'Hardy', '', '', '', '', String(level), ''].join('|');
}

async function battle({fx: payload, teams, moves, turns = 1, towerFx, seed = [1, 2, 3, 4]} = {}) {
  const stream = new BS.BattleStream();
  const lines = [];
  
  (async () => { try { for await (const chunk of stream) lines.push(...String(chunk).split('\n')); } catch (e) { /* surfaced by asserts */ } })();
  const format = {mod: 'gen9', gameType: 'singles', gen: 9, ruleset: [], effectType: 'Format'};
  if (payload !== undefined) format.ascensionFx = compact(payload);
  if (towerFx !== undefined) format.towerFx = towerFx;
  stream.write('>start ' + JSON.stringify({format, seed}));
  stream.write('>player p1 ' + JSON.stringify({name: 'A', team: teams[0]}));
  stream.write('>player p2 ' + JSON.stringify({name: 'B', team: teams[1]}));
  await sleep(200);
  for (let turn = 0; turn < turns && !stream.battle.ended; turn++) {
    const [first, second] = typeof moves === 'function' ? moves(turn) : moves;
    stream.write('>p1 ' + first);
    stream.write('>p2 ' + second);
    await sleep(200);
  }
  return {lines, battle: stream.battle};
}

const mon = (b, side) => b.battle.sides[side].active[0];
const lost = (b, side) => mon(b, side).maxhp - mon(b, side).hp;

const BLASTOISE = (health = '') => pack('Blastoise', U1, 'torrent', ['hydropump', 'icebeam', 'surf', 'earthquake', 'recover'], 50, health);
const CHARIZARD = () => pack('Charizard', U2, 'blaze', ['flamethrower', 'airslash', 'dragonclaw', 'roost'], 50);
const TEAMS = () => [BLASTOISE(), CHARIZARD()];

const tests = [];
const test = (name, fn) => tests.push({name, fn});
let baseline;

test('1. identity: effects keyed by the packed uuid reach exactly that Pokemon', async () => {
  const b = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'overwhelming_force', pct: 20}]}}});
  assert.strictEqual(mon(b, 0).uuid, U1, 'the simulator holds the uuid Cobblemon packed');
  assert.strictEqual(mon(b, 1).uuid, U2);
  assert.ok(lost(b, 1) > lost(baseline, 1), 'the keyed Pokemon hits harder');
  assert.strictEqual(lost(b, 0), lost(baseline, 0), 'the other Pokemon is untouched');
});

test('1b. identity: an unknown uuid does nothing', async () => {
  const b = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {'99999999-9999-9999-9999-999999999999': [{id: 'overwhelming_force', pct: 50}]}}});
  assert.strictEqual(lost(b, 1), lost(baseline, 1));
});

test('2. outgoing: +20% lands within native rounding of 1.2x', async () => {
  const b = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'overwhelming_force', pct: 20}]}}});
  const ratio = lost(b, 1) / lost(baseline, 1);
  assert.ok(Math.abs(ratio - 1.2) < 0.02, 'ratio ' + ratio);
});

test('2b. outgoing: a typed affix applies to its type only', async () => {
  const on = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'type_focus', pct: 20, type: 'Water'}]}}});
  const off = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'type_focus', pct: 20, type: 'Fire'}]}}});
  assert.ok(lost(on, 1) > lost(baseline, 1));
  assert.strictEqual(lost(off, 1), lost(baseline, 1), 'hydropump is not fire');
});

test('2c. outgoing: super_effective_force reads native effectiveness after the engine computed it', async () => {
  const on = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'super_effective_force', pct: 20}]}}});
  assert.ok(lost(on, 1) > lost(baseline, 1), 'water vs fire is super effective');
  const resisted = await battle({teams: TEAMS(), moves: ['move 4', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'super_effective_force', pct: 20}]}}});
  const plain = await battle({teams: TEAMS(), moves: ['move 4', 'move 1']});
  assert.strictEqual(lost(resisted, 1), lost(plain, 1), 'earthquake is not super effective on Charizard (flying: immune)');
});

test('2d. outgoing: an HP threshold uses the HP before the move', async () => {
  const low = [pack('Blastoise', U1, 'torrent', ['surf'], 50, '40'), pack('Charizard', U2, 'blaze', ['flamethrower', 'airslash', 'dragonclaw', 'roost'], 100)];
  const withFx = await battle({teams: low, moves: ['move 1', 'move 4'], fx: {v: 1, mons: {[U1]: [{id: 'last_stand', pct: 20}]}}});
  const without = await battle({teams: low, moves: ['move 1', 'move 4']});
  assert.ok(lost(withFx, 1) > lost(without, 1), `last_stand active at low HP: ${lost(withFx, 1)} vs ${lost(without, 1)} (hp ${mon(withFx, 0).hp}/${mon(withFx, 0).maxhp})`);
  const healthy = await battle({teams: TEAMS(), moves: ['move 3', 'move 4'], fx: {v: 1, mons: {[U1]: [{id: 'last_stand', pct: 20}]}}});
  const healthyPlain = await battle({teams: TEAMS(), moves: ['move 3', 'move 4']});
  assert.strictEqual(lost(healthy, 1), lost(healthyPlain, 1), 'last_stand inactive at full HP');
});

test('2e. outgoing: stacking multiplies and the channel total is capped once', async () => {
  const two = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'overwhelming_force', pct: 12}, {id: 'physical_force', pct: 0}, {id: 'kindred_force', pct: 10}]}}});
  const one = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'overwhelming_force', pct: 12}]}}});
  assert.ok(lost(two, 1) > lost(one, 1));
  const huge = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'overwhelming_force', pct: 90}, {id: 'kindred_force', pct: 90}]}}});
  const doubled = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'overwhelming_force', pct: 100}]}}});
  assert.strictEqual(lost(huge, 1), lost(doubled, 1), 'capped at +100% however it was reached');
});

test('3. incoming: a reduction lowers damage taken and never to zero', async () => {
  const b = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'iron_resolve', pct: 9}]}}});
  assert.ok(lost(b, 0) < lost(baseline, 0));
  const huge = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'iron_resolve', pct: 90}]}}});
  const half = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'iron_resolve', pct: 50}]}}});
  assert.strictEqual(lost(huge, 0), lost(half, 0), 'capped at 50% reduction');
});

test('3b. incoming: opening_guard works on the first hit only (once-per-battle state)', async () => {
  const fx = {v: 1, mons: {[U1]: [{id: 'opening_guard', pct: 10}]}};
  const two = await battle({teams: TEAMS(), moves: ['move 4', 'move 1'], fx, turns: 2});
  const plain = await battle({teams: TEAMS(), moves: ['move 4', 'move 1'], turns: 2});
  // Turn one the guard applies, turn two it must not: so the second hit equals the plain second hit.
  const hits = b => b.lines.filter(l => /^\|-damage\|p1a/.test(l) && !l.endsWith('/100')).map(l => Number(l.split('|')[3].split('/')[0]));
  const a = hits(two), p = hits(plain);
  assert.ok(a.length >= 2 && p.length >= 2, 'two incoming hits each: ' + JSON.stringify({a, p}));
  assert.ok(a[0] > p[0], 'first hit reduced (more HP left)');
  assert.strictEqual(a[0] - a[1], p[0] - p[1], 'second hit is the native amount ' + JSON.stringify({a, p}));
});

test('4. healing: restorative boosts the holder\'s own recovery move and nothing else', async () => {
  const hurt = [pack('Blastoise', U1, 'torrent', ['recover'], 50, '30'), CHARIZARD()];
  const fx = {v: 1, mons: {[U1]: [{id: 'restorative', pct: 20}]}};
  const withFx = await battle({teams: hurt, moves: ['move 1', 'move 4'], fx});
  const without = await battle({teams: hurt, moves: ['move 1', 'move 4']});
  const gain = (b) => mon(b, 0).hp;
  assert.ok(gain(withFx) > gain(without), `recover heals more: ${gain(withFx)} vs ${gain(without)}`);
});

test('4b. healing: drain moves are healed more', async () => {
  const teams = [pack('Blastoise', U1, 'torrent', ['gigadrain'], 50, '60'), pack('Charizard', U2, 'blaze', ['splash'], 50)];
  const fx = {v: 1, mons: {[U1]: [{id: 'restorative', pct: 20}]}};
  const withFx = await battle({teams, moves: ['move 1', 'move 1'], fx});
  const without = await battle({teams, moves: ['move 1', 'move 1']});
  assert.ok(mon(withFx, 0).hp > mon(without, 0).hp, `drain heals more: ${mon(withFx, 0).hp} vs ${mon(without, 0).hp}`);
});

test('4c. healing: a residual heal (Aqua Ring) is not boosted, only the holder own move', async () => {
  const teams = [pack('Blastoise', U1, 'torrent', ['aquaring'], 50, '30'), CHARIZARD()];
  const fx = {v: 1, mons: {[U1]: [{id: 'restorative', pct: 50}]}};
  const withFx = await battle({teams, moves: ['move 1', 'move 4'], fx, turns: 3});
  const without = await battle({teams, moves: ['move 1', 'move 4'], turns: 3});
  assert.ok(mon(without, 0).hp > 30, 'aqua ring did heal in the baseline: ' + mon(without, 0).hp);
  assert.strictEqual(mon(withFx, 0).hp, mon(without, 0).hp);
});

test('4d. healing: the opponent drain is not boosted by the holder affix', async () => {
  const teams = [pack('Blastoise', U1, 'torrent', ['surf'], 50), pack('Charizard', U2, 'blaze', ['gigadrain'], 50, '60')];
  const fx = {v: 1, mons: {[U1]: [{id: 'restorative', pct: 50}]}};
  const withFx = await battle({teams, moves: ['move 1', 'move 1'], fx});
  const without = await battle({teams, moves: ['move 1', 'move 1']});
  assert.strictEqual(mon(withFx, 1).hp, mon(without, 1).hp);
});

test('2f. outgoing: opening_strike boosts the first damaging move after entering, not the second', async () => {
  const fx = {v: 1, mons: {[U1]: [{id: 'opening_strike', pct: 20}]}};
  const dealt = b => b.lines.filter(l => /^\|-damage\|p2a/.test(l) && !l.endsWith('/100')).map(l => Number(l.split('|')[3].split('/')[0]));
  const big = [BLASTOISE(), pack('Charizard', U2, 'blaze', ['splash'], 100)];
  const withFx = await battle({teams: big, moves: ['move 3', 'move 1'], fx, turns: 2});
  const plain = await battle({teams: big, moves: ['move 3', 'move 1'], turns: 2});
  const a = dealt(withFx), p = dealt(plain);
  assert.ok(a.length >= 2 && p.length >= 2, JSON.stringify({a, p}));
  assert.ok(a[0] < p[0], 'first hit harder: ' + JSON.stringify({a, p}));
  assert.strictEqual(a[0] - a[1], p[0] - p[1], 'second hit native: ' + JSON.stringify({a, p}));
});

test('5. triumphant: a move that faints the target heals the holder', async () => {
  const teams = [pack('Blastoise', U1, 'torrent', ['surf'], 100, '50'), pack('Charizard', U2, 'blaze', ['splash'], 5)];
  const fx = {v: 1, mons: {[U1]: [{id: 'triumphant', pct: 20}]}};
  const withFx = await battle({teams, moves: ['move 1', 'move 1'], fx});
  const without = await battle({teams, moves: ['move 1', 'move 1']});
  assert.ok(mon(withFx, 0).hp > mon(without, 0).hp, `healed after the kill: ${mon(withFx, 0).hp} vs ${mon(without, 0).hp}`);
});

test('6. hostile payloads never stop a battle', async () => {
  for (const fx of ['text', 5, null, {v: 2, mons: {}}, {v: 1, mons: 'x'}, {v: 1, mons: {[U1]: 'no'}},
                    {v: 1, mons: {[U1]: [{id: 'overwhelming_force', pct: 'lots'}, {id: 'nonsense', pct: 5}, null, 7]}}]) {
    const b = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx});
    assert.ok(b.lines.some(l => l.startsWith('|switch|')), 'the battle started for ' + JSON.stringify(fx));
    assert.strictEqual(lost(b, 1), lost(baseline, 1));
  }
});

test('7. no payload: byte-identical to a battle without the module', async () => {
  const b = await battle({teams: TEAMS(), moves: ['move 3', 'move 1']});
  const strip = log => log.filter(l => !l.startsWith('|t:') && !l.startsWith('|request|'));
  assert.deepStrictEqual(strip(b.lines), strip(baseline.lines));
});


test('8. every affix in the catalog has a handler (so a new affix cannot silently do nothing)', async () => {
  const catalog = JSON.parse(fs.readFileSync(AFFIXES, 'utf8'));
  const ids = (Array.isArray(catalog) ? catalog : catalog.affixes).map(a => a.id);
  const missing = ids.filter(id => !fx.HANDLED.has(id));
  assert.deepStrictEqual(missing, []);
});

test('9. caps come from the payload and are bounded', async () => {
  const doubled = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, caps: {out: 10}, mons: {[U1]: [{id: 'overwhelming_force', pct: 50}]}}});
  const tenth = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'overwhelming_force', pct: 10}]}}});
  assert.strictEqual(lost(doubled, 1), lost(tenth, 1), 'a 10% cap makes 50% land as 10%');
});

test('10. a type the dex does not know drops that effect, not the battle', async () => {
  const b = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{id: 'type_focus', pct: 20, type: 'Nonsense'}]}}});
  assert.strictEqual(lost(b, 1), lost(baseline, 1));
});

test('11. it composes with the CobbleTowers tower-fx.js module, which wraps the same modifyDamage', async function () {
  if (!fs.existsSync(TOWER_FX)) { console.log('       (skipped: no tower-fx.js at ' + TOWER_FX + ')'); return; }
  require(TOWER_FX).install({Battle});
  // A sturdy target, so no hit is capped by the target's remaining HP.
  const teams = [BLASTOISE(), pack('Charizard', U2, 'blaze', ['splash'], 100)];
  const moves = ['move 3', 'move 1'];
  const tower = [{op: 'damage', sides: ['p1'], type: 'any', percent: 110}];
  const mine = {v: 1, mons: {[U1]: [{id: 'overwhelming_force', pct: 20}]}};
  const plain = await battle({teams, moves});
  const towerOnly = await battle({teams, moves, towerFx: tower});
  const ascensionOnly = await battle({teams, moves, fx: mine});
  const both = await battle({teams, moves, towerFx: tower, fx: mine});
  assert.ok(lost(towerOnly, 1) > lost(plain, 1), 'tower-fx damage is applied');
  assert.ok(lost(ascensionOnly, 1) > lost(plain, 1), 'ascension damage is applied');
  assert.ok(lost(both, 1) > lost(towerOnly, 1) && lost(both, 1) > lost(ascensionOnly, 1), 'both multipliers apply together');
  const ratio = lost(both, 1) / lost(plain, 1);
  assert.ok(Math.abs(ratio - 1.32) < 0.05, 'about 1.1 x 1.2, got ' + ratio);
});

// ---- status damage: Smoldering, Venomous, Rending (docs/STATUS-AFFIX-DESIGN.md) ----
// A sturdy, harmless target (Magikarp only splashes) so the only damage is the status residual.
const MAGIKARP = (item = 'none') => pack('Magikarp', U1, 'swiftswim', ['splash'], 50, '', item);
const INFLICTOR = (moves, item = 'none') => pack('Charizard', U2, 'blaze', moves, 50, '', item);
const statusRun = (move, fxList, extra = {}) => battle(Object.assign({
  teams: [MAGIKARP(), INFLICTOR([move, 'splash'])], moves: ['move 1', 'move 1'], turns: 4,
  fx: fxList ? {v: 1, mons: {[U2]: fxList}} : undefined,
}, extra));

test('12. smoldering: boosts burn damage on a target the holder burned', async () => {
  const plain = await statusRun('willowisp');
  const boosted = await statusRun('willowisp', [{id: 'smoldering', pct: 50}]);
  assert.strictEqual(mon(plain, 0).status, 'brn', 'the burn landed in the baseline');
  assert.ok(lost(plain, 0) > 0);
  assert.ok(lost(boosted, 0) > lost(plain, 0), `boosted ${lost(boosted, 0)} vs native ${lost(plain, 0)}`);
});

test('12b. smoldering: no boost on a burn the holder did not cause, nor on poison', async () => {
  const selfBurn = await battle({teams: [MAGIKARP('flameorb'), INFLICTOR(['splash'])], moves: ['move 1', 'move 1'], turns: 4,
    fx: {v: 1, mons: {[U1]: [{id: 'smoldering', pct: 50}]}}});
  const selfBurnPlain = await battle({teams: [MAGIKARP('flameorb'), INFLICTOR(['splash'])], moves: ['move 1', 'move 1'], turns: 4});
  assert.ok(lost(selfBurnPlain, 0) > 0, 'Flame Orb burned the holder');
  assert.strictEqual(lost(selfBurn, 0), lost(selfBurnPlain, 0), 'a self-inflicted burn stays native');
  const poison = await statusRun('poisongas', [{id: 'smoldering', pct: 50}]);
  const poisonPlain = await statusRun('poisongas');
  assert.strictEqual(lost(poison, 0), lost(poisonPlain, 0), 'smoldering does not touch poison');
});

test('13. venomous: boosts regular poison, not toxic', async () => {
  const plain = await statusRun('poisongas');
  const boosted = await statusRun('poisongas', [{id: 'venomous', pct: 50}]);
  assert.strictEqual(mon(plain, 0).status, 'psn', 'the poison landed in the baseline');
  assert.ok(lost(boosted, 0) > lost(plain, 0), `boosted ${lost(boosted, 0)} vs native ${lost(plain, 0)}`);
  const toxicPlain = await statusRun('toxic');
  const toxic = await statusRun('toxic', [{id: 'venomous', pct: 50}]);
  assert.strictEqual(lost(toxic, 0), lost(toxicPlain, 0), 'toxic stays native');
});

test('13b. potency pauses while the inflictor is benched, and the native status stays', async () => {
  const team2 = [pack('Charizard', U2, 'blaze', ['willowisp', 'splash'], 50), pack('Blastoise', '33333333-3333-3333-3333-333333333333', 'torrent', ['splash'], 50)].join(']');
  const fxList = [{id: 'smoldering', pct: 90}];
  const run = (moves, list) => battle({teams: [MAGIKARP(), team2], turns: 4, moves,
    fx: list ? {v: 1, mons: {[U2]: list}} : undefined});
  const leaves = turn => (turn === 0 ? ['move 1', 'move 1'] : ['move 1', 'switch 2']);
  const stays = () => ['move 1', 'move 1'];
  const plain = await run(leaves);
  const benched = await run(leaves, fxList);
  const staying = await run(stays, fxList);
  assert.strictEqual(mon(benched, 0).status, 'brn', 'the burn stays after the inflictor leaves');
  assert.ok(lost(benched, 0) >= lost(plain, 0), 'the one tick while the inflictor was out may be boosted');
  assert.ok(lost(benched, 0) < lost(staying, 0), `benched ${lost(benched, 0)} must be below staying ${lost(staying, 0)}`);
});

const bleedTicks = (lines, maxhp) => {
  // Direct move damage and bleed ticks are both plain -damage lines; a tick is a damage line that follows another one in a turn. The
  // log carries every hit twice (exact HP, then percent), so only the exact view (denominator = max HP) is read.
  const out = [];
  let prev = null;
  for (const line of lines) {
    if (line.startsWith('|turn|')) prev = null;
    const m = /^\|-damage\|p1a: [^|]+\|(\d+)\/(\d+)\s*$/.exec(line);
    if (m && Number(m[2]) === maxhp) { if (prev !== null) out.push(prev - Number(m[1])); prev = Number(m[1]); }
  }
  return out;
};

test('14. rending: a damaging hit can start a bleed that ticks 1/12 max HP and stacks to three', async () => {
  let bled = 0, sawStack = false;
  const seen = [];
  for (let s = 1; s <= 40 && !(bled && sawStack); s++) {
    const b = await battle({teams: [MAGIKARP(), INFLICTOR(['tackle'])], moves: ['move 1', 'move 1'], turns: 6, seed: [s, 2, 3, 4],
      fx: {v: 1, mons: {[U2]: [{id: 'rending', pct: 10}]}}});
    const text = b.lines.join('\n');
    if (text.includes('Magikarp is bleeding!')) bled++;
    const ticks = bleedTicks(b.lines, mon(b, 0).maxhp);
    if (ticks.length && seen.length < 4) seen.push({seed: s, ticks});
    const max = mon(b, 0).maxhp;
    const single = Math.trunc(max / 12 * 1.1);
    if (ticks.length) assert.ok(ticks.every(t => t === single || t === Math.trunc(max / 12 * 2 * 1.1) || t === Math.trunc(max / 12 * 3 * 1.1)),
      `ticks ${JSON.stringify(ticks)} are 1, 2 or 3 stacks of ${single}`);
    if (ticks.some(t => t === Math.trunc(max / 12 * 2 * 1.1) || t === Math.trunc(max / 12 * 3 * 1.1))) sawStack = true;
  }
  assert.ok(bled > 0, 'a bleed started in at least one of 40 seeds');
  assert.ok(sawStack, 'a deeper bleed (2+ stacks) ticked harder than a single stack; seen: ' + JSON.stringify(seen));
});

test('14b. rending: never starts a bleed without the affix', async () => {
  for (let s = 1; s <= 20; s++) {
    const b = await battle({teams: [MAGIKARP(), INFLICTOR(['tackle'])], moves: ['move 1', 'move 1'], turns: 4, seed: [s, 2, 3, 4]});
    assert.ok(!b.lines.join('\n').includes('is bleeding!'), 'no bleed without rending');
  }
});

test('14c. rending: at most one application per move use', async () => {
  for (let s = 1; s <= 40; s++) {
    const b = await battle({teams: [MAGIKARP(), INFLICTOR(['tackle'])], moves: ['move 1', 'move 1'], turns: 1, seed: [s, 2, 3, 4],
      fx: {v: 1, mons: {[U2]: [{id: 'rending', pct: 10}]}}});
    const starts = b.lines.filter(l => l.includes('is bleeding!')).length;
    assert.ok(starts <= 1, 'at most one application per move use');
  }
});

test('14d. rending: the bleed lines carry stacks and turns and end with a stop line (BleedTracker.java reads exactly these)', async () => {
  const START = /^\|-message\|(.+?) is (?:still )?bleeding! \(stack (\d+), (\d+) turns?\)$/;
  const STOP = /^\|-message\|(.+?)'s bleeding stopped\.$/;
  let started = 0, stopped = 0;
  for (let s = 1; s <= 40 && !stopped; s++) {
    const b = await battle({teams: [MAGIKARP(), INFLICTOR(['tackle'])], moves: ['move 1', 'move 1'], turns: 8, seed: [s, 2, 3, 4],
      fx: {v: 1, mons: {[U2]: [{id: 'rending', pct: 10}]}}});
    for (const line of b.lines) {
      const m = START.exec(line);
      if (m) { started++; assert.ok(Number(m[2]) >= 1 && Number(m[2]) <= 3 && Number(m[3]) === 3, 'stack 1-3 and 3 turns: ' + line); }
      if (STOP.test(line)) stopped++;
    }
    assert.ok(!b.lines.some(l => /bleeding/.test(l) && l.startsWith('|-message|') && !START.test(l) && !STOP.test(l)), 'every bleed line matches the contract');
  }
  assert.ok(started > 0 && stopped > 0, `a bleed started (${started}) and stopped (${stopped})`);
});

// ---- Uniques (docs/UNIQUES-DESIGN.md): each has a benefit and a fixed drawback ----
const on = (uuid, ...ids) => ({v: 1, mons: {[uuid]: ids.map(id => ({id, pct: 1}))}});

test('15. ashen_heart: burns you inflict hit harder, your direct damage is reduced', async () => {
  const plain = await statusRun('willowisp');
  const boosted = await statusRun('willowisp', [{id: 'ashen_heart', pct: 1}]);
  assert.strictEqual(mon(plain, 0).status, 'brn');
  assert.ok(lost(boosted, 0) > lost(plain, 0), `burn ${lost(boosted, 0)} vs ${lost(plain, 0)}`);
  const hit = await battle({teams: [BLASTOISE(), CHARIZARD()], moves: ['move 4', 'move 1'], fx: on(U2, 'ashen_heart')});
  const hitPlain = await battle({teams: [BLASTOISE(), CHARIZARD()], moves: ['move 4', 'move 1']});
  const ratio = lost(hit, 1) === 0 ? 1 : lost(hit, 0) / lost(hitPlain, 0);
  assert.ok(lost(hit, 0) < lost(hitPlain, 0), 'flamethrower is weaker with Ashen Heart');
  assert.ok(Math.abs(lost(hit, 0) / lost(hitPlain, 0) - 0.85) < 0.03, 'about 0.85, got ' + lost(hit, 0) / lost(hitPlain, 0));
});

test('15b. ashen_heart and smoldering share the residual channel and its cap', async () => {
  const both = await statusRun('willowisp', [{id: 'ashen_heart', pct: 1}, {id: 'smoldering', pct: 100}], {});
  const capped = await battle({teams: [MAGIKARP(), INFLICTOR(['willowisp', 'splash'])], moves: ['move 1', 'move 1'], turns: 4,
    fx: {v: 1, caps: {res: 20}, mons: {[U2]: [{id: 'ashen_heart', pct: 1}, {id: 'smoldering', pct: 100}]}}});
  assert.ok(lost(capped, 0) < lost(both, 0), 'a lower residual cap limits the combined boost');
});

test('16. last_breath: survives one lethal opposing hit at 1 HP, then faints on the next', async () => {
  const frail = [pack('Magikarp', U1, 'swiftswim', ['splash'], 50, '10'), INFLICTOR(['flamethrower', 'splash'])];
  const plain = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 1});
  assert.strictEqual(mon(plain, 0).hp, 0, 'it faints without the Unique');
  const held = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 1, fx: on(U1, 'last_breath')});
  assert.strictEqual(mon(held, 0).hp, 1, 'it hangs on at 1 HP');
  const second = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 2, fx: on(U1, 'last_breath')});
  assert.strictEqual(mon(second, 0).hp, 0, 'only once per battle');
  assert.ok(second.lines.join('\n').includes('hung on with Last Breath'));
});

test('16b. last_breath: healing is halved, and a hit that does not faint is untouched', async () => {
  const hurt = [pack('Blastoise', U1, 'torrent', ['recover'], 50, '20'), pack('Charizard', U2, 'blaze', ['splash'], 50)];
  const plain = await battle({teams: hurt, moves: ['move 1', 'move 1'], turns: 1});
  const halved = await battle({teams: hurt, moves: ['move 1', 'move 1'], turns: 1, fx: on(U1, 'last_breath')});
  const healedPlain = mon(plain, 0).hp - 20, healedHalved = mon(halved, 0).hp - 20;
  assert.ok(healedPlain > 0 && healedHalved > 0);
  assert.ok(Math.abs(healedHalved / healedPlain - 0.5) < 0.05, `about half, ${healedHalved} vs ${healedPlain}`);
  const light = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: on(U2, 'last_breath')});
  assert.strictEqual(lost(light, 1), lost(baseline, 1), 'damage dealt is unchanged');
});

test('17. creeping_venom: poison ramps with its age, toxic stays native, Psychic hurts the holder more', async () => {
  const plain = await statusRun('poisongas', undefined, {turns: 6});
  const ramp = await statusRun('poisongas', [{id: 'creeping_venom', pct: 1}], {turns: 6});
  assert.strictEqual(mon(plain, 0).status, 'psn');
  assert.ok(lost(ramp, 0) > lost(plain, 0), `ramped ${lost(ramp, 0)} vs native ${lost(plain, 0)}`);
  const toxicPlain = await statusRun('toxic', undefined, {turns: 6});
  const toxic = await statusRun('toxic', [{id: 'creeping_venom', pct: 1}], {turns: 6});
  assert.strictEqual(lost(toxic, 0), lost(toxicPlain, 0), 'toxic is excluded');
  const psychic = [BLASTOISE(), pack('Charizard', U2, 'blaze', ['psychic'], 50)];
  const hurt = await battle({teams: psychic, moves: ['move 4', 'move 1'], fx: on(U1, 'creeping_venom')});
  const hurtPlain = await battle({teams: psychic, moves: ['move 4', 'move 1']});
  const factor = lost(hurt, 0) / lost(hurtPlain, 0);
  assert.ok(Math.abs(factor - 1.2) < 0.04, 'about 1.2x, got ' + factor);
});

test('18. stormcaller: weather-type moves are boosted in that weather, and weaker with no weather', async () => {
  const teams = [pack('Blastoise', U1, 'torrent', ['raindance', 'hydropump', 'splash'], 50), pack('Charizard', U2, 'blaze', ['splash'], 100)];
  const sequence = turn => (turn === 0 ? ['move 1', 'move 1'] : ['move 2', 'move 1']);
  const plain = await battle({teams, moves: sequence, turns: 2});
  const boosted = await battle({teams, moves: sequence, turns: 2, fx: on(U1, 'stormcaller')});
  const ratio = lost(boosted, 1) / lost(plain, 1);
  assert.ok(Math.abs(ratio - 1.3) < 0.05, 'about +30% in rain, got ' + ratio);
  const dry = await battle({teams, moves: ['move 2', 'move 1'], turns: 1, fx: on(U1, 'stormcaller')});
  const dryPlain = await battle({teams, moves: ['move 2', 'move 1'], turns: 1});
  const dryRatio = lost(dry, 1) / lost(dryPlain, 1);
  assert.ok(Math.abs(dryRatio - 0.85) < 0.03, 'about -15% with no weather, got ' + dryRatio);
});

// A bulky target, so the bleed outlives three hits.
const SOAK = pack('Blastoise', U1, 'torrent', ['splash'], 100);
test('19. rupture: every physical move bleeds, stacks weaken 100/80/60, a special move does not', async () => {
  const near = (t, share, max) => Math.abs(t - max / 12 * share) < 1.01;   // float shares: allow the engine's truncation
  let deepest = 0;
  for (let s = 1; s <= 10; s++) {
    const b = await battle({teams: [SOAK, INFLICTOR(['tackle'])], moves: ['move 1', 'move 1'], turns: 5, seed: [s, 2, 3, 4], fx: on(U2, 'rupture')});
    assert.ok(b.lines.join('\n').includes('is bleeding!'), `seed ${s}: a physical hit always bleeds`);
    const max = mon(b, 0).maxhp, ticks = bleedTicks(b.lines, max);
    assert.ok(ticks.length > 0 && ticks.every(t => [1, 1.8, 2.4].some(share => near(t, share, max))), `ticks ${JSON.stringify(ticks)} are 1, 1.8 or 2.4 shares of ${Math.trunc(max / 12)}`);
    deepest = Math.max(deepest, ticks.filter(t => near(t, 2.4, max)).length);
  }
  assert.ok(deepest > 0, 'a third stack ticked');
  const special = await battle({teams: [MAGIKARP(), INFLICTOR(['flamethrower'])], moves: ['move 1', 'move 1'], turns: 2, fx: on(U2, 'rupture')});
  assert.ok(!special.lines.join('\n').includes('is bleeding!'), 'flamethrower is special');
});

test('20. titans_heart: super-effective hits add 10% of max HP, other hits do not, and no healing lands', async () => {
  const big = [BLASTOISE(), pack('Charizard', U2, 'blaze', ['splash'], 100)];
  const plain = await battle({teams: big, moves: ['move 1', 'move 1']});
  const boosted = await battle({teams: big, moves: ['move 1', 'move 1'], fx: on(U1, 'titans_heart')});
  assert.strictEqual(lost(boosted, 1) - lost(plain, 1), Math.floor(mon(boosted, 0).maxhp * 0.10), 'hydropump is super effective on Charizard');
  const resisted = [BLASTOISE(), pack('Magikarp', U2, 'swiftswim', ['splash'], 100)];
  const resistedPlain = await battle({teams: resisted, moves: ['move 1', 'move 1']});
  const resistedBoosted = await battle({teams: resisted, moves: ['move 1', 'move 1'], fx: on(U1, 'titans_heart')});
  assert.strictEqual(lost(resistedBoosted, 1), lost(resistedPlain, 1), 'water on water is not super effective');
  const hurt = [pack('Blastoise', U1, 'torrent', ['recover'], 50, '20'), pack('Charizard', U2, 'blaze', ['splash'], 50)];
  const healed = await battle({teams: hurt, moves: ['move 1', 'move 1'], turns: 2, fx: on(U1, 'titans_heart')});
  assert.strictEqual(mon(healed, 0).hp, 20, 'Recover heals nothing');
});

test('21. triple_seven: moves hurt the holder 25% more', async () => {
  const plain = await battle({teams: TEAMS(), moves: ['move 1', 'move 1']});
  const weak = await battle({teams: TEAMS(), moves: ['move 1', 'move 1'], fx: on(U1, 'triple_seven')});
  const ratio = lost(weak, 0) / lost(plain, 0);
  assert.ok(Math.abs(ratio - 1.25) < 0.03, 'about 1.25x, got ' + ratio);
  const bystander = await battle({teams: TEAMS(), moves: ['move 1', 'move 1'], fx: on(U2, 'triple_seven')});
  assert.strictEqual(lost(bystander, 0), lost(plain, 0), 'only the holder is weakened');
});

// ---- mechanic affixes (docs/DEPTH-DESIGN.md, part A) ----
const U3 = '33333333-3333-3333-3333-333333333333';
const U4 = '44444444-4444-4444-4444-444444444444';
const withPct = (uuid, id, pct) => ({v: 1, mons: {[uuid]: [{id, pct}]}});
const SEEDS = Array.from({length: 20}, (_, i) => i + 1);
const count = (b, needle) => b.lines.filter(line => line.includes(needle)).length;
/** The number of runs, across seeds, in which `needle` appears in the log. */
async function across(make, needle) {
  let hits = 0;
  for (const s of SEEDS) hits += count(await battle(Object.assign({seed: [s, 2, 3, 4]}, make())), needle) > 0 ? 1 : 0;
  return hits;
}
/** Successive HP values the log shows for one Pokemon, e.g. '|-damage|p2a: Snorlax|312/524'. */
const hpTrail = (b, who, maxhp) => b.lines.filter(l => l.startsWith('|-damage|' + who + '|') && l.split('|')[3].split(' ')[0].endsWith('/' + maxhp)).map(l => Number(l.split('|')[3].split('/')[0]));

test('22. keen_edge: forces crits on the holder\'s hits at its rolled chance, and armour still blocks them', async () => {
  const hydro = () => ({teams: TEAMS(), moves: ['move 1', 'move 4']});
  const plain = await across(hydro, '|-crit|');
  const keen = await across(() => Object.assign(hydro(), {fx: withPct(U1, 'keen_edge', 50)}), '|-crit|');
  assert.ok(keen >= plain + 4, `crits in ${keen} of ${SEEDS.length} seeds against ${plain} native`);
  const armoured = () => ({teams: [BLASTOISE(), pack('Omastar', U2, 'shellarmor', ['splash'], 50)], moves: ['move 1', 'move 1'],
    fx: withPct(U1, 'keen_edge', 50)});
  assert.strictEqual(await across(armoured, '|-crit|'), 0, 'Shell Armor still blocks a forced crit');
});

test('22b. keen_edge: a proc chance is bounded at 50 however high it is sent', async () => {
  const parsed = fx.parse(baseline.battle, {v: 1, mons: {[U1]: [{i: 'keen_edge', p: 100}, {i: 'overwhelming_force', p: 100}]}});
  const list = parsed.byUuid.get(U1);
  assert.strictEqual(list.find(e => e.i === 'keen_edge').p, 50);
  assert.strictEqual(list.find(e => e.i === 'overwhelming_force').p, 100, 'only chances are bounded');
});

test('23. swift_strike: a raised Speed stat changes who moves first', async () => {
  const order = b => {
    const first = b.lines.find(l => l.startsWith('|move|'));
    return first.split('|')[2].slice(0, 3);
  };
  const teams = [pack('Blastoise', U1, 'torrent', ['surf'], 50), pack('Charizard', U2, 'blaze', ['flamethrower'], 50)];
  const plain = await battle({teams, moves: ['move 1', 'move 1']});
  assert.strictEqual(order(plain), 'p2a', 'Charizard is faster natively');
  const swift = await battle({teams, moves: ['move 1', 'move 1'], fx: withPct(U1, 'swift_strike', 25)});
  assert.strictEqual(order(swift), 'p1a', 'Blastoise outruns it with +25% Speed');
  assert.ok(mon(swift, 0).getStat('spe') > mon(plain, 0).getStat('spe'));
  const small = await battle({teams, moves: ['move 1', 'move 1'], fx: withPct(U1, 'swift_strike', 5)});
  assert.strictEqual(order(small), 'p2a', 'a small bonus does not overtake');
});

test('24. momentum: a KO raises later damage, and leaving the field ends the stacks', async () => {
  const foes = [pack('Magikarp', U2, 'swiftswim', ['splash'], 5), pack('Snorlax', U3, 'immunity', ['splash'], 100)].join(']');
  const mine = [pack('Blastoise', U1, 'torrent', ['surf'], 100), pack('Charizard', U4, 'blaze', ['splash'], 100)].join(']');
  const script = [
    ['move 1', 'move 1'],      // KOs the Magikarp
    ['move 1', 'switch 2'],    // Snorlax comes in (p1 has nothing to choose)
    ['move 1', 'move 1'],      // Blastoise hits Snorlax with one stack
    ['switch 2', 'move 1'],    // Blastoise leaves
    ['switch 2', 'move 1'],    // and returns: no stacks
    ['move 1', 'move 1'],      // Blastoise hits Snorlax again
  ];
  const sequence = turn => script[turn];
  const run = fxPayload => battle({teams: [mine, foes], moves: sequence, turns: 6, fx: fxPayload});
  const plainRun = await run(undefined);
  const plainFirstHp = mon(plainRun, 1).maxhp;
  const plain = hpTrail(plainRun, 'p2a: ' + U3, plainFirstHp);
  const boosted = hpTrail(await run(withPct(U1, 'momentum', 20)), 'p2a: ' + U3, plainFirstHp);
  assert.ok(plain.length >= 2 && boosted.length >= 2, `trails ${JSON.stringify(plain)} / ${JSON.stringify(boosted)} `
    + plainRun.lines.filter(l => /Snorlax|Magikarp|error|Invalid|faint/i.test(l)).slice(0, 25).join(' // '));
  const dealt = trail => trail.slice(1).map((hp, i) => trail[i] - hp);
  const full = Number(plainFirstHp);
  const dPlain = dealt([full, ...plain]), dBoost = dealt([full, ...boosted]);
  assert.ok(dBoost[0] > dPlain[0], `one stack should hit harder: ${dBoost[0]} vs ${dPlain[0]}`);
  assert.strictEqual(dBoost[dBoost.length - 1], dPlain[dPlain.length - 1], `after leaving the field the stacks are gone: plain ${JSON.stringify(dPlain)} boosted ${JSON.stringify(dBoost)}`);
});

test('25. ensnaring: a hit may lower Speed by one stage, and the engine\'s own protections still apply', async () => {
  const hydro = target => () => ({teams: [BLASTOISE(), target], moves: ['move 1', 'move 1'], fx: withPct(U1, 'ensnaring', 50)});
  const slowed = await across(hydro(pack('Charizard', U2, 'blaze', ['splash'], 50)), '|-unboost|p2a: ' + U2 + '|spe|1');
  assert.ok(slowed >= 4, `slowed in ${slowed} of ${SEEDS.length} seeds`);
  const dust = await across(hydro(pack('Vivillon', U2, 'shielddust', ['splash'], 50)), '|-unboost|');
  assert.strictEqual(dust, 0, 'Shield Dust blocks it');
  const body = await across(hydro(pack('Metagross', U2, 'clearbody', ['splash'], 50)), '|-unboost|p2a: ' + U2 + '|spe');
  assert.strictEqual(body, 0, 'Clear Body blocks it');
  const none = await across(() => ({teams: [BLASTOISE(), pack('Charizard', U2, 'blaze', ['splash'], 50)], moves: ['move 1', 'move 1']}), '|-unboost|');
  assert.strictEqual(none, 0, 'native hydropump lowers nothing');
});

test('26. bracing_entry: a chance on entering the field, for the lead and for a switched-in Pokemon', async () => {
  const lead = await across(() => ({teams: [BLASTOISE(), CHARIZARD()], moves: ['move 5', 'move 4'], fx: withPct(U1, 'bracing_entry', 50)}), '|-boost|p1a: ' + U1 + '|def|1');
  assert.ok(lead >= 4 && lead < SEEDS.length, `lead braced in ${lead} of ${SEEDS.length} seeds`);
  const both = await across(() => ({teams: [BLASTOISE(), CHARIZARD()], moves: ['move 5', 'move 4'], fx: withPct(U1, 'bracing_entry', 50)}), '|-boost|p1a: ' + U1 + '|spd|1');
  assert.ok(both >= 4, 'Sp. Def is raised with Defense');
  const reserve = () => ({teams: [[CHARIZARD_U(U3), pack('Blastoise', U1, 'torrent', ['recover'], 50)].join(']'), CHARIZARD()], moves: ['switch 2', 'move 4'], fx: withPct(U1, 'bracing_entry', 50)});
  const switched = await across(reserve, '|-boost|p1a: ' + U1 + '|def|1');
  assert.ok(switched >= 4, `switched-in braced in ${switched} of ${SEEDS.length} seeds`);
  assert.strictEqual(await across(() => ({teams: [BLASTOISE(), CHARIZARD()], moves: ['move 5', 'move 4']}), '|-boost|'), 0, 'no effect, no boost');
});

function CHARIZARD_U(uuid) { return pack('Charizard', uuid, 'blaze', ['flamethrower', 'airslash', 'dragonclaw', 'roost'], 50); }

test('27. wardstone: a foe\'s status may fail at the rolled chance', async () => {
  const burn = fxPayload => () => ({teams: [BLASTOISE(), INFLICTOR(['willowisp'])], moves: ['move 5', 'move 1'], fx: fxPayload});
  const burned = async payload => {
    let n = 0;
    for (const s of SEEDS) if (mon(await battle(Object.assign({seed: [s, 2, 3, 4]}, burn(payload)())), 0).status === 'brn') n++;
    return n;
  };
  const plain = await burned(undefined);
  const warded = await burned(withPct(U1, 'wardstone', 50));
  assert.ok(plain >= 10, `native burns land in ${plain} of ${SEEDS.length}`);
  assert.ok(warded <= plain - 4, `warded ${warded} against ${plain}`);
  const note = await battle(Object.assign({seed: [1, 2, 3, 4]}, burn(withPct(U1, 'wardstone', 50))()));
  assert.ok(note.lines.join('\n').length > 0);
});

test('28. stubborn: a lethal foe move may leave 1 HP once per battle, at the rolled chance', async () => {
  const frail = [pack('Magikarp', U1, 'swiftswim', ['splash'], 50, '10'), INFLICTOR(['flamethrower', 'splash'])];
  let survived = 0, again = 0;
  for (const s of SEEDS) {
    const first = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 1, seed: [s, 2, 3, 4], fx: withPct(U1, 'stubborn', 50)});
    if (mon(first, 0).hp === 1) {
      survived++;
      const second = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 2, seed: [s, 2, 3, 4], fx: withPct(U1, 'stubborn', 50)});
      if (mon(second, 0).hp === 0) again++;
    }
  }
  assert.ok(survived >= 4 && survived < SEEDS.length, `held on in ${survived} of ${SEEDS.length} seeds`);
  assert.strictEqual(again, survived, 'only once per battle');
});

// ---- transcendents (docs/FUSION-DESIGN.md): two Uniques fused into one holder, each at its harmony share ----
const fused = (uuid, uniques, b, d, host, donor) => ({v: 1, mons: {[uuid]: [{i: 'transcendent', p: 1, u: uniques, b, d, t: host, r: donor}]}});
const near = (actual, expected, tolerance, label) => assert.ok(Math.abs(actual - expected) <= tolerance, `${label}: ${actual} is not within ${tolerance} of ${expected}`);

test('29. transcendent: both drawbacks are softened to the drawback share', async () => {
  const plain = await battle({teams: TEAMS(), moves: ['move 3', 'move 1']});
  const half = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: fused(U1, ['ashen_heart', 'triple_seven'], 50, 50)});
  near(lost(half, 1) / lost(plain, 1), 0.925, 0.03, 'Ashen Heart direct damage penalty 0.85 at 50% is 0.925');
  near(lost(half, 0) / lost(plain, 0), 1.125, 0.03, '777 damage taken 1.25 at 50% is 1.125');
  const full = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: fused(U1, ['ashen_heart', 'triple_seven'], 100, 100)});
  near(lost(full, 1) / lost(plain, 1), 0.85, 0.03, 'at 100% the Ashen Heart penalty is the plain one');
  near(lost(full, 0) / lost(plain, 0), 1.25, 0.03, 'at 100% the 777 penalty is the plain one');
});

test('30. transcendent: the host type is a typed offence and the donor type a typed defence, each only on its own type', async () => {
  const plain = await battle({teams: TEAMS(), moves: ['move 3', 'move 1']});
  const typed = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: fused(U1, ['rupture', 'last_breath'], 100, 100, 'Water', 'Fire')});
  near(lost(typed, 1) / lost(plain, 1), 1.10, 0.025, 'hydropump is Water, the host type: +10%');
  near(lost(typed, 0) / lost(plain, 0), 0.90, 0.025, 'flamethrower is Fire, the donor type: -10%');
  const wrong = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: fused(U1, ['rupture', 'last_breath'], 100, 100, 'Fire', 'Water')});
  assert.strictEqual(lost(wrong, 1), lost(plain, 1), 'a Fire host type does not boost a Water move');
  assert.strictEqual(lost(wrong, 0), lost(plain, 0), 'a Water donor type does not reduce a Fire move');
  const none = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: fused(U1, ['rupture', 'last_breath'], 100, 100)});
  assert.strictEqual(lost(none, 1), lost(plain, 1), 'no types sent, no typed bonus');
});

test('31. transcendent: composes with ordinary affixes inside the same capped channel', async () => {
  const plain = await battle({teams: TEAMS(), moves: ['move 3', 'move 1']});
  const payload = fused(U1, ['rupture', 'last_breath'], 100, 100, 'Water', 'Fire');
  payload.mons[U1].push({i: 'overwhelming_force', p: 20});
  const both = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: payload});
  near(lost(both, 1) / lost(plain, 1), 1.32, 0.03, '+20% times +10% multiply');
});

test('32. transcendent: Last Breath keeps its benefit as a chance and softens its healing drawback', async () => {
  const frail = [pack('Magikarp', U1, 'swiftswim', ['splash'], 50, '10'), INFLICTOR(['dragonclaw', 'splash'])];   // no burn chance: a burn tick would faint the survivor
  const held = fxPayload => () => ({teams: frail, moves: ['move 1', 'move 1'], turns: 1, fx: fxPayload});
  let always = 0, rarely = 0;
  for (const s of SEEDS) {
    const strong = await battle(Object.assign({seed: [s, 2, 3, 4]}, held(fused(U1, ['last_breath', 'rupture'], 100, 100))()));
    const weak = await battle(Object.assign({seed: [s, 2, 3, 4]}, held(fused(U1, ['last_breath', 'rupture'], 1, 100))()));
    if (mon(strong, 0).hp === 1) always++;
    if (mon(weak, 0).hp === 1) rarely++;
  }
  assert.strictEqual(always, SEEDS.length, `at a full benefit share it always triggers: ${always} of ${SEEDS.length}`);
  assert.ok(rarely <= 3, `at a 1% benefit share it almost never triggers: ${rarely} of ${SEEDS.length}`);
  const hurt = [pack('Blastoise', U1, 'torrent', ['recover'], 50, '20'), pack('Charizard', U2, 'blaze', ['splash'], 50)];
  const plain = await battle({teams: hurt, moves: ['move 1', 'move 1'], turns: 1});
  const soft = await battle({teams: hurt, moves: ['move 1', 'move 1'], turns: 1, fx: fused(U1, ['last_breath', 'rupture'], 100, 50)});
  const healedPlain = mon(plain, 0).hp - 20, healedSoft = mon(soft, 0).hp - 20;
  near(healedSoft / healedPlain, 0.75, 0.05, 'healing x0.5 at a 50% drawback share is x0.75');
});

test('33. transcendent: Titan\'s Heart scales its extra damage and lets part of the healing through', async () => {
  const big = [BLASTOISE(), pack('Charizard', U2, 'blaze', ['splash'], 100)];
  const plain = await battle({teams: big, moves: ['move 1', 'move 1']});
  const half = await battle({teams: big, moves: ['move 1', 'move 1'], fx: fused(U1, ['titans_heart', 'last_breath'], 50, 100)});
  assert.strictEqual(lost(half, 1) - lost(plain, 1), Math.floor(mon(half, 0).maxhp * 0.10 * 0.5), 'a 50% benefit share adds 5% of max HP');
  const hurt = [pack('Blastoise', U1, 'torrent', ['recover'], 50, '20'), pack('Charizard', U2, 'blaze', ['splash'], 50)];
  const none = await battle({teams: hurt, moves: ['move 1', 'move 1'], turns: 2, fx: on(U1, 'titans_heart')});
  assert.strictEqual(mon(none, 0).hp, 20, 'the plain Unique heals nothing');
  const some = await battle({teams: hurt, moves: ['move 1', 'move 1'], turns: 1, fx: fused(U1, ['titans_heart', 'stormcaller'], 100, 50)});
  const reference = await battle({teams: hurt, moves: ['move 1', 'move 1'], turns: 1});
  near((mon(some, 0).hp - 20) / (mon(reference, 0).hp - 20), 0.5, 0.06, 'a 50% drawback share lets half the healing through');
});

test('34. transcendent: Rupture bleeds at its benefit share instead of every physical move', async () => {
  const bleeding = payload => () => ({teams: [SOAK, INFLICTOR(['tackle'])], moves: ['move 1', 'move 1'], turns: 1, fx: payload});
  assert.strictEqual(await across(bleeding(fused(U2, ['rupture', 'last_breath'], 100, 100)), 'is bleeding!'), SEEDS.length, 'full share: always');
  const half = await across(bleeding(fused(U2, ['rupture', 'last_breath'], 50, 100)), 'is bleeding!');
  assert.ok(half >= 4 && half <= SEEDS.length - 4, `a 50% share bleeds in some of the seeds, not all or none: ${half} of ${SEEDS.length}`);
  assert.strictEqual(await across(() => ({teams: [SOAK, INFLICTOR(['tackle'])], moves: ['move 1', 'move 1'], turns: 1}), 'is bleeding!'), 0, 'no Unique, no bleed');
});

test('35. transcendent: an invalid payload does nothing and a payload cannot name the internal typed effects', async () => {
  const same = fused(U1, ['ashen_heart', 'ashen_heart'], 50, 50, 'Water', 'Fire');
  const unknown = fused(U1, ['ashen_heart', 'nope'], 50, 50, 'Water', 'Fire');
  const noShare = fused(U1, ['ashen_heart', 'triple_seven'], 0, 50, 'Water', 'Fire');
  const badType = fused(U1, ['ashen_heart', 'triple_seven'], 50, 50, 'Plasma', 'Fire');
  const direct = {v: 1, mons: {[U1]: [{i: 'transcend_offense', p: 50, t: 'Water'}, {i: 'transcend_defense', p: 50, t: 'Fire'}]}};
  for (const [name, payload] of Object.entries({same, unknown, noShare, badType, direct})) {
    const b = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: payload});
    assert.strictEqual(lost(b, 1), lost(baseline, 1), name + ' leaves outgoing damage alone');
    assert.strictEqual(lost(b, 0), lost(baseline, 0), name + ' leaves incoming damage alone');
  }
  const twice = fused(U1, ['rupture', 'last_breath'], 100, 100, 'Water', 'Fire');
  twice.mons[U1].push({i: 'transcendent', p: 1, u: ['ashen_heart', 'triple_seven'], b: 100, d: 100});
  const once = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: twice});
  const single = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: fused(U1, ['rupture', 'last_breath'], 100, 100, 'Water', 'Fire')});
  assert.strictEqual(lost(once, 1), lost(single, 1), 'a second transcendent on one Pokemon is ignored');
  assert.strictEqual(lost(once, 0), lost(single, 0));
});

// ---- Transcendent signatures, pulses and twists (docs/TRANSCENDENT-POWERS.md) ----
const UNIQUE_PAIRS = {
  phoenix_cinder: ['ashen_heart', 'last_breath'],
  eye_of_the_storm: ['last_breath', 'stormcaller'],
  jackpot_titan: ['titans_heart', 'triple_seven'],
};
/** A Transcendent with its own signature; the twist is a list of effects such as {op: 'heal', pct: 4}. */
const sig = (uuid, sg, b, d, tw, host, donor) => ({v: 1, mons: {[uuid]: [{i: 'transcendent', p: 1, u: UNIQUE_PAIRS[sg], b, d, t: host, r: donor, sg, tw}]}});
const IDLE = [{op: 'cleanse'}];   // a twist that does nothing unless the holder has a status, for comparisons
const idleSnorlax = () => pack('Snorlax', U2, 'immunity', ['splash', 'tackle', 'bodyslam'], 100);
const hurtBlastoise = hp => pack('Blastoise', U1, 'torrent', ['recover', 'raindance', 'splash', 'surf'], 100, String(hp));

test('36. phoenix_cinder: attackers that strike a holder below half HP are burned; above half they are not', async () => {
  const machamp = () => pack('Machamp', U2, 'guts', ['karatechop'], 30);
  const low = await battle({teams: [hurtBlastoise(120), machamp()], moves: ['move 3', 'move 1'], turns: 1, fx: sig(U1, 'phoenix_cinder', 100, 100, IDLE)});
  assert.strictEqual(mon(low, 1).status, 'brn', 'struck below half HP: the attacker burns');
  const high = await battle({teams: [hurtBlastoise(300), machamp()], moves: ['move 3', 'move 1'], turns: 1, fx: sig(U1, 'phoenix_cinder', 100, 100, IDLE)});
  assert.strictEqual(mon(high, 1).status, '', 'above half HP nothing burns');
  const weak = await across(() => ({teams: [hurtBlastoise(120), machamp()], moves: ['move 3', 'move 1'], turns: 1, fx: sig(U1, 'phoenix_cinder', 1, 100, IDLE)}), '|-status|');
  assert.ok(weak <= 3, `a 1% benefit share almost never burns: ${weak} of ${SEEDS.length}`);
});

test('37. phoenix_cinder: the clutch rises to a share of max HP, once; healing is capped by the drawback', async () => {
  const frail = [pack('Magikarp', U1, 'swiftswim', ['splash'], 50, '10'), INFLICTOR(['dragonclaw', 'splash'])];
  const one = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 1, fx: sig(U1, 'phoenix_cinder', 100, 100, IDLE)});
  assert.strictEqual(mon(one, 0).hp, Math.floor(mon(one, 0).maxhp * 0.20), `survives at 20% of max HP: hp ${mon(one, 0).hp} of ${mon(one, 0).maxhp}`);
  const half = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 1, fx: sig(U1, 'phoenix_cinder', 50, 100, IDLE)});
  assert.strictEqual(mon(half, 0).hp, Math.floor(mon(half, 0).maxhp * 0.10), 'at a 50% share, 10% of max HP');
  const twice = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 2, fx: sig(U1, 'phoenix_cinder', 100, 100, IDLE)});
  assert.strictEqual(mon(twice, 0).hp, 0, 'only once per battle');
  const heal = shareD => battle({teams: [hurtBlastoise(140), idleSnorlax()], moves: ['move 1', 'move 1'], turns: 1, fx: sig(U1, 'phoenix_cinder', 100, shareD, IDLE)});
  const full = await heal(100), partial = await heal(50);
  assert.strictEqual(mon(full, 0).hp, Math.floor(mon(full, 0).maxhp * 0.70), 'recover is cut off at 70% of max HP');
  assert.strictEqual(mon(partial, 0).hp, Math.floor(mon(partial, 0).maxhp * 0.85), 'a 50% drawback share caps at 85%');
});

test('38. eye_of_the_storm: weather heals and softens damage, no weather hurts; the clutch holds at 1 HP', async () => {
  const hit = (script, payload) => battle({teams: [hurtBlastoise(300), pack('Machamp', U2, 'guts', ['karatechop', 'splash'], 50)],
    moves: turn => script[turn], turns: script.length, fx: payload});
  const dry = [['move 3', 'move 2'], ['move 3', 'move 1']];                       // splash, then karate chop
  const rain = [['move 2', 'move 2'], ['move 3', 'move 2'], ['move 3', 'move 1']]; // rain dance, splash, then karate chop
  const struck = b => mon(b, 0).maxhp - hpTrail(b, 'p1a: ' + U1, mon(b, 0).maxhp)[0];   // the hit itself, before any end-of-turn heal
  const plainDry = await hit(dry, undefined), fxDry = await hit(dry, sig(U1, 'eye_of_the_storm', 100, 100, IDLE));
  near(struck(fxDry) / struck(plainDry), 1.12, 0.06, `no weather: 12% more damage (${struck(fxDry)} against ${struck(plainDry)})`);
  const plainRain = await hit(rain, undefined), fxRain = await hit(rain, sig(U1, 'eye_of_the_storm', 100, 100, IDLE));
  near(struck(fxRain) / struck(plainRain), 0.88, 0.06, `weather: 12% less damage (${struck(fxRain)} against ${struck(plainRain)})`);
  const wet = await battle({teams: [hurtBlastoise(100), idleSnorlax()], moves: turn => [['move 2', 'move 1'], ['move 3', 'move 1'], ['move 3', 'move 1']][turn],
    turns: 3, fx: sig(U1, 'eye_of_the_storm', 100, 100, IDLE)});
  const none = await battle({teams: [hurtBlastoise(100), idleSnorlax()], moves: turn => [['move 3', 'move 1']][0], turns: 3, fx: sig(U1, 'eye_of_the_storm', 100, 100, IDLE)});
  assert.strictEqual(mon(wet, 0).hp - 100, 3 * Math.floor(mon(wet, 0).maxhp * 0.03), 'three turns of rain heal 3% each');
  assert.strictEqual(mon(none, 0).hp, 100, 'without weather nothing heals');
  const frail = [pack('Magikarp', U1, 'swiftswim', ['splash'], 50, '10'), INFLICTOR(['dragonclaw', 'splash'])];
  const held = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 1, fx: sig(U1, 'eye_of_the_storm', 100, 100, IDLE)});
  assert.strictEqual(mon(held, 0).hp, 1, 'the clutch leaves 1 HP');
});

test('39. jackpot_titan: super-effective hits add max HP damage, the drawback costs damage taken, a KO is a pulse', async () => {
  const big = [BLASTOISE(), pack('Charizard', U2, 'blaze', ['splash', 'flamethrower'], 100)];
  const plain = await battle({teams: big, moves: ['move 3', 'move 1']});
  const half = await battle({teams: big, moves: ['move 3', 'move 1'], fx: sig(U1, 'jackpot_titan', 50, 100, IDLE)});
  assert.strictEqual(lost(half, 1) - lost(plain, 1), Math.floor(mon(half, 0).maxhp * 0.10 * 0.5), 'a 50% benefit share adds 5% of max HP');
  const burn = await battle({teams: big, moves: ['move 4', 'move 2'], fx: sig(U1, 'jackpot_titan', 100, 50, IDLE)});
  const burnPlain = await battle({teams: big, moves: ['move 4', 'move 2']});
  near(lost(burn, 0) / lost(burnPlain, 0), 1.11, 0.04, 'a 50% drawback share is 11% more damage taken');
  const prey = [pack('Magikarp', U2, 'swiftswim', ['splash'], 5), idleSnorlax()].join(']');
  const reward = trailing => battle({teams: [hurtBlastoise(100), prey], moves: ['move 4', 'move 1'], turns: 1, fx: trailing});
  const plainKo = await reward(undefined), fxKo = await reward(sig(U1, 'jackpot_titan', 100, 100, [{op: 'heal', pct: 4}]));
  assert.strictEqual(mon(plainKo, 0).hp, 100, 'no heal without the pulse');
  assert.strictEqual(mon(fxKo, 0).hp, 100 + Math.floor(mon(fxKo, 0).maxhp * 0.04), 'the KO pulse fires the twist (heal 4%)');
});

test('40. twist effects: each of the sixteen does what it says, scaled by the benefit share', async () => {
  const run = (tw, extra = {}) => battle(Object.assign({teams: [hurtBlastoise(150), pack('Machamp', U2, 'guts', ['splash', 'swordsdance', 'irondefense', 'poisongas'], 50)],
    moves: ['move 3', 'move 1'], turns: 1, fx: sig(U1, 'eye_of_the_storm', 100, 100, tw)}, extra));
  const foe = b => mon(b, 1), me = b => mon(b, 0);
  assert.strictEqual(foe(await run([{op: 'status', status: 'brn', chance: 100}])).status, 'brn', 'status');
  assert.strictEqual(foe(await run([{op: 'foeStage', stat: 'spe', delta: -1}])).boosts.spe, -1, 'foeStage');
  assert.strictEqual(me(await run([{op: 'selfStage', stat: 'atk', delta: 1, cap: 2}])).boosts.atk, 1, 'selfStage');
  const capped = await run([{op: 'selfStage', stat: 'atk', delta: 2, cap: 2}], {turns: 3});
  assert.strictEqual(me(capped).boosts.atk, 2, 'selfStage stops at its cap');
  const chip = await run([{op: 'chip', pct: 4}]);
  assert.strictEqual(lost(chip, 1), Math.floor(foe(chip).maxhp * 0.04), 'chip');
  const drain = await run([{op: 'drain', pct: 4}]);
  assert.strictEqual(lost(drain, 1), Math.floor(foe(drain).maxhp * 0.04), 'drain hurts the foe ...');
  assert.strictEqual(me(drain).hp, 150 + lost(drain, 1), '... and heals the holder by what it took');
  assert.strictEqual(me(await run([{op: 'heal', pct: 4}])).hp, 150 + Math.floor(me(await run([{op: 'heal', pct: 4}])).maxhp * 0.04), 'heal');
  const half = await battle({teams: [hurtBlastoise(150), pack('Machamp', U2, 'guts', ['splash'], 50)], moves: ['move 3', 'move 1'], turns: 1,
    fx: sig(U1, 'eye_of_the_storm', 50, 100, [{op: 'heal', pct: 4}])});
  assert.strictEqual(me(half).hp, 150 + Math.floor(me(half).maxhp * 0.04 * 0.5), 'a 50% share halves the twist');
  // Machamp sets up (+2 Atk, +2 Def) and the holder copies or strips.
  const setUp = (tw) => battle({teams: [hurtBlastoise(150), pack('Machamp', U2, 'guts', ['swordsdance', 'irondefense'], 50)], moves: ['move 3', 'move 1'], turns: 1,
    fx: sig(U1, 'eye_of_the_storm', 100, 100, tw)});
  assert.strictEqual(me(await setUp([{op: 'mimic'}])).boosts.atk, 2, 'mimic copies the highest positive stage');
  const strip = await battle({teams: [hurtBlastoise(150), pack('Machamp', U2, 'guts', ['irondefense'], 50)], moves: ['move 3', 'move 1'], turns: 1,
    fx: sig(U1, 'eye_of_the_storm', 100, 100, [{op: 'strip'}])});
  assert.strictEqual(foe(strip).boosts.def, 0, 'strip removes the foe\'s raised Defense');
  const ill = tw => battle({teams: [hurtBlastoise(150), pack('Machamp', U2, 'guts', ['poisongas', 'toxic'], 50)], moves: ['move 3', 'move 1'], turns: 1, seed: [3, 2, 3, 4],
    fx: sig(U1, 'eye_of_the_storm', 100, 100, tw)});
  assert.strictEqual(me(await ill([{op: 'cleanse'}])).status, '', 'cleanse removes a status at the end of the turn');
});

test('41. twist states: shield, boost, unresisted, hide, heal boost, refine and ward act on the next exchange only', async () => {
  const twoTurns = (tw, script, teams) => battle({teams, moves: turn => script[turn], turns: script.length, fx: sig(U1, 'eye_of_the_storm', 100, 100, tw)});
  const machamp = () => pack('Machamp', U2, 'guts', ['splash', 'karatechop'], 50);
  const dmgTaken = async tw => lost(await twoTurns(tw, [['move 3', 'move 1'], ['move 3', 'move 2']], [hurtBlastoise(300), machamp()]), 0);
  const control = await dmgTaken(IDLE);
  near((await dmgTaken([{op: 'shield', pct: 50}])) / control, 0.5, 0.06, 'shield 50% on the next hit taken');
  near((await dmgTaken([{op: 'hide', pct: 30, turns: 2}])) / control, 0.7, 0.06, `hide 30% (control ${control})`);
  near((await dmgTaken([{op: 'refine', pct: 50, turns: 2}])) / control, 1.06 / 1.12, 0.04, 'refine halves the no-weather drawback');
  const dealt = async tw => lost(await twoTurns(tw, [['move 3', 'move 1'], ['move 4', 'move 1']], [hurtBlastoise(300), idleSnorlax()]), 1);
  const dealtControl = await dealt(IDLE);
  near((await dealt([{op: 'boostNext', pct: 50}])) / dealtControl, 1.5, 0.08, 'boostNext 50% on the next damaging move');
  const resisted = async tw => lost(await twoTurns(tw, [['move 3', 'move 1'], ['move 4', 'move 1']],
    [hurtBlastoise(300), pack('Dragonite', U2, 'innerfocus', ['splash'], 100)]), 1);
  near((await resisted([{op: 'unresistedNext'}])) / (await resisted(IDLE)), 2, 0.2, 'unresisted: a resisted hit lands as neutral');
  const healed = async tw => (mon(await twoTurns(tw, [['move 3', 'move 1'], ['move 1', 'move 1']], [hurtBlastoise(40), idleSnorlax()]), 0).hp);
  const healedBase = (await healed(IDLE)) - 40, healedBoost = (await healed([{op: 'healBoost', pct: 50, turns: 2}])) - 40;
  assert.ok(healedBoost > healedBase * 1.3, `heal boost: ${healedBoost} against ${healedBase}`);
  const ward = tw => across(() => ({teams: [hurtBlastoise(300), pack('Machamp', U2, 'guts', ['splash', 'toxic'], 50)], moves: turn => [['move 3', 'move 1'], ['move 3', 'move 2']][turn],
    turns: 2, fx: sig(U1, 'eye_of_the_storm', 100, 100, tw)}), '|-status|');
  assert.ok((await ward(IDLE)) >= 10, 'toxic lands without a ward');
  assert.ok((await ward([{op: 'ward'}])) <= 2, 'a ward turns the next status aside');
});

test('42. twist pulse: a twist fires at most once per turn, however many hits the pulse sees', async () => {
  const multi = [hurtBlastoise(300), pack('Hitmonlee', U2, 'limber', ['doublekick'], 50)];
  const b = await battle({teams: multi, moves: ['move 3', 'move 1'], turns: 1, fx: sig(U1, 'phoenix_cinder', 100, 100, [{op: 'selfStage', stat: 'atk', delta: 1, cap: 6}])});
  assert.strictEqual(mon(b, 0).boosts.atk, 1, 'Double Kick hits twice but pulses once');
});

test('43. transcendent payloads: a bad twist ignores the whole effect; an unknown signature falls back to the two Uniques', async () => {
  const badOps = [[{op: 'explode', pct: 5}], [{op: 'heal', pct: 99}], [{op: 'heal'}], [{op: 'heal', pct: 4, extra: 1}], [], [IDLE[0], IDLE[0], IDLE[0]], 'heal'];
  for (const tw of badOps) {
    const b = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: sig(U1, 'jackpot_titan', 100, 100, tw)});
    assert.strictEqual(lost(b, 1), lost(baseline, 1), 'a bad twist leaves damage alone: ' + JSON.stringify(tw));
    assert.strictEqual(lost(b, 0), lost(baseline, 0));
  }
  const payload = fused(U1, ['ashen_heart', 'triple_seven'], 50, 50);
  payload.mons[U1][0].sg = 'not_implemented_yet';
  payload.mons[U1][0].tw = IDLE;
  const plain = await battle({teams: TEAMS(), moves: ['move 3', 'move 1']});
  const fallback = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: payload});
  near(lost(fallback, 1) / lost(plain, 1), 0.925, 0.03, 'the fallback is the scaled composition (Ashen Heart penalty at 50%)');
});

test('44. twist states: an effect armed by a move\'s own first hit does not touch that move\'s later hits', async () => {
  // Hitmonlee's Double Kick hits twice. A STRUCK pulse after hit one arms a shield; "the next hit taken" means the next MOVE, so
  // both hits of this move must land exactly as they do under a twist that arms nothing.
  const kick = tw => battle({teams: [hurtBlastoise(300), pack('Hitmonlee', U2, 'limber', ['doublekick'], 50)], moves: ['move 3', 'move 1'], turns: 1,
    fx: sig(U1, 'phoenix_cinder', 100, 100, tw)});
  const control = await kick(IDLE), shielded = await kick([{op: 'shield', pct: 50}]);
  assert.strictEqual(lost(shielded, 0), lost(control, 0), 'the shield armed by hit one does not reduce hit two');
});

// ---- the other eighteen signatures ----
const side = n => `p${n + 1}a: ${n ? U2 : U1}`;
const trail = (b, n) => hpTrail(b, side(n), mon(b, n).maxhp);
const firstHit = (b, n) => mon(b, n).maxhp - trail(b, n)[0];
const diffs = (b, n) => { const t = [mon(b, n).maxhp, ...trail(b, n)]; return t.slice(1).map((hp, i) => t[i] - hp); };
const duel = (a, f, script, fxp, extra) => battle(Object.assign({teams: [a, f], moves: turn => script[turn], turns: script.length, fx: fxp}, extra));
const BL = (moves, level = 50, hp = '') => pack('Blastoise', U1, 'torrent', moves, level, hp);
const CH = (moves, level = 50, hp = '') => pack('Charizard', U2, 'blaze', moves, level, hp);
const MH = (uuid, moves, level = 50, hp = '') => pack('Machamp', uuid, 'guts', moves, level, hp);
const SN = (moves = ['splash'], ability = 'immunity') => pack('Snorlax', U2, ability, moves, 100);
const ONE = [['move 1', 'move 1']];
const TWO = [['move 1', 'move 1'], ['move 1', 'move 1']];
const COMMON_PAIRS = {
  plague_pyre: ['ashen_heart', 'creeping_venom'], wildfire_crown: ['ashen_heart', 'stormcaller'], brand_of_ruin: ['ashen_heart', 'rupture'],
  titans_forge: ['ashen_heart', 'titans_heart'], gilded_ember: ['ashen_heart', 'triple_seven'], dying_bloom: ['last_breath', 'creeping_venom'],
  martyrs_edge: ['last_breath', 'rupture'], colossus_vow: ['last_breath', 'titans_heart'], last_gamble: ['last_breath', 'triple_seven'],
  miasma_front: ['creeping_venom', 'stormcaller'], festering_gash: ['creeping_venom', 'rupture'], blighted_giant: ['creeping_venom', 'titans_heart'],
  fortunes_rot: ['creeping_venom', 'triple_seven'], lightning_rend: ['stormcaller', 'rupture'], tempest_colossus: ['stormcaller', 'titans_heart'],
  skyfall_fortune: ['stormcaller', 'triple_seven'], breakers_might: ['rupture', 'titans_heart'], wager_of_blood: ['rupture', 'triple_seven'],
};
Object.assign(UNIQUE_PAIRS, COMMON_PAIRS);
const FULL = (sg, b = 100, d = 100, tw = IDLE) => sig(U1, sg, b, d, tw);

test('45. wildfire_crown: Fire moves and weather boost damage, Water and Ice moves are weaker', async () => {
  const fire = async fxp => firstHit(await duel(CH(['flamethrower']).replace(U2, U1), SN(), ONE, fxp), 1);
  const plain = await fire(undefined);
  near(await fire(FULL('wildfire_crown')) / plain, 1.20, 0.03, 'a Fire move is 20% stronger');
  near(await fire(FULL('wildfire_crown', 50)) / plain, 1.10, 0.03, 'a 50% benefit share halves it');
  const surf = async fxp => firstHit(await duel(BL(['surf']), SN(), ONE, fxp), 1);
  const dry = await surf(undefined);
  near(await surf(FULL('wildfire_crown')) / dry, 0.75, 0.03, 'a Water move is 25% weaker');
  near(await surf(FULL('wildfire_crown', 100, 50)) / dry, 0.875, 0.03, 'a 50% drawback share halves it');
  const claw = async fxp => firstHit(await duel(pack('Charizard', U1, 'blaze', ['sunnyday', 'dragonclaw'], 50), SN(), [['move 1', 'move 1'], ['move 2', 'move 1']], fxp), 1);
  near(await claw(FULL('wildfire_crown')) / await claw(undefined), 1.10, 0.03, 'any weather lifts every move 10%');
});

test('46. plague_pyre: a burned foe loses extra HP each turn and direct damage is weaker', async () => {
  const burned = fxp => duel(BL(['willowisp']), SN(), TWO, fxp);
  const plain = await burned(undefined), full = await burned(FULL('plague_pyre')), half = await burned(FULL('plague_pyre', 50));
  const max = mon(full, 1).maxhp;
  assert.strictEqual(lost(full, 1) - lost(plain, 1), 2 * Math.floor(max * 0.03), 'two turns of an extra 3%');
  assert.strictEqual(lost(half, 1) - lost(plain, 1), 2 * Math.floor(max * 0.03 * 0.5), 'a 50% benefit share halves it');
  assert.strictEqual(lost(await duel(BL(['splash']), SN(), TWO, FULL('plague_pyre')), 1), 0, 'a foe with no status loses nothing extra');
  const surf = async fxp => firstHit(await duel(BL(['surf']), SN(), ONE, fxp), 1);
  const dry = await surf(undefined);
  near(await surf(FULL('plague_pyre')) / dry, 0.90, 0.03, 'direct damage is 10% lower');
  near(await surf(FULL('plague_pyre', 100, 50)) / dry, 0.95, 0.03, 'a 50% drawback share halves it');
});

test('47. brand_of_ruin: a Brand makes the foe take more from you and burns hurt more; a new Brand costs HP', async () => {
  const fight = fxp => duel(BL(['surf']), SN(), TWO, fxp);
  const plain = await fight(undefined), full = await fight(FULL('brand_of_ruin'));
  const hit = (b, i) => diffs(b, 1)[i];
  near(hit(full, 1) / hit(plain, 1), 1.10, 0.03, 'the second hit on a branded foe is 10% stronger');
  assert.strictEqual(lost(full, 0), Math.floor(mon(full, 0).maxhp * 0.03), 'only the new Brand costs 3% of max HP (the refresh is free)');
  const half = await fight(FULL('brand_of_ruin', 100, 50));
  assert.strictEqual(lost(half, 0), Math.floor(mon(half, 0).maxhp * 0.03 * 0.5), 'a 50% drawback share halves the cost');
  const burn = fxp => duel(BL(['willowisp', 'surf']), SN(), [['move 1', 'move 1'], ['move 2', 'move 1']], fxp);
  const fire = diffs(await burn(FULL('brand_of_ruin')), 1);   // [burn tick, surf, burn tick]
  near(fire[2] / fire[0], 1.30, 0.04, 'a burn tick on a branded foe is 30% stronger');
  const base = diffs(await burn(undefined), 1);
  near(base[2] / base[0], 1.0, 0.02, 'and the same without the Brand');
});

test('48. titans_forge: super-effective hits add max HP damage and may burn; the holder bleeds HP while healthy', async () => {
  const fight = fxp => duel(BL(['surf']), CH(['splash'], 100), ONE, fxp);
  const plain = await fight(undefined), full = await fight(FULL('titans_forge')), half = await fight(FULL('titans_forge', 50));
  const max = mon(full, 0).maxhp;
  assert.strictEqual(firstHit(full, 1) - firstHit(plain, 1), Math.floor(max * 0.08), 'adds 8% of max HP');
  assert.strictEqual(firstHit(half, 1) - firstHit(plain, 1), Math.floor(max * 0.08 * 0.5), 'a 50% share adds half');
  assert.strictEqual(lost(full, 0), Math.floor(max * 0.03), 'the holder loses 3% of max HP while above 60% HP');
  const burns = await across(() => ({teams: [BL(['icebeam']), pack('Dragonite', U2, 'multiscale', ['splash'], 100)], moves: ONE[0], turns: 1, fx: FULL('titans_forge')}), '|-status|');
  assert.ok(burns >= 2 && burns <= 12, `a 25% burn chance: ${burns} of ${SEEDS.length}`);
  const weak = await battle({teams: [BL(['surf'], 50, '60'), CH(['splash'], 100)], moves: ['move 1', 'move 1'], turns: 1, fx: FULL('titans_forge')});
  assert.strictEqual(lost(weak, 0), mon(weak, 0).maxhp - 60, 'below 60% HP the holder is not drained: ' + mon(weak, 0).hp);
});

test('49. gilded_ember: burns hurt more, KOs stack damage, Water hurts the holder more', async () => {
  const burn = fxp => duel(BL(['willowisp']), SN(), ONE, fxp);
  near(firstHit(await burn(FULL('gilded_ember')), 1) / firstHit(await burn(undefined), 1), 1.30, 0.04, 'burn damage +30%');
  const wet = fxp => duel(CH(['splash'], 100).replace(U2, U1), BL(['surf'], 100).replace(U1, U2), ONE, fxp);
  near(firstHit(await wet(FULL('gilded_ember')), 0) / firstHit(await wet(undefined), 0), 1.15, 0.03, 'Water hits the holder 15% harder');
  near(firstHit(await wet(FULL('gilded_ember', 100, 50)), 0) / firstHit(await wet(undefined), 0), 1.075, 0.03, 'a 50% drawback share halves it');
  const foes = [pack('Magikarp', U2, 'swiftswim', ['splash'], 5), pack('Snorlax', U3, 'immunity', ['splash'], 100)].join(']');
  const mine = [pack('Blastoise', U1, 'torrent', ['surf'], 100), pack('Charizard', U4, 'blaze', ['splash'], 100)].join(']');
  const script = [['move 1', 'move 1'], ['move 1', 'switch 2'], ['move 1', 'move 1']];
  const run = fxp => battle({teams: [mine, foes], moves: turn => script[turn], turns: 3, fx: fxp});
  const stack = async fxp => { const b = await run(fxp); return mon(b, 1).maxhp - hpTrail(b, 'p2a: ' + U3, mon(b, 1).maxhp)[0]; };
  const plainHit = await stack(undefined), fxHit = await stack(FULL('gilded_ember'));
  near(fxHit / plainHit, 1.10, 0.03, 'one KO stack adds 10% damage');
});

test('50. dying_bloom: poisons attackers below half HP, clutches at 1 HP, cuts healing, ramps poison', async () => {
  const poisoned = await battle({teams: [hurtBlastoise(120), MH(U2, ['karatechop'], 30)], moves: ['move 3', 'move 1'], turns: 1, fx: FULL('dying_bloom')});
  assert.strictEqual(mon(poisoned, 1).status, 'psn', 'struck below half HP: the attacker is poisoned');
  const healthy = await battle({teams: [hurtBlastoise(300), MH(U2, ['karatechop'], 30)], moves: ['move 3', 'move 1'], turns: 1, fx: FULL('dying_bloom')});
  assert.strictEqual(mon(healthy, 1).status, '', 'above half HP nothing is poisoned');
  const frail = [pack('Magikarp', U1, 'swiftswim', ['splash'], 50, '10'), INFLICTOR(['dragonclaw', 'splash'])];
  const held = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 1, fx: FULL('dying_bloom')});
  assert.strictEqual(mon(held, 0).hp, 1, 'the clutch leaves 1 HP');
  const heal = fxp => battle({teams: [hurtBlastoise(100), idleSnorlax()], moves: ['move 1', 'move 1'], turns: 1, fx: fxp});
  const plainHeal = mon(await heal(undefined), 0).hp - 100, cut = mon(await heal(FULL('dying_bloom')), 0).hp - 100;
  near(cut / plainHeal, 0.65, 0.02, 'healing is 35% lower');
  const gas = fxp => duel(BL(['poisongas']), SN(['splash'], 'thickfat'), [['move 1', 'move 1'], ['move 1', 'move 1'], ['move 1', 'move 1'], ['move 1', 'move 1']], fxp);
  const ticks = diffs(await gas(FULL('dying_bloom')), 1), native = diffs(await gas(undefined), 1);
  near(ticks[1] / native[1], 1.2, 0.04, 'poison ticks grow 20% a turn ...');
  near(ticks[2] / native[2], 1.4, 0.04, '... turn by turn');
});

test('51. martyrs_edge: physical hits bleed, bleeding foes take more, the clutch guarantees the next crit', async () => {
  const fight = fxp => duel(MH(U1, ['karatechop']), SN(), TWO, fxp);
  const plain = await fight(undefined), full = await fight(FULL('martyrs_edge'));
  assert.ok(count(full, 'is bleeding') > 0, 'a physical hit makes the foe bleed');
  assert.strictEqual(count(await duel(BL(['surf']), SN(), ONE, FULL('martyrs_edge')), 'is bleeding'), 0, 'a special move does not');
  near(diffs(full, 1)[2] / diffs(plain, 1)[1], 1.12, 0.04, 'a bleeding foe takes 12% more from the holder');
  const crits = await across(() => ({teams: [MH(U1, ['karatechop'], 50, '10'), CH(['dragonclaw', 'splash'])], moves: ['move 1', 'move 1'], turns: 1, fx: FULL('martyrs_edge')}), '|-crit|');
  assert.strictEqual(crits, SEEDS.length, `the holder's next hit is always a critical hit: ${crits} of ${SEEDS.length}`);
  const survivor = await battle({teams: [MH(U1, ['karatechop'], 50, '10'), CH(['dragonclaw', 'splash'])], moves: ['move 1', 'move 1'], turns: 1, fx: FULL('martyrs_edge')});
  assert.strictEqual(mon(survivor, 0).hp, 1, 'it holds at 1 HP');
});

test('52. colossus_vow: super-effective hits are softer and countered; the clutch holds; the holder is slower', async () => {
  const fight = fxp => duel(CH(['splash'], 100).replace(U2, U1), BL(['surf'], 100).replace(U1, U2), ONE, fxp);
  const plain = await fight(undefined), full = await fight(FULL('colossus_vow'));
  near(firstHit(full, 0) / firstHit(plain, 0), 0.85, 0.03, 'a super-effective hit is 15% softer');
  assert.strictEqual(lost(full, 1), Math.floor(mon(full, 0).maxhp * 0.06), 'and the attacker takes 6% of the holder max HP back');
  assert.strictEqual(lost(plain, 1), 0);
  const speed = b => mon(b, 0).getStat('spe');
  near(speed(full) / speed(plain), 0.85, 0.02, 'Speed is 15% lower');
  const frail = [pack('Magikarp', U1, 'swiftswim', ['splash'], 50, '10'), INFLICTOR(['dragonclaw', 'splash'])];
  assert.strictEqual(mon(await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 1, fx: FULL('colossus_vow')}), 0).hp, 1, 'the clutch leaves 1 HP');
});

test('53. last_gamble: the clutch leaves 30% HP and powers two moves, then the holder takes more damage', async () => {
  const mine = hp => pack('Snorlax', U1, 'immunity', ['tackle', 'splash'], 100, hp);
  const foe = CH(['dragonclaw', 'splash'], 100);
  const script = [['move 1', 'move 1'], ['move 1', 'move 1'], ['move 1', 'move 2']];
  const gamble = await duel(mine('10'), foe, script, FULL('last_gamble'));
  const plain = await duel(mine(''), foe, script, undefined);
  const max = mon(gamble, 0).maxhp;
  const g = trail(gamble, 0), p = trail(plain, 0);
  assert.strictEqual(g[0], Math.floor(max * 0.30), 'the lethal hit leaves 30% of max HP');
  assert.ok(g.length >= 2 && p.length >= 2, JSON.stringify({g, p, max}));
  const hitG = g[0] - g[1], hitP = p[0] - p[1];
  near(hitG / hitP, 1.25, 0.04, 'after the clutch the holder takes 25% more');
  const foeG = diffs(gamble, 1), foeP = diffs(plain, 1);
  near(foeG[0] / foeP[0], 1.30, 0.04, 'the first move after is 30% stronger');
  near(foeG[1] / foeP[1], 1.30, 0.04, 'the second move too');
  near(foeG[2] / foeP[2], 1.00, 0.03, 'the third is back to normal');
});

test('54. miasma_front: weather drains every foe at a rising rate and boosts poison; no weather drains the holder', async () => {
  const rainy = fxp => duel(BL(['raindance', 'splash']), SN(), [['move 1', 'move 1'], ['move 2', 'move 1'], ['move 2', 'move 1']], fxp);
  const plain = await rainy(undefined), full = await rainy(FULL('miasma_front'));
  const max = mon(full, 1).maxhp;
  const expected = [2.5, 3.5, 4.5].reduce((sum, pct) => sum + Math.floor(max * pct / 100), 0);
  assert.strictEqual(lost(full, 1) - lost(plain, 1), expected, 'the foe loses 2.5%, then 3.5%, then 4.5% of its max HP');
  const dry = await duel(BL(['splash']), SN(), TWO, FULL('miasma_front'));
  assert.strictEqual(lost(dry, 0), 2 * Math.floor(mon(dry, 0).maxhp * 0.02), 'with no weather the holder loses 2% of max HP a turn');
  assert.strictEqual(lost(dry, 1), 0, 'and the foe loses nothing');
  const gas = fxp => duel(BL(['poisongas']), SN(['splash'], 'thickfat'), ONE, fxp);
  near(firstHit(await gas(FULL('miasma_front')), 1) / firstHit(await gas(undefined), 1), 1.20, 0.04, 'poison ticks the holder inflicts are 20% stronger');
});

test('55. festering_gash: bleeds grow, heal the holder from what they deal, and Psychic hurts more', async () => {
  const fight = fxp => duel(MH(U1, ['karatechop', 'splash'], 50, '100'), SN(), [['move 1', 'move 1'], ['move 2', 'move 1'], ['move 2', 'move 1']], fxp);
  const full = await fight(FULL('festering_gash'));
  const d = diffs(full, 1).slice(1);   // the bleed ticks after the hit
  assert.ok(d.length >= 3, 'three bleed ticks: ' + JSON.stringify(d));
  near(d[1] / d[0], 1.15, 0.04, 'the bleed grows 15% a turn');
  near(d[2] / d[0], 1.30, 0.04, 'and again');
  assert.strictEqual(mon(full, 0).hp - 100, d.reduce((sum, x) => sum + Math.floor(x * 0.25), 0), 'the holder heals 25% of the bleed damage');
  const psychic = fxp => duel(SN(['splash']).replace(U2, U1), pack('Alakazam', U2, 'magicguard', ['psychic'], 100), ONE, fxp);
  near(firstHit(await psychic(FULL('festering_gash')), 0) / firstHit(await psychic(undefined), 0), 1.20, 0.03, 'Psychic hits the holder 20% harder');
});

test('56. blighted_giant: super-effective hits add max HP damage and poison, poison hits harder, healing is cut', async () => {
  const fight = fxp => duel(BL(['surf']), CH(['splash'], 100), ONE, fxp);
  const plain = await fight(undefined), full = await fight(FULL('blighted_giant'));
  assert.strictEqual(firstHit(full, 1) - firstHit(plain, 1), Math.floor(mon(full, 0).maxhp * 0.07), 'adds 7% of max HP');
  assert.strictEqual(mon(full, 1).status, 'psn', 'and poisons the foe');
  const tick = diffs(full, 1)[1];
  near(tick / Math.floor(mon(full, 1).maxhp / 8), 1.25, 0.03, 'its poison is 25% stronger');
  const heal = fxp => battle({teams: [hurtBlastoise(100), idleSnorlax()], moves: ['move 1', 'move 1'], turns: 1, fx: fxp});
  const gain = b => mon(b, 0).hp - 100;
  near(gain(await heal(FULL('blighted_giant'))) / gain(await heal(undefined)), 0.40, 0.02, 'healing is 60% lower');
});

test('57. fortunes_rot: foes it poisoned bleed extra HP and the holder takes more damage', async () => {
  const gas = fxp => duel(BL(['poisongas']), SN(['splash'], 'thickfat'), TWO, fxp);
  const plain = await gas(undefined), full = await gas(FULL('fortunes_rot'));
  assert.strictEqual(lost(full, 1) - lost(plain, 1), 2 * Math.floor(mon(full, 1).maxhp * 0.03), 'two turns of an extra 3% on a poisoned foe');
  const hit = fxp => duel(BL(['splash']), MH(U2, ['karatechop'], 50), ONE, fxp);
  near(firstHit(await hit(FULL('fortunes_rot')), 0) / firstHit(await hit(undefined), 0), 1.20, 0.03, 'the holder takes 20% more damage');
});

test('58. lightning_rend: weather and Electric moves hit harder and bleed; with no weather direct damage drops', async () => {
  const bolt = fxp => duel(BL(['thunderbolt']), SN(), ONE, fxp);
  const plain = await bolt(undefined);
  near(firstHit(await bolt(FULL('lightning_rend', 100, 50)), 1) / firstHit(plain, 1), 1.125, 0.04, 'Electric +25%, no weather -10% at a half drawback');
  assert.ok(count(await bolt(FULL('lightning_rend')), 'is bleeding') > 0, 'an Electric hit makes the foe bleed');
  assert.strictEqual(count(await duel(BL(['flamethrower']), SN(), ONE, FULL('lightning_rend')), 'is bleeding'), 0, 'a Fire move does not');
  const rain = fxp => duel(BL(['raindance', 'surf']), SN(), [['move 1', 'move 1'], ['move 2', 'move 1']], fxp);
  near(firstHit(await rain(FULL('lightning_rend')), 1) / firstHit(await rain(undefined), 1), 1.25, 0.04, 'a weather move in weather is 25% stronger with no penalty');
});

test('59. tempest_colossus: weather moves and super-effective hits hit harder, Speed drops', async () => {
  const rain = fxp => duel(BL(['raindance', 'surf']), SN(), [['move 1', 'move 1'], ['move 2', 'move 1']], fxp);
  near(firstHit(await rain(FULL('tempest_colossus')), 1) / firstHit(await rain(undefined), 1), 1.20, 0.04, 'a weather move is 20% stronger');
  const se = fxp => duel(BL(['surf']), CH(['splash'], 100), ONE, fxp);
  const full = await se(FULL('tempest_colossus')), plain = await se(undefined);
  assert.strictEqual(firstHit(full, 1) - firstHit(plain, 1), Math.floor(mon(full, 0).maxhp * 0.07), 'a super-effective hit adds 7% of max HP');
  near(mon(full, 0).getStat('spe') / mon(plain, 0).getStat('spe'), 0.88, 0.02, 'Speed is 12% lower');
});

test('60. skyfall_fortune: weather moves hit harder, KOs under weather stack damage, no weather hurts', async () => {
  const rain = fxp => duel(BL(['raindance', 'surf']), SN(), [['move 1', 'move 1'], ['move 2', 'move 1']], fxp);
  near(firstHit(await rain(FULL('skyfall_fortune')), 1) / firstHit(await rain(undefined), 1), 1.25, 0.04, 'a weather move is 25% stronger');
  const foes = [pack('Magikarp', U2, 'swiftswim', ['splash'], 5), pack('Snorlax', U3, 'immunity', ['splash'], 100)].join(']');
  const mine = pack('Blastoise', U1, 'torrent', ['raindance', 'surf'], 100);
  const script = [['move 1', 'move 1'], ['move 2', 'move 1'], ['move 2', 'switch 2'], ['move 2', 'move 1']];
  const run = fxp => battle({teams: [mine, foes], moves: turn => script[turn], turns: 4, fx: fxp});
  const hit = async fxp => { const b = await run(fxp); return mon(b, 1).maxhp - hpTrail(b, 'p2a: ' + U3, mon(b, 1).maxhp)[0]; };
  near(await hit(FULL('skyfall_fortune')) / await hit(undefined), 1.25 * 1.12, 0.06, 'a weather move after one KO in weather');
  const dry = fxp => duel(BL(['splash']), MH(U2, ['karatechop'], 50), ONE, fxp);
  near(firstHit(await dry(FULL('skyfall_fortune')), 0) / firstHit(await dry(undefined), 0), 1.20, 0.03, 'with no weather the holder takes 20% more');
});

test('61. breakers_might: physical hits bleed, super-effective hits add max HP damage, healing is halved', async () => {
  const fight = fxp => duel(MH(U1, ['karatechop']), SN(), TWO, fxp);
  const plain = await fight(undefined), full = await fight(FULL('breakers_might'));
  assert.ok(count(full, 'is bleeding') > 0, 'a physical hit makes the foe bleed');
  const add = Math.floor(mon(full, 0).maxhp * 0.08);
  assert.strictEqual(firstHit(full, 1) - firstHit(plain, 1), add, 'the first hit adds 8% of max HP');
  near(diffs(full, 1)[2], diffs(plain, 1)[1] * 1.10 + add, 4, 'against a bleeding foe 10% more on top');
  const heal = fxp => battle({teams: [hurtBlastoise(100), idleSnorlax()], moves: ['move 1', 'move 1'], turns: 1, fx: fxp});
  const gain = b => mon(b, 0).hp - 100;
  near(gain(await heal(FULL('breakers_might'))) / gain(await heal(undefined)), 0.50, 0.02, 'healing is halved');
});

test('62. wager_of_blood: physical hits bleed and cost HP, a KO restores HP', async () => {
  const fight = fxp => duel(MH(U1, ['karatechop']), SN(), ONE, fxp);
  const full = await fight(FULL('wager_of_blood'));
  assert.ok(count(full, 'is bleeding') > 0, 'a physical hit makes the foe bleed');
  assert.strictEqual(lost(full, 0), Math.floor(mon(full, 0).maxhp * 0.02), 'every physical move costs 2% of max HP');
  const half = await fight(FULL('wager_of_blood', 100, 50));
  assert.strictEqual(lost(half, 0), Math.floor(mon(half, 0).maxhp * 0.02 * 0.5), 'a 50% drawback share halves it');
  const prey = [pack('Magikarp', U2, 'swiftswim', ['splash'], 5), idleSnorlax()].join(']');
  const ko = await duel(MH(U1, ['karatechop'], 100, '100'), prey, ONE, FULL('wager_of_blood'));
  const max = mon(ko, 0).maxhp;
  assert.strictEqual(mon(ko, 0).hp, 100 - Math.floor(max * 0.02) + Math.floor(max * 0.10), 'a KO restores 10% of max HP');
});

// ---- every signature with every motif twist (525 battles, a few minutes): node ascension_fx_test.js --matrix ----
test('63. matrix: every signature with every motif twist runs, applies and stays inside the damage caps', async () => {
  if (!args.includes('--matrix')) return;
  const book = JSON.parse(require('fs').readFileSync(require('path').join(__dirname, '..', '..', 'design', 'transcendents.json'), 'utf8'));
  const twists = Object.entries(book.twists);
  assert.strictEqual(twists.length, 25, 'twenty-five motif twists');
  assert.strictEqual(Object.keys(UNIQUE_PAIRS).length, 21, 'twenty-one signatures');
  const plain = await duel(BL(['surf', 'thunderbolt']), SN(), TWO, undefined);
  const ceiling = lost(plain, 1) * 3 + mon(plain, 1).maxhp * 0.5;
  for (const sg of Object.keys(UNIQUE_PAIRS)) {
    for (const [motif, twist] of twists) {
      const b = await duel(BL(['surf', 'thunderbolt']), SN(), TWO, FULL(sg, 100, 100, twist.effects));
      const where = sg + ' + ' + motif;
      assert.ok(!b.lines.some(l => l.includes('could not be applied')), where + ': the payload was applied');
      assert.ok(b.lines.some(l => l.startsWith('|turn|2')) || b.battle.ended, where + ': the battle advanced');
      assert.ok(lost(b, 1) <= ceiling, `${where}: the foe lost ${lost(b, 1)} HP, over the ${ceiling} ceiling`);
      assert.ok(mon(b, 0).hp >= 0 && mon(b, 1).hp >= 0, where + ': HP stays in range');
    }
  }
});

// ---- Hauntings (docs/HALLOWEEN-EVENT-DESIGN.md): ten event-only powers, one per Pokemon ----
const HOLDER = (moves, health = '') => pack('Blastoise', U1, 'torrent', moves, 50, health);
const SNORLAX = (moves = ['splash'], health = '') => pack('Snorlax', U2, 'immunity', moves, 50, health);
const hauntFx = (id, pct = 1) => withPct(U1, id, pct);

test('H1. haunting wisp: a hit may make a slower target flinch, at the rolled chance and never above the cap', async () => {
  const make = fxPayload => () => ({teams: [HOLDER(['hydropump']), SNORLAX(['tackle'])], moves: ['move 1', 'move 1'], fx: fxPayload});
  const flinched = await across(make(hauntFx('wisp', 50)), '|cant|p2a: ' + U2 + '|flinch');
  assert.ok(flinched >= 4 && flinched < SEEDS.length, `flinched in ${flinched} of ${SEEDS.length} seeds`);
  assert.strictEqual(await across(make(undefined), '|cant|p2a: ' + U2 + '|flinch'), 0, 'native hydropump never flinches');
  const capped = await across(make(hauntFx('wisp', 100)), '|cant|p2a: ' + U2 + '|flinch');
  assert.ok(capped < SEEDS.length, 'a chance sent above the cap is held to 50%');
});

test('H2. haunting hex: a foe with a status loses 1/16 of its max HP at the end of the turn, and a healthy foe does not', async () => {
  const run = (fxPayload, foeMoves) => battle({teams: [HOLDER(['spore', 'splash']), SNORLAX(foeMoves)], moves: ['move 1', 'move 1'], turns: 1, fx: fxPayload});
  const plain = await run(undefined, ['splash']);
  const hexed = await run(hauntFx('hex'), ['splash']);
  assert.strictEqual(mon(plain, 1).status, 'slp', 'the sleep landed');
  assert.strictEqual(lost(hexed, 1) - lost(plain, 1), Math.floor(mon(hexed, 1).baseMaxhp / 16), 'exactly 1/16 more');
  const healthy = await battle({teams: [HOLDER(['splash']), SNORLAX()], moves: ['move 1', 'move 1'], turns: 1, fx: hauntFx('hex')});
  assert.strictEqual(lost(healthy, 1), 0, 'a healthy, un-dropped foe takes nothing');
});

test('H3. haunting spectral_veil: Normal and Fighting moves hurt the holder 10% less, other types are untouched', async () => {
  const hit = (move, fxPayload) => battle({teams: [HOLDER(['splash']), pack('Charizard', U2, 'blaze', [move], 50)], moves: ['move 1', 'move 1'], turns: 1, fx: fxPayload});
  for (const move of ['return', 'closecombat']) {
    const base = await hit(move, undefined);
    const veiled = await hit(move, hauntFx('spectral_veil', 10));
    near(lost(veiled, 0) / lost(base, 0), 0.9, 0.03, move + ' is reduced by about 10%');
  }
  const fire = await hit('flamethrower', undefined);
  assert.strictEqual(lost(await hit('flamethrower', hauntFx('spectral_veil', 10)), 0), lost(fire, 0), 'a Fire move is untouched');
});

test('H4. haunting midnight_howl: only the first attack of the battle is 15% stronger', async () => {
  const run = (fxPayload, turns) => battle({teams: [HOLDER(['surf', 'splash']), SNORLAX(['splash'])], moves: ['move 1', 'move 1'], turns, fx: fxPayload});
  near(lost(await run(hauntFx('midnight_howl', 15), 1), 1) / lost(await run(undefined, 1), 1), 1.15, 0.03, 'first attack');
  const two = await run(hauntFx('midnight_howl', 15), 2);
  const twoPlain = await run(undefined, 2);
  const second = lost(two, 1) - lost(await run(hauntFx('midnight_howl', 15), 1), 1);
  const secondPlain = lost(twoPlain, 1) - lost(await run(undefined, 1), 1);
  near(second / secondPlain, 1.0, 0.04, 'the second attack is native');
});

test('H5. haunting pumpkin_lantern: entering the field restores 1/8 of max HP', async () => {
  const team = [CHARIZARD_U(U3), HOLDER(['splash'], '20')].join(']');
  const b = await battle({teams: [team, SNORLAX()], moves: ['switch 2', 'move 1'], turns: 1, fx: hauntFx('pumpkin_lantern')});
  assert.strictEqual(mon(b, 0).hp, 20 + Math.floor(mon(b, 0).maxhp / 8));
  const plain = await battle({teams: [team, SNORLAX()], moves: ['switch 2', 'move 1'], turns: 1});
  assert.strictEqual(mon(plain, 0).hp, 20, 'no Haunting, no heal');
});

test('H6. haunting trick_or_treat: the first hit each battle restores 1/8 of max HP, the second does not', async () => {
  const hits = (turns, fxPayload) => battle({teams: [HOLDER(['splash']), pack('Snorlax', U2, 'immunity', ['tackle'], 50)], moves: ['move 1', 'move 1'], turns, fx: fxPayload});
  const once = count(await hits(1, hauntFx('trick_or_treat')), '|-heal|p1a: ' + U1);
  assert.ok(once > 0, 'the first hit healed');
  assert.strictEqual(count(await hits(2, hauntFx('trick_or_treat')), '|-heal|p1a: ' + U1), once, 'the second hit did not');
  const lines = (await hits(1, hauntFx('trick_or_treat'))).lines.filter(l => /^\|-(heal|damage)\|p1a/.test(l));
  assert.ok(lines[0].startsWith('|-damage'), 'the heal is logged after the hit it answers: ' + lines.slice(0, 2).join(' // '));
  assert.strictEqual(count(await battle({teams: [HOLDER(['splash']), pack('Snorlax', U2, 'immunity', ['tackle'], 50)], moves: ['move 1', 'move 1'], turns: 2}), '|-heal|p1a: ' + U1), 0);
});

test('H7. haunting cobweb: entering the field lowers each foe\'s Speed one stage', async () => {
  const b = await battle({teams: [HOLDER(['splash']), SNORLAX()], moves: ['move 1', 'move 1'], turns: 1, fx: hauntFx('cobweb')});
  assert.ok(count(b, '|-unboost|p2a: ' + U2 + '|spe|1') >= 1, 'the foe was slowed on entry');
  const none = await battle({teams: [HOLDER(['splash']), SNORLAX()], moves: ['move 1', 'move 1'], turns: 1});
  assert.strictEqual(count(none, '|-unboost|'), 0);
});

test('H8. haunting graveyard_chill: a foe that makes contact loses 1/16 of its max HP, a non-contact move costs it nothing', async () => {
  const hit = (move, fxPayload) => battle({teams: [HOLDER(['splash']), pack('Charizard', U2, 'blaze', [move], 50)], moves: ['move 1', 'move 1'], turns: 1, fx: fxPayload});
  const contact = await hit('dragonclaw', hauntFx('graveyard_chill'));
  assert.strictEqual(lost(contact, 1), Math.floor(mon(contact, 1).baseMaxhp / 16));
  assert.strictEqual(lost(await hit('flamethrower', hauntFx('graveyard_chill')), 1), 0, 'flamethrower is not a contact move');
  assert.strictEqual(lost(await hit('dragonclaw', undefined), 1), 0);
});

test('H9. haunting headless_rider: a lethal foe move leaves 1 HP and raises Attack and Sp. Atk, once per battle', async () => {
  const frail = [pack('Blastoise', U1, 'torrent', ['splash'], 50, '10'), INFLICTOR(['flamethrower', 'splash'])];
  const first = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 1, fx: hauntFx('headless_rider')});
  assert.strictEqual(mon(first, 0).hp, 1, 'survived at 1 HP');
  assert.ok(count(first, '|-boost|p1a: ' + U1 + '|atk|1') >= 1 && count(first, '|-boost|p1a: ' + U1 + '|spa|1') >= 1, 'both stats rose');
  const second = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 2, fx: hauntFx('headless_rider')});
  assert.strictEqual(mon(second, 0).hp, 0, 'the second lethal hit is not stopped');
  const plain = await battle({teams: frail, moves: ['move 1', 'move 1'], turns: 1});
  assert.strictEqual(mon(plain, 0).hp, 0, 'without it the first hit is lethal');
});

test('H10. haunting reapers_toll: a KO heals 1/3 of max HP and raises the higher attacking stat', async () => {
  const teams = [pack('Blastoise', U1, 'torrent', ['hydropump', 'splash'], 50, '100'), [pack('Magikarp', U2, 'swiftswim', ['splash'], 50, '1'), pack('Magikarp', U3, 'swiftswim', ['splash'], 50)].join(']')];
  const b = await battle({teams, moves: ['move 1', 'move 1'], turns: 1, fx: hauntFx('reapers_toll')});
  const holder = mon(b, 0);
  const stat = holder.storedStats.spa > holder.storedStats.atk ? 'spa' : 'atk';
  assert.ok(count(b, '|faint|p2a') >= 1, 'the foe fainted');
  assert.strictEqual(holder.hp, 100 + Math.floor(holder.maxhp / 3), 'healed a third');
  assert.ok(count(b, '|-boost|p1a: ' + U1 + '|' + stat + '|1') >= 1, 'the better attacking stat rose');
  const plain = await battle({teams, moves: ['move 1', 'move 1'], turns: 1});
  assert.strictEqual(mon(plain, 0).hp, 100, 'no Haunting, no heal');
});

test('H11. hauntings: an unknown or malformed haunting is ignored and a payload may hold a Haunting beside a full set of affixes', async () => {
  const same = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: [{i: 'not_a_haunting', p: 5}]}}});
  assert.strictEqual(lost(same, 1), lost(baseline, 1));
  const ids = ['overwhelming_force', 'physical_force', 'special_force', 'healthy_force', 'last_stand', 'defiant_force', 'kindred_force', 'executioner', 'midnight_howl'];
  const b = await battle({teams: TEAMS(), moves: ['move 3', 'move 1'], fx: {v: 1, mons: {[U1]: ids.map(id => ({i: id, p: 5}))}}});
  assert.ok(lost(b, 1) > lost(baseline, 1), 'the ninth and tenth effects are no longer cut off');
});

// ---- the affix expansion (docs/AFFIX-EXPANSION-DESIGN.md): 20 base affixes ----
/** The exact HP values (not the spectator's /100 ones) the log shows for side `side`'s active Pokemon after each damage line. */
const exactHits = (b, side) => {
  const max = String(mon(b, side).maxhp);
  return b.lines.filter(l => l.startsWith(`|-damage|p${side + 1}a: `)).map(l => l.split('|')[3].split(' ')[0])
    .filter(hp => hp.split('/')[1] === max).map(hp => Number(hp.split('/')[0]));
};
/** Damage per hit from a trail of HP values, starting at full HP. */
const deltas = (b, side) => {
  const trail = [mon(b, side).maxhp, ...exactHits(b, side)];
  return trail.slice(1).map((hp, i) => trail[i] - hp);
};
const LAX = (uuid, moves = ['splash'], level = 100, health = '') => pack('Snorlax', uuid, 'immunity', moves, level, health);
const BLAST = (moves, health = '', level = 50) => pack('Blastoise', U1, 'torrent', moves, level, health);

test('E1. ambush: more damage to a target that entered this turn or last, not later', async () => {
  const teams = [BLAST(['surf']), LAX(U2)];
  const plain = await battle({teams, moves: ['move 1', 'move 1'], turns: 3});
  const amb = await battle({teams, moves: ['move 1', 'move 1'], turns: 3, fx: withPct(U1, 'ambush', 30)});
  const p = deltas(plain, 1), a = deltas(amb, 1);
  assert.ok(p.length >= 3 && a.length >= 3, JSON.stringify({p, a}));
  assert.ok(a[0] > p[0], `turn 1 boosted: ${a[0]} vs ${p[0]}`);
  assert.strictEqual(a[2], p[2], 'turn 3: the target has been in for two turns');
});

test('E2. brawler and sharpshooter: contact and non-contact moves, never both', async () => {
  const run = (move, id) => battle({teams: [BLAST(['aquatail', 'surf']), LAX(U2)], moves: [move, 'move 1'], fx: id && withPct(U1, id, 30)});
  assert.ok(lost(await run('move 1', 'brawler'), 1) > lost(await run('move 1'), 1), 'Aqua Tail makes contact');
  assert.strictEqual(lost(await run('move 2', 'brawler'), 1), lost(await run('move 2'), 1), 'Surf does not');
  assert.ok(lost(await run('move 2', 'sharpshooter'), 1) > lost(await run('move 2'), 1), 'Surf is boosted by Sharpshooter');
  assert.strictEqual(lost(await run('move 1', 'sharpshooter'), 1), lost(await run('move 1'), 1), 'Aqua Tail is not');
});

test('E3. giant_slayer: only against a target with more max HP than the holder', async () => {
  const run = (foe, id) => battle({teams: [BLAST(['surf']), foe], moves: ['move 1', 'move 1'], fx: id && withPct(U1, id, 30)});
  const big = LAX(U2);
  assert.ok(lost(await run(big, 'giant_slayer'), 1) > lost(await run(big), 1), 'Snorlax at level 100 is the giant');
  const small = pack('Charizard', U2, 'blaze', ['splash'], 50);
  assert.strictEqual(lost(await run(small, 'giant_slayer'), 1), lost(await run(small), 1), 'Charizard at 50 is not');
});

test('E4. relentless: the same move on consecutive turns stacks; changing moves resets it', async () => {
  const teams = [BLAST(['surf', 'hydropump']), LAX(U2)];
  const plain = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 3}), 1);
  const rel = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 3, fx: withPct(U1, 'relentless', 20)}), 1);
  assert.strictEqual(rel[0], plain[0], 'the first use has no stack');
  assert.ok(rel[1] > plain[1] && rel[2] > plain[2], JSON.stringify({rel, plain}));
  assert.ok(rel[2] / plain[2] > rel[1] / plain[1], 'the third use stacks higher than the second');
  const swap = turn => (turn % 2 === 0 ? ['move 1', 'move 1'] : ['move 2', 'move 1']);
  const plainSwap = deltas(await battle({teams, moves: swap, turns: 3}), 1);
  const relSwap = deltas(await battle({teams, moves: swap, turns: 3, fx: withPct(U1, 'relentless', 20)}), 1);
  assert.deepStrictEqual(relSwap, plainSwap, 'alternating moves never stacks');
});

test('E5. exploit_weakness: more damage to a target with a lowered stat', async () => {
  const teams = [BLAST(['surf']), LAX(U2, ['curse'])];   // Curse lowers Snorlax's own Speed (and raises Attack and Defense in both runs)
  const plain = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 2}), 1);
  const ex = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 2, fx: withPct(U1, 'exploit_weakness', 30)}), 1);
  assert.strictEqual(ex[0], plain[0], 'nothing lowered before the first hit');
  assert.ok(ex[1] > plain[1], `Speed lowered before the second: ${ex[1]} vs ${plain[1]}`);
});

test('E6. armorbreak: a hit may lower Defense by one stage; Clear Body and Shield Dust still block it', async () => {
  const hydro = target => () => ({teams: [BLASTOISE(), target], moves: ['move 1', 'move 1'], fx: withPct(U1, 'armorbreak', 50)});
  const broke = await across(hydro(pack('Charizard', U2, 'blaze', ['splash'], 50)), '|-unboost|p2a: ' + U2 + '|def|1');
  assert.ok(broke >= 4, `cracked in ${broke} of ${SEEDS.length} seeds`);
  assert.strictEqual(await across(hydro(pack('Metagross', U2, 'clearbody', ['splash'], 50)), '|-unboost|'), 0, 'Clear Body');
  assert.strictEqual(await across(hydro(pack('Vivillon', U2, 'shielddust', ['splash'], 50)), '|-unboost|'), 0, 'Shield Dust');
});

test('E7. pressure_point: critical hits deal more, other hits do not', async () => {
  const run = (move, id) => battle({teams: [BLAST(['frostbreath', 'surf']), LAX(U2)], moves: [move, 'move 1'], fx: id && withPct(U1, id, 30)});
  const crit = await run('move 1', 'pressure_point');
  assert.ok(count(crit, '|-crit|') >= 1, 'Frost Breath always crits');
  const ratio = lost(crit, 1) / lost(await run('move 1'), 1);
  assert.ok(Math.abs(ratio - 1.3) < 0.03, 'about 1.3x on a crit: ' + ratio);
  const normal = await run('move 2', 'pressure_point');
  assert.strictEqual(count(normal, '|-crit|'), 0);
  assert.strictEqual(lost(normal, 1), lost(await run('move 2'), 1), 'no crit, no bonus');
});

test('E8. searing_touch: contact hits may burn; non-contact never; a Fire type never', async () => {
  const burns = (moves, foe) => across(() => ({teams: [BLAST(moves), foe], moves: ['move 1', 'move 1'], fx: withPct(U1, 'searing_touch', 50)}), '|-status|p2a: ' + U2 + '|brn');
  assert.ok(await burns(['aquatail'], LAX(U2)) >= 4, 'Aqua Tail burns at the rolled chance');
  assert.strictEqual(await burns(['surf'], LAX(U2)), 0, 'Surf is not contact');
  assert.strictEqual(await burns(['aquatail'], pack('Charizard', U2, 'blaze', ['splash'], 50)), 0, 'Fire types cannot be burned');
});

test('E9. venom_touch: contact hits may poison (regular poison); Steel types never', async () => {
  const poisons = (foe, needle) => across(() => ({teams: [BLAST(['aquatail']), foe], moves: ['move 1', 'move 1'], fx: withPct(U1, 'venom_touch', 50)}), needle);
  const thick = pack('Snorlax', U2, 'thickfat', ['splash'], 100);   // not Immunity, which blocks poison (that rule is the engine's, and it holds)
  assert.ok(await poisons(thick, '|-status|p2a: ' + U2 + '|psn') >= 4, 'poisons at the rolled chance');
  assert.strictEqual(await poisons(thick, '|tox'), 0, 'never Toxic');
  assert.strictEqual(await poisons(LAX(U2), '|-status|'), 0, 'Immunity still blocks it');
  assert.strictEqual(await poisons(pack('Metagross', U2, 'clearbody', ['splash'], 50), '|-status|'), 0, 'Steel types cannot be poisoned');
});

test('E10. siphon: the holder heals a share of the damage its move deals', async () => {
  const teams = [BLAST(['surf'], '60'), LAX(U2)];
  const b = await battle({teams, moves: ['move 1', 'move 1'], fx: withPct(U1, 'siphon', 20)});
  const plain = await battle({teams, moves: ['move 1', 'move 1']});
  assert.strictEqual(mon(plain, 0).hp, 60, 'no healing natively');
  assert.strictEqual(mon(b, 0).hp, 60 + Math.floor(lost(b, 1) * 20 / 100), 'healed 20% of ' + lost(b, 1));
});

test('E11. thorns: a contact attacker loses a share of its max HP; a non-contact one does not', async () => {
  const run = (move, id) => battle({teams: [BLAST(['splash']), LAX(U2, ['bodyslam', 'hypervoice'])], moves: ['move 1', move], fx: id && withPct(U1, id, 10)});
  const thorned = await run('move 1', 'thorns');
  assert.strictEqual(mon(thorned, 1).hp, mon(thorned, 1).maxhp - Math.floor(mon(thorned, 1).maxhp / 10), 'Body Slam costs 10%');
  assert.strictEqual(lost(await run('move 1'), 1), 0, 'nothing natively');
  assert.strictEqual(lost(await run('move 2', 'thorns'), 1), 0, 'Hyper Voice is not contact');
  const capped = await battle({teams: [BLAST(['splash']), LAX(U2, ['bodyslam'])], moves: ['move 1', 'move 1'], fx: withPct(U1, 'thorns', 60)});
  assert.strictEqual(lost(capped, 1), Math.floor(mon(capped, 1).maxhp * 17 / 100), 'capped at 17% a hit');
});

test('E12. purging: a chance each turn to cure the holder\'s own status', async () => {
  const run = (payload, seed) => battle({teams: [BLAST(['splash']), INFLICTOR(['willowisp', 'splash'])], moves: turn => ['move 1', turn === 0 ? 'move 1' : 'move 2'],
    turns: 3, seed: [seed, 2, 3, 4], fx: payload});
  let cured = 0, plainCured = 0, burned = 0;
  for (const s of SEEDS) {
    const plain = await run(undefined, s);
    if (count(plain, '|-status|p1a') === 0) continue;   // the burn missed this seed
    burned++;
    if (!mon(plain, 0).status) plainCured++;
    if (!mon(await run(withPct(U1, 'purging', 50), s), 0).status) cured++;
  }
  assert.ok(burned >= 10, 'burned in ' + burned);
  assert.strictEqual(plainCured, 0, 'a burn is not cured natively');
  assert.ok(cured >= 4, `purged in ${cured} of ${burned}`);
});

test('E13. fresh_legs: switching out heals the holder', async () => {
  const teams = [[BLAST(['splash'], '60'), CHARIZARD_U(U3)].join(']'), LAX(U2)];
  const run = payload => battle({teams, moves: ['switch 2', 'move 1'], fx: payload});
  const benched = b => b.battle.sides[0].pokemon.find(p => p.uuid === U1);
  assert.strictEqual(benched(await run(undefined)).hp, 60, 'no healing natively');
  const healed = benched(await run(withPct(U1, 'fresh_legs', 10)));
  assert.strictEqual(healed.hp, 60 + Math.floor(healed.maxhp / 10), 'healed 10% of max HP');
});

test('E14. stalwart: less damage from critical hits only', async () => {
  const run = (move, id) => battle({teams: [BLAST(['splash']), pack('Charizard', U2, 'blaze', ['frostbreath', 'flamethrower'], 50)],
    moves: ['move 1', move], fx: id && withPct(U1, id, 20)});
  assert.ok(lost(await run('move 1', 'stalwart'), 0) < lost(await run('move 1'), 0), 'Frost Breath crits');
  const flame = await run('move 2', 'stalwart');
  assert.strictEqual(count(flame, '|-crit|'), 0);
  assert.strictEqual(lost(flame, 0), lost(await run('move 2'), 0), 'no crit, no reduction');
});

test('E15. unyielding: a foe\'s stat drop may fail; the holder\'s own drops always apply', async () => {
  const growled = payload => across(() => ({teams: [BLAST(['splash']), INFLICTOR(['growl'])], moves: ['move 1', 'move 1'], fx: payload}), '|-unboost|p1a');
  const plain = await growled(undefined);
  const held = await growled(withPct(U1, 'unyielding', 50));
  assert.strictEqual(plain, SEEDS.length, 'Growl always lands natively');
  assert.ok(held <= plain - 4, `held in ${plain - held} of ${plain}`);
  const own = await across(() => ({teams: [BLAST(['superpower']), LAX(U2)], moves: ['move 1', 'move 1'], fx: withPct(U1, 'unyielding', 50)}), '|-unboost|p1a');
  assert.strictEqual(own, SEEDS.length, 'Superpower lowers its user every time');
});

test('E16. watchful: less damage from priority moves only', async () => {
  const run = (move, id) => battle({teams: [BLAST(['splash']), LAX(U2, ['quickattack', 'tackle'])], moves: ['move 1', move], fx: id && withPct(U1, id, 30)});
  assert.ok(lost(await run('move 1', 'watchful'), 0) < lost(await run('move 1'), 0), 'Quick Attack has +1 priority');
  assert.strictEqual(lost(await run('move 2', 'watchful'), 0), lost(await run('move 2'), 0), 'Tackle does not');
});

test('E17. resolute: less damage while one of the holder\'s stats is lowered', async () => {
  const teams = [BLAST(['splash']), INFLICTOR(['flamethrower', 'growl'])];
  const script = turn => ['move 1', turn === 0 ? 'move 1' : turn === 1 ? 'move 2' : 'move 1'];
  const plain = deltas(await battle({teams, moves: script, turns: 3}), 0);
  const res = deltas(await battle({teams, moves: script, turns: 3, fx: withPct(U1, 'resolute', 30)}), 0);
  assert.strictEqual(res[0], plain[0], 'nothing lowered for the first hit');
  assert.ok(res[1] < plain[1], `Attack lowered by Growl before the second: ${res[1]} vs ${plain[1]}`);
});

test('E18. insulated: less burn damage, direct hits unchanged', async () => {
  const teams = [BLAST(['splash']), INFLICTOR(['willowisp', 'splash'])];
  const script = turn => ['move 1', turn === 0 ? 'move 1' : 'move 2'];
  let checked = 0;
  for (const s of SEEDS.slice(0, 6)) {
    const plain = await battle({teams, moves: script, turns: 3, seed: [s, 2, 3, 4]});
    if (mon(plain, 0).status !== 'brn') continue;
    const ins = await battle({teams, moves: script, turns: 3, seed: [s, 2, 3, 4], fx: withPct(U1, 'insulated', 50)});
    assert.ok(lost(ins, 0) > 0 && lost(ins, 0) < lost(plain, 0), `burn hurts less: ${lost(ins, 0)} vs ${lost(plain, 0)}`);
    checked++;
  }
  assert.ok(checked >= 2, 'burned in ' + checked + ' seeds');
  const hit = teams2 => battle({teams: teams2, moves: ['move 1', 'move 1']});
  const direct = [BLAST(['splash']), INFLICTOR(['flamethrower'])];
  const directIns = await battle({teams: direct, moves: ['move 1', 'move 1'], fx: withPct(U1, 'insulated', 50)});
  assert.strictEqual(lost(directIns, 0), lost(await hit(direct), 0), 'a move hit is not residual');
});

test('E19. hardening: each enemy hit softens the next, up to three stacks', async () => {
  const teams = [LAX(U1, ['splash']), pack('Blastoise', U2, 'torrent', ['surf'], 75)];
  const plain = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 5}), 0);
  const hard = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 5, fx: withPct(U1, 'hardening', 10)}), 0);
  assert.ok(plain.length >= 5 && hard.length >= 5, JSON.stringify({plain, hard}));
  const ratio = i => hard[i] / plain[i];
  assert.strictEqual(hard[0], plain[0], 'the first hit is native');
  for (const [i, expected] of [[1, 0.9], [2, 0.8], [3, 0.7], [4, 0.7]]) {
    assert.ok(Math.abs(ratio(i) - expected) < 0.03, `hit ${i + 1}: ${ratio(i)} not about ${expected} ` + JSON.stringify({plain, hard}));
  }
});

test('E20. expansion: every new id is understood, and every new chance is bounded at 50', async () => {
  const catalog = JSON.parse(fs.readFileSync(AFFIXES, 'utf8')).affixes;
  const ids = ['ambush', 'brawler', 'sharpshooter', 'giant_slayer', 'relentless', 'exploit_weakness', 'armorbreak', 'pressure_point', 'searing_touch',
    'venom_touch', 'siphon', 'thorns', 'purging', 'fresh_legs', 'stalwart', 'unyielding', 'watchful', 'resolute', 'insulated', 'hardening'];
  for (const id of ids) {
    assert.ok(catalog.some(a => a.id === id), id + ' is in the catalog');
    const parsed = fx.parse(baseline.battle, {v: 1, mons: {[U1]: [{i: id, p: 100}]}});
    const list = parsed.byUuid.get(U1) || [];
    assert.strictEqual(list.length, 1, id + ' is handled');
    const chanceBased = ['armorbreak', 'searing_touch', 'venom_touch', 'purging', 'unyielding'].includes(id);
    assert.strictEqual(list[0].p, chanceBased ? 50 : 100, id);
  }
});

// ---- the second batch of Uniques (docs/AFFIX-EXPANSION-DESIGN.md, part B) ----
const uq = id => withPct(U1, id, 1);
const ratioNear = (actual, expected, tolerance, label) => assert.ok(Math.abs(actual - expected) <= tolerance, `${label}: ${actual.toFixed(3)} is not about ${expected}`);

test('U1. crescendo: weaker on the first two turns, then +8% a turn on the field', async () => {
  const teams = [BLAST(['surf']), LAX(U2)];
  const plain = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 4}), 1);
  const cres = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 4, fx: uq('crescendo')}), 1);
  ratioNear(cres[0] / plain[0], 0.80, 0.03, 'turn 1: drawback only');
  ratioNear(cres[1] / plain[1], 0.864, 0.03, 'turn 2: +8% and the drawback');
  ratioNear(cres[3] / plain[3], 1.24, 0.03, 'turn 4: +24%');
});

test('U2. nightfang: heals 25% of the damage dealt; Fire and Fairy moves hurt 30% more', async () => {
  const teams = [BLAST(['surf'], '60'), LAX(U2)];
  const b = await battle({teams, moves: ['move 1', 'move 1'], fx: uq('nightfang')});
  assert.strictEqual(mon(b, 0).hp, 60 + Math.floor(lost(b, 1) * 25 / 100), 'healed a quarter of ' + lost(b, 1));
  const fire = id => battle({teams: [BLAST(['splash']), INFLICTOR(['flamethrower'])], moves: ['move 1', 'move 1'], fx: id && uq(id)});
  ratioNear(lost(await fire('nightfang'), 0) / lost(await fire(), 0), 1.3, 0.04, 'Flamethrower');
});

test('U3. iron_maiden: contact attackers lose 1/8 of max HP; the holder\'s own contact moves deal less', async () => {
  const struck = await battle({teams: [BLAST(['splash']), LAX(U2, ['bodyslam'])], moves: ['move 1', 'move 1'], fx: uq('iron_maiden')});
  assert.strictEqual(lost(struck, 1), Math.floor(mon(struck, 1).maxhp * 12.5 / 100), 'Body Slam costs an eighth');
  const own = id => battle({teams: [BLAST(['aquatail']), LAX(U2)], moves: ['move 1', 'move 1'], fx: id && uq(id)});
  ratioNear(lost(await own('iron_maiden'), 1) / lost(await own(), 1), 0.8, 0.03, 'Aqua Tail');
});

test('U4. tailwind_heart: +50% Speed changes who moves first; Defense is lower', async () => {
  const first = b => b.lines.find(l => l.startsWith('|move|')).split('|')[2].slice(0, 3);
  const teams = [pack('Blastoise', U1, 'torrent', ['surf'], 50), pack('Charizard', U2, 'blaze', ['flamethrower'], 50)];
  assert.strictEqual(first(await battle({teams, moves: ['move 1', 'move 1']})), 'p2a');
  assert.strictEqual(first(await battle({teams, moves: ['move 1', 'move 1'], fx: uq('tailwind_heart')})), 'p1a');
  const hit = id => battle({teams: [BLAST(['splash']), LAX(U2, ['tackle'])], moves: ['move 1', 'move 1'], fx: id && uq(id)});
  assert.ok(lost(await hit('tailwind_heart'), 0) > lost(await hit(), 0), 'Tackle hurts more through lower Defense');
});

test('U5. huntmark: later moves against the marked foe deal 25% more; no critical hits', async () => {
  const teams = [BLAST(['surf']), LAX(U2)];
  const plain = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 2}), 1);
  const hunt = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 2, fx: uq('huntmark')}), 1);
  assert.strictEqual(hunt[0], plain[0], 'the first hit only marks');
  ratioNear(hunt[1] / plain[1], 1.25, 0.03, 'the second hit');
  const crit = await battle({teams: [BLAST(['frostbreath']), LAX(U2)], moves: ['move 1', 'move 1'], fx: uq('huntmark')});
  assert.strictEqual(count(crit, '|-crit|'), 0, 'even Frost Breath cannot crit');
});

test('U6. martyrs_gift: when the holder faints, the next ally heals half and gains Attack and Sp. Atk', async () => {
  const mine = [pack('Magikarp', U1, 'swiftswim', ['splash'], 50, '1'), pack('Blastoise', U3, 'torrent', ['splash'], 50, '60')].join(']');
  const run = payload => battle({teams: [mine, INFLICTOR(['flamethrower', 'splash'])], moves: turn => (turn === 0 ? ['move 1', 'move 1'] : ['switch 2', 'move 2']),
    turns: 2, fx: payload});
  const ally = b => b.battle.sides[0].pokemon.find(p => p.uuid === U3);
  const plain = ally(await run(undefined));
  assert.strictEqual(plain.hp, 60);
  const gifted = await run(uq('martyrs_gift'));
  assert.strictEqual(ally(gifted).hp, 60 + Math.floor(ally(gifted).maxhp / 2), 'healed half its max HP');
  assert.ok(count(gifted, '|-boost|p1a: ' + U3 + '|atk|1') >= 1 && count(gifted, '|-boost|p1a: ' + U3 + '|spa|1') >= 1, 'both stats rose');
});

test('U7. aegis_oath: 30% less damage taken and 30% less dealt', async () => {
  const hit = id => battle({teams: [BLAST(['surf']), LAX(U2, ['tackle'])], moves: ['move 1', 'move 1'], fx: id && uq(id)});
  const aegis = await hit('aegis_oath'), plain = await hit();
  ratioNear(lost(aegis, 0) / lost(plain, 0), 0.7, 0.04, 'taken');
  ratioNear(lost(aegis, 1) / lost(plain, 1), 0.7, 0.03, 'dealt');
});

test('U8. fickle_fortune: heads +40%, tails -20%, from the battle\'s own coin', async () => {
  let heads = 0, tails = 0;
  for (const s of SEEDS.slice(0, 10)) {
    const make = payload => battle({teams: [BLAST(['surf']), LAX(U2)], moves: ['move 1', 'move 1'], seed: [s, 2, 3, 4], fx: payload});
    const b = await make(uq('fickle_fortune'));
    const plain = await make(undefined);
    const ratio = lost(b, 1) / lost(plain, 1);
    if (count(b, 'heads!')) { heads++; ratioNear(ratio, 1.4, 0.05, 'heads'); } else { tails++; ratioNear(ratio, 0.8, 0.05, 'tails'); }
  }
  assert.ok(heads >= 2 && tails >= 2, `both faces came up: ${heads} heads, ${tails} tails`);
});

test('U9. the second batch: every new Unique is understood and can be fused', async () => {
  for (const id of ['crescendo', 'nightfang', 'iron_maiden', 'tailwind_heart', 'huntmark', 'martyrs_gift', 'aegis_oath', 'fickle_fortune']) {
    const list = fx.parse(baseline.battle, {v: 1, mons: {[U1]: [{i: id, p: 1}]}}).byUuid.get(U1) || [];
    assert.strictEqual(list.length, 1, id + ' is handled');
  }
  const fusedList = fx.parse(baseline.battle, {v: 1, mons: {[U1]: [{i: 'transcendent', p: 1, u: ['crescendo', 'huntmark'], b: 75, d: 60}]}}).byUuid.get(U1) || [];
  assert.deepStrictEqual(fusedList.map(e => e.i).sort(), ['crescendo', 'huntmark'], 'a fusion with no signature falls back to its two Uniques');
});

// ---- the 84 composed signatures (docs/AFFIX-EXPANSION-DESIGN.md, part C) ----
const TR_BOOK = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'design', 'transcendents.json'), 'utf8'));
const COMPOSED_IDS = TR_BOOK.bases.slice(21).map(b => b.id);
const csig = (sg, b = 100, d = 100, tw = [{op: 'cleanse'}]) =>
  ({v: 1, mons: {[U1]: [{i: 'transcendent', p: 1, u: TR_BOOK.bases.find(x => x.id === sg).uniques, b, d, sg, tw}]}});

test('C1. composed: all 84 are recognised as signatures, and each runs a battle', async () => {
  assert.strictEqual(COMPOSED_IDS.length, 84);
  for (const sg of COMPOSED_IDS) {
    const list = fx.parse(baseline.battle, csig(sg, 75, 60)).byUuid.get(U1) || [];
    assert.ok(list.some(e => e.i === 'transcendent_sig' && e.sg === sg), sg + ' is a known signature, not the fallback');
    const b = await battle({teams: [BLAST(['surf', 'aquatail']), LAX(U2, ['bodyslam'])], moves: turn => [turn ? 'move 2' : 'move 1', 'move 1'], turns: 2,
      fx: csig(sg, 75, 60, [{op: 'heal', pct: 4}])});
    assert.ok(!b.lines.some(l => l.includes('could not be applied')), sg + ': applied');
    assert.ok(b.lines.some(l => l.startsWith('|turn|3')) || b.battle.ended, sg + ': the battle advanced');
  }
});

test('C2. composed ramp and first-turns drawback (rising_pyre): weaker at first, then stronger each turn', async () => {
  const teams = [BLAST(['surf']), LAX(U2)];
  const plain = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 3}), 1);
  const c = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 3, fx: csig('rising_pyre')}), 1);
  ratioNear(c[0] / plain[0], 0.80, 0.03, 'turn 1');
  ratioNear(c[2] / plain[2], 1.12, 0.03, 'turn 3: +12%');
});

test('C3. composed mark and no-crit (giant_hunter): the second hit on a marked foe is stronger; no crits', async () => {
  const teams = [BLAST(['surf']), LAX(U2)];
  const plain = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 2}), 1);
  const c = deltas(await battle({teams, moves: ['move 1', 'move 1'], turns: 2, fx: csig('giant_hunter')}), 1);
  assert.strictEqual(c[0], plain[0]);
  ratioNear(c[1] / plain[1], 1.15, 0.03, 'marked');
  assert.strictEqual(count(await battle({teams: [BLAST(['frostbreath']), LAX(U2)], moves: ['move 1', 'move 1'], fx: csig('giant_hunter')}), '|-crit|'), 0);
});

test('C4. composed gamble (all_in): heads x1.4, tails x0.75', async () => {
  let heads = 0, tails = 0;
  for (const s of SEEDS.slice(0, 10)) {
    const make = payload => battle({teams: [BLAST(['surf']), LAX(U2)], moves: ['move 1', 'move 1'], seed: [s, 2, 3, 4], fx: payload});
    const b = await make(csig('all_in'));
    const ratio = lost(b, 1) / lost(await make(undefined), 1);
    if (count(b, 'heads!')) { heads++; ratioNear(ratio, 1.4, 0.05, 'heads'); } else { tails++; ratioNear(ratio, 0.75, 0.05, 'tails'); }
  }
  assert.ok(heads >= 2 && tails >= 2, `${heads} heads, ${tails} tails`);
});

test('C5. composed counter, clutch and shield (iron_coffin)', async () => {
  const struck = await battle({teams: [BLAST(['splash']), LAX(U2, ['bodyslam'])], moves: ['move 1', 'move 1'], fx: csig('iron_coffin')});
  assert.strictEqual(lost(struck, 1), Math.floor(mon(struck, 1).maxhp * 10 / 100), 'Body Slam costs 10%');
  const frail = [pack('Magikarp', U1, 'swiftswim', ['splash'], 50, '10'), INFLICTOR(['dragonclaw', 'splash'])];
  const held = await battle({teams: frail, moves: ['move 1', 'move 1'], fx: csig('iron_coffin')});
  assert.strictEqual(mon(held, 0).hp, 1, 'held on at 1 HP');
});

test('C6. composed legacy (inheritance): the next ally heals 40% and gains Attack and Sp. Atk', async () => {
  const mine = [pack('Magikarp', U1, 'swiftswim', ['splash'], 50, '1'), pack('Blastoise', U3, 'torrent', ['splash'], 50, '60')].join(']');
  const b = await battle({teams: [mine, INFLICTOR(['flamethrower', 'splash'])], moves: turn => (turn === 0 ? ['move 1', 'move 1'] : ['switch 2', 'move 2']),
    turns: 2, fx: csig('inheritance')});
  const ally = b.battle.sides[0].pokemon.find(p => p.uuid === U3);
  assert.strictEqual(ally.hp, 60 + Math.floor(ally.maxhp * 40 / 100));
  assert.ok(count(b, '|-boost|p1a: ' + U3 + '|atk|1') >= 1);
});

test('C7. composed lifesteal (undying_thirst) and incoming (vault_keeper) and type weakness (cinder_fang)', async () => {
  const leech = await battle({teams: [BLAST(['surf'], '60'), LAX(U2)], moves: ['move 1', 'move 1'], fx: csig('undying_thirst')});
  assert.strictEqual(mon(leech, 0).hp, 60 + Math.floor(lost(leech, 1) * 15 / 100));
  const hit = payload => battle({teams: [BLAST(['splash']), LAX(U2, ['tackle'])], moves: ['move 1', 'move 1'], fx: payload});
  ratioNear(lost(await hit(csig('vault_keeper')), 0) / lost(await hit(undefined), 0), 0.85, 0.04, 'vault_keeper takes 15% less');
  const fairy = payload => battle({teams: [BLAST(['splash']), INFLICTOR(['dazzlinggleam'])], moves: ['move 1', 'move 1'], fx: payload});
  ratioNear(lost(await fairy(csig('cinder_fang')), 0) / lost(await fairy(undefined), 0), 1.25, 0.04, 'cinder_fang: Fairy hurts 25% more');
});

test('C8. composed speed (phantom_step), frail (wildfire_gale) and bleed (razor_wind)', async () => {
  const first = b => b.lines.find(l => l.startsWith('|move|')).split('|')[2].slice(0, 3);
  const race = [pack('Blastoise', U1, 'torrent', ['surf'], 50), pack('Charizard', U2, 'blaze', ['flamethrower'], 50)];
  assert.strictEqual(first(await battle({teams: race, moves: ['move 1', 'move 1'], fx: csig('phantom_step')})), 'p1a', '+30% Speed outruns Charizard');
  const hit = payload => battle({teams: [BLAST(['splash']), LAX(U2, ['tackle'])], moves: ['move 1', 'move 1'], fx: payload});
  assert.ok(lost(await hit(csig('wildfire_gale')), 0) > lost(await hit(undefined), 0), 'lower Defense');
  const bled = await battle({teams: [BLAST(['surf']), LAX(U2)], moves: ['move 1', 'move 1'], fx: csig('razor_wind')});
  assert.ok(count(bled, 'is bleeding!') >= 1, 'a special hit bleeds');
});

test('C9. composed status on being struck (toxic_thorns): contact attackers may be poisoned', async () => {
  const thick = pack('Snorlax', U2, 'thickfat', ['bodyslam'], 100);
  const poisoned = await across(() => ({teams: [BLAST(['splash']), thick], moves: ['move 1', 'move 1'], fx: csig('toxic_thorns')}), '|-status|p2a: ' + U2 + '|psn');
  assert.ok(poisoned >= 4, `poisoned in ${poisoned} of ${SEEDS.length}`);
});

(async () => {
  baseline = await battle({teams: TEAMS(), moves: ['move 3', 'move 1']});          // before the module is installed
  fx.install({Battle});
  let failed = 0;
  const only = args.indexOf('--only') >= 0 ? args[args.indexOf('--only') + 1] : null;   // e.g. --only 25 runs tests whose name starts with '25'
  for (const {name, fn} of tests) {
    if (only && !name.startsWith(only)) continue;
    try { await fn(); console.log('  ok   ' + name); } catch (err) { failed++; console.log('  FAIL ' + name + '\n       ' + (err && err.message)); }
  }
  console.log(failed ? `\n${failed} of ${tests.length} failed` : `\nall ${tests.length} passed`);
  process.exit(failed ? 1 : 0);
})();
