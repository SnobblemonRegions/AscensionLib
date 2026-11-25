"""The Transcendent power tables (docs/TRANSCENDENT-POWERS.md): signatures, motif twists, motif riders and the bespoke twist of every
curated recipe. Imported by build_fusion_data.py, which validates them against the recipes and writes design/transcendents.json.

A twist is a short list of pulse effects ("ops") from one shared vocabulary, so the 25 motif twists and the 80 bespoke recipe twists are
data, not code. Magnitudes are at 100 percent; the battle module scales them by the harmony benefit share."""

# ---------------------------------------------------------------- the pulse-effect vocabulary and its limits
STATS = ['atk', 'def', 'spa', 'spd', 'spe']
OP_RULES = {
    # op: {field: (low, high)} for ints, or a set of allowed strings
    'status': {'status': {'brn', 'psn', 'par', 'confusion'}, 'chance': (1, 100)},
    'foeStage': {'stat': set(STATS) | {'accuracy'}, 'delta': (-2, -1)},
    'selfStage': {'stat': set(STATS), 'delta': (1, 2), 'cap': (1, 6)},
    'chip': {'pct': (1, 10)},
    'drain': {'pct': (1, 10)},
    'heal': {'pct': (1, 10)},
    'cleanse': {},
    'ward': {},
    'mimic': {},
    'strip': {},
    'unresistedNext': {},
    'shield': {'pct': (1, 50)},
    'boostNext': {'pct': (1, 50)},
    'healBoost': {'pct': (1, 100), 'turns': (1, 5)},
    'hide': {'pct': (1, 30), 'turns': (1, 5)},
    'refine': {'pct': (1, 50), 'turns': (1, 5)},
}


def check_ops(ops, where):
    assert 1 <= len(ops) <= 2, where + ': a twist has one or two effects'
    for op in ops:
        rules = OP_RULES[op['op']]
        assert set(op) - {'op'} == set(rules), f"{where}: {op['op']} needs exactly {sorted(rules)}"
        for field, allowed in rules.items():
            value = op[field]
            if isinstance(allowed, set):
                assert value in allowed, f'{where}: {field}={value}'
            else:
                assert isinstance(value, int) and allowed[0] <= value <= allowed[1], f'{where}: {field}={value}'


def S(status, chance): return {'op': 'status', 'status': status, 'chance': chance}
def FOE(stat, delta=-1): return {'op': 'foeStage', 'stat': stat, 'delta': delta}
def SELF(stat, delta=1, cap=2): return {'op': 'selfStage', 'stat': stat, 'delta': delta, 'cap': cap}
def CHIP(pct): return {'op': 'chip', 'pct': pct}
def DRAIN(pct): return {'op': 'drain', 'pct': pct}
def HEAL(pct): return {'op': 'heal', 'pct': pct}
def SHIELD(pct): return {'op': 'shield', 'pct': pct}
def BOOST(pct): return {'op': 'boostNext', 'pct': pct}
def HEALBOOST(pct, turns=2): return {'op': 'healBoost', 'pct': pct, 'turns': turns}
def HIDE(pct, turns=2): return {'op': 'hide', 'pct': pct, 'turns': turns}
def REFINE(pct, turns=2): return {'op': 'refine', 'pct': pct, 'turns': turns}
CLEANSE = {'op': 'cleanse'}
WARD = {'op': 'ward'}
MIMIC = {'op': 'mimic'}
STRIP = {'op': 'strip'}
UNRESISTED = {'op': 'unresistedNext'}

# ---------------------------------------------------------------- the 25 motif twists (host primary motif)
TWISTS = {
    'EMBER': ('Scorch', [S('brn', 30)]),
    'TIDE': ('Wash', [CLEANSE]),
    'GALE': ('Tailwind', [SELF('spe')]),
    'VERDANT': ('Bloom', [HEAL(4)]),
    'FROST': ('Chill', [FOE('spe')]),
    'STORM': ('Static', [S('par', 30)]),
    'STONE': ('Brace', [SELF('def')]),
    'IRON': ('Plating', [SHIELD(15)]),
    'VENOM': ('Taint', [S('psn', 30)]),
    'SPIRIT': ('Haunt', [DRAIN(4)]),
    'DREAM': ('Daze', [S('confusion', 25)]),
    'WILD': ('Frenzy', [SELF('atk')]),
    'HIVE': ('Sting', [CHIP(3)]),
    'FAE': ('Charm', [FOE('atk')]),
    'WYRM': ("Dragon's Breath", [BOOST(15)]),
    'SHADE': ('Blind', [FOE('accuracy')]),
    'RADIANCE': ('Dawn', [SELF('spa')]),
    'CHIME': ('Dissonance', [FOE('spd')]),
    'GUARD': ('Ward', [WARD]),
    'HARVEST': ('Feast', [HEALBOOST(20)]),
    'COSMOS': ('Warp', [UNRESISTED]),
    'ANCIENT': ('Primeval Hide', [HIDE(10)]),
    'ARTISAN': ('Refine', [REFINE(20)]),
    'TRICKSTER': ('Mimic', [MIMIC]),
    'BRAWL': ('Break Guard', [STRIP]),
}

# ---------------------------------------------------------------- the 25 motif riders (donor primary motif): an existing affix, small
RIDERS = {
    'EMBER': ('smoldering', 10), 'TIDE': ('restorative', 8), 'GALE': ('swift_strike', 4), 'VERDANT': ('steadfast_guard', 5),
    'FROST': ('opening_guard', 6), 'STORM': ('ensnaring', 6), 'STONE': ('physical_bulwark', 4), 'IRON': ('special_bulwark', 4),
    'VENOM': ('venomous', 10), 'SPIRIT': ('stubborn', 5), 'DREAM': ('status_guard', 4), 'WILD': ('keen_edge', 5),
    'HIVE': ('rending', 8), 'FAE': ('bracing_entry', 8), 'WYRM': ('overwhelming_force', 3), 'SHADE': ('opening_strike', 6),
    'RADIANCE': ('healthy_force', 4), 'CHIME': ('momentum', 3), 'GUARD': ('iron_resolve', 4), 'HARVEST': ('triumphant', 8),
    'COSMOS': ('super_effective_force', 4), 'ANCIENT': ('resilient_hide', 4), 'ARTISAN': ('kindred_force', 5),
    'TRICKSTER': ('wardstone', 6), 'BRAWL': ('executioner', 6),
}

