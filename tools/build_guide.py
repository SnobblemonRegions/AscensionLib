"""Builds the in-game guide and the wiki handoff from one source, design/guide.json, with every number read from the code and the design data.

usage: python -I tools/build_guide.py            write the outputs
       python -I tools/build_guide.py --check     fail (exit 1) if an output is out of date or a placeholder cannot be resolved

Writes (both committed):
  ascensionlib/src/main/resources/web/ascensionlib/guide-data.js   window.GUIDE = {...}: the topics the Guide tab and guide.html draw (wikiOnly topics left out)
  docs/WIKI-HANDOFF.md                                             every topic as one Markdown document a wiki can be built from, plus the generated reference tables

Numbers: {placeholders} in guide.json are replaced from FACTS below, which reads the Java constants (so a changed rule changes the text) and the design JSON files.
A placeholder with no fact is an error, so the guide never shows a stale or empty number. Pure Python, no libraries, deterministic.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
J = ROOT / 'domain/src/main/java/com/cobbleascend/domain/v1'
DESIGN = ROOT / 'design'
OUT_JS = ROOT / 'ascensionlib/src/main/resources/web/ascensionlib/guide-data.js'
OUT_MD = ROOT / 'docs/WIKI-HANDOFF.md'


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def java_const(file, name):
    """The integer value of `public static final int NAME = N;` (or a multi-constant line) in a Java file."""
    text = (file if isinstance(file, pathlib.Path) else ROOT / file).read_text(encoding='utf-8')
    m = re.search(r'\b' + re.escape(name) + r'\s*=\s*(-?\d+)\b', text)
    if not m:
        raise SystemExit(f'cannot find the constant {name} in {file}')
    return int(m.group(1))


def js_const(name):
    text = (ROOT / 'ascensionlib/src/main/resources/assets/ascensionlib/showdown/ascension-fx.js').read_text(encoding='utf-8')
    m = re.search(r'const ' + re.escape(name) + r'\s*=\s*(\d+)', text)
    if not m:
        raise SystemExit(f'cannot find {name} in ascension-fx.js')
    return int(m.group(1))


def tower_bands():
    """The completion chest's tiers, parsed from TowerCompletionBands.DEFAULTS."""
    text = (J / 'TowerCompletionBands.java').read_text(encoding='utf-8')
    head = re.search(r'new TowerCompletionBands\((\d+), (\d+), (\d+), (\d+), (\d+), (\d+), (\d+), (\d+), List\.of\(', text)
    dust, facets, fragments, scouters, marks, gold_bonus, mythic_bonus, bundle = (int(x) for x in head.groups())
    tiers = []
    for m in re.finditer(r'new TierSpec\(Tier\.(\w+), (\d+), (\d+), (\d+), (\d+), new int\[\] \{([^}]*)\}\)', text):
        name, min_score, percent, slots, attunement, weights = m.groups()
        tiers.append(dict(name=name, min=int(min_score), percent=int(percent), slots=int(slots), attunement=int(attunement), weights=[int(w) for w in weights.split(',')]))
    return dict(dust=dust, facets=facets, fragments=fragments, scouters=scouters, marks=marks, gold_bonus=gold_bonus, mythic_bonus=mythic_bonus, bundle=bundle, tiers=tiers)


def mini_chest():
    """The default mini chest, from `MiniChest DEFAULTS = new MiniChest(...)` in Tunables."""
    text = (J / 'Tunables.java').read_text(encoding='utf-8')
    m = re.search(r'MiniChest DEFAULTS = new MiniChest\((\d+), (\d+), (\d+), (\d+), (\d+), (\d+)\)', text)
    keys = ['dust', 'facets', 'fragments', 'scouters', 'marks', 'corePermille']
    return dict(zip(keys, (int(x) for x in m.groups())))


def half_up(x):
    """The rounding the payouts use (Java Math.round), not Python's round-half-even."""
    import math
    return int(math.floor(x + 0.5))


