'use strict';
// AscensionLib's Showdown extension (docs/BATTLE-ADAPTER-DESIGN.md).
//
// Installed by CobbleRaids' extension loader as showdown/ext-ascensionlib-fx.js and require()d by raid-patch.js, which is
// why the relative require('./sim/battle') below resolves to the simulator. This file never edits any Showdown file.
//
// WHAT IT DOES. A battle can carry `ascensionFx` on the format object of its >start payload: the ranked affixes of every
// Pokemon that holds any, keyed by the uuid Cobblemon packs into the team (the simulator keeps it as pokemon.uuid):
//
//   {"v":1,"caps":{"out":100,"inc":50,"heal":50},"mons":{"<uuid>":[{"i":"type_focus","p":8,"t":"Water"}, ...]}}
//
// "i" is the affix id, "p" the rolled percent, "t" a Showdown type for the typed affixes. Three channels: outgoing damage,
// incoming damage reduction and healing. Within a channel every active effect is its own multiplier and the total is
// capped once. All conditions are decided HERE, inside the simulator, from native state; Java only says what a Pokemon holds.
//
// WHY IT IS STABLE (the same properties as CobbleTowers' tower-fx.js, which proved them).
//   * It does nothing unless asked: it wraps Battle.prototype.start and returns at once unless format.ascensionFx is a
//     version-1 object. A battle without it is byte-for-byte unchanged.
//   * It is declarative and bounded: no code, no eval; at most MAX_MONS Pokemon with MAX_EFFECTS each, percentages 0..100,
//     unknown ids ignored, types checked against the battle's own dex.
//   * Damage wraps modifyDamage on THIS battle's own `actions` object, so no other battle sees it. The wrapper runs after the
//     engine's own maths, so native rounding, immunity and the minimum of 1 are kept, and it fails open to the engine's number.
//   * Healing wraps heal() on each affected Pokemon (a heal-flag move such as Recover calls pokemon.heal directly, so
//     battle.heal alone would miss it). Only the holder's own move counts: items, abilities and residual healing do not.
//   * Every hook is wrapped in try/catch and falls back to the native value.

const MAX_MONS = 32;
const MAX_EFFECTS = 11;   // 6 slots, a Unique and a Haunting, with room for a Transcendent's rider, and the rarity aura
const HP_HIGH = 0.8;
const HP_LOW = 0.35;
const DEFAULT_CAPS = {out: 100, inc: 50, heal: 50, res: 100};

const hpFraction = mon => (mon.maxhp > 0 ? mon.hp / mon.maxhp : 1);

// Outgoing: (effect, user, target, move, hit, ctx) -> does it apply to this hit? `hit.typeMod` is native effectiveness.
const OUTGOING = {
  type_focus: (fx, user, target, move) => move.type === fx.t,
  type_mastery: (fx, user, target, move) => move.type === fx.t,
  physical_force: (fx, user, target, move) => move.category === 'Physical',
  special_force: (fx, user, target, move) => move.category === 'Special',
  healthy_force: (fx, user) => hpFraction(user) >= HP_HIGH,
  last_stand: (fx, user) => hpFraction(user) <= HP_LOW,
  defiant_force: (fx, user) => !!user.status,
  overwhelming_force: () => true,
  kindred_force: (fx, user, target, move) => user.hasType(move.type),
  executioner: (fx, user, target) => hpFraction(target) <= 0.5,
  opening_strike: (fx, user, target, move, hit, ctx) => ctx.firstMove,
  midnight_howl: (fx, user, target, move, hit, ctx) => ctx.firstMove,   // a Haunting: the holder's first attack of the battle
  super_effective_force: (fx, user, target, move, hit) => hit.typeMod > 0,
  transcend_offense: (fx, user, target, move) => move.type === fx.t,
};

// Incoming: (effect, holder, attacker, move, hit, state) -> does it apply to this hit?
const INCOMING = {
  type_ward: (fx, holder, attacker, move) => move.type === fx.t,
  type_bulwark: (fx, holder, attacker, move) => move.type === fx.t,
  healthy_guard: (fx, holder) => hpFraction(holder) >= HP_HIGH,
  steadfast_guard: (fx, holder) => hpFraction(holder) <= HP_LOW,
  status_guard: (fx, holder) => !!holder.status,
  opening_guard: (fx, holder, attacker, move, hit, state) => !state.hitTaken,
  spectral_veil: (fx, holder, attacker, move) => move.type === 'Normal' || move.type === 'Fighting',   // a Haunting
  iron_resolve: () => true,
  resilient_hide: (fx, holder, attacker, move, hit) => hit.typeMod > 0,
  physical_bulwark: (fx, holder, attacker, move) => move.category === 'Physical',
  special_bulwark: (fx, holder, attacker, move) => move.category === 'Special',
  transcend_defense: (fx, holder, attacker, move) => move.type === fx.t,
};

const HEALING = {restorative: true, triumphant: true};

// Status damage (docs/STATUS-AFFIX-DESIGN.md). The potency affixes boost the native residual of the status the holder inflicted;
// Showdown already records the inflictor in pokemon.statusState.source, so no ledger is needed. Rending is the library's own
// bleed: a chance on each direct damaging hit, ticked here once a turn.
const POTENCY = {smoldering: 'brn', venomous: 'psn'};
const RENDING = 'rending';
const RENDING_CHANCE = 30;      // percent per damaging move that deals damage
const BLEED_DIVISOR = 12;       // each stack bleeds 1/12 of max HP a turn
const BLEED_STACKS = 3;
const BLEED_TURNS = 3;          // refreshed by every new application

// Uniques (docs/UNIQUES-DESIGN.md): one fixed power each, sent as an effect whose percent is only an "on" flag. A benefit joins its
// channel (so it is capped with the rest); a drawback is a fixed multiplier applied after the caps and never removed by them.
// The rarity aura (balance phase 1, sent only while the Threat system is on): +p% to Attack, Sp. Atk, Defense and Sp. Def, applied
// as a damage multiplier, attacker's aura over defender's, after the channel caps (it is not an affix and has no cap of its own
// beyond the 100 every percent is clamped to). Sent first in a Pokemon's list so the effect limit can never cut it off.
const AURA = 'aura';
const UNIQUES = {ashen_heart: 1, last_breath: 1, creeping_venom: 1, stormcaller: 1, rupture: 1, titans_heart: 1, triple_seven: 1};
const ASHEN_BURN = 50;          // +50% burn damage you inflict (residual channel)
const ASHEN_PENALTY = 0.85;     // your direct move damage
const VENOM_STEP = 20;          // +20% per turn the poison has lasted ...
const VENOM_MAX = 80;           // ... up to +80%
const VENOM_PSYCHIC = 1.2;      // drawback: Psychic moves hurt you 20% more
const STORM_BONUS = 30;         // +30% for moves of the active weather's type (outgoing channel)
const STORM_PENALTY = 0.85;     // drawback: direct damage with no weather active
const BREATH_HEAL = 0.5;        // drawback: healing you receive
const RUPTURE_WEIGHTS = [1, 0.8, 0.6];   // drawback: stack n of a bleed you inflict deals this share of a normal stack
const TITAN_FRACTION = 0.10;    // extra damage on a super-effective move: this share of the holder's max HP
const SEVEN_PENALTY = 1.25;     // drawback of 777: damage taken from moves, equal to about 20% less effective HP
// Transcendent (docs/FUSION-DESIGN.md): two Uniques fused into one holder. Each runs at its harmony share, benefit b and drawback d
// (percent of the plain Unique), and the host's type adds a typed offence, the donor's a typed defence, through the existing typed
// channels (so they are capped with the rest). The payload is one effect {"i":"transcendent","u":[a,b],"b":78,"d":60,"t":host,"r":donor};
// it is expanded here into the two Uniques and the two internal typed effects, which a payload cannot name directly.
const TRANSCEND_OFFENSE = 10;
const TRANSCEND_DEFENSE = 10;
const INTERNAL = new Set(['transcend_offense', 'transcend_defense', 'transcendent_sig']);
const WEATHER_TYPE = {
  raindance: 'Water', primordialsea: 'Water', sunnyday: 'Fire', desolateland: 'Fire',
  sandstorm: 'Rock', snow: 'Ice', snowscape: 'Ice', hail: 'Ice',
};

// Mechanic affixes (docs/DEPTH-DESIGN.md, part A). Chance-based ones are bounded per affix, so no roll can become a guarantee.
const PROCS = new Set(['keen_edge', 'ensnaring', 'bracing_entry', 'wardstone', 'stubborn']);
const PROC_CAP = 50;
const MOMENTUM = 'momentum';
const MOMENTUM_STACKS = 3;      // each KO adds the rolled percent to outgoing damage, up to this many times
const SWIFT = 'swift_strike';   // the holder's Speed stat is raised by the rolled percent
const MECHANICS = new Set([...PROCS, MOMENTUM, SWIFT]);

// Hauntings (docs/HALLOWEEN-EVENT-DESIGN.md): one event-only power per Pokemon, in its own slot. Midnight Howl and Spectral Veil are
// ordinary channel effects (tables above); the other eight are small mechanics handled by applyHauntings. All fixed and bounded.
const HAUNT_MECHANICS = new Set(['wisp', 'hex', 'pumpkin_lantern', 'trick_or_treat', 'cobweb', 'graveyard_chill', 'headless_rider', 'reapers_toll']);
const WISP_FLINCH_CAP = 50;   // percent chance a hit flinches the target, however high it is sent
const HEX_DIVISOR = 16;       // a hexed foe loses 1/16 of its max HP at the end of each turn
const LANTERN_FRACTION = 8;   // Pumpkin Lantern and Trick or Treat restore 1/8 of max HP
const CHILL_DIVISOR = 16;     // Graveyard Chill: a foe that makes contact loses 1/16 of its max HP
const REAPER_FRACTION = 3;    // Reaper's Toll restores 1/3 of max HP after a KO

const HANDLED = new Set([...Object.keys(OUTGOING), ...Object.keys(INCOMING), ...Object.keys(HEALING),
  ...Object.keys(POTENCY), RENDING, ...Object.keys(UNIQUES), ...MECHANICS, ...HAUNT_MECHANICS, AURA]);
for (const id of INTERNAL) HANDLED.delete(id);   // only a validated transcendent may create these

function clamp(value, low, high, fallback) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(low, Math.min(high, Math.trunc(n)));
}

/** A line in the battle log the player can read; never throws. */
function note(battle, text) {
  try {
    battle.add('-message', String(text).slice(0, 200));
  } catch (err) {
    /* a log line is never worth losing a battle over */
  }
}

/** 1 + the compounded bonuses, never above 1 + cap%. */
function grow(percents, cap) {
  let value = 1;
  for (const p of percents) value *= 1 + p / 100;
  return Math.min(1 + cap / 100, value);
}

/** What is left after the compounded reductions, never below 1 - cap%. */
function shrink(percents, cap) {
  let value = 1;
  for (const p of percents) value *= 1 - p / 100;
  return Math.max(1 - cap / 100, value);
}

/** One validated type name, undefined when absent, null when present but unknown. */
function typeName(battle, value) {
  if (value === undefined) return undefined;
  const type = typeof value === 'string' ? battle.dex.types.get(value) : null;
  return type && type.exists ? type.name : null;
}