# ---------------------------------------------------------------- the 21 signatures (id matches the base Transcendent id)
# id: (pulse, core, clutch, drawback) - English at 100 percent strength, for the hover and the design record
SIGNATURES = {
    'phoenix_cinder': ('STRUCK', 'Attackers that hit you while you are below 50% HP are burned.', 'A lethal hit leaves you at 20% HP instead of fainting.', 'Healing cannot raise you above 70% of max HP.'),
    'plague_pyre': ('TICK', 'Burned or poisoned foes take an extra 3% of max HP each turn.', '', 'Your direct damage is 10% lower.'),
    'wildfire_crown': ('HIT', 'Fire moves deal 20% more; while any weather is active all your moves deal 10% more.', '', 'Water and Ice moves deal 25% less.'),
    'brand_of_ruin': ('HIT', 'A damaging hit Brands the foe for 3 turns: it takes 10% more from you and 30% more from burn and bleed.', '', 'A new Brand costs you 3% of max HP.'),
    'titans_forge': ('HIT', 'A super-effective hit adds 8% of your max HP in damage and has a 25% chance to burn.', '', 'You lose 3% of max HP each turn while above 60% HP.'),
    'gilded_ember': ('KO', 'Burn damage you inflict +30%; each KO adds +10% damage (3 stacks, lost on switching out); item rewards +10%.', '', 'You take 15% more damage from Water moves.'),
    'dying_bloom': ('STRUCK', 'Attackers that hit you while you are below 50% HP are poisoned; your poison ramps twice as fast then.', 'A lethal hit leaves you at 1 HP.', 'Healing you receive is 35% lower.'),
    'eye_of_the_storm': ('TICK', 'While any weather is active, heal 3% of max HP each turn and take 12% less damage.', 'A lethal hit leaves you at 1 HP.', 'With no weather you take 12% more damage.'),
    'martyrs_edge': ('HIT', 'Physical hits bleed; you deal 12% more to bleeding foes.', 'A lethal hit leaves you at 1 HP and your next hit is a guaranteed critical.', 'Your bleeds are weaker (stack weights 1, 0.8, 0.6).'),
    'colossus_vow': ('STRUCK', 'You take 15% less from super-effective hits and counter any super-effective hit for 6% of your max HP.', 'A lethal hit leaves you at 1 HP.', 'Your Speed is 15% lower.'),
    'last_gamble': ('KO', 'Item rewards +15%.', 'A lethal hit leaves you at 30% HP and your next 2 moves deal 30% more.', 'After the clutch fires you take 25% more damage for the rest of the battle.'),
    'miasma_front': ('TICK', 'While any weather is active every foe takes 2.5% of max HP each turn, rising 1% a turn to 5%; poison you inflict +20%.', '', 'With no weather you lose 2% of max HP each turn.'),
    'festering_gash': ('HIT', 'Physical hits bleed; each turn a foe bleeds its bleed damage rises 15% (to 60%); you heal 25% of the bleed damage dealt.', '', 'You take 20% more damage from Psychic moves.'),
    'blighted_giant': ('HIT', 'A super-effective hit adds 7% of your max HP in damage and poisons the foe; poison damage you inflict +25%.', '', 'Healing you receive is 60% lower.'),
    'fortunes_rot': ('TICK', 'Foes you have poisoned lose an extra 3% of max HP each turn; item rewards +15%.', '', 'You take 20% more damage.'),
    'lightning_rend': ('HIT', "Moves of the active weather's type, or Electric moves, deal 25% more and cause a bleed whatever their category.", '', 'With no weather your direct damage is 20% lower.'),
    'tempest_colossus': ('HIT', 'Weather-type moves deal 20% more; a super-effective hit adds 7% of your max HP in damage.', '', 'Your Speed is 12% lower.'),
    'skyfall_fortune': ('KO', 'Weather-type moves deal 25% more; each KO while a weather is active adds 12% damage (3 stacks); item rewards +15%.', '', 'With no weather you take 20% more damage.'),
    'breakers_might': ('HIT', 'Physical hits bleed; a super-effective hit adds 8% of your max HP in damage, and 10% more if the foe is bleeding.', '', 'Healing you receive is 50% lower.'),
    'wager_of_blood': ('KO', 'Physical hits bleed; each KO restores 10% of your max HP; item rewards +15%.', '', 'Every physical move you use costs you 2% of max HP.'),
    'jackpot_titan': ('KO', 'A super-effective hit adds 10% of your max HP in damage; item rewards +20%.', '', 'You take 22% more damage.'),
}

