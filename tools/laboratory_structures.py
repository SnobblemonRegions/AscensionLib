"""Generates the Abandoned Laboratory datapack data (docs/LABORATORY-DESIGN.md).

Writes, under ascensionlib/src/main/resources/data/ascensionlib/:
  structure/laboratory/*.nbt          room templates (gzip NBT, Minecraft 1.21.1, DataVersion 3955)
  worldgen/template_pool/laboratory/  jigsaw pools (start, hub, halls, rooms, core)
  worldgen/structure/laboratory.json, worldgen/structure_set/laboratory.json, worldgen/processor_list/laboratory_decay.json
  tags/worldgen/biome/has_structure/laboratory.json
  loot_table/laboratory/*.json        EMPTY marker tables (the mod recognises a lab container by them; never rename)
  loot_table/chests/laboratory/*.json vanilla filler given on a player's first search
  spawn_pool_world/laboratory.json    the guards (Cobblemon spawn file)

Pure Python, deterministic: running it twice writes identical files. Run: python -I tools/laboratory_structures.py
Layout: a ruined hut at the surface over a ladder shaft (one template, the start piece, sunk by start_height) -> the hub room below
(vertical, aligned jigsaw) -> halls east and west -> one room each; the hub's south door -> the core lab (exactly one research terminal).
"""
import gzip
import io
import json
import os
import random
import struct
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ascensionlib", "src", "main", "resources", "data", "ascensionlib")
DATA_VERSION = 3955  # Minecraft 1.21.1
NS = "ascensionlib"
DOOR = NS + ":lab_door"
HUB_TOP = NS + ":lab_hub_top"
SHAFT_DEPTH = 14


# ---------------------------------------------------------------- NBT
class Tag:
    def __init__(self, kind, value):
        self.kind, self.value = kind, value


def i32(v): return Tag(3, v)
def i64(v): return Tag(4, v)
def s(v): return Tag(8, v)
def lst(kind, items): return Tag(9, (kind, items))
def comp(d): return Tag(10, d)


def _name(out, text):
    raw = text.encode("utf-8")
    out.write(struct.pack(">H", len(raw)))
    out.write(raw)


def _payload(out, tag):
    k, v = tag.kind, tag.value
    if k == 3: out.write(struct.pack(">i", v))
    elif k == 4: out.write(struct.pack(">q", v))
    elif k == 8: _name(out, v)
    elif k == 9:
        kind, items = v
        out.write(struct.pack(">bi", kind if items else 0, len(items)))
        for item in items: _payload(out, item)
    elif k == 10:
        for key in sorted(v):
            out.write(struct.pack(">b", v[key].kind))
            _name(out, key)
            _payload(out, v[key])
        out.write(b"\x00")
    else: raise ValueError(k)


def nbt_bytes(root):
    raw = io.BytesIO()
    raw.write(b"\x0a")
    _name(raw, "")
    _payload(raw, root)
    packed = io.BytesIO()
    with gzip.GzipFile(fileobj=packed, mode="wb", mtime=0) as gz:
        gz.write(raw.getvalue())
    return packed.getvalue()


# ---------------------------------------------------------------- pieces
def parse_state(text):
    if "[" not in text: return text, {}
    name, rest = text[:-1].split("[", 1)
    props = dict(p.split("=") for p in rest.split(","))
    return name, props


