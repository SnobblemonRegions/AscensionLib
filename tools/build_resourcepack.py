"""Builds the wallet currency items' resource pack and everything derived from design/currency-items.json.

usage: python -I tools/build_resourcepack.py

Writes (all committed, regenerate after editing the design file or the art (tools/pixel_art.py)):
  resourcepack/                                                  the pack sources (pack.mcmeta, textures, models, base-item overrides)
  art/icons/*.png   the same icons as source art (not shipped; tests check every currency and modifier has one)
  ascensionlib/src/main/resources/data/ascensionlib/loot_table/currency/*.json   one-item loot tables other mods and datapacks can reference
and a zip you can host for clients, in build/AscensionLib-resourcepack.zip, printing its sha1 (put both in server.properties:
resource-pack=<url>, resource-pack-sha1=<sha1>, require-resource-pack=true).
"""
import hashlib
import json
import pathlib
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
DESIGN = json.loads((ROOT / 'design' / 'currency-items.json').read_text(encoding='utf-8'))
NS = DESIGN['namespace']
PACK = ROOT / 'resourcepack'
ICONS = ROOT / 'art/icons'
LOOT = ROOT / 'ascensionlib/src/main/resources/data' / NS / 'loot_table/currency'
ICON = ROOT / 'ascensionlib/src/main/resources/assets/ascensionlib/icon.png'
AFFIX_ICONS = ICONS / 'affix'   # one crest per modifier (tools/affix_art.py), shown on the craft, inspect and overlay pages
PACK_FORMAT = 34  # Minecraft 1.21.1

# The pictures are drawn in code (tools/pixel_art.py, 32x32, no libraries) so a clean checkout reproduces the same pixels. Loaded by path because
# this script runs isolated (python -I), which does not put its own folder on the import path.
import importlib.util
_spec = importlib.util.spec_from_file_location('pixel_art', pathlib.Path(__file__).resolve().parent / 'pixel_art.py')
pixel_art = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pixel_art)
_spec_affix = importlib.util.spec_from_file_location('affix_art', pathlib.Path(__file__).resolve().parent / 'affix_art.py')
affix_art = importlib.util.module_from_spec(_spec_affix)
_spec_affix.loader.exec_module(affix_art)


_spec_dome = importlib.util.spec_from_file_location('dome_art', pathlib.Path(__file__).resolve().parent / 'dome_art.py')
dome_art = importlib.util.module_from_spec(_spec_dome)
_spec_dome.loader.exec_module(dome_art)
_spec_bar = importlib.util.spec_from_file_location('bossbar_art', pathlib.Path(__file__).resolve().parent / 'bossbar_art.py')
bossbar_art = importlib.util.module_from_spec(_spec_bar)
_spec_bar.loader.exec_module(bossbar_art)
DOME_BASE = 'ender_eye'      # keep in step with ItemModelOverrides.DOME_BASE
DOME_FIRST_MODEL = 8201      # and ItemModelOverrides.DOME_FIRST_MODEL; variants 0 to 2 swirl, the last is the crossing flash


def icon_png(name):
    return pixel_art.png_bytes(pixel_art.render(name))


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(data, str):
        data = data.encode('utf-8')
    path.write_bytes(data)


def rarity_lore(rarity_id, kind):
    """The tooltip's first line as a loot-table text component: the rarity bold in its colour, then the kind in dark gray."""
    r = DESIGN['rarities'][rarity_id]
    return json.dumps(['', {'text': r['label'], 'color': r['color'], 'bold': True, 'italic': False},
                       {'text': ' \u00b7 ' + kind, 'color': 'dark_gray', 'bold': False, 'italic': False}], separators=(',', ':'))


def component_text(text, color):
    return json.dumps({'text': text, 'color': color, 'italic': False}, separators=(',', ':'))