# ---------------------------------------------------------------- the bespoke twist of every curated recipe (pairs, then groups)
RECIPE_TWISTS = {
    # pairs
    'primordial_clash': ('Cataclysm', [CHIP(4), SELF('def')]),
    'hand_of_creation': ('Fold of Space-Time', [SHIELD(20), UNRESISTED]),
    'taijitu': ('Yin and Yang', [DRAIN(4), BOOST(10)]),
    'white_boundary': ('Truth Frozen', [S('brn', 30), FOE('spe')]),
    'black_boundary': ('Ideal Frozen', [S('par', 30), FOE('spe')]),
    'crowned_pair': ('Sword and Shield', [BOOST(15), SHIELD(15)]),
    'wheel_of_life': ('Life and Death', [HEAL(4), DRAIN(4)]),
    'eclipse': ('Totality', [FOE('accuracy'), SELF('spa')]),
    'dusk_mane': ('Prism Feed', [SELF('atk'), BOOST(10)]),
    'dawn_wings': ('Prism Veil', [CLEANSE, FOE('accuracy')]),
    'eon_bond': ('Shared Dream', [HEAL(4), CLEANSE]),
    'rainbow_tower': ('Seven Colours', [HEAL(3), SELF('spa')]),
    'original_and_copy': ('Perfect Copy', [MIMIC, STRIP]),
    'ice_rider': ('Rider of Ice', [FOE('spe'), BOOST(15)]),
    'shadow_rider': ('Rider of Shadow', [FOE('accuracy'), DRAIN(4)]),
    'past_and_future': ('Time Fold', [HIDE(10), BOOST(15)]),
    'origin_and_shadow': ('Distortion', [STRIP, S('confusion', 25)]),
    'elder_tusk': ('Mammoth Charge', [SELF('atk'), HIDE(10)]),
    'wheel_of_donphan': ('Rolling Armour', [SELF('def'), BOOST(10)]),
    'tusk_and_tread': ('Tusk and Tread', [SHIELD(15), SELF('atk')]),
    'lullaby_unbound': ('Scream Lullaby', [S('confusion', 25), FOE('spd')]),
    'gift_of_tomorrow': ('Gift of Tomorrow', [HEAL(4), FOE('spe')]),
    'sumo_machine': ('Piston Slap', [BOOST(15), FOE('spe')]),
    'iron_hydra': ('Three Heads Online', [CHIP(3), STRIP]),
    'foo_fighter': ('Sun Probe', [S('brn', 30), FOE('accuracy')]),
    'dawn_of_moths': ('Sun Dust', [CHIP(3), S('brn', 20)]),
    'moth_across_ages': ('Dust of Ages', [HIDE(10), S('brn', 20)]),
    'mecha_kaiju': ('Stomp Protocol', [SHIELD(15), SELF('atk')]),
    'paladin_oath': ("Knight's Pledge", [WARD, SHIELD(15)]),
    'paladin_blade': ("Knight's Edge", [WARD, BOOST(15)]),
    'flying_head': ('Wail', [S('confusion', 25), DRAIN(3)]),
    'ironsand_age': ('Magnetic Storm', [S('par', 30), FOE('spe')]),
    'mold_beneath': ('Spore Rot', [S('psn', 30), HEAL(3)]),
    'dragon_of_the_dream': ('Dream Wyrm', [SELF('spe'), BOOST(15)]),
    'purifying_wake': ('Clear Tide', [CLEANSE, HEAL(4)]),
    'shining_blades': ('Julienne', [BOOST(20), FOE('def')]),
    'regenerated_pyre': ('Reborn Flame', [S('brn', 30), HEAL(3)]),
    'thunder_lizard': ('Thunderous Step', [S('par', 30), CHIP(3)]),
    'blade_of_the_cavern': ('Spade Rush', [SELF('def'), BOOST(15)]),
    'crown_of_iron': ('Crowned Resolve', [SHIELD(15), STRIP]),
    'prehistoric_skies': ('Ancient Updraft', [SELF('spe'), HIDE(10)]),
    'two_golems': ('Clay and Stone', [SELF('def'), SHIELD(15)]),
    'borrowed_face': ('Borrowed Smile', [MIMIC, HEAL(3)]),
    'blade_and_pincer': ('Mantis Cut', [BOOST(15), FOE('def')]),
    'moon_shadow': ('Moonlit Mimic', [MIMIC, FOE('accuracy')]),
    # groups
    'weather_pact': ('Pact of Sky, Sea and Land', [UNRESISTED, CHIP(3)]),
    'legendary_flight': ('Wings Unfurled', [SELF('spe'), FOE('spe')]),
    'roaming_beasts': ('Brass Tower Rebirth', [HEAL(4), SELF('spe')]),
    'titan_golems': ('Seal Unbroken', [SHIELD(20), SELF('def')]),
    'lake_guardians': ('Triple Lake', [CLEANSE, WARD]),
    'swords_of_justice': ('Sworn Blades', [BOOST(15), SHIELD(15)]),
    'forces_of_nature': ('Tempest Cycle', [S('par', 25), SELF('spe')]),
    'guardian_deities': ('Island Challenge', [WARD, HEAL(3)]),
    'treasures_of_ruin': ('Ruinous Aura', [CHIP(3), FOE('spd')]),
    'beyond_the_wormhole': ('Ultra Space', [UNRESISTED, FOE('accuracy')]),
    'retainers_of_the_ogre': ('Reversed Tale', [S('psn', 30), MIMIC]),
    'pseudo_summit': ('Summit Power', [SELF('atk'), HIDE(10)]),
    'kanto_triad': ('First Partners', [HEAL(4), SELF('atk')]),
    'johto_triad': ('Johto Bond', [HEAL(3), SHIELD(15)]),
    'hoenn_triad': ('Hoenn Surge', [SELF('spe'), BOOST(10)]),
    'sinnoh_triad': ('Sinnoh Unity', [SELF('def'), HEAL(3)]),
    'unova_triad': ('Unova Resolve', [BOOST(15), CLEANSE]),
    'kalos_triad': ('Kalos Elegance', [SELF('spa'), FOE('accuracy')]),
    'alola_triad': ('Alola Pride', [SELF('atk'), SHIELD(10)]),
    'galar_triad': ('Galar Champion', [BOOST(15), SELF('spe')]),
    'paldea_triad': ('Paldea Spirit', [HEAL(4), BOOST(10)]),
    'kitsune_court': ('Nine Tails', [S('confusion', 25), S('brn', 25)]),
    'bushido_court': ('Way of the Blade', [BOOST(15), STRIP]),
    'dragon_gate': ('Leap the Falls', [SELF('atk'), HEAL(4)]),
    'moon_court': ('Moonlight', [HEAL(4), FOE('accuracy')]),
    'sun_court': ('Sunlight', [SELF('spa'), S('brn', 20)]),
    'animated_earth': ('Clay Awakened', [SHIELD(20), SELF('def')]),
    'halloween_hollow': ('Trick and Treat', [S('confusion', 25), HEAL(3)]),
    'tea_ceremony': ('Steeped', [HEALBOOST(20), CLEANSE]),
    'dessert_table': ('Sweet Tooth', [HEALBOOST(25), FOE('spe')]),
    'machine_choir': ('Synchronised', [S('par', 25), SELF('spe')]),
    'three_wise_monkeys': ('Hear No Evil', [WARD, FOE('spd')]),
    'fossil_revival': ('Revived Might', [HIDE(10), SELF('atk')]),
    'galar_fossils': ('Mismatched Parts', [MIMIC, CHIP(3)]),
    'mythical_dawn': ('Elusive', [FOE('accuracy'), WARD]),
}

for _name, (_label, _ops) in list(TWISTS.items()) + list(RECIPE_TWISTS.items()):
    check_ops(_ops, _name)
assert len(TWISTS) == 25 and len(RIDERS) == 25 and len(SIGNATURES) == 21 and len(RECIPE_TWISTS) == 80
assert {p for p, *_ in SIGNATURES.values()} <= {'HIT', 'STRUCK', 'KO', 'LAST', 'TICK'}


# ================================================================ the composed signatures (docs/AFFIX-EXPANSION-DESIGN.md, part C)
# The 84 Transcendents made with the second batch of Uniques are data: a pulse, core parts, an optional clutch and one drawback, from a
# small vocabulary that ascension-fx.js implements once (composeSignature). The text players read is generated from the same data
# (composed_text), and so is the JS table (write_composed_js), so the two cannot drift. Numbers are at 100 percent strength.