class Piece:
    def __init__(self, name, sx, sy, sz, seed):
        self.name, self.size = name, (sx, sy, sz)
        self.cells = {}
        self.rng = random.Random(seed)

    def set(self, x, y, z, state, nbt=None):
        sx, sy, sz = self.size
        assert 0 <= x < sx and 0 <= y < sy and 0 <= z < sz, (self.name, x, y, z)
        self.cells[(x, y, z)] = (state, nbt)

    def fill(self, x0, y0, z0, x1, y1, z1, state):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, state() if callable(state) else state)

    def box(self, x0, y0, z0, x1, y1, z1, wall, floor, ceiling):
        """A closed room: floor at y0, ceiling at y1, walls around, air inside."""
        self.fill(x0, y0, z0, x1, y1, z1, "minecraft:air")
        self.fill(x0, y0, z0, x1, y0, z1, floor)
        self.fill(x0, y1, z0, x1, y1, z1, ceiling)
        for y in range(y0 + 1, y1):
            for x in range(x0, x1 + 1):
                self.set(x, y, z0, wall() if callable(wall) else wall)
                self.set(x, y, z1, wall() if callable(wall) else wall)
            for z in range(z0, z1 + 1):
                self.set(x0, y, z, wall() if callable(wall) else wall)
                self.set(x1, y, z, wall() if callable(wall) else wall)

    def jigsaw(self, x, y, z, orientation, name, pool="minecraft:empty", target="minecraft:empty", joint="rollable"):
        """A jigsaw block. A child piece attaches where its jigsaw NAME equals this one's TARGET, facing back at it."""
        nbt = {"id": s("minecraft:jigsaw"), "name": s(name), "target": s(target), "pool": s(pool), "joint": s(joint),
               "final_state": s("minecraft:air"), "placement_priority": i32(0), "selection_priority": i32(0)}
        self.set(x, y, z, "minecraft:jigsaw[orientation=" + orientation + "]", nbt)

    def container(self, x, y, z, kind, state):
        self.set(x, y, z, state, {"id": s("minecraft:chest" if "chest" in state else "minecraft:barrel"),
                                  "LootTable": s(NS + ":laboratory/" + kind)})

    def chance(self, p): return self.rng.random() < p

    def pick(self, *options):
        return self.rng.choice(options)

    def to_nbt(self):
        palette, index, blocks = [], {}, []
        for pos in sorted(self.cells, key=lambda p: (p[1], p[2], p[0])):
            state, nbt = self.cells[pos]
            if state not in index:
                index[state] = len(palette)
                name, props = parse_state(state)
                entry = {"Name": s(name)}
                if props: entry["Properties"] = comp({k: s(v) for k, v in props.items()})
                palette.append(comp(entry))
            block = {"pos": lst(3, [i32(c) for c in pos]), "state": i32(index[state])}
            if nbt is not None: block["nbt"] = comp(nbt)
            blocks.append(comp(block))
        return comp({"DataVersion": i32(DATA_VERSION), "size": lst(3, [i32(c) for c in self.size]),
                     "palette": lst(10, palette), "blocks": lst(10, blocks), "entities": lst(10, [])})


# ---------------------------------------------------------------- palette helpers
def lab_wall(p):
    return lambda: p.pick("minecraft:white_concrete", "minecraft:white_concrete", "minecraft:white_concrete", "minecraft:light_gray_concrete",
                          "minecraft:smooth_quartz")


def lab_floor(p):
    return lambda: p.pick("minecraft:polished_andesite", "minecraft:polished_andesite", "minecraft:light_gray_concrete", "minecraft:smooth_stone")


CEILING = "minecraft:smooth_stone"
LAMP = "minecraft:sea_lantern"
DEAD_LAMP = "minecraft:redstone_lamp[lit=false]"
BARREL = "minecraft:barrel[facing=up,open=false]"


def chest(facing): return "minecraft:chest[facing=" + facing + ",type=single,waterlogged=false]"


def ceiling_lights(p, y, spots):
    for x, z in spots:
        p.set(x, y, z, LAMP if p.chance(0.6) else DEAD_LAMP)


def cobwebs(p, count, x0, y0, z0, x1, y1, z1):
    for _ in range(count):
        p.set(p.rng.randint(x0, x1), p.rng.randint(y0, y1), p.rng.randint(z0, z1), "minecraft:cobweb")


def west_door(p, length_z, center_z, height=3, half=1):
    """Opens a doorway in the west wall (x=0) and puts the entrance jigsaw at floor+1 in its centre."""
    for y in range(1, 1 + height):
        for z in range(center_z - half, center_z + half + 1):
            p.set(0, y, z, "minecraft:air")
    p.jigsaw(0, 1, center_z, "west_up", DOOR)