def build_facts():
    balance = load(DESIGN / 'balance.json')
    ranked = 'domain/src/main/java/com/cobbleascend/domain/v1/RankedProgression.java'
    fusion = J / 'FusionRules.java'
    towers = tower_bands()
    mini = mini_chest()
    f = {
        'milestone.step': java_const(J / 'Milestones.java', 'STEP'),
        'milestone.credits': java_const(J / 'Milestones.java', 'MAX_LEVEL') // java_const(J / 'Milestones.java', 'STEP'),
        'attunement.perVictory': java_const(J / 'Tunables.java', 'DEFAULT_ATTUNEMENT_PER_VICTORY'),
        'refine.dust': balance['crafts']['refine']['dust'],
        'reforge.dust': balance['crafts']['reforge']['dust'],
        'reforge.facets': balance['crafts']['reforge']['facets'],
        'perfect.base': java_const(ranked, 'PERFECT_BASE_DUST'),
        'perfect.step': java_const(ranked, 'PERFECT_STEP_DUST'),
        'overreach.chance': java_const(ranked, 'OVERREACH_CHANCE_PERCENT'),
        'overreach.min': java_const(ranked, 'OVERREACH_MIN_PERCENT'),
        'overreach.max': java_const(ranked, 'OVERREACH_MAX_PERCENT'),
        'overreach.dust': java_const(ranked, 'OVERREACH_DUST'),
        'overreach.facets': java_const(ranked, 'OVERREACH_FACETS'),
        'overreach.marks': java_const(ranked, 'OVERREACH_MARKS'),
        'install.marks': java_const(ranked, 'INSTALL_MARKS'),
        'respec.price': java_const(J / 'Tunables.java', 'DEFAULT_RESPEC_MARKS_PRICE'),
        'catalyst.fragments': int(re.search(r'new StoreConfig\((\d+)\)', (ROOT / 'store/src/main/java/com/cobbleascend/store/StoreConfig.java').read_text(encoding='utf-8')).group(1)),
        'fusion.dust': java_const(fusion, 'DUST'), 'fusion.facets': java_const(fusion, 'FACETS'), 'fusion.cores': java_const(fusion, 'CORES'),
        'fusion.catalysts': java_const(fusion, 'CATALYSTS'), 'fusion.marks': java_const(fusion, 'MARKS'), 'fusion.hostAttunement': java_const(fusion, 'HOST_ATTUNEMENT'),
        'fee.percent': java_const(J / 'Tunables.java', 'DEFAULT_FEE_PERCENT'),
        'tower.bundle': towers['bundle'],
        'mini.dust': mini['dust'], 'mini.facets': mini['facets'], 'mini.fragments': mini['fragments'], 'mini.scouters': mini['scouters'],
        'mini.marks': mini['marks'], 'mini.corePercent': mini['corePermille'] // 10,
        'bleed.percent': round(100 / js_const('BLEED_DIVISOR')), 'bleed.stacks': js_const('BLEED_STACKS'), 'bleed.turns': js_const('BLEED_TURNS'),
        'ui.min': java_const('ascensionlib/src/main/java/com/ascensionlib/web/UiPrefs.java', 'MIN_PERCENT'),
        'ui.max': java_const('ascensionlib/src/main/java/com/ascensionlib/web/UiPrefs.java', 'MAX_PERCENT'),
        'backup.keep': java_const('store/src/main/java/com/cobbleascend/store/StoreBackups.java', 'KEEP'),
        'transcendent.count': len(load(DESIGN / 'transcendents.json')['signatures']),
    }
    for name, value in balance['capsPercent'].items():
        f['caps.' + name] = value
    budget = re.search(r'DailyRewardBudget DEFAULTS = new DailyRewardBudget\((\d+), (\d+)\)', (J / 'DailyRewardBudget.java').read_text(encoding='utf-8'))
    f['budget.full'], f['budget.percent'] = int(budget.group(1)), int(budget.group(2))
    # how often the respec items drop, from the crown weights themselves: a tier's chance for an entry is weight / total, times its slots, weighted by the tier mix
    mix = {'BRONZE': 0.15, 'SILVER': 0.45, 'GOLD': 0.30, 'MYTHIC': 0.10}   # the approved assumption (docs/TOWER-COMPLETION-REWARDS.md)
    rate = {'rank': 0.0, 'unique': 0.0}
    for t in towers['tiers']:
        total = sum(t['weights'])
        rate['rank'] += mix[t['name']] * t['slots'] * t['weights'][6] / total
        rate['unique'] += mix[t['name']] * t['slots'] * t['weights'][7] / total
    f['respec.rankOneIn'] = half_up(1 / rate['rank'])
    f['respec.uniqueOneIn'] = half_up(1 / rate['unique'])
    return f, towers, balance