def OUT(pct, w='always'): return {'k': 'out', 'pct': pct, 'w': w}
def INC(pct, w='always'): return {'k': 'inc', 'pct': pct, 'w': w}
def SEADD(pct): return {'k': 'seAdd', 'pct': pct}
def HSTAT(status, ch, w='any'): return {'k': 'hitStatus', 's': status, 'ch': ch, 'w': w}
def SSTAT(status, ch, contact=True): return {'k': 'struckStatus', 's': status, 'ch': ch, 'contact': contact}
def COUNTER(pct, w='contact'): return {'k': 'counter', 'pct': pct, 'w': w}
def HHEAL(pct): return {'k': 'hitHeal', 'pct': pct}
def LEECH(pct): return {'k': 'lifesteal', 'pct': pct}
def THEAL(pct, w='always'): return {'k': 'tickHeal', 'pct': pct, 'w': w}
def TCHIP(pct, w='statused'): return {'k': 'tickChip', 'pct': pct, 'w': w}
def KOHEAL(pct): return {'k': 'koHeal', 'pct': pct}
def KOSTACK(pct, top=3): return {'k': 'koStack', 'pct': pct, 'max': top}
def RAMP(pct, top): return {'k': 'ramp', 'pct': pct, 'max': top}
def SPEED(pct): return {'k': 'speed', 'pct': pct}
def BLEED(w='physical'): return {'k': 'bleed', 'w': w}
def SBLEED(contact=True): return {'k': 'struckBleed', 'contact': contact}
def POT(status, pct): return {'k': 'potency', 's': status, 'pct': pct}
def HSTAGE(stat, ch): return {'k': 'hitStage', 'stat': stat, 'ch': ch}
def SSTAGE(stat, ch): return {'k': 'struckStage', 'stat': stat, 'ch': ch}
def KOSTAGE(stat): return {'k': 'koStage', 'stat': stat}
def MARK(pct): return {'k': 'mark', 'pct': pct}
def GAMBLE(pct): return {'k': 'gamble', 'pct': pct}
def LEGACY(heal): return {'k': 'legacy', 'heal': heal}
def LOOT(pct): return {'k': 'loot', 'pct': pct}          # item rewards, Java-side (ItemQuantityBonus); no battle effect
def CL(remain=0, then=None, pct=0): return {'remain': remain, 'then': then, 'pct': pct}
def OPEN(pct, w='always'): return {'k': 'outPen', 'pct': pct, 'w': w}
def IPEN(pct, w='always', types=None):
    part = {'k': 'incPen', 'pct': pct, 'w': w}
    if types:
        part['types'] = types
    return part
def HCUT(pct): return {'k': 'healCut', 'pct': pct}
NOCRIT = {'k': 'noCrit'}
def SLOW(pct): return {'k': 'slow', 'pct': pct}
def FRAIL(pct): return {'k': 'frail', 'pct': pct}
def TCOST(pct, w='always'): return {'k': 'tickCost', 'pct': pct, 'w': w}
def MCOST(pct, w='any'): return {'k': 'moveCost', 'pct': pct, 'w': w}
def HCEIL(pct): return {'k': 'healCeil', 'pct': pct}
def GDOWN(pct): return {'k': 'gambleDown', 'pct': pct}

FIRE_FAIRY = ['Fire', 'Fairy']

