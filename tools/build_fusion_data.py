"""Writes design/lore/motifs.json and design/transcendents.json (the fusion recipe book) from design/lore/species-lore.json.
Curated recipes name Pokemon by English name; they are resolved to Cobblemon species ids and validated here.
Run from the repository root: python tools/build_fusion_data.py"""
import json
import re
import sys

import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import transcendent_powers as powers
REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'design')
with open(os.path.join(REPO, 'lore', 'species-lore.json'), encoding='utf-8') as handle:
    lore = json.load(handle)['species']


def norm(text):
    return re.sub(r'[^a-z0-9]', '', text.lower().replace('♀', 'f').replace('♂', 'm').replace('é', 'e'))


by_norm = {norm(e['name']): e['species'] for e in lore}
by_norm.update({norm(e['species']): e['species'] for e in lore})


def sid(name):
    key = norm(name)
    if key not in by_norm:
        sys.exit('unknown species in a curated recipe: ' + name)
    return by_norm[key]


# ---------------------------------------------------------------- motifs
MOTIFS = {
    # id: (adjective, noun, summary)
    'EMBER': ('Cinder', 'Ember', 'fire, volcano, forge and the legend of the fire salamander'),
    'TIDE': ('Tidal', 'Tide', 'sea, river and rain, from kappa to leviathan'),
    'GALE': ('Gale', 'Gale', 'wind, wings and sky, from sparrows to thunderbirds'),
    'VERDANT': ('Verdant', 'Grove', 'plants, fungi, kodama and the world tree'),
    'FROST': ('Frozen', 'Frost', 'snow, ice and winter spirits like yuki-onna'),
    'STORM': ('Storm', 'Tempest', 'lightning, thunder gods and the raiju'),
    'STONE': ('Granite', 'Mountain', 'rock, earth, caves and golems of clay'),
    'IRON': ('Iron', 'Forge', 'steel, machines, armour and the bulgasari'),
    'VENOM': ('Blighted', 'Blight', 'poison, sludge and venomous beasts'),
    'SPIRIT': ('Spectral', 'Veil', 'ghosts, tsukumogami and what lingers after death'),
    'DREAM': ('Dreaming', 'Dream', 'minds, sleep, baku and psychic mystics'),
    'WILD': ('Feral', 'Wilds', 'predators and pack hunters of the wild'),
    'HIVE': ('Swarming', 'Hive', 'insects, spiders and the life of the colony'),
    'FAE': ('Fey', 'Glade', 'fairies, moon rabbits and gentle charm'),
    'WYRM': ('Draconic', 'Wyrm', 'dragons, sea serpents and dragon gates'),
    'SHADE': ('Shadowed', 'Dusk', 'night, thieves, oni and hellhounds'),
    'RADIANCE': ('Radiant', 'Dawn', 'sun, stars, lights and holy glow'),
    'CHIME': ('Chiming', 'Song', 'music, bells, dance and voices'),
    'GUARD': ('Warded', 'Bulwark', 'shields, shells, guardians and protectors'),
    'HARVEST': ('Bountiful', 'Harvest', 'food, feasts, honey and the nurturing hand'),
    'COSMOS': ('Starborn', 'Cosmos', 'space, time, creation and the legendary'),
    'ANCIENT': ('Primeval', 'Ages', 'fossils, relics and the deep past'),
    'ARTISAN': ('Wrought', 'Craft', 'made things, dolls, tools and makers'),
    'TRICKSTER': ('Masked', 'Mirage', 'illusion, mimicry and shape-shifters'),
    'BRAWL': ('Mighty', 'Arena', 'martial arts, wrestlers and fighters'),
}
KINDRED = [
    'EMBER-STORM', 'EMBER-STONE', 'EMBER-WYRM', 'TIDE-FROST', 'TIDE-STORM', 'TIDE-VERDANT', 'GALE-STORM', 'GALE-WYRM',
    'GALE-CHIME', 'VERDANT-HIVE', 'VERDANT-FAE', 'VERDANT-HARVEST', 'HIVE-VENOM', 'HIVE-WILD', 'VENOM-SHADE',
    'SHADE-SPIRIT', 'SHADE-TRICKSTER', 'SHADE-WILD', 'SPIRIT-DREAM', 'SPIRIT-ANCIENT', 'DREAM-COSMOS', 'DREAM-FAE',
    'DREAM-TRICKSTER', 'RADIANCE-COSMOS', 'RADIANCE-FAE', 'IRON-STONE', 'IRON-ARTISAN', 'IRON-GUARD', 'STONE-GUARD',
    'STONE-ANCIENT', 'WILD-BRAWL', 'BRAWL-GUARD', 'ANCIENT-WYRM', 'CHIME-FAE', 'CHIME-ARTISAN', 'HARVEST-FAE',
    'COSMOS-WYRM', 'FROST-STONE', 'ARTISAN-SPIRIT',
]
OPPOSED = [
    'EMBER-FROST', 'EMBER-TIDE', 'STONE-GALE', 'RADIANCE-SHADE', 'VERDANT-IRON', 'VENOM-FAE', 'SPIRIT-RADIANCE',
    'BRAWL-DREAM',
]
motifs_json = {
    'schemaVersion': 1,
    'motifs': [{'id': k, 'adjective': v[0], 'noun': v[1], 'summary': v[2]} for k, v in MOTIFS.items()],
    'kindred': sorted(set(KINDRED)),
    'opposed': sorted(set(OPPOSED)),
}
for pair in KINDRED + OPPOSED:
    a, b = pair.split('-')
    if a not in MOTIFS or b not in MOTIFS:
        sys.exit('bad relation ' + pair)