def main():
    items = DESIGN['items']
    # pack metadata
    write(PACK / 'pack.mcmeta', json.dumps({'pack': {'pack_format': PACK_FORMAT, 'description': 'AscensionLib wallet currency items'}}, indent=2) + '\n')
    overrides = {}
    for icon_id, grid in affix_art.render_all().items():
        write(AFFIX_ICONS / f'{icon_id}.png', pixel_art.png_bytes(grid))
    for material, spec in items.items():
        image = icon_png(material)
        write(PACK / f'assets/{NS}/textures/item/{material}.png', image)
        write(ICONS / f'{material}.png', image)
        write(PACK / f'assets/{NS}/models/item/{material}.json',
              json.dumps({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/{material}'}}, indent=2) + '\n')
        base = spec['base'].split(':', 1)[1]
        overrides.setdefault(base, []).append({'predicate': {'custom_model_data': spec['model']}, 'model': f'{NS}:item/{material}'})
        # the voucher form: the same picture with a paper tag and a seal, under model number + VOUCHER_OFFSET (never made for a bound material)
        if spec.get('tradable', True):
            write(PACK / f'assets/{NS}/textures/item/{material}_voucher.png', pixel_art.png_bytes(pixel_art.render_voucher(material)))
            write(PACK / f'assets/{NS}/models/item/{material}_voucher.json',
                  json.dumps({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/{material}_voucher'}}, indent=2) + '\n')
            overrides[base].append({'predicate': {'custom_model_data': spec['model'] + pixel_art.VOUCHER_OFFSET}, 'model': f'{NS}:item/{material}_voucher'})
        # the one-item loot table
        components = {
            'minecraft:custom_data': {NS: {'material': material}},
            'minecraft:custom_model_data': spec['model'],
            'minecraft:custom_name': component_text(spec['name'], spec['color']),
            'minecraft:lore': [rarity_lore(spec['rarity'], spec['kind'])] + [component_text(line, 'gray') for line in spec['lore']],
        }
        table = {'type': 'minecraft:generic', 'pools': [{'rolls': 1, 'entries': [
            {'type': 'minecraft:item', 'name': spec['base'], 'functions': [{'function': 'minecraft:set_components', 'components': components}]}]}]}
        write(LOOT / f'{material}.json', json.dumps(table, indent=2) + '\n')
    # the AscensionDex: not a currency, so no icon list or loot table entry, but the same pack
    dex = DESIGN['dex']
    image = icon_png('ascension_dex')
    write(PACK / f'assets/{NS}/textures/item/ascension_dex.png', image)
    write(ICONS / 'ascension_dex.png', image)
    write(PACK / f'assets/{NS}/models/item/ascension_dex.json',
          json.dumps({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/ascension_dex'}}, indent=2) + '\n')
    overrides.setdefault(dex['base'].split(':', 1)[1], []).append({'predicate': {'custom_model_data': dex['model']}, 'model': f'{NS}:item/ascension_dex'})
    dex_components = {
        'minecraft:custom_data': {NS: {'dex': True}},
        'minecraft:custom_model_data': dex['model'],
        'minecraft:custom_name': component_text(dex['name'], dex['color']),
        'minecraft:lore': [rarity_lore(dex['rarity'], dex['kind'])] + [component_text(line, 'gray') for line in dex['lore']],
    }
    write(LOOT.parent / 'ascension_dex.json', json.dumps({'type': 'minecraft:generic', 'pools': [{'rolls': 1, 'entries': [
        {'type': 'minecraft:item', 'name': dex['base'], 'functions': [{'function': 'minecraft:set_components', 'components': dex_components}]}]}]}, indent=2) + '\n')
    # the World Boss dome's glass panels (CobbleRaids places them as display entities; the numbers are frozen, see WorldBossDome there)
    for variant in range(dome_art.VARIANTS):
        name = f'world_event_dome_{variant}'
        write(PACK / f'assets/{NS}/textures/item/{name}.png', pixel_art.png_bytes(dome_art.strip(variant)))
        write(PACK / f'assets/{NS}/textures/item/{name}.png.mcmeta', dome_art.mcmeta())
        write(PACK / f'assets/{NS}/models/item/{name}.json', dome_art.model(f'{NS}:item/{name}'))
        overrides.setdefault(DOME_BASE, []).append({'predicate': {'custom_model_data': DOME_FIRST_MODEL + variant}, 'model': f'{NS}:item/{name}'})
    # the World Boss bar: replaces vanilla's PINK boss bar sprites and the 20-notch overlay (CobbleRaids shows the World Boss as PINK, NOTCHED_20)
    for name, grid in bossbar_art.sprites().items():
        write(PACK / 'assets/minecraft/textures/gui/sprites/boss_bar' / name, pixel_art.png_bytes(grid))
    # the mod's own icon (shown by mod menus and launchers): the Ascension Sigil at 128x128 on the dark tablet colour
    write(ICON, pixel_art.png_bytes(pixel_art.mod_icon()))
    # each base item's model gains the overrides; the base items are all plain generated items, so the override file is the whole model
    for base, entries in overrides.items():
        model = {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'minecraft:item/{base}'}, 'overrides': entries}
        write(PACK / f'assets/minecraft/models/item/{base}.json', json.dumps(model, indent=2) + '\n')
    # a deterministic zip
    out = ROOT / 'build' / 'AscensionLib-resourcepack.zip'
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as archive:
        # only pack files: a stray archive or scratch file left in the folder must not ship in the pack
        for path in sorted(p for p in PACK.rglob('*') if p.is_file() and p.suffix.lower() not in ('.rar', '.zip', '.7z')):
            info = zipfile.ZipInfo(path.relative_to(PACK).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
    print('pack:', out)
    print('sha1:', hashlib.sha1(out.read_bytes()).hexdigest())


if __name__ == '__main__':
    sys.exit(main())