# ---------------------------------------------------------------- the pieces
def hut():
    d = SHAFT_DEPTH
    p = Piece("hut", 11, d + 10, 11, 101)
    stoneish = lambda: p.pick("minecraft:stone_bricks", "minecraft:mossy_stone_bricks", "minecraft:cobblestone", "minecraft:mossy_cobblestone")
    # Plinth just under the floor, so a hut on a slope stands on stone rather than floating.
    p.fill(0, d - 3, 0, 10, d - 1, 10, stoneish)
    # Shaft: walls x3..7 z3..7, interior x4..6 z4..6, from the bottom up to the floor.
    for y in range(0, d):
        for x in range(3, 8):
            for z in range(3, 8):
                inner = 4 <= x <= 6 and 4 <= z <= 6
                p.set(x, y, z, "minecraft:air" if inner else "minecraft:stone_bricks" if p.chance(0.8) else "minecraft:cracked_stone_bricks")
    for y in range(0, d + 1):
        p.set(5, y, 4, "minecraft:ladder[facing=south,waterlogged=false]")
    p.jigsaw(5, 0, 5, "down_south", NS + ":lab_hut_bottom", NS + ":laboratory/hub", HUB_TOP, joint="aligned")
    # Floor with the hatch over the shaft.
    p.fill(1, d, 1, 9, d, 9, lambda: p.pick("minecraft:stone_bricks", "minecraft:polished_andesite", "minecraft:cracked_stone_bricks"))
    for x in range(4, 7):
        for z in range(4, 7):
            p.set(x, d, z, "minecraft:air")
    p.set(5, d, 4, "minecraft:ladder[facing=south,waterlogged=false]")
    # Ruined walls and the inside.
    p.fill(2, d + 1, 2, 8, d + 5, 8, "minecraft:air")
    for y in range(d + 1, d + 5):
        for i in range(1, 10):
            for x, z in ((i, 1), (i, 9), (1, i), (9, i)):
                if y >= d + 3 and p.chance(0.35 + 0.2 * (y - d - 3)): p.set(x, y, z, "minecraft:air"); continue
                p.set(x, y, z, stoneish())
    for y in range(d + 1, d + 4):           # doorway, south
        for x in range(4, 7): p.set(x, y, 9, "minecraft:air")
    for x in (3, 7):                          # windows
        p.set(x, d + 2, 1, "minecraft:iron_bars")
    p.set(1, d + 2, 5, "minecraft:iron_bars"); p.set(9, d + 2, 5, "minecraft:air")
    # Collapsed roof.
    for x in range(1, 10):
        for z in range(1, 10):
            if p.chance(0.45): p.set(x, d + 5, z, "minecraft:stone_brick_slab[type=bottom,waterlogged=false]")
    # Iron railing round the hatch, a dead console, the antenna.
    for x, z in ((3, 3), (3, 7), (7, 3), (7, 7)):
        p.set(x, d + 1, z, "minecraft:iron_bars")
    p.set(8, d + 1, 2, "minecraft:lectern[facing=west,has_book=false,powered=false]")
    p.set(8, d + 1, 3, DEAD_LAMP)
    for y in range(d + 1, d + 9):
        p.set(2, y, 8, "minecraft:iron_bars")
    p.set(2, d + 9, 8, "minecraft:lightning_rod[facing=up,powered=false,waterlogged=false]")
    cobwebs(p, 3, 2, d + 2, 2, 8, d + 4, 8)
    return p