if set(KINDRED) & set(OPPOSED):
    sys.exit('relation both kindred and opposed')

# ---------------------------------------------------------------- the 21 base transcendents (one per pair of Uniques)
BASES = [
    ('ashen_heart', 'last_breath', 'phoenix_cinder', 'Phoenix Cinder', 'Embers that refuse to go out: burns hit harder and one fatal blow is survived.'),
    ('ashen_heart', 'creeping_venom', 'plague_pyre', 'Plague Pyre', 'Fire and poison feed each other, each turn of a status hurts more.'),
    ('ashen_heart', 'stormcaller', 'wildfire_crown', 'Wildfire Crown', 'Sunlit fire and weather-borne force burn as one.'),
    ('ashen_heart', 'rupture', 'brand_of_ruin', 'Brand of Ruin', 'Burns and bleeds together, weaker but wider.'),
    ('ashen_heart', 'titans_heart', 'titans_forge', "Titan's Forge", 'A burning giant whose super-effective hits shake the ground.'),
    ('ashen_heart', 'triple_seven', 'gilded_ember', 'Gilded Ember', 'Fortune and flame: richer rewards and stronger burns.'),
    ('last_breath', 'creeping_venom', 'dying_bloom', 'Dying Bloom', 'Hang on at 1 HP while poison builds stronger.'),
    ('last_breath', 'stormcaller', 'eye_of_the_storm', 'Eye of the Storm', 'Survive the lethal strike, then ride the weather.'),
    ('last_breath', 'rupture', 'martyrs_edge', "Martyr's Edge", 'Survive once and bleed them as they fall.'),
    ('last_breath', 'titans_heart', 'colossus_vow', 'Colossus Vow', 'A colossus that refuses to fall and strikes with its full weight.'),
    ('last_breath', 'triple_seven', 'last_gamble', 'Last Gamble', 'Stake everything on one more turn for richer winnings.'),
    ('creeping_venom', 'stormcaller', 'miasma_front', 'Miasma Front', 'Poison carried on the weather front.'),
    ('creeping_venom', 'rupture', 'festering_gash', 'Festering Gash', 'Wounds that bleed and rot at once.'),
    ('creeping_venom', 'titans_heart', 'blighted_giant', 'Blighted Giant', 'A poisoned giant: hits land hard and poison ramps.'),
    ('creeping_venom', 'triple_seven', 'fortunes_rot', "Fortune's Rot", 'Prosperity that spoils its owner and its victims alike.'),
    ('stormcaller', 'rupture', 'lightning_rend', 'Lightning Rend', 'Weather-fed strikes that open bleeding wounds.'),
    ('stormcaller', 'titans_heart', 'tempest_colossus', 'Tempest Colossus', 'A giant under its own storm.'),
    ('stormcaller', 'triple_seven', 'skyfall_fortune', 'Skyfall Fortune', 'Weather and luck falling from the same sky.'),
    ('rupture', 'titans_heart', 'breakers_might', "Breaker's Might", 'Physical strikes that bleed and super-effective strikes that hammer.'),
    ('rupture', 'triple_seven', 'wager_of_blood', 'Wager of Blood', 'A bleeding bet: richer prizes for riskier fights.'),
    ('titans_heart', 'triple_seven', 'jackpot_titan', 'Jackpot Titan', 'A giant paid in gold, hurt by its own weight.'),
]
# The 84 made with the second batch of Uniques (docs/AFFIX-EXPANSION-DESIGN.md): composed signatures, from tools/transcendent_powers.py.
BASES += [(a, b, i, n, t) for i, (a, b, n, t, *_rest) in powers.COMPOSED.items()]
uniques = {u for b in BASES for u in b[:2]}
pairs_seen = {frozenset((a, b)) for a, b, *_ in BASES}
if len(uniques) != 15 or len(BASES) != 105 or len(pairs_seen) != 105:
    sys.exit('the base table must cover every pair of the fifteen Uniques exactly once')