# id: (unique a, unique b, name, blurb, pulse, core, clutch or None, drawback)
COMPOSED = {
    # ---- the first seven Uniques with the second batch
    'rising_pyre': ('ashen_heart', 'crescendo', 'Rising Pyre', 'A fire that builds the longer it is left to burn.',
                    'HIT', [RAMP(6, 30), POT('brn', 30)], None, OPEN(20, 'firstTurns')),
    'cinder_fang': ('ashen_heart', 'nightfang', 'Cinder Fang', 'A bite that burns and feeds.',
                    'HIT', [HSTAT('brn', 20), LEECH(15)], None, IPEN(25, types=['Water', 'Fairy'])),
    'brazen_bull': ('ashen_heart', 'iron_maiden', 'Brazen Bull', 'A burning bronze hide that scalds whatever strikes it.',
                    'STRUCK', [SSTAT('brn', 30), COUNTER(8)], None, OPEN(20, 'contact')),
    'wildfire_gale': ('ashen_heart', 'tailwind_heart', 'Wildfire Gale', 'Flame carried on a racing wind.',
                      'HIT', [SPEED(25), HSTAT('brn', 15)], None, FRAIL(12)),
    'hunters_pyre': ('ashen_heart', 'huntmark', "Hunter's Pyre", 'Mark the prey, then let it burn.',
                     'HIT', [MARK(20), OUT(10, 'foeStatus')], None, NOCRIT),
    'funeral_pyre': ('ashen_heart', 'martyrs_gift', 'Funeral Pyre', 'Its ashes kindle the next to fight.',
                     'STRUCK', [LEGACY(35), POT('brn', 30)], None, IPEN(12)),
    'hearthguard': ('ashen_heart', 'aegis_oath', 'Hearthguard', 'A hearth that warms its keeper and burns the hand that strikes it.',
                    'STRUCK', [INC(18), SSTAT('brn', 20)], None, OPEN(20)),
    'gamblers_flame': ('ashen_heart', 'fickle_fortune', "Gambler's Flame", 'Every turn a coin, every heads a blaze.',
                       'HIT', [GAMBLE(35), HSTAT('brn', 15)], None, GDOWN(20)),
    'endless_encore': ('last_breath', 'crescendo', 'Endless Encore', 'The song that will not end until it is finished.',
                       'TICK', [RAMP(8, 40)], CL(0, 'boost', 20), OPEN(20, 'firstTurns')),
    'undying_thirst': ('last_breath', 'nightfang', 'Undying Thirst', 'It will not die while there is still blood to drink.',
                       'HIT', [LEECH(15)], CL(15), IPEN(25, types=FIRE_FAIRY)),
    'iron_coffin': ('last_breath', 'iron_maiden', 'Iron Coffin', 'Sealed in iron, it outlasts the blow that should have ended it.',
                    'STRUCK', [COUNTER(10)], CL(0, 'shield', 30), OPEN(20, 'contact')),
    'phantom_step': ('last_breath', 'tailwind_heart', 'Phantom Step', 'Too quick to catch, too stubborn to fall.',
                     'HIT', [SPEED(30)], CL(0), FRAIL(15)),
    'the_last_hunt': ('last_breath', 'huntmark', 'The Last Hunt', 'Wounded, it marks one last prey.',
                      'HIT', [MARK(20)], CL(0, 'boost', 30), SLOW(12)),
    'final_vigil': ('last_breath', 'martyrs_gift', 'Final Vigil', 'It stands watch to the last, and leaves its strength behind.',
                    'STRUCK', [LEGACY(50)], CL(0), IPEN(15)),
    'unbroken_oath': ('last_breath', 'aegis_oath', 'Unbroken Oath', 'A vow kept past the breaking point.',
                      'STRUCK', [INC(20)], CL(20), OPEN(25)),
    'dead_mans_hand': ('last_breath', 'fickle_fortune', "Dead Man's Hand", 'Aces and eights, and one more hand to play.',
                       'HIT', [GAMBLE(40)], CL(0, 'boost', 30), GDOWN(20)),
    'slow_rot': ('creeping_venom', 'crescendo', 'Slow Rot', 'Poison that grows patient and then grows strong.',
                 'TICK', [POT('psn', 25), RAMP(5, 25)], None, OPEN(20, 'firstTurns')),
    'venom_kiss': ('creeping_venom', 'nightfang', 'Venom Kiss', 'A kiss that poisons and feeds.',
                   'HIT', [HSTAT('psn', 25, 'contact'), LEECH(10)], None, IPEN(25, types=['Fire', 'Psychic'])),
    'toxic_thorns': ('creeping_venom', 'iron_maiden', 'Toxic Thorns', 'Barbs dipped in venom.',
                     'STRUCK', [SSTAT('psn', 35), COUNTER(6)], None, OPEN(20, 'contact')),
    'plague_wind': ('creeping_venom', 'tailwind_heart', 'Plague Wind', 'A fast, foul wind that wears down the sick.',
                    'TICK', [SPEED(20), TCHIP(3, 'statused')], None, FRAIL(12)),
    'stalking_venom': ('creeping_venom', 'huntmark', 'Stalking Venom', 'It marks its prey and lets the poison work.',
                       'HIT', [MARK(15), POT('psn', 30)], None, NOCRIT),
    'bitter_legacy': ('creeping_venom', 'martyrs_gift', 'Bitter Legacy', 'Even its death is poison to its foes.',
                      'STRUCK', [LEGACY(35), SSTAT('psn', 25, False)], None, IPEN(12)),
    'miasma_shell': ('creeping_venom', 'aegis_oath', 'Miasma Shell', 'A shell wrapped in toxic fog.',
                     'STRUCK', [INC(15), SSTAT('psn', 30)], None, OPEN(20)),
    'poisoned_dice': ('creeping_venom', 'fickle_fortune', 'Poisoned Dice', 'Loaded dice, laced with venom.',
                      'HIT', [GAMBLE(35), HSTAT('psn', 20)], None, GDOWN(20)),
    'gathering_storm': ('stormcaller', 'crescendo', 'Gathering Storm', 'Clouds that build turn by turn.',
                        'TICK', [RAMP(6, 30), OUT(10, 'weather')], None, OPEN(15, 'noWeather')),
    'blood_moon': ('stormcaller', 'nightfang', 'Blood Moon', 'Under a red sky, it feeds.',
                   'HIT', [LEECH(12), THEAL(3, 'weather')], None, IPEN(25, types=FIRE_FAIRY)),
    'lightning_rod': ('stormcaller', 'iron_maiden', 'Lightning Rod', 'Strike it and the storm strikes back.',
                      'STRUCK', [SSTAT('par', 30), COUNTER(6)], None, IPEN(15, 'noWeather')),
    'jet_stream': ('stormcaller', 'tailwind_heart', 'Jet Stream', 'Riding the high winds of its own weather.',
                   'HIT', [SPEED(25), OUT(15, 'weatherType')], None, FRAIL(12)),
    'storm_hunt': ('stormcaller', 'huntmark', 'Storm Hunt', 'The storm finds the prey it has marked.',
                   'HIT', [MARK(15), OUT(10, 'weather')], None, NOCRIT),
    'rain_of_remembrance': ('stormcaller', 'martyrs_gift', 'Rain of Remembrance', 'A rain that falls for the fallen.',
                            'TICK', [LEGACY(40), THEAL(3, 'weather')], None, IPEN(15, 'noWeather')),
    'storm_bulwark': ('stormcaller', 'aegis_oath', 'Storm Bulwark', 'A wall of wind and rain.',
                      'STRUCK', [INC(25, 'weather')], None, OPEN(20)),
    'thunder_lottery': ('stormcaller', 'fickle_fortune', 'Thunder Lottery', 'Lightning never strikes the same place twice. Usually.',
                        'HIT', [GAMBLE(30), OUT(15, 'weatherType')], None, GDOWN(20)),
    'bloodletting': ('rupture', 'crescendo', 'Bloodletting', 'Each turn the wounds run deeper.',
                     'HIT', [BLEED('physical'), RAMP(5, 25)], None, OPEN(20, 'firstTurns')),
    'vampire_bite': ('rupture', 'nightfang', 'Vampire Bite', 'Fangs that open a wound and drink from it.',
                     'HIT', [BLEED('contact'), LEECH(12)], None, IPEN(30, types=FIRE_FAIRY)),
    'barbed_hide': ('rupture', 'iron_maiden', 'Barbed Hide', 'Every barb leaves the attacker bleeding.',
                    'STRUCK', [SBLEED(), COUNTER(5)], None, OPEN(20, 'contact')),
    'razor_wind': ('rupture', 'tailwind_heart', 'Razor Wind', 'A cutting gale.',
                   'HIT', [SPEED(20), BLEED('any')], None, FRAIL(15)),
    'blood_trail': ('rupture', 'huntmark', 'Blood Trail', 'It follows the blood to its prey.',
                    'HIT', [MARK(15), OUT(12, 'foeBleeding'), BLEED('physical')], None, NOCRIT),
    'blood_oath': ('rupture', 'martyrs_gift', 'Blood Oath', 'Sworn in blood, kept in death.',
                   'KO', [BLEED('physical'), LEGACY(35)], None, IPEN(12)),
    'scarred_veteran': ('rupture', 'aegis_oath', 'Scarred Veteran', 'Every scar a lesson, every lesson a wound returned.',
                        'STRUCK', [INC(15), SBLEED()], None, OPEN(20)),
    'knife_toss': ('rupture', 'fickle_fortune', 'Knife Toss', 'Throw the knife, flip the coin.',
                   'HIT', [GAMBLE(30), BLEED('any')], None, GDOWN(20)),
    'rising_colossus': ('titans_heart', 'crescendo', 'Rising Colossus', 'A giant that wakes slowly and hits harder each turn.',
                        'HIT', [RAMP(6, 30), SEADD(6)], None, HCUT(50)),
    'giants_hunger': ('titans_heart', 'nightfang', "Giant's Hunger", 'A giant that feeds on what it crushes.',
                      'HIT', [SEADD(7), LEECH(10)], None, IPEN(25, types=FIRE_FAIRY)),
    'iron_colossus': ('titans_heart', 'iron_maiden', 'Iron Colossus', 'Strike its weak point and pay for it.',
                      'STRUCK', [COUNTER(10, 'se'), COUNTER(5, 'contact')], None, SLOW(15)),
    'falling_star': ('titans_heart', 'tailwind_heart', 'Falling Star', 'Fast, heavy, and gone.',
                     'HIT', [SPEED(20), SEADD(7)], None, FRAIL(15)),
    'giant_hunter': ('titans_heart', 'huntmark', 'Giant Hunter', 'It picks the biggest prey and brings it down.',
                     'HIT', [MARK(15), SEADD(6)], None, NOCRIT),
    'titans_legacy': ('titans_heart', 'martyrs_gift', "Titan's Legacy", 'When the giant falls, its strength passes on.',
                      'KO', [SEADD(6), LEGACY(40)], None, HCUT(50)),
    'living_fortress': ('titans_heart', 'aegis_oath', 'Living Fortress', 'A wall with no weak point.',
                        'STRUCK', [INC(30, 'se'), INC(8)], None, OPEN(20)),
    'titans_wager': ('titans_heart', 'fickle_fortune', "Titan's Wager", 'A giant betting on one great blow.',
                     'HIT', [GAMBLE(30), SEADD(6)], None, GDOWN(20)),
    'compound_interest': ('triple_seven', 'crescendo', 'Compound Interest', 'Every turn it stays, the returns grow.',
                          'KO', [RAMP(5, 25), LOOT(15)], None, IPEN(15)),
    'blood_money': ('triple_seven', 'nightfang', 'Blood Money', 'Paid in full, in blood.',
                    'KO', [KOHEAL(10), LOOT(15)], None, IPEN(25, types=FIRE_FAIRY)),
    'golden_cage': ('triple_seven', 'iron_maiden', 'Golden Cage', 'Gilded bars that bite back.',
                    'STRUCK', [COUNTER(8), LOOT(15)], None, OPEN(20, 'contact')),
    'gold_rush': ('triple_seven', 'tailwind_heart', 'Gold Rush', 'First to the gold.',
                  'KO', [SPEED(20), LOOT(15)], None, FRAIL(15)),
    'bounty_hunter': ('triple_seven', 'huntmark', 'Bounty Hunter', 'Mark the bounty, collect the prize.',
                      'KO', [MARK(15), LOOT(15)], None, NOCRIT),
    'inheritance': ('triple_seven', 'martyrs_gift', 'Inheritance', 'Its fortune passes to the next in line.',
                    'KO', [LEGACY(40), LOOT(15)], None, IPEN(15)),
    'vault_keeper': ('triple_seven', 'aegis_oath', 'Vault Keeper', 'Guarding the treasure, at a cost.',
                     'STRUCK', [INC(15), LOOT(15)], None, OPEN(20)),
    'all_in': ('triple_seven', 'fickle_fortune', 'All In', 'Everything on one flip.',
               'KO', [GAMBLE(40), LOOT(20)], None, GDOWN(25)),
    # ---- the second batch with itself
    'night_symphony': ('crescendo', 'nightfang', 'Night Symphony', 'A slow song that feeds as it swells.',
                       'HIT', [RAMP(6, 30), LEECH(10)], None, IPEN(25, types=FIRE_FAIRY)),
    'tempered_steel': ('crescendo', 'iron_maiden', 'Tempered Steel', 'Every blow it takes hardens it further.',
                       'STRUCK', [RAMP(5, 25), COUNTER(6)], None, OPEN(25, 'firstTurns')),
    'accelerando': ('crescendo', 'tailwind_heart', 'Accelerando', 'Faster and stronger with every bar.',
                    'HIT', [RAMP(5, 25), SPEED(20)], None, FRAIL(12)),
    'stalkers_rhythm': ('crescendo', 'huntmark', "Stalker's Rhythm", 'It learns its prey, beat by beat.',
                        'HIT', [RAMP(5, 25), MARK(15)], None, NOCRIT),
    'requiem': ('crescendo', 'martyrs_gift', 'Requiem', 'A last song that carries on after the singer.',
                'TICK', [RAMP(6, 30), LEGACY(40)], None, OPEN(20, 'firstTurns')),
    'rising_bastion': ('crescendo', 'aegis_oath', 'Rising Bastion', 'Walls that rise higher every turn it holds.',
                       'STRUCK', [INC(15), RAMP(4, 20)], None, OPEN(30, 'firstTurns')),
    'double_or_nothing': ('crescendo', 'fickle_fortune', 'Double or Nothing', 'Let it ride.',
                          'HIT', [GAMBLE(30), RAMP(4, 20)], None, GDOWN(20)),
    'blood_maiden': ('nightfang', 'iron_maiden', 'Blood Maiden', 'Spikes that drink.',
                     'STRUCK', [COUNTER(8), LEECH(10)], None, IPEN(25, types=FIRE_FAIRY)),
    'night_flight': ('nightfang', 'tailwind_heart', 'Night Flight', 'Wings in the dark, fangs at the end.',
                     'HIT', [SPEED(25), LEECH(10)], None, FRAIL(15)),
    'blood_scent': ('nightfang', 'huntmark', 'Blood Scent', 'Once it tastes you, it will not stop.',
                    'HIT', [MARK(15), LEECH(10)], None, NOCRIT),
    'eternal_night': ('nightfang', 'martyrs_gift', 'Eternal Night', 'The night does not end when it falls.',
                      'KO', [KOHEAL(12), LEGACY(35)], None, IPEN(25, types=FIRE_FAIRY)),
    'crimson_aegis': ('nightfang', 'aegis_oath', 'Crimson Aegis', 'A shield that feeds its bearer.',
                      'STRUCK', [INC(15), LEECH(8)], None, OPEN(20)),
    'vampires_gamble': ('nightfang', 'fickle_fortune', "Vampire's Gamble", 'Feast or famine.',
                        'HIT', [GAMBLE(30), LEECH(10)], None, GDOWN(20)),
    'spiked_wheel': ('iron_maiden', 'tailwind_heart', 'Spiked Wheel', 'Rolling fast, and sharp all over.',
                     'HIT', [SPEED(20), COUNTER(6)], None, FRAIL(15)),
    'iron_trap': ('iron_maiden', 'huntmark', 'Iron Trap', 'Bait, mark and snap.',
                  'STRUCK', [COUNTER(8), MARK(15)], None, NOCRIT),
    'iron_martyr': ('iron_maiden', 'martyrs_gift', 'Iron Martyr', 'It falls on its own spikes for its allies.',
                    'STRUCK', [COUNTER(8), LEGACY(35)], None, OPEN(20, 'contact')),
    'iron_aegis': ('iron_maiden', 'aegis_oath', 'Iron Aegis', 'Touch it and regret it.',
                   'STRUCK', [INC(25, 'contact'), COUNTER(6)], None, OPEN(25)),
    'spiked_roulette': ('iron_maiden', 'fickle_fortune', 'Spiked Roulette', 'Spin the wheel, mind the spikes.',
                        'STRUCK', [GAMBLE(30), COUNTER(6)], None, GDOWN(20)),
    'swift_hunt': ('tailwind_heart', 'huntmark', 'Swift Hunt', 'Fast enough to mark the prey first.',
                   'HIT', [SPEED(20), MARK(15)], None, NOCRIT),
    'last_flight': ('tailwind_heart', 'martyrs_gift', 'Last Flight', 'It flies until it falls, then lifts the next.',
                    'HIT', [SPEED(25), LEGACY(35)], None, FRAIL(15)),
    'gale_bulwark': ('tailwind_heart', 'aegis_oath', 'Gale Bulwark', 'A wall of wind.',
                     'STRUCK', [INC(15), SPEED(15)], None, OPEN(20)),
    'fortunes_wind': ('tailwind_heart', 'fickle_fortune', "Fortune's Wind", 'Which way will the wind blow?',
                      'HIT', [GAMBLE(30), SPEED(15)], None, GDOWN(20)),
    'pack_hunt': ('huntmark', 'martyrs_gift', 'Pack Hunt', 'One marks the prey, the pack finishes it.',
                  'HIT', [MARK(20), LEGACY(35)], None, NOCRIT),
    'sentinels_watch': ('huntmark', 'aegis_oath', "Sentinel's Watch", 'It guards and watches for the one to strike.',
                        'STRUCK', [INC(15), MARK(15)], None, NOCRIT),
    'lucky_shot': ('huntmark', 'fickle_fortune', 'Lucky Shot', 'Mark the target, trust your luck.',
                   'HIT', [GAMBLE(30), MARK(15)], None, GDOWN(20)),
    'oathkeeper': ('martyrs_gift', 'aegis_oath', 'Oathkeeper', 'It holds the line and hands its strength on.',
                   'STRUCK', [INC(18), LEGACY(50)], None, OPEN(25)),
    'final_wager': ('martyrs_gift', 'fickle_fortune', 'Final Wager', 'Bet it all, and leave the winnings behind.',
                    'HIT', [GAMBLE(35), LEGACY(35)], None, GDOWN(25)),
    'loaded_shield': ('aegis_oath', 'fickle_fortune', 'Loaded Shield', 'A shield with a gambler behind it.',
                      'STRUCK', [INC(15), GAMBLE(25)], None, GDOWN(20)),
}