def hub():
    p = Piece("hub", 17, 7, 17, 202)
    p.box(0, 0, 0, 16, 6, 16, lab_wall(p), lab_floor(p), CEILING)
    for x in range(7, 10):                    # ceiling hatch under the shaft
        for z in range(7, 10):
            p.set(x, 6, z, "minecraft:air")
    p.jigsaw(8, 6, 8, "up_south", HUB_TOP, joint="aligned")
    for y in range(1, 6): p.set(8, y, 6, "minecraft:iron_block")
    for y in range(1, 7): p.set(8, y, 7, "minecraft:ladder[facing=south,waterlogged=false]")
    for y in range(1, 6):                     # pillars
        for x, z in ((1, 1), (1, 15), (15, 1), (15, 15)): p.set(x, y, z, "minecraft:polished_andesite")
    ceiling_lights(p, 6, ((4, 4), (12, 4), (4, 12), (12, 12)))
    # Doors east and west (to the halls) and south (to the core lab).
    for y in range(1, 4):
        for k in range(7, 10):
            p.set(16, y, k, "minecraft:air"); p.set(0, y, k, "minecraft:air"); p.set(k, y, 16, "minecraft:air")
    p.jigsaw(16, 1, 8, "east_up", DOOR, NS + ":laboratory/halls", DOOR)
    p.jigsaw(0, 1, 8, "west_up", DOOR, NS + ":laboratory/halls", DOOR)
    p.jigsaw(8, 1, 16, "south_up", DOOR, NS + ":laboratory/core", DOOR)
    # Benches along the north wall, a broken divider, clutter.
    for x in range(3, 14):
        if x in (7, 8, 9): continue
        p.set(x, 1, 1, "minecraft:smooth_stone_slab[type=top,waterlogged=false]" if p.chance(0.8) else "minecraft:air")
    p.set(4, 2, 1, "minecraft:brewing_stand[has_bottle_0=false,has_bottle_1=false,has_bottle_2=false]")
    p.set(11, 2, 1, "minecraft:cauldron")
    p.set(12, 2, 1, "minecraft:flower_pot")
    for z in range(3, 6): p.set(3, 1, z, "minecraft:iron_bars" if p.chance(0.7) else "minecraft:air")
    p.set(13, 1, 13, "minecraft:anvil[facing=north]")
    cobwebs(p, 5, 1, 3, 1, 15, 5, 15)
    return p


def hall(name, seed, flooded=False):
    p = Piece(name, 9, 5, 5, seed)
    p.box(0, 0, 0, 8, 4, 4, lab_wall(p), lab_floor(p), CEILING)
    for y in range(1, 4):
        for z in range(1, 4):
            p.set(0, y, z, "minecraft:air"); p.set(8, y, z, "minecraft:air")
    p.jigsaw(0, 1, 2, "west_up", DOOR)
    p.jigsaw(8, 1, 2, "east_up", DOOR, NS + ":laboratory/rooms", DOOR)
    p.set(4, 4, 2, LAMP if p.chance(0.5) else DEAD_LAMP)
    for x in (2, 6):
        p.set(x, 3, 1, "minecraft:chain[axis=y,waterlogged=false]")
    if flooded:
        for x in range(1, 8): p.set(x, 1, 2 if x % 2 else 3, "minecraft:cobweb")
    else:
        cobwebs(p, 2, 1, 2, 1, 7, 3, 3)
    p.set(3, 2, 3, "minecraft:iron_trapdoor[facing=north,half=top,open=false,powered=false,waterlogged=false]")
    return p


def room_shell(name, seed):
    p = Piece(name, 11, 6, 11, seed)
    p.box(0, 0, 0, 10, 5, 10, lab_wall(p), lab_floor(p), CEILING)
    west_door(p, 11, 5)
    ceiling_lights(p, 5, ((3, 3), (7, 7)))
    return p


def storage():
    p = room_shell("storage", 301)
    for x in range(2, 9):                     # shelving rows
        for z in (2, 8):
            p.set(x, 1, z, "minecraft:smooth_stone_slab[type=top,waterlogged=false]")
            if p.chance(0.5): p.set(x, 2, z, p.pick("minecraft:iron_block", "minecraft:cauldron", "minecraft:chain[axis=y,waterlogged=false]"))
    p.container(9, 1, 3, "supply", BARREL)
    p.container(9, 1, 7, "supply", BARREL)
    p.set(9, 1, 5, "minecraft:iron_block")
    cobwebs(p, 3, 1, 2, 1, 9, 4, 9)
    return p