/** The effects a transcendent stands for: its two Uniques at their shares, and the typed offence and defence. [] when invalid. */
function expandTranscendent(battle, raw) {
  const ids = Array.isArray(raw.u) ? raw.u : [];
  if (ids.length !== 2 || ids[0] === ids[1] || !ids.every(id => typeof id === 'string' && Object.prototype.hasOwnProperty.call(UNIQUES, id))) return [];
  // Both shares are required whole percents; a missing or out-of-range one makes the whole transcendent invalid (never clamped into one).
  if (![raw.b, raw.d].every(v => Number.isInteger(v) && v >= 1 && v <= 100)) return [];
  const b = raw.b;
  const d = raw.d;
  const host = typeName(battle, raw.t);
  const donor = typeName(battle, raw.r);
  if (host === null || donor === null) return [];
  let out = ids.map(i => ({i, p: 1, t: undefined, b, d}));
  // A signature the module implements replaces the two Uniques; one it does not falls back to them at their shares.
  if (typeof raw.sg === 'string' && Object.prototype.hasOwnProperty.call(TRANSCEND_SIGNATURES, raw.sg)) {
    const tw = cleanOps(raw.tw);
    if (!tw) return [];
    out = [{i: 'transcendent_sig', p: 1, t: undefined, b, d, sg: raw.sg, tw}];
  }
  if (host) out.push({i: 'transcend_offense', p: TRANSCEND_OFFENSE, t: host});
  if (donor) out.push({i: 'transcend_defense', p: TRANSCEND_DEFENSE, t: donor});
  return out;
}

/** The effects of a payload, validated and bounded: uuid -> [{i, p, t}]. */
function parse(battle, payload) {
  const caps = {
    out: clamp(payload.caps && payload.caps.out, 0, 100, DEFAULT_CAPS.out),
    inc: clamp(payload.caps && payload.caps.inc, 0, 90, DEFAULT_CAPS.inc),
    heal: clamp(payload.caps && payload.caps.heal, 0, 100, DEFAULT_CAPS.heal),
    res: clamp(payload.caps && payload.caps.res, 0, 100, DEFAULT_CAPS.res),
  };
  const byUuid = new Map();
  let seen = 0;
  for (const [uuid, list] of Object.entries(payload.mons || {})) {
    if (++seen > MAX_MONS) break;
    if (!Array.isArray(list)) continue;
    const effects = [];
    for (const raw of list.slice(0, MAX_EFFECTS)) {
      if (raw && raw.i === 'transcendent') {
        // One transcendent per Pokemon, and never on top of a plain Unique it would duplicate.
        if (!effects.some(e => e.b)) {
          for (const fused of expandTranscendent(battle, raw)) {
            if (!(UNIQUES[fused.i] && effects.some(e => e.i === fused.i))) effects.push(fused);
          }
        }
        continue;
      }
      if (!raw || typeof raw.i !== 'string' || !HANDLED.has(raw.i)) continue;
      const p = clamp(raw.p, 0, PROCS.has(raw.i) ? PROC_CAP : 100, 0);
      if (p <= 0) continue;
      let t;
      if (raw.t !== undefined) {
        const type = typeof raw.t === 'string' ? battle.dex.types.get(raw.t) : null;
        if (!type || !type.exists) continue;
        t = type.name;
      }
      effects.push({i: raw.i, p, t});
    }
    if (effects.length) byUuid.set(uuid, effects);
  }
  return {caps, byUuid};
}

function apply(battle, payload) {
  const {caps, byUuid} = parse(battle, payload);
  if (!byUuid.size) return 0;
  const state = new Map();
  const stateOf = mon => {
    if (!state.has(mon)) state.set(mon, {hitTaken: false});
    return state.get(mon);
  };
  const of = (mon, table) => (byUuid.get(mon.uuid) || []).filter(fx => Object.prototype.hasOwnProperty.call(table, fx.i));
  const momentum = new Map();   // holder -> KO stacks, reset when the holder leaves the field
  const titanUsed = new Set();   // titans_heart pays once per move use, however many targets or hits
  const has = (mon, id) => !!mon && (byUuid.get(mon.uuid) || []).some(fx => fx.i === id);
  /** The holder's rarity aura in percent, 0 when it has none. */
  const auraOf = mon => { const fx = mon && (byUuid.get(mon.uuid) || []).find(e => e.i === AURA); return fx ? fx.p : 0; };
  // A Unique held plainly runs at full strength; one held through a transcendent at its benefit share b and drawback share d.
  const uniqueOf = (mon, id) => (mon && byUuid.get(mon.uuid) || []).find(fx => fx.i === id);
  const bScale = (mon, id) => { const fx = uniqueOf(mon, id); return fx && fx.b ? fx.b / 100 : 1; };
  const dScale = (mon, id) => { const fx = uniqueOf(mon, id); return fx && fx.d ? fx.d / 100 : 1; };
  /** A drawback multiplier moved toward 1 by the drawback share: 0.85 at 60% becomes 0.91, 1.25 becomes 1.15. */
  const drawback = (multiplier, share) => 1 + (multiplier - 1) * share;
  const scale = {b: bScale, d: dScale, drawback};
  let transcend = null;   // the Transcendent signatures' damage hook, set once the wrappers are installed
  /** The weather in force (Cloud Nine and Air Lock suppress it), or ''. */
  const weatherNow = () => {
    try { return String(battle.field.effectiveWeather ? battle.field.effectiveWeather() : battle.field.weather || ''); } catch (err) { return ''; }
  };

  // ---- damage dealt and taken ----
  const actions = battle.actions;
  const originalDamage = actions && actions.modifyDamage;
  if (typeof originalDamage === 'function') {
    actions.modifyDamage = function (baseDamage, pokemon, target, move) {
      const damage = originalDamage.apply(this, arguments);
      try {
        if (typeof damage !== 'number' || !(damage > 0) || !pokemon || !target || !move) return damage;
        const hit = target.getMoveHitData(move);
        const ctx = {firstMove: pokemon.activeMoveActions <= 1};
        const out = of(pokemon, OUTGOING).filter(fx => OUTGOING[fx.i](fx, pokemon, target, move, hit, ctx)).map(fx => fx.p);
        const holder = stateOf(target);
        const inc = of(target, INCOMING).filter(fx => INCOMING[fx.i](fx, target, pokemon, move, hit, holder)).map(fx => fx.p);
        if (byUuid.has(target.uuid)) holder.hitTaken = true;   // opening_guard: only the first damaging hit of the battle
        const weather = weatherNow();
        if (has(pokemon, 'stormcaller') && WEATHER_TYPE[weather] === move.type) out.push(STORM_BONUS * bScale(pokemon, 'stormcaller'));
        for (const fx of of(pokemon, {[MOMENTUM]: 1})) {
          const stacks = momentum.get(pokemon) || 0;
          if (stacks > 0) out.push(fx.p * stacks);
        }
        let scaled = damage;
        if (out.length) scaled = battle.modify(scaled, Math.round(grow(out, caps.out) * 1000), 1000);
        if (inc.length) scaled = Math.max(1, battle.modify(scaled, Math.round(shrink(inc, caps.inc) * 1000), 1000));
        // Rarity aura: outside the caps, the attacker's over the defender's.
        const auraOut = auraOf(pokemon), auraIn = auraOf(target);
        if (auraOut || auraIn) scaled = Math.max(1, battle.modify(scaled, Math.round((100 + auraOut) / (100 + auraIn) * 1000), 1000));
        // Unique drawbacks: fixed, after the caps.
        if (has(pokemon, 'ashen_heart')) scaled = Math.max(1, battle.modify(scaled, Math.round(drawback(ASHEN_PENALTY, dScale(pokemon, 'ashen_heart')) * 1000), 1000));
        if (has(pokemon, 'stormcaller') && !weather) scaled = Math.max(1, battle.modify(scaled, Math.round(drawback(STORM_PENALTY, dScale(pokemon, 'stormcaller')) * 1000), 1000));
        if (has(pokemon, 'titans_heart') && hit.typeMod > 0) {
          const key = `${battle.turn}:${pokemon.uuid}:${pokemon.activeMoveActions}`;
          if (!titanUsed.has(key)) { titanUsed.add(key); scaled += Math.floor(pokemon.maxhp * TITAN_FRACTION * bScale(pokemon, 'titans_heart')); }
        }
        if (has(target, 'creeping_venom') && move.type === 'Psychic') scaled = battle.modify(scaled, Math.round(drawback(VENOM_PSYCHIC, dScale(target, 'creeping_venom')) * 1000), 1000);
        if (has(target, 'triple_seven')) scaled = battle.modify(scaled, Math.round(drawback(SEVEN_PENALTY, dScale(target, 'triple_seven')) * 1000), 1000);
        if (transcend) scaled = transcend.adjust(pokemon, target, move, hit, scaled, weather);
        return scaled;
      } catch (err) {
        return damage;
      }
    };
  } else {
    note(battle, 'Ascension damage effects are not available in this simulator.');
  }

  // ---- healing from the holder's own move ----
  const rawHeal = Object.getPrototypeOf(battle.sides.find(Boolean).pokemon[0]).heal;
  for (const side of battle.sides) {
    if (!side) continue;
    for (const holder of side.pokemon) {
      const heals = of(holder, {restorative: 1}).map(fx => fx.p);
      if (!heals.length) continue;
      const inner = holder.heal;
      holder.heal = function (d, source, effect) {
        let amount = d;
        try {
          // A heal-flag move passes no effect; drain passes the 'drain' condition; both happen inside the holder's own action.
          const ownMove = battle.activePokemon === this && battle.activeMove &&
            ((effect && effect.id === 'drain') || !effect || effect.effectType === 'Move');
          if (ownMove) amount = battle.modify(d, Math.round(grow(heals, caps.heal) * 1000), 1000);
        } catch (err) {
          amount = d;
        }
        return inner.call(this, amount, source, effect);
      };
    }
  }

  // ---- triumphant: a direct move of the holder fainted the target ----
  const originalFaint = battle.faintMessages;
  if (typeof originalFaint === 'function' && [...byUuid.values()].some(list => list.some(fx => fx.i === 'triumphant' || fx.i === MOMENTUM))) {
    battle.faintMessages = function () {
      const killers = [];
      const scorers = [];
      try {
        for (const entry of this.faintQueue) {
          if (entry && entry.source && entry.effect && entry.effect.effectType === 'Move' && entry.source !== entry.target) {
            const heals = of(entry.source, {triumphant: 1}).map(fx => fx.p);
            if (heals.length) killers.push([entry.source, heals]);
            if (has(entry.source, MOMENTUM)) scorers.push(entry.source);
          }
        }
      } catch (err) { /* fail open */ }
      const result = originalFaint.apply(this, arguments);
      for (const holder of scorers) {
        try {
          if (holder.hp > 0 && !holder.fainted) {
            const before = momentum.get(holder) || 0;
            momentum.set(holder, Math.min(MOMENTUM_STACKS, before + 1));
            if (momentum.get(holder) > before) note(battle, `${holder.name}'s Momentum rises! (stack ${momentum.get(holder)})`);
          }
        } catch (err) { /* fail open */ }
      }
      for (const [holder, heals] of killers) {
        try {
          // Titan's Heart takes all healing (a transcendent only the share d of it), Last Breath halves it (or less); both apply here
          // because this heal bypasses holder.heal on purpose.
          const titan = has(holder, 'titans_heart') ? 1 - dScale(holder, 'titans_heart') : 1;
          if (holder.hp > 0 && !holder.fainted && titan > 0) {
            const percent = Math.min(caps.heal, heals.reduce((a, b) => a + b, 0));
            const healScale = titan * (has(holder, 'last_breath') ? drawback(BREATH_HEAL, dScale(holder, 'last_breath')) : 1);
            // Healed directly with a plain -heal line: a `[from]` the client does not know would be misread.
            if (rawHeal.call(holder, Math.max(1, Math.floor(holder.maxhp * percent / 100 * healScale)))) battle.add('-heal', holder, holder.getHealth);
          }
        } catch (err) { /* fail open */ }
      }
      return result;
    };
  }
  // What the burn/poison hook and the bleed engine share with the Transcendent signatures (which register into it after the fact).
  const shared = {
    bleeds: new Map(),            // afflicted Pokemon -> bleed state (Rending, Rupture and the signatures all bleed through one engine)
    bleedKeys: new Set(),         // one signature bleed per move use and target
    statusBoosts: [],             // fn(inflictor, victim, causeId) -> percent added to the residual channel for that tick
    statusVictimMult: [],         // fn(victim, causeId) -> multiplier on a burn / poison / bleed tick the victim takes
    needStatus: false, needBleed: false,
    isBleeding: () => false,
    inflictBleed: () => {},
  };
  for (const list of byUuid.values()) {
    for (const effect of list) {
      const signature = effect.i === 'transcendent_sig' ? TRANSCEND_SIGNATURES[effect.sg] : null;
      if (signature && signature.hooks) {
        shared.needStatus = shared.needStatus || !!signature.hooks.status;
        shared.needBleed = shared.needBleed || !!signature.hooks.bleed;
      }
    }
  }
  applyStatusDamage(battle, byUuid, caps, of, has, scale, shared);
  applyMechanics(battle, byUuid, of, has, momentum);
  applyHauntings(battle, byUuid, of, has);
  transcend = applyTranscendents({battle, byUuid, weatherNow, rawHeal, shared});
  return byUuid.size;
}