# ---------------------------------------------------------------- the text players read, from the same data
_STATUS_VERB = {'brn': 'burn', 'psn': 'poison', 'par': 'paralyse'}
_STATUS_PAST = {'brn': 'burned', 'psn': 'poisoned', 'par': 'paralysed'}
_STAT = {'atk': 'Attack', 'def': 'Defense', 'spa': 'Sp. Atk', 'spd': 'Sp. Def', 'spe': 'Speed'}
_OUT = {
    'always': 'Your moves deal {p}% more', 'contact': 'Your contact moves deal {p}% more', 'noncontact': 'Your non-contact moves deal {p}% more',
    'physical': 'Your physical moves deal {p}% more', 'special': 'Your special moves deal {p}% more',
    'se': 'Your super-effective moves deal {p}% more', 'foeStatus': 'You deal {p}% more to foes with a status condition',
    'foeLowered': 'You deal {p}% more to foes with a lowered stat', 'foeBleeding': 'You deal {p}% more to bleeding foes',
    'weather': 'While any weather is active your moves deal {p}% more', 'noWeather': 'With no weather your moves deal {p}% more',
    'weatherType': "Moves of the active weather's type deal {p}% more", 'hpLow': 'Below half HP your moves deal {p}% more',
    'hpHigh': 'Above 80% HP your moves deal {p}% more', 'firstTurns': 'On your first two turns on the field your moves deal {p}% more',
}
_INC = {
    'always': '', 'se': ' from super-effective moves', 'crit': ' from critical hits', 'contact': ' from contact moves',
    'physical': ' from physical moves', 'special': ' from special moves', 'hpLow': ' while below half HP', 'hpHigh': ' while above 80% HP',
    'weather': ' while any weather is active', 'noWeather': ' with no weather', 'priority': ' from priority moves',
    'statused': ' while you have a status condition',
}
_PEN_OUT = {
    'always': 'Your moves deal {p}% less', 'noWeather': 'With no weather your moves deal {p}% less', 'contact': 'Your contact moves deal {p}% less',
    'noncontact': 'Your non-contact moves deal {p}% less', 'firstTurns': 'On your first two turns on the field your moves deal {p}% less',
}