def dorm():
    p = room_shell("dorm", 302)
    for z in (2, 5, 8):
        p.set(8, 1, z, "minecraft:white_bed[facing=east,occupied=false,part=head]")
        p.set(7, 1, z, "minecraft:white_bed[facing=east,occupied=false,part=foot]")
    p.set(2, 1, 2, "minecraft:bookshelf"); p.set(3, 1, 2, "minecraft:bookshelf")
    p.set(2, 1, 8, "minecraft:crafting_table")
    p.container(9, 1, 9, "supply", BARREL)
    cobwebs(p, 4, 1, 2, 1, 9, 4, 9)
    return p


def server_room():
    p = room_shell("server_room", 303)
    for x in (3, 5, 7):                       # racks
        for z in range(2, 9):
            if z == 5: continue
            for y in (1, 2, 3):
                p.set(x, y, z, p.pick("minecraft:polished_blackstone", DEAD_LAMP, "minecraft:polished_blackstone_bricks", "minecraft:observer[facing=up,powered=false]"))
    p.set(9, 1, 5, "minecraft:lectern[facing=west,has_book=false,powered=false]")
    p.container(9, 1, 2, "supply", BARREL)
    cobwebs(p, 2, 1, 1, 1, 9, 4, 9)
    return p


def containment():
    p = room_shell("containment", 304)
    for cz in (2, 7):                         # two glass cells, one cracked open
        for x in range(4, 8):
            for z in range(cz, cz + 2):
                p.set(x, 1, z, "minecraft:light_blue_stained_glass" if (x in (4, 7) or z in (cz, cz + 1)) else "minecraft:air")
        for x in range(4, 8):
            p.set(x, 4, cz, "minecraft:iron_bars"); p.set(x, 4, cz + 1, "minecraft:iron_bars")
    for x in range(4, 8):
        for z in (2, 3, 7, 8):
            for y in (2, 3):
                broken = z in (7, 8) and x in (5, 6)
                p.set(x, y, z, "minecraft:air" if broken else "minecraft:light_blue_stained_glass")
    p.set(5, 1, 7, "minecraft:water_cauldron[level=1]")
    p.container(9, 1, 5, "specimen", chest("west"))
    cobwebs(p, 3, 1, 2, 1, 9, 4, 9)
    return p


def core():
    p = Piece("core", 15, 7, 13, 401)
    p.box(0, 0, 0, 14, 6, 12, lab_wall(p), lab_floor(p), CEILING)
    west_door(p, 13, 6)
    ceiling_lights(p, 6, ((4, 3), (10, 3), (4, 9), (10, 9)))
    # The pod in the middle: a sea lantern under glass, ringed in iron.
    for x in range(6, 9):
        for z in range(5, 8):
            p.set(x, 1, z, "minecraft:polished_andesite")
            for y in (2, 3): p.set(x, y, z, "minecraft:glass")
            p.set(x, 4, z, "minecraft:iron_block")
    p.set(7, 2, 6, LAMP); p.set(7, 3, 6, "minecraft:end_rod[facing=up]")
    # The research terminal on the east wall: a console of lamps and observers with the terminal chest in front.
    for z in range(4, 9):
        p.set(13, 1, z, "minecraft:polished_blackstone_bricks")
        p.set(13, 2, z, DEAD_LAMP if z % 2 else "minecraft:observer[facing=west,powered=false]")
        p.set(13, 3, z, "minecraft:tinted_glass")
    p.set(12, 1, 5, "minecraft:lectern[facing=west,has_book=false,powered=false]")
    p.container(12, 1, 6, "terminal", chest("west"))
    p.set(12, 1, 7, "minecraft:lever[face=floor,facing=west,powered=false]")
    p.container(1, 1, 11, "supply", BARREL)
    for z in (1, 11):
        for x in range(3, 12, 2): p.set(x, 1, z, "minecraft:smooth_stone_slab[type=top,waterlogged=false]")
    cobwebs(p, 5, 1, 2, 1, 13, 5, 11)
    return p