if len({n for _, _, _, n, _ in BASES}) != len(BASES) or len({i for _, _, i, _, _ in BASES}) != len(BASES):
    sys.exit('every base needs its own id and name')

# ---------------------------------------------------------------- curated recipes
PAIRS = [  # (host, donor, id, name, harmony, blurb) - recognised in either order
    ('Kyogre', 'Groudon', 'primordial_clash', 'the Primordial Clash', 'OPPOSED', 'Sea and land, locked in the oldest war.'),
    ('Dialga', 'Palkia', 'hand_of_creation', 'the Hand of Creation', 'PURE', 'Time and space, the two halves of the world.'),
    ('Reshiram', 'Zekrom', 'taijitu', 'the Taijitu', 'OPPOSED', 'Truth and ideals, yang and yin turning together.'),
    ('Kyurem', 'Reshiram', 'white_boundary', 'the White Boundary', 'PURE', 'The empty vessel filled with truth.'),
    ('Kyurem', 'Zekrom', 'black_boundary', 'the Black Boundary', 'PURE', 'The empty vessel filled with ideals.'),
    ('Zacian', 'Zamazenta', 'crowned_pair', 'the Crowned Pair', 'PURE', 'Sword and shield of a forgotten king.'),
    ('Xerneas', 'Yveltal', 'wheel_of_life', 'the Wheel of Life', 'OPPOSED', 'Life and destruction, one cocoon to the next.'),
    ('Solgaleo', 'Lunala', 'eclipse', 'the Eclipse', 'OPPOSED', 'Sun and moon, devouring and giving light.'),
    ('Necrozma', 'Solgaleo', 'dusk_mane', 'the Dusk Mane', 'KINDRED', 'A prism feeding on the sun.'),
    ('Necrozma', 'Lunala', 'dawn_wings', 'the Dawn Wings', 'KINDRED', 'A prism feeding on the moon.'),
    ('Latias', 'Latios', 'eon_bond', 'the Eon Bond', 'PURE', 'Siblings who share their dreams.'),
    ('Lugia', 'Ho-Oh', 'rainbow_tower', 'the Rainbow Tower', 'KINDRED', 'Guardians of the sea and the sky.'),
    ('Mew', 'Mewtwo', 'original_and_copy', 'the Original and the Copy', 'PURE', 'The ancestor and the mind that refused its making.'),
    ('Calyrex', 'Glastrier', 'ice_rider', 'the Ice Rider', 'KINDRED', 'The king upon the steed of ice.'),
    ('Calyrex', 'Spectrier', 'shadow_rider', 'the Shadow Rider', 'KINDRED', 'The king upon the steed of shadow.'),
    ('Koraidon', 'Miraidon', 'past_and_future', 'Past and Future', 'OPPOSED', 'The ancient dragon and the iron serpent.'),
    ('Arceus', 'Giratina', 'origin_and_shadow', 'the Origin and Shadow', 'OPPOSED', 'The creator and its banished echo.'),
    ('Donphan', 'Great Tusk', 'elder_tusk', 'the Elder Tusk', 'PURE', 'Armoured elephant meets its primeval mammoth.'),
    ('Donphan', 'Iron Treads', 'wheel_of_donphan', 'the Wheeled Elder', 'PURE', 'Armoured elephant meets its future machine.'),
    ('Great Tusk', 'Iron Treads', 'tusk_and_tread', 'Tusk and Tread', 'OPPOSED', 'Past and future of the same armoured giant.'),
    ('Jigglypuff', 'Scream Tail', 'lullaby_unbound', 'the Unbound Lullaby', 'PURE', 'The gentle singer and her screaming ancestor.'),
    ('Delibird', 'Iron Bundle', 'gift_of_tomorrow', 'the Gift of Tomorrow', 'PURE', 'Penguin deliverer and its machine descendant.'),
    ('Hariyama', 'Iron Hands', 'sumo_machine', 'the Sumo Machine', 'PURE', 'Rikishi and its future palm-striker.'),
    ('Hydreigon', 'Iron Jugulis', 'iron_hydra', 'the Iron Hydra', 'PURE', 'Three-headed brute and its iron successor.'),
    ('Volcarona', 'Iron Moth', 'foo_fighter', 'the Foo Fighter', 'PURE', 'Sun moth and its UFO descendant.'),
    ('Volcarona', 'Slither Wing', 'dawn_of_moths', 'the Dawn of Moths', 'PURE', 'Sun moth and its primeval ancestor.'),
    ('Slither Wing', 'Iron Moth', 'moth_across_ages', 'the Moth Across Ages', 'OPPOSED', 'Past and future of the sun moth.'),
    ('Tyranitar', 'Iron Thorns', 'mecha_kaiju', 'the Mecha Kaiju', 'PURE', 'Armoured kaiju and its mechanical echo.'),
    ('Gardevoir', 'Iron Valiant', 'paladin_oath', "the Paladin's Oath", 'PURE', 'The knight-dancer and a future paladin.'),
    ('Gallade', 'Iron Valiant', 'paladin_blade', "the Paladin's Blade", 'PURE', 'The warrior elf and a future paladin.'),
    ('Misdreavus', 'Flutter Mane', 'flying_head', 'the Flying Head', 'PURE', 'Wailing ghost and its ancient hair-winged kin.'),
    ('Magneton', 'Sandy Shocks', 'ironsand_age', 'the Ironsand Age', 'PURE', 'Three magnets and ten thousand years of dust.'),
    ('Amoonguss', 'Brute Bonnet', 'mold_beneath', 'the Mold Beneath', 'PURE', 'The lure mushroom and its parasitic mold dinosaur.'),
    ('Salamence', 'Roaring Moon', 'dragon_of_the_dream', 'the Dragon of the Dream', 'PURE', 'The dream-born dragon and its ancient rampager.'),
    ('Suicune', 'Walking Wake', 'purifying_wake', 'the Purifying Wake', 'PURE', 'Aurora beast and its ancient walking wake.'),
    ('Virizion', 'Iron Leaves', 'shining_blades', 'the Shining Blades', 'PURE', 'Musketeer of the grass and its blade descendant.'),
    ('Entei', 'Gouging Fire', 'regenerated_pyre', 'the Regenerated Pyre', 'PURE', 'Volcano lion reborn as a horned fire.'),
    ('Raikou', 'Raging Bolt', 'thunder_lizard', 'the Thunder Lizard', 'PURE', 'Tiger of thunder and its sauropod ancestor.'),
    ('Terrakion', 'Iron Boulder', 'blade_of_the_cavern', 'the Blade of the Cavern', 'PURE', 'Cavern charger and its spade-bladed echo.'),
    ('Cobalion', 'Iron Crown', 'crown_of_iron', 'the Crown of Iron', 'PURE', 'Leader of the swords and its crowned echo.'),
    ('Aerodactyl', 'Archeops', 'prehistoric_skies', 'the Prehistoric Skies', 'KINDRED', 'Pterosaur and first bird sharing the ancient sky.'),
    ('Golem', 'Golurk', 'two_golems', 'the Two Golems', 'KINDRED', 'Earthen golem and clay automaton.'),
    ('Pikachu', 'Mimikyu', 'borrowed_face', 'the Borrowed Face', 'TRICKSTER_PAIR', 'The mascot and the ghost who wishes to be loved like it.'),
    ('Scyther', 'Scizor', 'blade_and_pincer', 'the Blade and the Pincer', 'PURE', 'Mantis and its steel-claw successor.'),
    ('Gengar', 'Clefable', 'moon_shadow', 'the Moon Shadow', 'OPPOSED', 'The shadow that mimics a moon pixie.'),
]
GROUPS = [  # (id, name, harmony, members, blurb) - any two distinct members, in either order
    ('weather_pact', 'the Weather Pact', 'KINDRED', ['Kyogre', 'Groudon', 'Rayquaza'], 'The three who shaped the sky, sea and land.'),
    ('legendary_flight', 'the Legendary Flight', 'KINDRED', ['Articuno', 'Zapdos', 'Moltres'], 'The three great birds of Kanto.'),
    ('roaming_beasts', 'the Roaming Beasts', 'KINDRED', ['Raikou', 'Entei', 'Suicune'], 'Those revived after the Brass Tower fell.'),
    ('titan_golems', 'the Titan Golems', 'KINDRED', ['Regirock', 'Regice', 'Registeel', 'Regigigas', 'Regieleki', 'Regidrago'], 'Golems of writing and stone.'),
    ('lake_guardians', 'the Lake Guardians', 'KINDRED', ['Uxie', 'Mesprit', 'Azelf'], 'Knowledge, emotion and willpower.'),
    ('swords_of_justice', 'the Swords of Justice', 'KINDRED', ['Cobalion', 'Terrakion', 'Virizion', 'Keldeo'], 'Knights who protected Pokemon from humans.'),
    ('forces_of_nature', 'the Forces of Nature', 'KINDRED', ['Tornadus', 'Thundurus', 'Landorus', 'Enamorus'], 'Wind, thunder, earth and love.'),
    ('guardian_deities', 'the Guardian Deities', 'KINDRED', ['Tapu Koko', 'Tapu Lele', 'Tapu Bulu', 'Tapu Fini'], 'The land spirits of Alola.'),
    ('treasures_of_ruin', 'the Treasures of Ruin', 'KINDRED', ['Wo-Chien', 'Chien-Pao', 'Ting-Lu', 'Chi-Yu'], 'Grudges bound to four sealed relics.'),
    ('beyond_the_wormhole', 'the Wormhole', 'KINDRED', ['Nihilego', 'Buzzwole', 'Pheromosa', 'Xurkitree', 'Celesteela', 'Kartana', 'Guzzlord', 'Poipole', 'Naganadel', 'Stakataka', 'Blacephalon'], 'Beasts that came from another world.'),
    ('retainers_of_the_ogre', 'the Ogre Retainers', 'KINDRED', ['Okidogi', 'Munkidori', 'Fezandipiti', 'Ogerpon', 'Pecharunt'], 'The tale of Momotaro with its heroes and villains reversed.'),
    ('pseudo_summit', 'the Dragon Summit', 'KINDRED', ['Dragonite', 'Tyranitar', 'Salamence', 'Metagross', 'Garchomp', 'Hydreigon', 'Goodra', 'Kommo-o', 'Dragapult', 'Baxcalibur'], 'The pinnacle of the three-stage dragons.'),
    ('kanto_triad', 'the Kanto Triad', 'KINDRED', ['Venusaur', 'Charizard', 'Blastoise'], 'The first partners.'),
    ('johto_triad', 'the Johto Triad', 'KINDRED', ['Meganium', 'Typhlosion', 'Feraligatr'], 'The partners of Johto.'),
    ('hoenn_triad', 'the Hoenn Triad', 'KINDRED', ['Sceptile', 'Blaziken', 'Swampert'], 'The partners of Hoenn.'),
    ('sinnoh_triad', 'the Sinnoh Triad', 'KINDRED', ['Torterra', 'Infernape', 'Empoleon'], 'The partners of Sinnoh.'),
    ('unova_triad', 'the Unova Triad', 'KINDRED', ['Serperior', 'Emboar', 'Samurott'], 'The partners of Unova.'),
    ('kalos_triad', 'the Kalos Triad', 'KINDRED', ['Chesnaught', 'Delphox', 'Greninja'], 'The partners of Kalos.'),
    ('alola_triad', 'the Alola Triad', 'KINDRED', ['Decidueye', 'Incineroar', 'Primarina'], 'The partners of Alola.'),
    ('galar_triad', 'the Galar Triad', 'KINDRED', ['Rillaboom', 'Cinderace', 'Inteleon'], 'The partners of Galar.'),
    ('paldea_triad', 'the Paldea Triad', 'KINDRED', ['Meowscarada', 'Skeledirge', 'Quaquaval'], 'The partners of Paldea.'),
    ('kitsune_court', 'the Kitsune Court', 'KINDRED', ['Vulpix', 'Ninetales', 'Zorua', 'Zoroark', 'Fennekin', 'Braixen', 'Delphox'], 'Fox spirits and their many tails.'),
    ('bushido_court', 'the Bushido Court', 'KINDRED', ['Samurott', 'Bisharp', 'Kingambit', 'Pawniard', 'Golisopod', 'Kartana', 'Honedge', 'Aegislash'], 'Warriors of blade and honour.'),
    ('dragon_gate', 'the Dragon Gate', 'KINDRED', ['Magikarp', 'Gyarados', 'Dratini', 'Dragonair', 'Dragonite', 'Feebas', 'Milotic'], 'The carp that leaps the falls and becomes a dragon.'),
    ('moon_court', 'the Moon Court', 'KINDRED', ['Clefairy', 'Clefable', 'Cleffa', 'Lunatone', 'Lunala', 'Cresselia', 'Umbreon', 'Nidoran-F'], 'Creatures of the moon.'),
    ('sun_court', 'the Sun Court', 'KINDRED', ['Solrock', 'Solgaleo', 'Volcarona', 'Sunflora', 'Espeon', 'Ho-Oh'], 'Creatures of the sun.'),
    ('animated_earth', 'the Animated Earth', 'KINDRED', ['Baltoy', 'Claydol', 'Golett', 'Golurk', 'Regirock', 'Nosepass', 'Probopass', 'Sudowoodo'], 'Clay, stone and the golem that walks.'),
    ('halloween_hollow', 'the Hollow', 'KINDRED', ['Pumpkaboo', 'Gourgeist', 'Phantump', 'Trevenant', 'Gastly', 'Haunter', 'Gengar', 'Shuppet', 'Banette', 'Mimikyu'], 'The ghosts that gather at harvest end.'),
    ('tea_ceremony', 'the Tea Ceremony', 'KINDRED', ['Sinistea', 'Polteageist', 'Poltchageist', 'Sinistcha'], 'Poltergeists of the tea cup.'),
    ('dessert_table', 'the Dessert Table', 'KINDRED', ['Swirlix', 'Slurpuff', 'Milcery', 'Alcremie', 'Fidough', 'Dachsbun', 'Vanillite', 'Vanillish', 'Vanilluxe', 'Applin', 'Appletun', 'Dipplin'], 'Sweet things shared.'),
    ('machine_choir', 'the Machine Choir', 'KINDRED', ['Magnemite', 'Magneton', 'Magnezone', 'Klink', 'Klang', 'Klinklang', 'Beldum', 'Metang', 'Metagross', 'Porygon', 'Porygon2', 'Porygon-Z', 'Rotom'], 'Gears, magnets and living circuits.'),
    ('three_wise_monkeys', 'the Three Wise Monkeys', 'KINDRED', ['Pansage', 'Simisage', 'Pansear', 'Simisear', 'Panpour', 'Simipour'], 'See, hear and speak no evil.'),
    ('fossil_revival', 'the Fossil Revival', 'KINDRED', ['Omanyte', 'Omastar', 'Kabuto', 'Kabutops', 'Aerodactyl', 'Lileep', 'Cradily', 'Anorith', 'Armaldo', 'Cranidos', 'Rampardos', 'Shieldon', 'Bastiodon', 'Tirtouga', 'Carracosta', 'Archen', 'Archeops', 'Tyrunt', 'Tyrantrum', 'Amaura', 'Aurorus'], 'Creatures brought back from stone.'),
    ('galar_fossils', 'the Mismatched Fossils', 'KINDRED', ['Dracozolt', 'Arctozolt', 'Dracovish', 'Arctovish'], 'Chimeras stitched from the wrong halves.'),
    ('mythical_dawn', 'the Mythical Dawn', 'KINDRED', ['Mew', 'Celebi', 'Jirachi', 'Manaphy', 'Shaymin', 'Victini', 'Keldeo', 'Meloetta', 'Diancie', 'Hoopa', 'Volcanion', 'Magearna', 'Marshadow', 'Zeraora', 'Meltan', 'Melmetal', 'Zarude', 'Deoxys', 'Genesect', 'Darkrai'], 'The elusive Pokemon known only through rumours.'),
]
PAIR_HARMONY = {'TRICKSTER_PAIR': 'KINDRED'}