def _part_text(part):
    k = part['k']
    if k == 'out':
        return _OUT[part['w']].format(p=part['pct'])
    if k == 'inc':
        return 'You take {}% less damage{}'.format(part['pct'], _INC[part['w']])
    if k == 'seAdd':
        return 'A super-effective hit adds {}% of your max HP in damage'.format(part['pct'])
    if k == 'hitStatus':
        kind = {'any': '', 'contact': 'contact ', 'physical': 'physical '}[part['w']]
        return 'Your {}hits have a {}% chance to {} the foe'.format(kind, part['ch'], _STATUS_VERB[part['s']])
    if k == 'struckStatus':
        how = ' with a contact move' if part['contact'] else ''
        return 'Foes that hit you{} have a {}% chance to be {}'.format(how, part['ch'], _STATUS_PAST[part['s']])
    if k == 'counter':
        how = {'contact': 'a contact move', 'se': 'a super-effective move', 'any': 'a move'}[part['w']]
        return 'Foes that hit you with {} lose {}% of their max HP'.format(how, part['pct'])
    if k == 'hitHeal':
        return 'Each move that hits heals you {}% of max HP'.format(part['pct'])
    if k == 'lifesteal':
        return 'Your hits heal you {}% of the damage they deal'.format(part['pct'])
    if k == 'tickHeal':
        when = {'always': '', 'weather': ' while any weather is active', 'hpLow': ' while below half HP'}[part['w']]
        return 'Heal {}% of max HP at the end of each turn{}'.format(part['pct'], when)
    if k == 'tickChip':
        who = {'statused': 'Foes with a status condition', 'all': 'Every foe', 'lowered': 'Foes with a lowered stat'}[part['w']]
        return '{} lose {}% of max HP at the end of each turn'.format(who, part['pct'])
    if k == 'koHeal':
        return 'Each KO heals you {}% of max HP'.format(part['pct'])
    if k == 'koStack':
        return 'Each KO adds {}% damage ({} stacks, lost on switching out)'.format(part['pct'], part['max'])
    if k == 'ramp':
        return 'Each turn you stay in adds {}% damage, up to {}%'.format(part['pct'], part['max'])
    if k == 'speed':
        return 'Your Speed is {}% higher'.format(part['pct'])
    if k == 'bleed':
        return 'Your {} hits cause bleeding'.format({'physical': 'physical', 'contact': 'contact', 'any': 'damaging'}[part['w']])
    if k == 'struckBleed':
        return 'Foes that hit you{} start bleeding'.format(' with a contact move' if part['contact'] else '')
    if k == 'potency':
        return '{} you inflict deal{} {}% more'.format('Burns' if part['s'] == 'brn' else 'Poison', '' if part['s'] == 'brn' else 's', part['pct'])
    if k == 'hitStage':
        return "Your hits have a {}% chance to lower the foe's {}".format(part['ch'], _STAT[part['stat']])
    if k == 'struckStage':
        return 'Being hit has a {}% chance to raise your {} (up to +2)'.format(part['ch'], _STAT[part['stat']])
    if k == 'koStage':
        return 'Each KO raises your {} by one stage (up to +2)'.format(_STAT[part['stat']])
    if k == 'mark':
        return 'Your first hit marks a foe; your later moves against it deal {}% more'.format(part['pct'])
    if k == 'gamble':
        return 'Each turn a coin is flipped: heads, your moves deal {}% more that turn'.format(part['pct'])
    if k == 'legacy':
        return 'When you faint, the next ally to enter heals {}% of its max HP and gains +1 Attack and Sp. Atk'.format(part['heal'])
    if k == 'loot':
        return 'item rewards +{}%'.format(part['pct'])
    raise AssertionError('no text for core part ' + k)