/** Smoldering / Venomous (potency of the holder's own burn / poison) and Rending (the library's bleed). */
function applyStatusDamage(battle, byUuid, caps, of, has, scale, shared) {
  const holds = ids => [...byUuid.values()].some(list => list.some(fx => ids.includes(fx.i)));

  // ---- potency: scale a native burn / regular-poison residual once, only when the holder inflicted it ----
  if ((holds([...Object.keys(POTENCY), 'ashen_heart', 'creeping_venom']) || shared.needStatus) && typeof battle.damage === 'function') {
    const rawDamage = battle.damage;
    const venomTicks = new Map();   // victim -> {state, ticks}: how long this poison has lasted, reset when it is cured or replaced
    battle.damage = function (damage, target, source, effect, instafaint) {
      let scaled = damage;
      try {
        const victim = target || (this.event && this.event.target);
        const cause = effect || (this.event && this.effect);
        if (typeof damage === 'number' && damage > 0 && victim && cause && cause.effectType === 'Status' && (cause.id === 'brn' || cause.id === 'psn')) {
          const inflictor = victim.statusState && victim.statusState.source;
          // An unknown, self-inflicted (Flame Orb) or allied source, a fainted or benched inflictor: native damage only.
          if (inflictor && inflictor !== victim && inflictor.hp > 0 && inflictor.isActive && inflictor.side !== victim.side) {
            const percents = of(inflictor, POTENCY).filter(fx => POTENCY[fx.i] === cause.id).map(fx => fx.p);
            if (cause.id === 'brn' && has(inflictor, 'ashen_heart')) percents.push(ASHEN_BURN * scale.b(inflictor, 'ashen_heart'));
            if (cause.id === 'psn' && has(inflictor, 'creeping_venom')) {
              let entry = venomTicks.get(victim);
              if (!entry || entry.state !== victim.statusState) {
                entry = {state: victim.statusState, ticks: 0};
                venomTicks.set(victim, entry);
              }
              const share = scale.b(inflictor, 'creeping_venom');
              const ramp = Math.min(VENOM_MAX * share, entry.ticks * VENOM_STEP * share);
              entry.ticks++;
              if (ramp > 0) percents.push(ramp);
            }
            for (const boost of shared.statusBoosts) {
              const extra = boost(inflictor, victim, cause.id);
              if (extra) percents.push(extra);
            }
            if (percents.length) scaled = damage * grow(percents, caps.res);
          }
          // What the victim itself makes of the tick (a Brand makes burn and bleed hurt more), whoever inflicted it.
          for (const mult of shared.statusVictimMult) scaled *= mult(victim, cause.id);
        }
      } catch (err) {
        scaled = damage;
      }
      return rawDamage.call(this, scaled, target, source, effect, instafaint);
    };
  }

  // ---- titan's heart: the holder cannot be healed in battle (every heal, whatever its source, does nothing) ----
  for (const side of battle.sides) {
    if (!side) continue;
    for (const holder of side.pokemon) {
      if (has(holder, 'titans_heart')) {
        // Plain: no healing at all. Through a transcendent only the share d of each heal is refused, so (1 - d) of it lands.
        const kept = 1 - scale.d(holder, 'titans_heart');
        const innerTitan = holder.heal;
        holder.heal = function (amount, source, effect) {
          if (kept <= 0) return 0;
          const reduced = typeof amount === 'number' ? Math.floor(amount * kept) : amount;
          return reduced > 0 || typeof amount !== 'number' ? innerTitan.call(this, reduced, source, effect) : 0;
        };
      }
    }
  }

  // ---- last breath: once per battle, an opposing move that would faint the holder leaves it at 1 HP; healing it receives is halved ----
  for (const side of battle.sides) {
    if (!side) continue;
    for (const holder of side.pokemon) {
      if (!has(holder, 'last_breath')) continue;
      const state = {used: false};
      const innerDamage = holder.damage;
      holder.damage = function (d, source, effect) {
        let amount = d;
        try {
          const share = scale.b(this, 'last_breath');
          if (!state.used && this.hp > 0 && typeof d === 'number' && d >= this.hp && source && source !== this && source.side !== this.side &&
              effect && typeof effect === 'object' && effect.effectType === 'Move' && (share >= 1 || battle.random(100) < Math.round(share * 100))) {
            state.used = true;
            amount = this.hp - 1;
            note(battle, `${this.name} hung on with Last Breath!`);
          }
        } catch (err) {
          amount = d;
        }
        return innerDamage.call(this, amount, source, effect);
      };
      const innerHeal = holder.heal;
      holder.heal = function (d, source, effect) {
        let amount = d;
        try {
          if (typeof d === 'number' && d > 0) amount = Math.max(1, Math.floor(d * scale.drawback(BREATH_HEAL, scale.d(this, 'last_breath'))));
        } catch (err) {
          amount = d;
        }
        return innerHeal.call(this, amount, source, effect);
      };
    }
  }

  // ---- the bleed engine: Rending, Rupture and the Transcendent signatures all bleed through this ----
  if (!holds([RENDING, 'rupture']) && !shared.needBleed) return;
  const bleeds = shared.bleeds;       // afflicted Pokemon -> {stacks, turns, percent, weights, growth, age, leech, source}
  const rolled = new Set();           // one roll per move use and target, however many hits it lands
  shared.isBleeding = mon => bleeds.has(mon);
  /**
   * Starts or deepens a bleed on target. opts: percent (potency, residual channel), weights (the stack weights, default 1,1,1),
   * growth {step, max} (percent more per turn it has bled), leech (share of the damage healed to source). The latest inflictor decides.
   */
  shared.inflictBleed = (source, target, opts) => {
    const state = bleeds.get(target) || {stacks: 0, turns: 0, percent: 0, weights: null, growth: null, age: 0, leech: 0, source: null};
    const before = state.stacks;
    state.stacks = Math.min(BLEED_STACKS, state.stacks + 1);
    state.turns = BLEED_TURNS;
    state.percent = Math.max(state.percent, opts.percent || 0);
    state.weights = opts.weights || null;
    state.growth = opts.growth || null;
    state.leech = opts.leech || 0;
    state.source = source;
    bleeds.set(target, state);
    // The wording is a contract with BleedWatch.java, which reads these lines to draw the bleed badge on the overlay: keep it in step.
    note(battle, state.stacks > before ? `${target.name} is bleeding! (stack ${state.stacks}, ${state.turns} turns)`
      : `${target.name} is still bleeding! (stack ${state.stacks}, ${state.turns} turns)`);
  };
  const rawSpread = battle.spreadDamage;
  if (typeof rawSpread === 'function') {
    battle.spreadDamage = function (damage, targetArray, source, effect, instafaint) {
      const result = rawSpread.apply(this, arguments);
      try {
        if (source && effect && typeof effect === 'object' && effect.effectType === 'Move' && Array.isArray(targetArray)) {
          const percents = of(source, {[RENDING]: 1}).map(fx => fx.p);
          // Rupture: every physical move bleeds, no roll (through a transcendent, with the chance b). Rending rolls for whatever
          // Rupture does not cover.
          const ruptures = has(source, 'rupture') && effect.category === 'Physical';
          const ruptureShare = scale.b(source, 'rupture');
          for (const [i, target] of targetArray.entries()) {
            if (!(percents.length || ruptures) || !target || target === source || target.side === source.side || !(target.hp > 0)) continue;
            if (!(typeof result[i] === 'number' && result[i] > 0)) continue;
            const key = `${this.turn}:${source.uuid}:${source.activeMoveActions}:${target.uuid}`;
            if (rolled.has(key)) continue;
            rolled.add(key);
            const rupturing = ruptures && (ruptureShare >= 1 || this.random(100) < Math.round(ruptureShare * 100));
            if (!rupturing && (!percents.length || this.random(100) >= RENDING_CHANCE)) continue;
            shared.inflictBleed(source, target, {
              percent: percents.length ? Math.max(...percents) : 0,
              // The latest inflictor decides whether the Rupture drawback applies.
              weights: has(source, 'rupture') ? RUPTURE_WEIGHTS.map(w => scale.drawback(w, scale.d(source, 'rupture'))) : null,
            });
          }
        }
      } catch (err) { /* fail open: the hit already happened */ }
      return result;
    };
  }

  const rawResidual = battle.residualEvent;
  if (typeof rawResidual === 'function') {
    battle.residualEvent = function (eventid) {
      const result = rawResidual.apply(this, arguments);
      if (eventid !== 'Residual') return result;
      for (const [mon, state] of [...bleeds]) {
        try {
          if (!(mon.hp > 0) || !mon.isActive) {
            // A fainted or switched-out Pokemon loses its bleed, as a native volatile would.
            bleeds.delete(mon);
            note(this, `${mon.name}'s bleeding stopped.`);
            continue;
          }
          const bleed = {id: 'ascensionbleed', name: 'Bleed', fullname: 'bleed', effectType: 'Status'};
          const weights = state.weights || [1, 1, 1];
          const share = weights.slice(0, state.stacks).reduce((a, b) => a + b, 0);
          const growth = state.growth ? 1 + Math.min(state.growth.max, state.age * state.growth.step) / 100 : 1;
          let victim = 1;
          for (const mult of shared.statusVictimMult) victim *= mult(mon, 'bleed');
          let amount = Math.max(1, Math.trunc(mon.baseMaxhp / BLEED_DIVISOR * share * grow([state.percent], caps.res) * growth * victim));
          // Magic Guard and other native damage rules get their say; a plain -damage line is used because the client
          // misreads a [from] it does not know.
          const allowed = this.runEvent('Damage', mon, null, bleed, amount, true);
          if (allowed || allowed === 0) {
            amount = Math.max(1, Math.trunc(allowed));
            const dealt = mon.damage(amount, null, bleed);
            if (dealt) {
              this.add('-damage', mon, mon.getHealth);
              const leecher = state.source;
              if (state.leech > 0 && leecher && leecher.hp > 0 && leecher.isActive) {
                const healed = leecher.heal(Math.max(1, Math.floor(dealt * state.leech)));
                if (healed) this.add('-heal', leecher, leecher.getHealth);
              }
            }
          }
          state.age++;
          if (--state.turns <= 0) {
            bleeds.delete(mon);
            note(this, `${mon.name}'s bleeding stopped.`);
          }
        } catch (err) { bleeds.delete(mon); }
      }
      return result;
    };
  }
}