def pair_entry(host, donor, rid, name, harmony, blurb):
    return {'id': rid, 'name': name, 'harmony': PAIR_HARMONY.get(harmony, harmony), 'species': [sid(host), sid(donor)], 'blurb': blurb}


pairs = [pair_entry(*p) for p in PAIRS]
seen = set()
for entry in pairs:
    key = frozenset(entry['species'])
    if key in seen:
        sys.exit('duplicate pair ' + str(entry['species']))
    seen.add(key)
groups = []
for gid, name, harmony, members, blurb in GROUPS:
    ids = [sid(m) for m in members]
    if len(set(ids)) != len(ids):
        sys.exit('duplicate member in group ' + gid)
    groups.append({'id': gid, 'name': name, 'harmony': harmony, 'members': ids, 'blurb': blurb})
ids_used = [p['id'] for p in pairs] + [g['id'] for g in groups]
if len(set(ids_used)) != len(ids_used):
    sys.exit('duplicate recipe id')

# ---------------------------------------------------------------- Transcendent powers (docs/TRANSCENDENT-POWERS.md)
for entry in pairs + groups:
    if entry['id'] not in powers.RECIPE_TWISTS:
        sys.exit('curated recipe without a bespoke twist: ' + entry['id'])
    label, ops = powers.RECIPE_TWISTS[entry['id']]
    entry['twist'] = {'name': label, 'effects': ops}
