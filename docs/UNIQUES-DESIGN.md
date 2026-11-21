# Unique powers: design and tuning (2026-10-05)

Owner decisions: build all four first-batch Uniques; every Unique has a fixed drawback; any Pokemon may hold any Unique (no type or move
gate); Ashen Heart is +50% burn / -15% direct damage. The other three were proposed from the spec's "later candidates" list and
are **provisional until a balance pass**.

A Unique is one fixed power with no rank, installed with a Catalyst (`installUnique`, replaced with another Catalyst: `replaceUnique`).
Rules of the module (`ascension-fx.js`): a **benefit joins its channel**, so it is capped with the rest; a **drawback is a fixed
multiplier applied after the caps** and is never removed by them. A Unique reaches the simulator as one more effect whose percent is a
plain "on" flag (1); tuning lives only in the module. The text players read lives in `ranked-catalog.json` (`benefit`, `drawback`).

| Unique | Benefit | Drawback | Hook |
|---|---|---|---|
| Ashen Heart | Burns you inflict deal +50% (residual channel, cap 100, shared with Smoldering) | Your direct move damage x0.85 | `battle.damage` for `brn` residual with `statusState.source`; `modifyDamage` |
| Last Breath | Once per battle, an opposing move that would faint you leaves 1 HP | All healing you receive x0.5 (including Triumphant) | per-holder `damage` and `heal` wrappers |
| Creeping Venom | Regular poison you inflict deals +20% per turn it has lasted, up to +80% (residual channel); resets on cure or replacement; toxic excluded | Psychic moves deal x1.2 to you | `battle.damage` for `psn` residual; incoming multiplier |
| Stormcaller | While a weather is active, your moves of its type deal +30% (outgoing channel): Rain/Primordial Sea Water, Sun/Desolate Land Fire, Sandstorm Rock, Snow/Hail Ice; Delta Stream has no type | With no weather active your direct move damage x0.85 | `modifyDamage`; `field.effectiveWeather()` so Cloud Nine and Air Lock count |
| Rupture | Every physical move you make bleeds (no roll); same bleed as Rending (1/12 max HP per stack, 3 stacks, 3 turns) | Bleed you inflict is weaker per stack: stack 1 100%, stack 2 80%, stack 3 60% (3 stacks = 2.4/12 = 20% max HP a turn) | spreadDamage hook shared with Rending |
| Titan's Heart | A super-effective move deals extra damage equal to 10% of your max HP (once per move use, after the caps) | No healing is received in battle, from any source | `modifyDamage`; `holder.heal` returns 0 |
| 777 | Item rewards you earn are +20% while it is in your party (Raid and Tower items only, any party member, no stacking; materials, currency, cards unaffected; a fraction is rolled with a seeded roll) | You take 25% more damage from moves (about 20% less effective HP; residual damage is not scaled) | `AscensionRewards.scaleItemQuantity` called by Raids and Towers at delivery; incoming multiplier |

Boundaries: Last Breath triggers only on an opposing direct move, never on residual damage, recoil or self-damage. Stormcaller does not set
weather. Uniques on enemies exist (a boss spec may declare one) but no caller declares one yet.

## Player flow

Catalyst sources: 5% Unique Fragment per Tower boss, 40 fragments assemble one Catalyst (100 until 12 Nov 2026, when live data showed no player ever reached it) (the **Assemble Catalyst** button on the Unique tab), or an operator grant
(`/ascend admin grant <player> unique_catalyst 1`). `/ascend craft` has a **Unique** tab: pick one of the four, see benefit and drawback, confirm; a
Catalyst is consumed; replacing needs another Catalyst. Nothing is spent without a confirmation, and the tab says why it is blocked.

## Verified

37 simulator tests on two simulators (one or two per Unique, plus the shared residual cap). Not seen in a live battle; not seen on a client.
