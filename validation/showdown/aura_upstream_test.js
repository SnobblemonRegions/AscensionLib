#!/usr/bin/env node
// Tests the rarity aura in ascension-fx.js (balance phase 1) against the upstream Showdown simulator from npm, which needs no
// Minecraft server and no Cobblemon unbundle:
//
//   npm install pokemon-showdown@0.11.11   (in any folder; run this script from there)
//   node <repo>/validation/showdown/aura_upstream_test.js <repo>/ascensionlib/src/main/resources/assets/ascensionlib/showdown/ascension-fx.js
//
// Upstream Pokemon have no uuid, so the test gives them the one Cobblemon's fork packs. Same seed and moves with and without
// aura: the damage must move by the aura's ratio, equal auras must cancel, and the aura must apply after the channel caps.
const assert = require('assert');
globalThis.__ASCENSION_FX_TEST__ = true;
const sim = require(require.resolve('pokemon-showdown/dist/sim', {paths: [process.cwd()]}));
const fx = require(process.argv[2]);
const U1 = '11111111-1111-1111-1111-111111111111', U2 = '22222222-2222-2222-2222-222222222222';

function damageDealt(mons, extraForU1 = []) {
  const team = s => sim.Teams.pack([{species: s, name: s, ability: 'Thick Fat', moves: ['tackle'], level: 50, evs: {hp:0,atk:0,def:0,spa:0,spd:0,spe:0}, ivs: {hp:31,atk:31,def:31,spa:31,spd:31,spe:31}, nature: 'Hardy', item: ''}]);
  const battle = new sim.Battle({formatid: 'gen9customgame', seed: [1, 2, 3, 4]});
  battle.setPlayer('p1', {name: 'A', team: team('Snorlax')});
  battle.setPlayer('p2', {name: 'B', team: team('Snorlax')});
  battle.sides[0].pokemon[0].uuid = U1;
  battle.sides[1].pokemon[0].uuid = U2;
  const payload = {v: 1, caps: {out: 100, inc: 50, heal: 50, res: 100}, mons: {}};
  if (mons[U1] || extraForU1.length) payload.mons[U1] = [...(mons[U1] ? [{i: 'aura', p: mons[U1]}] : []), ...extraForU1];
  if (mons[U2]) payload.mons[U2] = [{i: 'aura', p: mons[U2]}];
  fx.apply(battle, payload);
  const before = battle.sides[1].pokemon[0].maxhp;
  battle.choose('p1', 'team 1'); battle.choose('p2', 'team 1');
  battle.choose('p1', 'move 1'); battle.choose('p2', 'move 1');
  return before - battle.sides[1].pokemon[0].hp;
}

const base = damageDealt({});
const up = damageDealt({[U1]: 26});
const down = damageDealt({[U2]: 26});
const both = damageDealt({[U1]: 26, [U2]: 26});
console.log({base, attackerMythicAura: up, defenderMythicAura: down, both});
assert(base > 0);
assert(Math.abs(up / base - 1.26) < 0.06, 'attacker aura raises damage by about 26%');
assert(Math.abs(down / base - 1 / 1.26) < 0.06, 'defender aura lowers damage by about 1/1.26');
assert(Math.abs(both - base) <= 1, 'equal auras cancel');
// The aura sits outside the outgoing cap: an attacker already at the 100% cap still gains the aura on top.
const capped = damageDealt({}, [{i: 'overwhelming_force', p: 100}, {i: 'physical_force', p: 100}]);
const cappedAura = damageDealt({[U1]: 26}, [{i: 'overwhelming_force', p: 100}, {i: 'physical_force', p: 100}]);
console.log({capped, cappedAura});
assert(Math.abs(cappedAura / capped - 1.26) < 0.06, 'aura applies after the channel cap');
// Effect limit: an 11th effect (the aura first) survives parsing.
const many = Array.from({length: 10}, (_, k) => ({i: 'physical_force', p: 1}));
const parsed = fx.parse({dex: sim.Dex}, {v: 1, mons: {[U1]: [{i: 'aura', p: 26}, ...many]}});
assert.strictEqual(parsed.byUuid.get(U1)[0].i, 'aura');
console.log('aura tests passed');