# The numbers a server owner can tune (Tunables): the in-game guide keeps these as placeholders and fills them from the server's live values; the wiki handoff shows the defaults.
LIVE = ['budget.full', 'budget.percent', 'fee.percent', 'respec.price', 'attunement.perVictory', 'mini.dust', 'mini.facets', 'mini.fragments', 'mini.scouters',
        'mini.marks', 'mini.corePercent', 'tower.bundle', 'respec.rankOneIn', 'respec.uniqueOneIn']
LIVE_TABLES = ['tower_tier_table']


def fill(text, facts, keep_live=False):
    def sub(m):
        key = m.group(1)
        if keep_live and key in LIVE:
            return m.group(0)
        if key not in facts:
            raise SystemExit(f'guide.json uses {{{key}}} but there is no such fact in tools/build_guide.py')
        return str(facts[key])
    return re.sub(r'\{([a-zA-Z]+\.[a-zA-Z]+)\}', sub, text)



# What players see for each stored rarity id. The ids stay (saved profiles use them); Legendary and Mythical were renamed on screen so they
# cannot be confused with Cobblemon's Legendary and Mythical species. Keep in step with Rarity.displayName() and A.rarityName in ui.js.
RARITY_NAMES = {'legendary': 'Radiant', 'mythical': 'Ascended'}
SLOT_NAMES = {'prefix': 'Attack', 'suffix': 'Guard'}


def rarity_name(rarity_id):
    return RARITY_NAMES.get(rarity_id, rarity_id.capitalize())


# ---- generated tables: each returns {head, rows} ---------------------------------------------------------------------------------------------

def table_rarity(facts, towers, balance):
    total = sum(r['weight'] for r in balance['rarities'])
    rows = []
    for r in balance['rarities']:
        pct = r['weight'] * 100 / total
        rows.append([rarity_name(r['id']), str(r['prefixSlots'] + r['suffixSlots']), f"{r['prefixSlots']} attack, {r['suffixSlots']} guard", (f'{pct:.1f}%' if pct >= 1 else f'{pct:.2f}%') + ''])
    return {'head': ['Rarity', 'Trait slots', 'Layout', 'Base chance'], 'rows': rows}


def table_promotion(facts, towers, balance):
    rows = []
    for p in balance['promotions']:
        cost = [f"{p['dust']} dust"] + ([f"{p['facets']} facets"] if p['facets'] else []) + ([f"{p['cores']} cores"] if p['cores'] else [])
        rows.append([f"{rarity_name(p['from'])} to {rarity_name(p['to'])}", ', '.join(cost), str(p['attunement'])])
    return {'head': ['Promotion', 'Cost', 'Attunement needed'], 'rows': rows}


def table_unique(facts, towers, balance):
    ranked = load(DESIGN / 'ranked-catalog.json')
    return {'head': ['Unique', 'Benefit', 'Drawback'], 'rows': [[u['name'], u['benefit'], u['drawback']] for u in ranked['uniques']]}


def table_material(facts, towers, balance):
    items = load(DESIGN / 'currency-items.json')
    rar = items['rarities']
    rows = []
    for key, spec in items['items'].items():
        rows.append([spec['name'], rar[spec['rarity']]['label'].capitalize(), ' '.join(spec['lore']), 'Bound to you' if spec.get('tradable') is False else 'Tradable'])
    return {'head': ['Item', 'Rarity', 'What it is for', 'Trade'], 'rows': rows}