/** Keen Edge, Ensnaring, Bracing Entry, Wardstone, Stubborn, Swift Strike and the reset half of Momentum (docs/DEPTH-DESIGN.md, A). */
function applyMechanics(battle, byUuid, of, has, momentum) {
  const holds = ids => [...byUuid.values()].some(list => list.some(fx => ids.includes(fx.i)));
  const chance = fx => battle.randomChance(fx.p, 100);   // the battle's own random source, so a replay rolls the same
  const effectOf = (id, name) => ({id, name, fullname: name, effectType: 'Status'});
  const holders = [];
  for (const side of battle.sides) {
    if (!side) continue;
    for (const holder of side.pokemon) if (byUuid.has(holder.uuid)) holders.push(holder);
  }

  // ---- keen edge: a damaging move of the holder may be forced to crit; the target's own crit rules (Battle Armor, Shell Armor, ...) still run ----
  const actions = battle.actions;
  if (holds(['keen_edge']) && actions && typeof actions.getDamage === 'function') {
    const rawGetDamage = actions.getDamage;
    actions.getDamage = function (source, target, move) {
      let forced = false;
      try {
        const rolls = source && target && move && typeof move === 'object' ? of(source, {keen_edge: 1}) : [];
        if (rolls.length && move.willCrit === undefined && !move.damage && !move.ohko && rolls.some(chance)) {
          move.willCrit = true;
          forced = true;
        }
      } catch (err) { forced = false; }
      try {
        return rawGetDamage.apply(this, arguments);
      } finally {
        if (forced) move.willCrit = undefined;
      }
    };
  }

  // ---- ensnaring: a damaging move that hit may lower the target's Speed by one stage, through the engine's own boost path ----
  if (holds(['ensnaring']) && typeof battle.spreadDamage === 'function') {
    const rawSpread = battle.spreadDamage;
    const rolled = new Set();
    battle.spreadDamage = function (damage, targetArray, source, effect) {
      const result = rawSpread.apply(this, arguments);
      try {
        if (source && effect && typeof effect === 'object' && effect.effectType === 'Move' && Array.isArray(targetArray)) {
          const rolls = of(source, {ensnaring: 1});
          for (const [i, target] of targetArray.entries()) {
            if (!rolls.length || !target || target === source || target.side === source.side || !(target.hp > 0)) continue;
            if (!(typeof result[i] === 'number' && result[i] > 0)) continue;
            const key = `${this.turn}:${source.uuid}:${source.activeMoveActions}:${target.uuid}`;
            if (rolled.has(key)) continue;
            rolled.add(key);
            if (target.hasAbility('shielddust') || target.hasItem('covertcloak')) continue;
            // the badge line only when the engine really lowered Speed (Clear Body, Mist and a Substitute make boost() report false)
            if (rolls.some(chance) && this.boost({spe: -1}, target, source, effectOf('ascensionensnare', 'Ensnaring'), true)) note(this, `${target.name} was ensnared!`);
          }
        }
      } catch (err) { /* fail open: the hit already happened */ }
      return result;
    };
  }

  // ---- bracing entry: on entering the field, a chance to raise Defense and Sp. Def; the leads entered before this module installed ----
  if (holds(['bracing_entry'])) {
    const brace = pokemon => {
      try {
        const rolls = of(pokemon, {bracing_entry: 1});
        if (rolls.length && pokemon.hp > 0 && rolls.some(chance)) battle.boost({def: 1, spd: 1}, pokemon, pokemon, effectOf('ascensionbracing', 'Bracing Entry'), false, true);
      } catch (err) { /* fail open */ }
    };
    if (actions && typeof actions.runSwitch === 'function') {
      const rawRunSwitch = actions.runSwitch;
      actions.runSwitch = function (pokemon) {
        const result = rawRunSwitch.apply(this, arguments);
        brace(pokemon);
        return result;
      };
    }
    for (const side of battle.sides) {
      if (!side) continue;
      for (const lead of side.active) if (lead && lead.isActive && battle.turn <= 1) brace(lead);
    }
  }

  for (const holder of holders) {
    // ---- swift strike: the holder's Speed stat (after the engine's own modifiers) is raised ----
    const swift = of(holder, {[SWIFT]: 1}).map(fx => fx.p);
    if (swift.length) {
      const rawGetStat = holder.getStat;
      holder.getStat = function (statName, unboosted, unmodified) {
        const stat = rawGetStat.apply(this, arguments);
        try {
          if (statName === 'spe' && !unmodified && typeof stat === 'number') {
            const raised = battle.modify(stat, Math.round(grow(swift, 100) * 1000), 1000);
            return battle.format.battle && battle.format.battle.trunc ? raised : Math.min(raised, 1e4);
          }
        } catch (err) { /* fail open */ }
        return stat;
      };
    }

    // ---- wardstone: a status condition inflicted by a foe may fail ----
    if (has(holder, 'wardstone')) {
      const rawSetStatus = holder.setStatus;
      holder.setStatus = function (status, source) {
        try {
          const id = status && typeof status === 'object' ? status.id : status;
          const inflictor = source || (battle.event && battle.event.source);
          if (id && this.hp > 0 && this.status !== id && inflictor && inflictor !== this && inflictor.side && inflictor.side !== this.side &&
              of(this, {wardstone: 1}).some(chance)) {
            note(battle, `${this.name}'s Wardstone turned the condition aside!`);
            return false;
          }
        } catch (err) { /* fail open */ }
        return rawSetStatus.apply(this, arguments);
      };
    }

    // ---- stubborn: once per battle, a lethal foe move may leave the holder at 1 HP ----
    if (has(holder, 'stubborn')) {
      const state = {used: false};
      const rawDamage = holder.damage;
      holder.damage = function (d, source, effect) {
        let amount = d;
        try {
          if (!state.used && this.hp > 0 && typeof d === 'number' && d >= this.hp && source && source !== this && source.side !== this.side &&
              effect && typeof effect === 'object' && effect.effectType === 'Move' && of(this, {stubborn: 1}).some(chance)) {
            state.used = true;
            amount = this.hp - 1;
            note(battle, `${this.name} stubbornly held on!`);
          }
        } catch (err) { amount = d; }
        return rawDamage.call(this, amount, source, effect);
      };
    }

    // ---- momentum: the stacks end when the holder leaves the field (or faints) ----
    if (has(holder, MOMENTUM)) {
      const rawClear = holder.clearVolatile;
      holder.clearVolatile = function () {
        if (momentum.get(this) > 0) note(battle, `${this.name}'s Momentum faded.`);
        momentum.delete(this);
        return rawClear.apply(this, arguments);
      };
    }
  }
}

/**
 * The Hauntings that are mechanics rather than channel effects: Wisp, Hex, Pumpkin Lantern, Trick or Treat, Cobweb, Graveyard Chill,
 * Headless Rider and Reaper's Toll. Every hook is installed only on a Pokemon that holds the Haunting, wraps the engine's own method, fails
 * open to the native result, and does its damage and healing through plain log lines (a `[from]` the client does not know is misread).
 */