PIECES = [hut(), hub(), hall("hall", 501), hall("hall_webbed", 502, flooded=True), storage(), dorm(), server_room(), containment(), core()]


# ---------------------------------------------------------------- json data
def pool(name, entries):
    return {"fallback": "minecraft:empty",
            "elements": [{"weight": w, "element": {"element_type": "minecraft:single_pool_element", "location": NS + ":laboratory/" + piece,
                                                     "projection": "rigid", "processors": NS + ":laboratory_decay"}} for piece, w in entries]}


def item(name, weight, lo=1, hi=1):
    entry = {"type": "minecraft:item", "name": name, "weight": weight}
    if hi > 1: entry["functions"] = [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}}]
    return entry


def filler(rolls_lo, rolls_hi, entries):
    return {"type": "minecraft:chest", "random_sequence": NS + ":chests/laboratory",
            "pools": [{"rolls": {"type": "minecraft:uniform", "min": rolls_lo, "max": rolls_hi}, "entries": entries}]}


JSON = {
    "worldgen/structure/laboratory.json": {
        "type": "minecraft:jigsaw", "biomes": "#" + NS + ":has_structure/laboratory", "step": "underground_structures",
        "spawn_overrides": {}, "terrain_adaptation": "none", "start_pool": NS + ":laboratory/start", "size": 5,
        "start_height": {"absolute": -(SHAFT_DEPTH)}, "project_start_to_heightmap": "WORLD_SURFACE_WG",
        "max_distance_from_center": 80, "use_expansion_hack": False},
    "worldgen/structure_set/laboratory.json": {
        "structures": [{"structure": NS + ":laboratory", "weight": 1}],
        "placement": {"type": "minecraft:random_spread", "spacing": 48, "separation": 16, "salt": 1120334291}},
    "worldgen/template_pool/laboratory/start.json": pool("start", [("hut", 1)]),
    "worldgen/template_pool/laboratory/hub.json": pool("hub", [("hub", 1)]),
    "worldgen/template_pool/laboratory/halls.json": pool("halls", [("hall", 3), ("hall_webbed", 1)]),
    "worldgen/template_pool/laboratory/rooms.json": pool("rooms", [("storage", 3), ("dorm", 2), ("server_room", 2), ("containment", 2)]),
    "worldgen/template_pool/laboratory/core.json": pool("core", [("core", 1)]),
    "worldgen/processor_list/laboratory_decay.json": {"processors": [{"processor_type": "minecraft:rule", "rules": [
        {"input_predicate": {"predicate_type": "minecraft:random_block_match", "block": "minecraft:stone_bricks", "probability": 0.2},
         "location_predicate": {"predicate_type": "minecraft:always_true"}, "output_state": {"Name": "minecraft:cracked_stone_bricks"}},
        {"input_predicate": {"predicate_type": "minecraft:random_block_match", "block": "minecraft:white_concrete", "probability": 0.12},
         "location_predicate": {"predicate_type": "minecraft:always_true"}, "output_state": {"Name": "minecraft:light_gray_concrete"}},
        {"input_predicate": {"predicate_type": "minecraft:random_block_match", "block": "minecraft:polished_andesite", "probability": 0.1},
         "location_predicate": {"predicate_type": "minecraft:always_true"}, "output_state": {"Name": "minecraft:gravel"}},
        {"input_predicate": {"predicate_type": "minecraft:random_block_match", "block": "minecraft:glass", "probability": 0.25},
         "location_predicate": {"predicate_type": "minecraft:always_true"}, "output_state": {"Name": "minecraft:air"}}]}]},
    "tags/worldgen/biome/has_structure/laboratory.json": {"replace": False, "values": [
        "#minecraft:is_forest", "#minecraft:is_taiga", "#minecraft:is_jungle", "#minecraft:is_savanna", "#minecraft:is_badlands",
        "minecraft:plains", "minecraft:sunflower_plains", "minecraft:desert", "minecraft:snowy_plains", "minecraft:meadow", "minecraft:swamp"]},
    # Marker tables: empty on purpose. The mod pays a search itself; a hopper or an explosion that unpacks one gets nothing.
    "loot_table/laboratory/supply.json": {"type": "minecraft:chest", "pools": []},
    "loot_table/laboratory/specimen.json": {"type": "minecraft:chest", "pools": []},
    "loot_table/laboratory/terminal.json": {"type": "minecraft:chest", "pools": []},
    "loot_table/chests/laboratory/supply.json": filler(1, 3, [
        item("minecraft:iron_ingot", 10, 1, 3), item("minecraft:redstone", 10, 2, 6), item("minecraft:copper_ingot", 8, 2, 5),
        item("minecraft:paper", 8, 1, 4), item("minecraft:glass_bottle", 6, 1, 3), item("minecraft:glowstone_dust", 4, 1, 3),
        item("cobblemon:poke_ball", 6, 1, 3), item("cobblemon:potion", 5), item("cobblemon:exp_candy_s", 3)]),
    "loot_table/chests/laboratory/specimen.json": filler(1, 2, [
        item("cobblemon:great_ball", 8, 1, 2), item("cobblemon:super_potion", 6), item("cobblemon:ether", 4), item("cobblemon:exp_candy_m", 4),
        item("minecraft:slime_ball", 6, 1, 3), item("minecraft:experience_bottle", 4, 1, 3), item("cobblemon:thunder_stone", 1)]),
    "loot_table/chests/laboratory/terminal.json": filler(2, 3, [
        item("cobblemon:ultra_ball", 6, 1, 2), item("cobblemon:rare_candy", 3), item("cobblemon:exp_candy_m", 6, 1, 2),
        item("cobblemon:upgrade", 2), item("cobblemon:dubious_disc", 1), item("cobblemon:electirizer", 1), item("cobblemon:magnet", 2),
        item("cobblemon:metal_coat", 2), item("cobblemon:link_cable", 1), item("minecraft:diamond", 2), item("minecraft:experience_bottle", 6, 2, 5)]),
}