def table_tower_tier(facts, towers, balance):
    crowns = ['Core', 'Fragment bundle', 'Sigil', 'Catalyst', 'Armor piece', 'CobbleCards pack', 'Hourglass of Undoing', 'Unbinding Prism']
    rows = []
    for t in towers['tiers']:
        marks = towers['marks'] + (towers['gold_bonus'] if t['name'] in ('GOLD', 'MYTHIC') else 0) + (towers['mythic_bonus'] if t['name'] == 'MYTHIC' else 0)
        total = sum(t['weights'])
        odds = {c: t['weights'][i] * 100 / total for i, c in enumerate(crowns)}
        core = f"{half_up(odds['Core'])}% Core"
        rows.append([t['name'].capitalize().replace('Mythic', 'Mythic'), f"score {t['min']}+", f"x{t['percent'] / 100:g}",
                     f"{half_up(towers['dust'] * t['percent'] / 100)} dust, {half_up(towers['facets'] * t['percent'] / 100)} facets, {half_up(towers['fragments'] * t['percent'] / 100)} fragments, {towers['scouters']} scouters, {marks} Marks",
                     f"{t['slots']} crown roll{'s' if t['slots'] > 1 else ''} ({core} each)"])
    return {'head': ['Tier', 'Reached at', 'Pile', 'Guaranteed pile', 'Crown'], 'rows': rows}


def table_affix(facts, towers, balance):
    affixes = load(DESIGN / 'affixes.json')['affixes']
    ranks = load(DESIGN / 'ranked-catalog.json')['ranks']
    rows = []
    for a in affixes:
        bands = ranks[a['id']]
        rows.append([a['name'], SLOT_NAMES.get(a['slot'], a['slot'].capitalize()), a['channel'], a['condition'], ' / '.join(f'{lo}-{hi}%' for lo, hi in bands)])
    return {'head': ['Trait', 'Slot', 'Channel', 'Effect', 'Values at ranks I / II / III / IV / V'], 'rows': rows}


def table_transcendent(facts, towers, balance):
    t = load(DESIGN / 'transcendents.json')
    ranked = {u['id']: u['name'] for u in load(DESIGN / 'ranked-catalog.json')['uniques']}
    rows = []
    for b in t['bases']:
        rows.append([b['name'] if 'name' in b else b['id'].replace('_', ' ').title(), ' + '.join(ranked.get(u, u) for u in b['uniques']), b['blurb']])
    return {'head': ['Transcendent', 'Made from', 'Flavour'], 'rows': rows}


GEN = {'rarity_table': table_rarity, 'promotion_table': table_promotion, 'unique_table': table_unique, 'material_table': table_material,
       'tower_tier_table': table_tower_tier, 'affix_table': table_affix, 'transcendent_table': table_transcendent}


def resolve(guide, facts, towers, balance, keep_live=False):
    """Every placeholder filled and every gen block turned into a table; returns the topics."""
    topics = []
    for topic in guide['topics']:
        blocks = []
        for b in topic['blocks']:
            if b['type'] == 'gen':
                if b['name'] not in GEN:
                    raise SystemExit(f"guide.json asks for the unknown generated table {b['name']}")
                table = dict(type='table', **GEN[b['name']](facts, towers, balance))
                if keep_live and b['name'] in LIVE_TABLES:
                    table['live'] = b['name']
                blocks.append(table)
            elif b['type'] in ('p', 'tip', 'warn'):
                blocks.append(dict(type=b['type'], text=fill(b['text'], facts, keep_live)))
            elif b['type'] in ('ul', 'steps'):
                blocks.append(dict(type=b['type'], items=[fill(i, facts, keep_live) for i in b['items']]))
            elif b['type'] == 'table':
                blocks.append(dict(type='table', head=[fill(h, facts, keep_live) for h in b['head']], rows=[[fill(c, facts, keep_live) for c in row] for row in b['rows']]))
            else:
                raise SystemExit(f"unknown block type {b['type']}")
        topics.append({'id': topic['id'], 'title': topic['title'], 'wikiOnly': bool(topic.get('wikiOnly')), 'blocks': blocks})
    return topics