function applyHauntings(battle, byUuid, of, has) {
  const holds = ids => [...byUuid.values()].some(list => list.some(fx => ids.includes(fx.i)));
  if (!holds([...HAUNT_MECHANICS])) return;
  const chance = fx => battle.randomChance(Math.min(fx.p, WISP_FLINCH_CAP), 100);
  const effectOf = (id, name) => ({id, name, fullname: name, effectType: 'Status'});
  const holders = [];
  for (const side of battle.sides) {
    if (!side) continue;
    for (const holder of side.pokemon) if (byUuid.has(holder.uuid)) holders.push(holder);
  }
  const foesOf = mon => battle.sides.filter(side => side && side !== mon.side).flatMap(side => side.active).filter(foe => foe && foe.hp > 0);
  const isFoeMove = (source, target, effect) => !!source && source !== target && source.side !== target.side &&
    !!effect && typeof effect === 'object' && effect.effectType === 'Move';
  /** Chip damage from a Haunting: runs the engine's Damage event (Magic Guard and friends) and logs a plain -damage line. */
  const chip = (victim, amount, source, effect) => {
    try {
      if (!victim || !(victim.hp > 0)) return 0;
      const allowed = battle.runEvent('Damage', victim, source, effect, amount, true);
      if (!allowed && allowed !== 0) return 0;
      const dealt = victim.damage(Math.max(1, Math.trunc(allowed)), source, effect);
      if (dealt) battle.add('-damage', victim, victim.getHealth);
      return dealt || 0;
    } catch (err) { return 0; }
  };
  const restore = (mon, amount) => {
    try {
      if (mon.hp > 0 && mon.hp < mon.maxhp && mon.heal(Math.max(1, amount))) battle.add('-heal', mon, mon.getHealth);
    } catch (err) { /* fail open */ }
  };

  // ---- wisp: a damaging move that hit may make the target flinch (it only matters if the target has not moved yet) ----
  if (holds(['wisp']) && typeof battle.spreadDamage === 'function') {
    const rawSpread = battle.spreadDamage;
    const rolled = new Set();
    battle.spreadDamage = function (damage, targetArray, source, effect) {
      const result = rawSpread.apply(this, arguments);
      try {
        if (source && effect && typeof effect === 'object' && effect.effectType === 'Move' && Array.isArray(targetArray)) {
          const rolls = of(source, {wisp: 1});
          for (const [i, target] of targetArray.entries()) {
            if (!rolls.length || !target || target === source || target.side === source.side || !(target.hp > 0)) continue;
            if (!(typeof result[i] === 'number' && result[i] > 0)) continue;
            const key = `${this.turn}:${source.uuid}:${source.activeMoveActions}:${target.uuid}`;
            if (rolled.has(key)) continue;
            rolled.add(key);
            if (target.hasAbility('innerfocus') || target.hasAbility('shielddust')) continue;
            if (rolls.some(chance)) target.addVolatile('flinch', source, effectOf('ascensionwisp', 'Wisp'));
          }
        }
      } catch (err) { /* fail open: the hit already happened */ }
      return result;
    };
  }

  // ---- hex: at the end of each turn, foes of the holder with a status or a lowered stat lose 1/16 of their max HP ----
  if (holds(['hex']) && typeof battle.residualEvent === 'function') {
    const rawResidual = battle.residualEvent;
    battle.residualEvent = function (eventid) {
      const result = rawResidual.apply(this, arguments);
      if (eventid !== 'Residual') return result;
      for (const holder of holders) {
        try {
          if (!has(holder, 'hex') || !(holder.hp > 0) || !holder.isActive) continue;
          for (const foe of foesOf(holder)) {
            const lowered = Object.values(foe.boosts || {}).some(stage => stage < 0);
            if (!foe.status && !lowered) continue;
            chip(foe, Math.floor(foe.baseMaxhp / HEX_DIVISOR), holder, effectOf('ascensionhex', 'Hex'));
          }
        } catch (err) { /* fail open */ }
      }
      return result;
    };
  }

  // ---- pumpkin lantern and cobweb: on entering the field (the leads entered before this module installed) ----
  if (holds(['pumpkin_lantern', 'cobweb'])) {
    const enter = pokemon => {
      try {
        if (!pokemon || !(pokemon.hp > 0)) return;
        if (has(pokemon, 'pumpkin_lantern')) restore(pokemon, Math.floor(pokemon.maxhp / LANTERN_FRACTION));
        if (has(pokemon, 'cobweb')) {
          for (const foe of foesOf(pokemon)) battle.boost({spe: -1}, foe, pokemon, effectOf('ascensioncobweb', 'Cobweb'), true);
        }
      } catch (err) { /* fail open */ }
    };
    const actions = battle.actions;
    if (actions && typeof actions.runSwitch === 'function') {
      const rawRunSwitch = actions.runSwitch;
      actions.runSwitch = function (pokemon) {
        const result = rawRunSwitch.apply(this, arguments);
        enter(pokemon);
        return result;
      };
    }
    for (const side of battle.sides) {
      if (!side) continue;
      for (const lead of side.active) if (lead && lead.isActive && battle.turn <= 1) enter(lead);
    }
  }

  // ---- damage-taking hooks: trick or treat, graveyard chill, headless rider ----
  // The engine logs a move's -damage line AFTER target.damage() returns, so a heal or chip done inside the hook would be logged before the
  // hit it answers (the client would see HP rise and then fall). The reactions are therefore queued and run as soon as spreadDamage has
  // logged its damage.
  const reactions = [];
  const flush = () => {
    while (reactions.length) {
      const reaction = reactions.shift();
      try { reaction(); } catch (err) { /* fail open */ }
    }
  };
  let reactive = false;
  for (const holder of holders) {
    const wantsHit = has(holder, 'trick_or_treat') || has(holder, 'graveyard_chill') || has(holder, 'headless_rider');
    if (!wantsHit) continue;
    reactive = true;
    const state = {treated: false, ridden: false};
    const rawDamage = holder.damage;
    holder.damage = function (d, source, effect) {
      let amount = d;
      let rode = false;
      try {
        // Headless Rider: once per battle a lethal foe move leaves the holder at 1 HP.
        if (has(this, 'headless_rider') && !state.ridden && this.hp > 0 && typeof d === 'number' && d >= this.hp &&
            isFoeMove(source, this, effect)) {
          state.ridden = true;
          rode = true;
          amount = this.hp - 1;
          note(battle, `${this.name} rides on, headless!`);
        }
      } catch (err) { amount = d; rode = false; }
      const dealt = rawDamage.call(this, amount, source, effect);
      try {
        if (dealt > 0 && this.hp > 0 && isFoeMove(source, this, effect)) {
          const holderNow = this;
          if (rode) reactions.push(() => battle.boost({atk: 1, spa: 1}, holderNow, holderNow, effectOf('ascensionheadless', 'Headless Rider'), true));
          if (has(this, 'trick_or_treat') && !state.treated) {
            state.treated = true;
            reactions.push(() => restore(holderNow, Math.floor(holderNow.maxhp / LANTERN_FRACTION)));
          }
          if (has(this, 'graveyard_chill') && effect.flags && effect.flags.contact && source.hp > 0) {
            reactions.push(() => chip(source, Math.floor(source.baseMaxhp / CHILL_DIVISOR), holderNow, effectOf('ascensionchill', 'Graveyard Chill')));
          }
        }
      } catch (err) { /* fail open: the hit already happened */ }
      return dealt;
    };
  }
  if (reactive && typeof battle.spreadDamage === 'function') {
    const rawSpread = battle.spreadDamage;
    battle.spreadDamage = function () {
      const result = rawSpread.apply(this, arguments);
      flush();
      return result;
    };
  }

  // ---- reaper's toll: a direct move of the holder fainted a foe -> heal 1/3 and raise the better attacking stat ----
  if (holds(['reapers_toll']) && typeof battle.faintMessages === 'function') {
    const rawFaint = battle.faintMessages;
    battle.faintMessages = function () {
      const reapers = [];
      try {
        for (const entry of this.faintQueue) {
          if (entry && entry.source && entry.effect && entry.effect.effectType === 'Move' && entry.source !== entry.target &&
              has(entry.source, 'reapers_toll')) reapers.push(entry.source);
        }
      } catch (err) { /* fail open */ }
      const result = rawFaint.apply(this, arguments);
      for (const holder of reapers) {
        try {
          if (!(holder.hp > 0) || holder.fainted) continue;
          restore(holder, Math.floor(holder.maxhp / REAPER_FRACTION));
          const stat = holder.storedStats && holder.storedStats.spa > holder.storedStats.atk ? 'spa' : 'atk';
          battle.boost({[stat]: 1}, holder, holder, effectOf('ascensionreaper', "Reaper's Toll"), true);
        } catch (err) { /* fail open */ }
      }
      return result;
    };
  }
}

// ===================================================================================================================
// Transcendent powers (docs/TRANSCENDENT-POWERS.md). A Transcendent is a SIGNATURE (a new power made by a pair of Uniques, with
// its own pulse), a TWIST (one or two effects fired on every pulse, at most once per turn) and the harmony shares b (benefit) and
// d (drawback). The donor's rider arrives as an ordinary affix effect and the typed offence and defence as the two internal
// typed effects, so they need nothing here. A signature that is not implemented yet falls back to the two Uniques at their shares.
// ===================================================================================================================

const OP_SPECS = {
  status: {status: ['brn', 'psn', 'par', 'confusion'], chance: [1, 100]},
  foeStage: {stat: ['atk', 'def', 'spa', 'spd', 'spe', 'accuracy'], delta: [-2, -1]},
  selfStage: {stat: ['atk', 'def', 'spa', 'spd', 'spe'], delta: [1, 2], cap: [1, 6]},
  chip: {pct: [1, 10]}, drain: {pct: [1, 10]}, heal: {pct: [1, 10]},
  cleanse: {}, ward: {}, mimic: {}, strip: {}, unresistedNext: {},
  shield: {pct: [1, 50]}, boostNext: {pct: [1, 50]},
  healBoost: {pct: [1, 100], turns: [1, 5]}, hide: {pct: [1, 30], turns: [1, 5]}, refine: {pct: [1, 50], turns: [1, 5]},
};
const PULSE_STATS = ['atk', 'def', 'spa', 'spd', 'spe'];

/** A twist's effects, validated against the fixed vocabulary; null when anything is off (the whole Transcendent is then ignored). */
function cleanOps(raw) {
  if (!Array.isArray(raw) || raw.length < 1 || raw.length > 2) return null;
  const out = [];
  for (const entry of raw) {
    const spec = entry && typeof entry.op === 'string' && Object.prototype.hasOwnProperty.call(OP_SPECS, entry.op) ? OP_SPECS[entry.op] : null;
    if (!spec) return null;
    const keys = Object.keys(entry).filter(k => k !== 'op');
    if (keys.length !== Object.keys(spec).length || !keys.every(k => Object.prototype.hasOwnProperty.call(spec, k))) return null;
    const clean = {op: entry.op};
    for (const [key, allowed] of Object.entries(spec)) {
      const value = entry[key];
      if (typeof allowed[0] === 'string') {
        if (!allowed.includes(value)) return null;
      } else if (!Number.isInteger(value) || value < allowed[0] || value > allowed[1]) {
        return null;
      }
      clean[key] = value;
    }
    out.push(clean);
  }
  return out;
}

const REFINE_NONE = 0;
const newTranscendState = () => ({
  pulseTurn: -1, armedBy: '', clutchUsed: false,
  shield: 0, shieldActive: 0, shieldKey: '', boost: 0, boostActive: 0, boostKey: '', unresisted: false, unresistedActive: false, unresistedKey: '',
  ward: false, healBoost: 0, healBoostUntil: -1, hide: 0, hideUntil: -1, refine: REFINE_NONE, refineUntil: -1,
});

/** Survive a lethal foe move once per battle: at 1 HP, or brought up to remainOf(mon) HP (a plain heal that no cap refuses). */
function clutch(t, remainOf, message, onTrigger) {
  const mon = t.mon;
  const battle = t.env.battle;
  const inner = mon.damage;
  mon.damage = function (d, source, effect) {
    let amount = d;
    let remain = 0;
    try {
      if (!t.st.clutchUsed && this.hp > 0 && typeof d === 'number' && d >= this.hp && source && source !== this && source.side !== this.side &&
          effect && typeof effect === 'object' && effect.effectType === 'Move') {
        t.st.clutchUsed = true;
        amount = this.hp - 1;
        remain = remainOf(this);
        note(battle, `${this.name} ${message}`);
        if (onTrigger) onTrigger();
      }
    } catch (err) { amount = d; remain = 0; }
    const result = inner.call(this, amount, source, effect);
    try {
      if (remain > 1 && this.hp > 0 && this.hp < remain && t.env.rawHeal.call(this, remain - this.hp)) battle.add('-heal', this, this.getHealth);
    } catch (err) { /* fail open */ }
    return result;
  };
}

const transcendEffect = {id: 'ascensiontranscendent', name: 'Transcendent', fullname: 'transcendent', effectType: 'Status'};

/** A drawback or benefit multiplier moved toward 1 by a share: soften(0.85, 0.5) = 0.925, soften(1.25, 0.5) = 1.125. */
const soften = (multiplier, share) => 1 + (multiplier - 1) * share;

// ---- helpers the signatures share ---------------------------------------------------------------------------------------------

/** Damage the holder to pay a price (never to a faint: a price is not a kill). A plain -damage line, like the other residuals. */
function selfDamage(t, amount) {
  const mon = t.mon;
  const cost = Math.min(Math.floor(amount), mon.hp - 1);
  if (cost < 1) return 0;
  const dealt = mon.damage(cost, null, transcendEffect);
  if (dealt) t.env.battle.add('-damage', mon, mon.getHealth);
  return dealt;
}

/** Damage a foe outside any move (a tick, a counter): plain -damage line, so a client that does not know the cause is not confused. */
function chipFoe(t, foe, amount) {
  if (!foe || !(foe.hp > 0)) return 0;
  const dealt = foe.damage(Math.max(1, Math.floor(amount)), t.mon, transcendEffect);
  if (dealt) t.env.battle.add('-damage', foe, foe.getHealth);
  return dealt;
}