def guard(pid, species, level, weight):
    return {"id": "ascensionlib-laboratory-" + pid, "pokemon": species, "type": "pokemon", "spawnablePositionType": "grounded",
            "bucket": "common", "level": level, "weight": weight,
            # The structures condition covers the lab's whole area, surface included (seen live 2026-11-20: 16 of 36 Pokemon there were lab
            # species on the grass). No sky and little sky light keeps them in the rooms (and in any cave under the area).
            "condition": {"structures": [NS + ":laboratory"], "canSeeSky": False, "maxSkyLight": 10}}


JSON["spawn_pool_world/laboratory.json"] = {"enabled": True, "neededInstalledMods": [], "neededUninstalledMods": [], "spawns": [
    guard("porygon", "porygon", "18-35", 500.0), guard("magnemite", "magnemite", "15-32", 900.0), guard("voltorb", "voltorb", "15-32", 900.0),
    guard("klink", "klink", "15-32", 700.0), guard("ditto", "ditto", "18-35", 300.0), guard("magneton", "magneton", "30-40", 120.0)]}


def write(path, data):
    full = os.path.join(ROOT, *path.split("/"))
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "wb") as out: out.write(data)


def main():
    for piece in PIECES:
        write("structure/laboratory/" + piece.name + ".nbt", nbt_bytes(piece.to_nbt()))
    for path, data in JSON.items():
        write(path, (json.dumps(data, indent=2) + "\n").encode("utf-8"))
    containers = {p.name: sum(1 for st, n in p.cells.values() if n and "LootTable" in n) for p in PIECES}
    print("wrote", len(PIECES), "templates and", len(JSON), "json files; containers per piece:", containers)


if __name__ == "__main__":
    sys.exit(main())