unused = set(powers.RECIPE_TWISTS) - {e['id'] for e in pairs + groups}
if unused:
    sys.exit('twists for recipes that do not exist: ' + str(sorted(unused)))
base_ids = {i for _, _, i, _, _ in BASES}
all_signatures = dict(powers.SIGNATURES)
all_signatures.update({i: powers.composed_text(i) for i in powers.COMPOSED})
if set(all_signatures) != base_ids:
    sys.exit('signatures and bases differ: ' + str(set(all_signatures) ^ base_ids))
if set(powers.TWISTS) != set(MOTIFS) or set(powers.RIDERS) != set(MOTIFS):
    sys.exit('every motif needs exactly one twist and one rider')
signatures = [{'id': i, 'pulse': v[0], 'core': v[1], 'clutch': v[2], 'drawback': v[3]} for i, v in all_signatures.items()]
twists = {m: {'name': n, 'effects': ops} for m, (n, ops) in powers.TWISTS.items()}
riders = {m: {'affix': a, 'value': v} for m, (a, v) in powers.RIDERS.items()}

transcendents = {
    'schemaVersion': 2,
    'status': 'provisional-research-based-recipes',
    'scales': {
        'LINEAGE': {'benefit': 85, 'drawback': 55},
        'PURE': {'benefit': 82, 'drawback': 58},
        'KINDRED': {'benefit': 78, 'drawback': 60},
        'NEUTRAL': {'benefit': 75, 'drawback': 60},
        'OPPOSED': {'benefit': 88, 'drawback': 78},
    },
    'bases': [{'id': i, 'name': n, 'uniques': [a, b], 'blurb': t} for a, b, i, n, t in BASES],
    'signatures': signatures,
    'twists': twists,
    'riders': riders,
    'pairs': pairs,
    'groups': groups,
}
with open(REPO + '/lore/motifs.json', 'w', encoding='utf-8', newline='\n') as handle:
    json.dump(motifs_json, handle, ensure_ascii=False, indent=1)
with open(REPO + '/transcendents.json', 'w', encoding='utf-8', newline='\n') as handle:
    json.dump(transcendents, handle, ensure_ascii=False, indent=1)
powers.write_composed_js(os.path.join(REPO, '..', 'ascensionlib', 'src', 'main', 'resources', 'assets', 'ascensionlib', 'showdown', 'ascension-fx.js'))
print('pairs', len(pairs), 'groups', len(groups), 'bases', len(BASES), 'motifs', len(MOTIFS), 'signatures', len(signatures), 'twists', len(twists), 'riders', len(riders))