/** Healing the holder receives is cut by this fraction of the drawback share (a plain heal, a drain, a twist: all of it). */
function cutHealing(t, fraction) {
  const inner = t.mon.heal;
  t.mon.heal = function (amount, source, effect) {
    if (typeof amount === 'number' && amount > 0) amount = Math.max(1, Math.floor(amount * (1 - fraction * t.S)));
    return inner.call(this, amount, source, effect);
  };
}

/** The holder's Speed stat (after the engine's own modifiers) is lowered by this fraction of the drawback share. */
function slowSpeed(t, fraction) {
  const inner = t.mon.getStat;
  t.mon.getStat = function (statName, unboosted, unmodified) {
    const stat = inner.apply(this, arguments);
    try {
      if (statName === 'spe' && !unmodified && typeof stat === 'number') {
        return Math.max(1, t.env.battle.modify(stat, Math.round((1 - fraction * t.S) * 1000), 1000));
      }
    } catch (err) { /* fail open */ }
    return stat;
  };
}

/** Adds percent to the residual channel for a burn / poison tick this holder inflicted (percentOf(victim) is read at tick time). */
function boostStatus(t, cause, percentOf) {
  t.env.shared.statusBoosts.push((inflictor, victim, id) => (inflictor === t.mon && id === cause ? percentOf(victim) : 0));
}

/** Starts a bleed on a foe, once per move use and target. */
function bleedOn(t, foe, moveKey, opts) {
  const shared = t.env.shared;
  const key = `sg:${moveKey}:${foe.uuid}`;
  if (shared.bleedKeys.has(key)) return;
  shared.bleedKeys.add(key);
  shared.inflictBleed(t.mon, foe, opts || {});
}

/** True the first time it is asked for this move use (a flat bonus is paid once per move, however many targets or hits). */
function oncePerMove(t, field, ctx) {
  if (t.st[field] === ctx.key) return false;
  t.st[field] = ctx.key;
  return true;
}

/** The foes of the holder that are on the field and standing. */
const activeFoes = t => t.env.battle.sides.flatMap(side => (side ? side.active : [])).filter(foe => foe && foe.side !== t.mon.side && foe.hp > 0);

/**
 * A super-effective hit adds percent of the holder's max HP (times the benefit share) once per move use, and remembers the target so
 * that postHit can follow up on the same hit (see {@link statusAfterSuperEffective}). Returns the flat damage to add, or 0.
 */
function superEffectiveAdd(t, ctx, percent) {
  if (!(ctx.hit.typeMod > 0) || !oncePerMove(t, 'seKey', ctx)) return 0;
  t.st.seTarget = ctx.target;
  return Math.floor(t.mon.maxhp * percent / 100 * t.P);
}

/** After a super-effective hit that paid {@link superEffectiveAdd}: the foe may get a status, at chancePercent times the benefit share. */
function statusAfterSuperEffective(t, role, foe, moveKey, status, chancePercent) {
  if (role !== 'hit' || t.st.seKey !== moveKey || t.st.seTarget !== foe || !foe || foe.status || !(foe.hp > 0)) return;
  if (t.chance(chancePercent * t.P)) foe.trySetStatus(status, t.mon, transcendEffect);
}

/** True for a landed physical hit on a foe (alive or not): the trigger of the bleeding signatures. */
const physicalHit = (role, foe, move) => role === 'hit' && !!foe && !!move && move.category === 'Physical';

const weatherMove = (ctx, move) => !!ctx.weather && WEATHER_TYPE[ctx.weather] === move.type;
const isStatused = foe => ['brn', 'psn', 'tox'].includes(foe.status);