def _draw_text(part):
    k = part['k']
    if k == 'outPen':
        return _PEN_OUT[part['w']].format(p=part['pct']) + '.'
    if k == 'incPen':
        if part.get('types'):
            return 'You take {}% more damage from {} moves.'.format(part['pct'], ' and '.join(part['types']))
        return 'You take {}% more damage{}.'.format(part['pct'], _INC[part['w']])
    if k == 'healCut':
        return 'Healing you receive is {}% lower.'.format(part['pct'])
    if k == 'noCrit':
        return 'You cannot land critical hits.'
    if k == 'slow':
        return 'Your Speed is {}% lower.'.format(part['pct'])
    if k == 'frail':
        return 'Your Defense and Sp. Def are {}% lower.'.format(part['pct'])
    if k == 'tickCost':
        when = {'always': '', 'aboveHalf': ' while above half HP', 'noWeather': ' with no weather'}[part['w']]
        return 'You lose {}% of max HP each turn{}.'.format(part['pct'], when)
    if k == 'moveCost':
        kind = {'any': 'damaging', 'physical': 'physical', 'contact': 'contact'}[part['w']]
        return 'Every {} move you use costs you {}% of max HP.'.format(kind, part['pct'])
    if k == 'healCeil':
        return 'Healing cannot raise you above {}% of max HP.'.format(100 - part['pct'])
    if k == 'gambleDown':
        return 'Tails: your moves deal {}% less that turn.'.format(part['pct'])
    raise AssertionError('no text for drawback part ' + k)


def _clutch_text(c):
    if c is None:
        return ''
    text = 'A lethal hit leaves you at 1 HP' if not c['remain'] else 'A lethal hit leaves you at {}% HP instead of fainting'.format(c['remain'])
    if c['then'] == 'crit':
        text += ' and your next hit is a guaranteed critical'
    elif c['then'] == 'boost':
        text += ' and your next move deals {}% more'.format(c['pct'])
    elif c['then'] == 'shield':
        text += ' and the next hit you take deals {}% less'.format(c['pct'])
    return text + '.'


def composed_text(sig_id):
    """(pulse, core, clutch, drawback) for a composed signature, in the same shape as SIGNATURES."""
    _a, _b, _name, _blurb, pulse, core, clutch, drawback = COMPOSED[sig_id]
    parts = [_part_text(part) for part in core]
    sentence = '; '.join([parts[0]] + [part[0].lower() + part[1:] for part in parts[1:]])
    return (pulse, sentence[0].upper() + sentence[1:] + '.', _clutch_text(clutch), _draw_text(drawback))


def loot_percent(sig_id):
    """The item-reward bonus a composed signature carries (Java-side, ItemQuantityBonus), or 0."""
    return next((part['pct'] for part in COMPOSED[sig_id][5] if part['k'] == 'loot'), 0)


# ---------------------------------------------------------------- validation of the composed data against the vocabulary
_CORE_KINDS = {'out', 'inc', 'seAdd', 'hitStatus', 'struckStatus', 'counter', 'hitHeal', 'lifesteal', 'tickHeal', 'tickChip', 'koHeal', 'koStack',
               'ramp', 'speed', 'bleed', 'struckBleed', 'potency', 'hitStage', 'struckStage', 'koStage', 'mark', 'gamble', 'legacy', 'loot'}
_DRAW_KINDS = {'outPen', 'incPen', 'healCut', 'noCrit', 'slow', 'frail', 'tickCost', 'moveCost', 'healCeil', 'gambleDown'}
for _id, (_a, _b, _name, _blurb, _pulse, _core, _clutch, _draw) in COMPOSED.items():
    assert _pulse in {'HIT', 'STRUCK', 'KO', 'TICK'}, _id
    assert 1 <= len(_core) <= 3 and all(p['k'] in _CORE_KINDS for p in _core), _id
    assert _draw['k'] in _DRAW_KINDS, _id
    assert ('gamble' in {p['k'] for p in _core}) == (_draw['k'] == 'gambleDown'), _id + ': a gamble and its tails go together'
    for _p in _core + [_draw]:
        for _field in ('pct', 'ch', 'heal'):
            if _field in _p:
                assert 1 <= _p[_field] <= 50, '{}: {} {}'.format(_id, _field, _p[_field])
    composed_text(_id)
assert len(COMPOSED) == 84 and len({(a, b) for a, b, *_ in COMPOSED.values()}) == 84


def write_composed_js(path):
    """Writes COMPOSED_SIGNATURES (battle data only: no names, no text, no loot) between the markers in ascension-fx.js."""
    import json as _json
    table = {}
    for sig_id, (_a, _b, _name, _blurb, pulse, core, clutch, drawback) in COMPOSED.items():
        spec = {'p': pulse, 'core': [p for p in core if p['k'] != 'loot'], 'draw': [drawback]}
        if clutch:
            spec['clutch'] = clutch
        table[sig_id] = spec
    lines = ['const COMPOSED_SIGNATURES = {']
    for sig_id, spec in table.items():
        lines.append('  {}: {},'.format(sig_id, _json.dumps(spec, separators=(',', ':'))))
    lines.append('};')
    with open(path, encoding='utf-8') as handle:
        text = handle.read()
    begin = '// BEGIN GENERATED COMPOSED SIGNATURES (tools/transcendent_powers.py; do not edit by hand)\n'
    end = '// END GENERATED COMPOSED SIGNATURES'
    head, rest = text.split(begin, 1)
    _old, tail = rest.split(end, 1)
    with open(path, 'w', encoding='utf-8', newline='\n') as handle:
        handle.write(head + begin + '\n'.join(lines) + '\n' + end + tail)