def render_js(topics, facts):
    game = [{k: v for k, v in t.items() if k != 'wikiOnly'} for t in topics if not t['wikiOnly']]
    live = {k: str(facts[k]) for k in LIVE}
    return ('/* GENERATED by tools/build_guide.py from design/guide.json: do not edit. The in-game guide (the Guide tab and guide.html) draws this. facts are the defaults of the\n'
            '   numbers a server can tune; the page replaces them with the live values the server sends (the text keeps them as {placeholders}). */\n'
            'window.GUIDE = ' + json.dumps({'facts': live, 'topics': game}, indent=1, ensure_ascii=False) + ';\n')


def md_table(block):
    esc = lambda c: str(c).replace('|', '\\|').replace('\n', ' ')
    lines = ['| ' + ' | '.join(esc(h) for h in block['head']) + ' |', '|' + '|'.join('---' for _ in block['head']) + '|']
    lines += ['| ' + ' | '.join(esc(c) for c in row) + ' |' for row in block['rows']]
    return '\n'.join(lines)


def render_md(guide, topics, facts):
    out = ['# AscensionLib: wiki handoff', '',
           'Generated by `tools/build_guide.py` from `design/guide.json`, the design data and the code, so every number matches the rules. Do not edit by hand: change the source and rebuild. Each `##` section is one wiki page.',
           '', '**About the mod.** AscensionLib is a server-side Fabric mod for Cobblemon (Minecraft 1.21.1). Every Pokémon gets a rarity and modifiers that change how it fights, and players grow them with upgrades, '
           'materials and rare items. Screens are web pages shown by the WebGUI mod on the client. It is the shared library for CobbleRaids, CobbleTowers and ascensionDungeons.', '']
    for topic in topics:
        out += [f"## {topic['title']}", '']
        for b in topic['blocks']:
            if b['type'] == 'p':
                out += [b['text'], '']
            elif b['type'] == 'tip':
                out += [f"> **Tip:** {b['text']}", '']
            elif b['type'] == 'warn':
                out += [f"> **Important:** {b['text']}", '']
            elif b['type'] == 'ul':
                out += [f'- {i}' for i in b['items']] + ['']
            elif b['type'] == 'steps':
                out += [f'{n}. {i}' for n, i in enumerate(b['items'], 1)] + ['']
            elif b['type'] == 'table':
                out += [md_table(b), '']
    out += ['## Credits and licences', '',
            'AscensionLib is by cmormods and is released under the MIT licence. The page font is Monocraft (OFL). Pokémon lore notes are summarised in our own words from the Cobblemon jar data, PokeAPI and Bulbapedia (see `THIRD-PARTY.md`). '
            'The item tooltips borrow the idea of rarity-coloured tooltips from Rarity Tooltips by Snuzzle (MIT); the tooltip frames themselves need Minecraft 1.21.2 or later.', '']
    return '\n'.join(out)


def main():
    check = '--check' in sys.argv
    guide = load(DESIGN / 'guide.json')
    facts, towers, balance = build_facts()
    topics = resolve(guide, facts, towers, balance)
    game_topics = resolve(guide, facts, towers, balance, keep_live=True)
    outputs = {OUT_JS: render_js(game_topics, facts), OUT_MD: render_md(guide, topics, facts)}
    stale = []
    for path, text in outputs.items():
        current = path.read_text(encoding='utf-8') if path.exists() else None
        if current != text:
            stale.append(path.relative_to(ROOT).as_posix())
            if not check:
                path.write_text(text, encoding='utf-8', newline='\n')
    if check and stale:
        print('out of date (run python -I tools/build_guide.py):', ', '.join(stale))
        return 1
    print('guide: ' + ('up to date' if check else 'written') + f" ({sum(1 for t in topics if not t['wikiOnly'])} in-game topics, {len(topics)} wiki topics, {len(facts)} facts)")
    return 0


if __name__ == '__main__':
    sys.exit(main())