// ---- the signatures -------------------------------------------------------------------------------------------------------------
// Each: its pulse, optional hooks flags (status: burn/poison tick hook; bleed: bleed engine), forceCrit, an install (wraps the holder
// once), outgoing/incoming damage adjustments (the numbers act after the caps, like the Uniques' drawbacks), postHit(t, role, foe,
// moveKey, move), tick(t), onKo(t). t is the holder's context: {mon, fx, env, st, P (benefit share 0..1), S (drawback share, softened
// by Refine), chance(p)}. Item-reward bonuses are Java-side and not here.
const TRANSCEND_SIGNATURES = {
  phoenix_cinder: {
    pulse: 'STRUCK',
    install(t) {
      // Clutch: a lethal hit leaves 20% HP (times the benefit share) instead of fainting.
      clutch(t, mon => Math.max(1, Math.floor(mon.maxhp * 0.20 * t.P)), 'rose from the cinders!');
      // Drawback: healing cannot raise the holder above 100% - 30% x the drawback share of max HP.
      const inner = t.mon.heal;
      t.mon.heal = function (amount, source, effect) {
        if (typeof amount === 'number' && amount > 0) {
          const ceiling = Math.floor(this.maxhp * (1 - 0.30 * t.S));
          const room = ceiling - this.hp;
          if (room <= 0) return 0;
          amount = Math.min(amount, room);
        }
        return inner.call(this, amount, source, effect);
      };
    },
    // Core: attackers that hit the holder while it is below half HP are burned.
    postHit(t, role, foe) {
      if (role !== 'struck' || !foe || !(foe.hp > 0) || foe.status || !(t.mon.hp < t.mon.maxhp / 2) || !t.chance(100 * t.P)) return;
      foe.trySetStatus('brn', t.mon, transcendEffect);
    },
  },

  plague_pyre: {
    pulse: 'TICK',
    // Core: burned or poisoned foes take an extra 3% of max HP each turn.
    tick(t) {
      for (const foe of activeFoes(t)) if (isStatused(foe)) chipFoe(t, foe, foe.maxhp * 0.03 * t.P);
    },
    // Drawback: direct damage 10% lower.
    outgoing(t) { return {mult: soften(0.90, t.S)}; },
  },

  wildfire_crown: {
    pulse: 'HIT',
    // Core: Fire moves +20%, and while any weather is active every move +10%. Drawback: Water and Ice moves 25% weaker.
    outgoing(t, ctx) {
      let mult = 1;
      if (ctx.move.type === 'Fire') mult *= 1 + 0.20 * t.P;
      if (ctx.weather) mult *= 1 + 0.10 * t.P;
      if (ctx.move.type === 'Water' || ctx.move.type === 'Ice') mult *= soften(0.75, t.S);
      return {mult};
    },
  },

  brand_of_ruin: {
    pulse: 'HIT',
    hooks: {status: true},
    install(t) {
      t.st.brands = new Map();   // foe -> the last turn it stays branded
      const branded = foe => (t.st.brands.get(foe) || -1) >= t.env.battle.turn;
      t.env.shared.statusVictimMult.push((victim, cause) => (branded(victim) && (cause === 'brn' || cause === 'bleed') ? 1 + 0.30 * t.P : 1));
      t.branded = branded;
    },
    // Core: a damaging hit Brands the foe for 3 turns. Drawback: a NEW Brand costs 3% of max HP (a refresh is free).
    postHit(t, role, foe) {
      if (role !== 'hit' || !foe || !(foe.hp > 0)) return;
      const fresh = !t.branded(foe);
      t.st.brands.set(foe, t.env.battle.turn + 3);
      if (fresh) {
        note(t.env.battle, `${foe.name} is branded!`);
        selfDamage(t, t.mon.maxhp * 0.03 * t.S);
      }
    },
    // Core: a branded foe takes 10% more from the holder.
    outgoing(t, ctx) { return t.branded && t.branded(ctx.target) ? {mult: 1 + 0.10 * t.P} : null; },
  },

  titans_forge: {
    pulse: 'HIT',
    // Core: a super-effective hit adds 8% of the holder's max HP in damage and may burn.
    outgoing(t, ctx) {
      const add = superEffectiveAdd(t, ctx, 8);
      return add ? {add} : null;
    },
    postHit(t, role, foe, moveKey) { statusAfterSuperEffective(t, role, foe, moveKey, 'brn', 25); },
    // Drawback: lose 3% of max HP each turn while above 60% HP.
    tick(t) { if (t.mon.hp > t.mon.maxhp * 0.6) selfDamage(t, t.mon.maxhp * 0.03 * t.S); },
  },

  gilded_ember: {
    pulse: 'KO',
    hooks: {status: true},
    // Core: burn damage the holder inflicts +30%; each KO adds +10% damage, up to 3 times, until it leaves the field.
    install(t) { boostStatus(t, 'brn', () => 30 * t.P); },
    outgoing(t) { return t.st.koStacks ? {mult: 1 + 0.10 * t.P * t.st.koStacks} : null; },
    onKo(t) { t.st.koStacks = Math.min(3, (t.st.koStacks || 0) + 1); },
    // Drawback: 15% more damage from Water moves.
    incoming(t, ctx) { return ctx.move.type === 'Water' ? soften(1.15, t.S) : 1; },
  },

  dying_bloom: {
    pulse: 'STRUCK',
    hooks: {status: true},
    install(t) {
      clutch(t, () => 1, 'hung on with a dying bloom!');
      cutHealing(t, 0.35);
      // Core: poison the holder inflicts ramps +20% a turn it has lasted (to +80%), twice as fast while the holder is below half HP.
      const ticks = new Map();
      t.env.shared.statusBoosts.push((inflictor, victim, id) => {
        if (inflictor !== t.mon || id !== 'psn') return 0;
        let entry = ticks.get(victim);
        if (!entry || entry.state !== victim.statusState) { entry = {state: victim.statusState, ticks: 0}; ticks.set(victim, entry); }
        const step = (t.mon.hp < t.mon.maxhp / 2 ? 40 : 20) * t.P;
        const ramp = Math.min(80 * t.P, entry.ticks * step);
        entry.ticks++;
        return ramp;
      });
    },
    // Core: attackers that hit the holder while it is below half HP are poisoned.
    postHit(t, role, foe) {
      if (role !== 'struck' || !foe || !(foe.hp > 0) || foe.status || !(t.mon.hp < t.mon.maxhp / 2) || !t.chance(100 * t.P)) return;
      foe.trySetStatus('psn', t.mon, transcendEffect);
    },
  },

  martyrs_edge: {
    pulse: 'HIT',
    hooks: {bleed: true},
    forceCrit: true,
    // Clutch: a lethal hit leaves 1 HP and the holder's next move is a guaranteed critical hit.
    install(t) { clutch(t, () => 1, 'fought on as a martyr!', () => { t.st.crit = true; }); },
    // Core: physical hits bleed (Rupture's weaker stacks are the drawback) and the holder deals 12% more to a bleeding foe.
    postHit(t, role, foe, moveKey, move) {
      if (physicalHit(role, foe, move) && foe.hp > 0) bleedOn(t, foe, moveKey, {weights: RUPTURE_WEIGHTS.map(w => soften(w, t.S))});
    },
    outgoing(t, ctx) { return t.env.shared.isBleeding(ctx.target) ? {mult: 1 + 0.12 * t.P} : null; },
  },

  colossus_vow: {
    pulse: 'STRUCK',
    install(t) {
      clutch(t, () => 1, 'stood firm!');
      slowSpeed(t, 0.15);
    },
    // Core: 15% less damage from a super-effective hit, and that hit is countered for 6% of the holder's max HP.
    incoming(t, ctx) {
      if (!(ctx.hit.typeMod > 0)) return 1;
      t.st.struckSeKey = ctx.key;
      return 1 - 0.15 * t.P;
    },
    postHit(t, role, foe, moveKey) {
      if (role === 'struck' && t.st.struckSeKey === moveKey && oncePerMove(t, 'counterKey', {key: moveKey})) chipFoe(t, foe, t.mon.maxhp * 0.06 * t.P);
    },
  },

  last_gamble: {
    pulse: 'KO',
    // Clutch: a lethal hit leaves 30% HP and the next two moves deal +30%. Drawback: after it fires, 25% more damage for the rest of the battle.
    install(t) {
      clutch(t, mon => Math.max(1, Math.floor(mon.maxhp * 0.30 * t.P)), 'bet everything and survived!', () => {
        t.st.gambled = true;
        t.st.gambleLeft = 2;
      });
    },
    outgoing(t, ctx) {
      if (!(t.st.gambleLeft > 0) && t.st.gambleKey !== ctx.key) return null;
      if (t.st.gambleKey !== ctx.key) { t.st.gambleKey = ctx.key; t.st.gambleLeft--; }
      return {mult: 1 + 0.30 * t.P};
    },
    incoming(t) { return t.st.gambled ? 1 + 0.25 * t.S : 1; },
  },

  miasma_front: {
    pulse: 'TICK',
    hooks: {status: true},
    install(t) { boostStatus(t, 'psn', () => 20 * t.P); },
    // Core: while any weather is active every foe takes 2.5% of max HP a turn, rising 1% a turn to 5%.
    // Drawback: with no weather the holder loses 2% of max HP each turn.
    tick(t) {
      if (t.env.weatherNow()) {
        t.st.miasmaTurns = (t.st.miasmaTurns || 0) + 1;
        const percent = Math.min(5, 2.5 + (t.st.miasmaTurns - 1)) * t.P;
        for (const foe of activeFoes(t)) chipFoe(t, foe, foe.maxhp * percent / 100);
      } else {
        t.st.miasmaTurns = 0;
        selfDamage(t, t.mon.maxhp * 0.02 * t.S);
      }
    },
  },

  festering_gash: {
    pulse: 'HIT',
    hooks: {bleed: true},
    // Core: physical hits bleed; the bleed grows 15% a turn (to 60%) and the holder heals 25% of the bleed damage it deals.
    postHit(t, role, foe, moveKey, move) {
      if (physicalHit(role, foe, move) && foe.hp > 0) bleedOn(t, foe, moveKey, {growth: {step: 15 * t.P, max: 60 * t.P}, leech: 0.25 * t.P});
    },
    // Drawback: 20% more damage from Psychic moves.
    incoming(t, ctx) { return ctx.move.type === 'Psychic' ? soften(1.20, t.S) : 1; },
  },

  blighted_giant: {
    pulse: 'HIT',
    hooks: {status: true},
    install(t) {
      boostStatus(t, 'psn', () => 25 * t.P);
      cutHealing(t, 0.60);
    },
    // Core: a super-effective hit adds 7% of the holder's max HP in damage and poisons the foe.
    outgoing(t, ctx) {
      const add = superEffectiveAdd(t, ctx, 7);
      return add ? {add} : null;
    },
    postHit(t, role, foe, moveKey) { statusAfterSuperEffective(t, role, foe, moveKey, 'psn', 100); },
  },

  fortunes_rot: {
    pulse: 'TICK',
    // Core: foes the holder has poisoned lose an extra 3% of max HP each turn. (Item rewards +15% are Java-side.)
    tick(t) {
      for (const foe of activeFoes(t)) {
        const poisonedByHolder = (foe.status === 'psn' || foe.status === 'tox') && foe.statusState && foe.statusState.source === t.mon;
        if (poisonedByHolder) chipFoe(t, foe, foe.maxhp * 0.03 * t.P);
      }
    },
    // Drawback: 20% more damage taken.
    incoming(t) { return 1 + 0.20 * t.S; },
  },

  lightning_rend: {
    pulse: 'HIT',
    hooks: {bleed: true},
    // Core: weather-type moves and Electric moves deal 25% more and bleed whatever their category. Drawback: with no weather, 20% less direct damage.
    outgoing(t, ctx) {
      let mult = 1;
      if (weatherMove(ctx, ctx.move) || ctx.move.type === 'Electric') mult *= 1 + 0.25 * t.P;
      if (!ctx.weather) mult *= soften(0.80, t.S);
      return {mult};
    },
    postHit(t, role, foe, moveKey, move) {
      if (role !== 'hit' || !foe || !(foe.hp > 0) || !move) return;
      if (move.type === 'Electric' || (t.env.weatherNow() && WEATHER_TYPE[t.env.weatherNow()] === move.type)) bleedOn(t, foe, moveKey, {});
    },
  },

  tempest_colossus: {
    pulse: 'HIT',
    install(t) { slowSpeed(t, 0.12); },
    // Core: weather-type moves +20%, and a super-effective hit adds 7% of the holder's max HP in damage.
    outgoing(t, ctx) {
      const out = {};
      if (weatherMove(ctx, ctx.move)) out.mult = 1 + 0.20 * t.P;
      out.add = superEffectiveAdd(t, ctx, 7);
      return out.mult || out.add ? out : null;
    },
  },

  skyfall_fortune: {
    pulse: 'KO',
    // Core: weather-type moves +25%; each KO while a weather is active adds +12% damage (3 stacks, until the holder leaves).
    outgoing(t, ctx) {
      let mult = 1;
      if (weatherMove(ctx, ctx.move)) mult *= 1 + 0.25 * t.P;
      if (t.st.koStacks) mult *= 1 + 0.12 * t.P * t.st.koStacks;
      return mult !== 1 ? {mult} : null;
    },
    onKo(t) { if (t.env.weatherNow()) t.st.koStacks = Math.min(3, (t.st.koStacks || 0) + 1); },
    // Drawback: with no weather, 20% more damage taken.
    incoming(t, ctx) { return ctx.weather ? 1 : 1 + 0.20 * t.S; },
  },

  breakers_might: {
    pulse: 'HIT',
    hooks: {bleed: true},
    install(t) { cutHealing(t, 0.50); },
    // Core: physical hits bleed; a super-effective hit adds 8% of the holder's max HP in damage, and 10% more against a bleeding foe.
    outgoing(t, ctx) {
      const out = {};
      out.add = superEffectiveAdd(t, ctx, 8);
      if (ctx.hit.typeMod > 0 && t.env.shared.isBleeding(ctx.target)) out.mult = 1 + 0.10 * t.P;
      return out.mult || out.add ? out : null;
    },
    postHit(t, role, foe, moveKey, move) {
      if (physicalHit(role, foe, move) && foe.hp > 0) bleedOn(t, foe, moveKey, {});
    },
  },

  wager_of_blood: {
    pulse: 'KO',
    hooks: {bleed: true},
    // Core: physical hits bleed and each KO restores 10% of max HP. Drawback: every physical move that hits costs 2% of max HP.
    postHit(t, role, foe, moveKey, move) {
      if (!physicalHit(role, foe, move)) return;
      if (foe.hp > 0) bleedOn(t, foe, moveKey, {});
      if (oncePerMove(t, 'costKey', {key: moveKey})) selfDamage(t, t.mon.maxhp * 0.02 * t.S);
    },
    onKo(t) {
      if (t.mon.heal(Math.max(1, Math.floor(t.mon.maxhp * 0.10 * t.P)))) t.env.battle.add('-heal', t.mon, t.mon.getHealth);
    },
  },

  eye_of_the_storm: {
    pulse: 'TICK',
    install(t) { clutch(t, () => 1, 'held on in the eye of the storm!'); },
    // Core: under any weather take 12% less damage; drawback: with none take 12% more.
    incoming(t, ctx) { return ctx.weather ? 1 - 0.12 * t.P : 1 + 0.12 * t.S; },
    // Core: under any weather heal 3% of max HP each turn.
    tick(t) {
      if (!t.env.weatherNow()) return;
      if (t.mon.heal(Math.max(1, Math.floor(t.mon.maxhp * 0.03 * t.P)))) t.env.battle.add('-heal', t.mon, t.mon.getHealth);
    },
  },

  jackpot_titan: {
    pulse: 'KO',
    // Core: a super-effective hit adds 10% of the holder's max HP in damage, once per move use. (Item rewards +20% are a Java-side bonus.)
    outgoing(t, ctx) {
      const add = superEffectiveAdd(t, ctx, 10);
      return add ? {add} : null;
    },
    // Drawback: 22% more damage taken.
    incoming(t) { return 1 + 0.22 * t.S; },
  },
};

function applyTranscendents(env) {
  const {battle, byUuid} = env;
  const holders = new Map();
  for (const side of battle.sides) {
    if (!side) continue;
    for (const mon of side.pokemon) {
      const fx = (byUuid.get(mon.uuid) || []).find(e => e.i === 'transcendent_sig');
      if (!fx) continue;
      const st = newTranscendState();
      const t = {
        mon, fx, env, st, P: fx.b / 100,
        get S() {
          const refined = st.refineUntil >= battle.turn ? st.refine : 0;
          return (fx.d / 100) * (1 - refined);
        },
        chance: p => battle.randomChance(Math.max(0, Math.min(100, Math.round(p))), 100),
      };
      holders.set(mon, t);
    }
  }
  if (!holders.size) return null;
  const sigOf = t => TRANSCEND_SIGNATURES[t.fx.sg];
  const foesOf = mon => { try { return mon.foes().filter(f => f.hp > 0); } catch (err) { return []; } };

  // ---- the twist's effects ----
  const OPS = {
    status(t, op, targets) {
      for (const foe of targets) {
        if (!(foe.hp > 0) || !t.chance(op.chance * t.P)) continue;
        if (op.status === 'confusion') foe.addVolatile('confusion', t.mon, transcendEffect);
        else if (!foe.status) foe.trySetStatus(op.status, t.mon, transcendEffect);
      }
    },
    foeStage(t, op, targets) {
      for (const foe of targets) if (foe.hp > 0) battle.boost({[op.stat]: op.delta}, foe, t.mon, transcendEffect, true);
    },
    selfStage(t, op) {
      if (t.mon.boosts[op.stat] < op.cap) battle.boost({[op.stat]: op.delta}, t.mon, t.mon, transcendEffect, false, true);
    },
    chip(t, op, targets) {
      for (const foe of targets) {
        if (!(foe.hp > 0)) continue;
        if (foe.damage(Math.max(1, Math.floor(foe.maxhp * op.pct * t.P / 100)), t.mon, transcendEffect)) battle.add('-damage', foe, foe.getHealth);
      }
    },
    drain(t, op, targets) {
      const foe = targets[0];
      if (!foe || !(foe.hp > 0)) return;
      const dealt = foe.damage(Math.max(1, Math.floor(foe.maxhp * op.pct * t.P / 100)), t.mon, transcendEffect);
      if (!dealt) return;
      battle.add('-damage', foe, foe.getHealth);
      if (t.mon.hp > 0 && t.mon.heal(dealt)) battle.add('-heal', t.mon, t.mon.getHealth);
    },
    heal(t, op) {
      if (t.mon.heal(Math.max(1, Math.floor(t.mon.maxhp * op.pct * t.P / 100)))) battle.add('-heal', t.mon, t.mon.getHealth);
    },
    cleanse(t) { if (t.mon.status) t.mon.cureStatus(); },
    ward(t) { t.st.ward = true; },
    mimic(t, op, targets) {
      const foe = targets[0];
      if (!foe) return;
      let best = null;
      for (const stat of PULSE_STATS) if (foe.boosts[stat] > 0 && (!best || foe.boosts[stat] > foe.boosts[best])) best = stat;
      if (best && t.mon.boosts[best] < 2) battle.boost({[best]: Math.min(2, foe.boosts[best])}, t.mon, t.mon, transcendEffect, false, true);
    },
    strip(t, op, targets) {
      for (const foe of targets) {
        for (const stat of ['def', 'spd']) {
          if (foe.boosts[stat] > 0) { foe.boosts[stat] = 0; battle.add('-setboost', foe, stat, 0); }
        }
      }
    },
    unresistedNext(t) { t.st.unresisted = true; },
    shield(t, op) { t.st.shield = Math.max(t.st.shield, op.pct * t.P / 100); },
    boostNext(t, op) { t.st.boost = Math.max(t.st.boost, op.pct * t.P / 100); },
    healBoost(t, op) { t.st.healBoost = op.pct * t.P / 100; t.st.healBoostUntil = battle.turn + op.turns; },
    hide(t, op) { t.st.hide = op.pct * t.P / 100; t.st.hideUntil = battle.turn + op.turns; },
    refine(t, op) { t.st.refine = Math.min(0.9, op.pct * t.P / 100); t.st.refineUntil = battle.turn + op.turns; },
  };

  // moveKey names the move use that caused the pulse (null for a KO or the end of a turn). An effect armed by a move's own hit is
  // for the NEXT move use: without this, a "next hit taken" shield armed by hit one of a two-hit move would also soften hit two.
  const pulse = (t, kind, foe, moveKey) => {
    try {
      const sig = sigOf(t);
      if (!sig || sig.pulse !== kind || !(t.mon.hp > 0) || t.st.pulseTurn === battle.turn) return;
      t.st.pulseTurn = battle.turn;
      t.st.armedBy = moveKey || '';
      const targets = foe && foe.hp > 0 ? [foe] : foesOf(t.mon);
      for (const op of t.fx.tw || []) {
        if (OPS[op.op]) OPS[op.op](t, op, targets);
      }
    } catch (err) { /* a twist must never cost a battle */ }
  };

  // ---- install: wrappers on each holder ----
  for (const t of holders.values()) {
    const mon = t.mon;
    // Ward: the next status condition a foe inflicts fails.
    const innerStatus = mon.setStatus;
    mon.setStatus = function (status, source) {
      try {
        const id = status && typeof status === 'object' ? status.id : status;
        const inflictor = source || (battle.event && battle.event.source);
        if (t.st.ward && id && this.hp > 0 && this.status !== id && inflictor && inflictor !== this && inflictor.side && inflictor.side !== this.side) {
          t.st.ward = false;
          note(battle, `${this.name} shrugged off the condition!`);
          return false;
        }
      } catch (err) { /* fail open */ }
      return innerStatus.apply(this, arguments);
    };
    // Feast: for a few turns the holder's own healing moves heal more.
    const innerHeal = mon.heal;
    mon.heal = function (amount, source, effect) {
      try {
        const ownMove = battle.activePokemon === this && battle.activeMove &&
          ((effect && effect.id === 'drain') || !effect || effect.effectType === 'Move');
        if (t.st.healBoostUntil >= battle.turn && ownMove && typeof amount === 'number') amount = Math.floor(amount * (1 + t.st.healBoost));
      } catch (err) { /* fail open */ }
      return innerHeal.call(this, amount, source, effect);
    };
    // What a signature counts "while on the field" (KO stacks, a rising tick) ends when the holder leaves it.
    const innerClear = mon.clearVolatile;
    mon.clearVolatile = function () {
      t.st.koStacks = 0;
      t.st.miasmaTurns = 0;
      return innerClear.apply(this, arguments);
    };
    const sig = sigOf(t);
    if (sig && sig.install) sig.install(t);
  }

  // ---- a guaranteed critical hit (Martyr's Edge): the move use after the clutch, every hit and target of it ----
  const actions = battle.actions;
  if ([...holders.values()].some(t => sigOf(t) && sigOf(t).forceCrit) && actions && typeof actions.getDamage === 'function') {
    const rawGetDamage = actions.getDamage;
    actions.getDamage = function (source, target, move) {
      let forced = false;
      try {
        const t = holders.get(source);
        if (t && move && typeof move === 'object' && move.willCrit === undefined && !move.damage && !move.ohko) {
          const key = `${battle.turn}:${source.uuid}:${source.activeMoveActions}`;
          if (t.st.critKey === key || (t.st.crit && t.st.critKey !== key)) {
            t.st.crit = false;
            t.st.critKey = key;
            move.willCrit = true;
            forced = true;
          }
        }
      } catch (err) { forced = false; }
      try {
        return rawGetDamage.apply(this, arguments);
      } finally {
        if (forced) move.willCrit = undefined;
      }
    };
  }

  // ---- pulses: HIT and STRUCK after a damaging move connects ----
  const rawSpread = battle.spreadDamage;
  if (typeof rawSpread === 'function') {
    battle.spreadDamage = function (damage, targetArray, source, effect) {
      const result = rawSpread.apply(this, arguments);
      try {
        if (source && effect && typeof effect === 'object' && effect.effectType === 'Move' && Array.isArray(targetArray)) {
          for (const [i, target] of targetArray.entries()) {
            if (!target || target === source || target.side === source.side || !(typeof result[i] === 'number' && result[i] > 0)) continue;
            const moveKey = `${battle.turn}:${source.uuid}:${source.activeMoveActions}`;   // the same key adjust() uses for this move use
            const attacker = holders.get(source);
            if (attacker) {
              const sig = sigOf(attacker);
              if (sig && sig.postHit) sig.postHit(attacker, 'hit', target, moveKey, effect);
              pulse(attacker, 'HIT', target, moveKey);
            }
            const defender = holders.get(target);
            if (defender) {
              const sig = sigOf(defender);
              if (sig && sig.postHit) sig.postHit(defender, 'struck', source, moveKey, effect);
              pulse(defender, 'STRUCK', source, moveKey);
            }
          }
        }
      } catch (err) { /* fail open: the hit already happened */ }
      return result;
    };
  }

  // ---- KO ----
  const rawFaint = battle.faintMessages;
  if (typeof rawFaint === 'function') {
    battle.faintMessages = function () {
      const killers = [];
      try {
        for (const entry of this.faintQueue) {
          if (entry && entry.source && entry.target && entry.effect && entry.effect.effectType === 'Move' && entry.source !== entry.target &&
              entry.source.side !== entry.target.side && holders.has(entry.source) && !killers.includes(entry.source)) killers.push(entry.source);
        }
      } catch (err) { /* fail open */ }
      const result = rawFaint.apply(this, arguments);
      for (const killer of killers) {
        const t = holders.get(killer);
        if (!t || !(killer.hp > 0)) continue;
        const sig = sigOf(t);
        try { if (sig && sig.onKo) sig.onKo(t); } catch (err) { /* fail open */ }
        pulse(t, 'KO', null);
      }
      return result;
    };
  }

  // ---- TICK: the end of every turn ----
  const rawResidual = battle.residualEvent;
  if (typeof rawResidual === 'function') {
    battle.residualEvent = function (eventid) {
      const result = rawResidual.apply(this, arguments);
      if (eventid !== 'Residual') return result;
      for (const t of holders.values()) {
        try {
          if (!(t.mon.hp > 0) || !t.mon.isActive) continue;
          const sig = sigOf(t);
          if (sig && sig.tick) sig.tick(t);
          pulse(t, 'TICK', null);
        } catch (err) { /* fail open */ }
      }
      return result;
    };
  }

  /** Per-move-use consumption of a pending state: the same move use (all its hits and targets) sees it, the next one does not. */
  const consume = (st, pending, active, keyField, key) => {
    if (st[active] && st[keyField] === key) return st[active];
    st[active] = typeof st[pending] === 'boolean' ? false : 0;
    if (st[pending] && st.armedBy !== key) { st[active] = st[pending]; st[pending] = typeof st[pending] === 'boolean' ? false : 0; st[keyField] = key; return st[active]; }
    return st[active];
  };

  return {
    /** The damage of one hit after the signature, the pending twist states and the drawbacks have their say. */
    adjust(attacker, target, move, hit, scaled, weather) {
      let out = scaled;
      try {
        const key = `${battle.turn}:${attacker.uuid}:${attacker.activeMoveActions}`;
        const a = holders.get(attacker);
        if (a) {
          const sig = sigOf(a);
          if (a.st.unresisted || a.st.unresistedActive) {
            if (consume(a.st, 'unresisted', 'unresistedActive', 'unresistedKey', key) && hit.typeMod < 0) out = Math.round(out * Math.pow(2, -hit.typeMod));
          }
          const boost = consume(a.st, 'boost', 'boostActive', 'boostKey', key);
          if (boost) out = battle.modify(out, Math.round((1 + boost) * 1000), 1000);
          const extra = sig && sig.outgoing ? sig.outgoing(a, {move, hit, weather, key, target, attacker}) : null;
          if (extra) {
            if (extra.mult) out = battle.modify(out, Math.round(extra.mult * 1000), 1000);
            if (extra.add) out += extra.add;
          }
        }
        const d = holders.get(target);
        if (d) {
          const sig = sigOf(d);
          const mult = sig && sig.incoming ? sig.incoming(d, {attacker, move, hit, weather, key, target}) : 1;
          if (mult !== 1) out = Math.max(1, battle.modify(out, Math.round(mult * 1000), 1000));
          if (d.st.hideUntil >= battle.turn && d.st.hide) out = Math.max(1, battle.modify(out, Math.round((1 - d.st.hide) * 1000), 1000));
          const shield = consume(d.st, 'shield', 'shieldActive', 'shieldKey', key);
          if (shield) out = Math.max(1, battle.modify(out, Math.round((1 - shield) * 1000), 1000));
        }
      } catch (err) {
        return scaled;
      }
      return out;
    },
  };
}

function install(sim) {
  const Battle = sim.Battle;
  const oldStart = Battle.prototype.start;
  Battle.prototype.start = function () {
    const result = oldStart.apply(this, arguments);
    if (this.deserialized) return result;   // a restored battle must not re-apply what already happened
    const payload = this.format && this.format.ascensionFx;
    if (!payload || typeof payload !== 'object' || payload.v !== 1) return result;
    try {
      const count = apply(this, payload);
      // To the server log as well as the battle: the Java side logs what it HANDED to Showdown, and only this line proves the
      // JavaScript received it and applied it.
      console.log(`[AscensionLib] Applied ascension effects to ${count} Pokemon in a battle.`);
    } catch (err) {
      note(this, 'Ascension effects could not be applied.');
    }
    return result;
  };
}

module.exports = {install, apply, parse, HANDLED, MAX_MONS, MAX_EFFECTS};

// Loaded by Showdown (through CobbleRaids' extension loader): install against the simulator beside this file. The tests
// set the flag and call install() themselves against a simulator they loaded.
if (!globalThis.__ASCENSION_FX_TEST__) install({Battle: require('./sim/battle').Battle});
