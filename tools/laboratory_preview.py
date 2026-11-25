"""Assembles one Abandoned Laboratory from the templates the way the jigsaw placer does, checks it, and draws a preview.

Checks: every child attaches to a parent jigsaw (name == target, facing back at it); no two pieces' boxes overlap; each doorway is open on both
sides; the ladder runs unbroken from the hub floor to the hut floor; exactly one research terminal. Writes build/laboratory-preview.png
(floor plan of the underground level and a side cut through the shaft). Pure Python. Run: python -I tools/laboratory_preview.py [seed]
"""
import gzip
import io
import os
import random
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
STRUCT = os.path.join(HERE, "..", "ascensionlib", "src", "main", "resources", "data", "ascensionlib", "structure", "laboratory")
OUT = os.path.join(HERE, "..", "build", "laboratory-preview.png")

POOLS = {"ascensionlib:laboratory/hub": [("hub", 1)], "ascensionlib:laboratory/halls": [("hall", 3), ("hall_webbed", 1)],
         "ascensionlib:laboratory/rooms": [("storage", 3), ("dorm", 2), ("server_room", 2), ("containment", 2)],
         "ascensionlib:laboratory/core": [("core", 1)]}
DIRS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
OPP = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "down", "down": "up"}
CW = {"north": "east", "east": "south", "south": "west", "west": "north", "up": "up", "down": "down"}


# ------------------------------------------------------------ NBT reader
def read_nbt(path):
    data = io.BytesIO(gzip.open(path).read())
    def rd(fmt): return struct.unpack(fmt, data.read(struct.calcsize(fmt)))[0]
    def rs(): return data.read(rd(">H")).decode("utf-8")
    def payload(kind):
        if kind == 1: return rd(">b")
        if kind == 3: return rd(">i")
        if kind == 4: return rd(">q")
        if kind == 8: return rs()
        if kind == 9:
            inner, n = rd(">b"), rd(">i")
            return [payload(inner) for _ in range(n)]
        if kind == 10:
            out = {}
            while True:
                k = rd(">b")
                if k == 0: return out
                key = rs()                      # the name comes first: d[rs()] = payload(k) would read the payload first
                out[key] = payload(k)
        raise ValueError(kind)
    assert rd(">b") == 10
    rs()
    return payload(10)


class Template:
    def __init__(self, name):
        root = read_nbt(os.path.join(STRUCT, name + ".nbt"))
        self.name, self.size = name, tuple(root["size"])
        self.blocks = {}
        self.jigsaws = []
        for b in root["blocks"]:
            entry = root["palette"][b["state"]]
            props = entry.get("Properties", {})
            pos = tuple(b["pos"])
            self.blocks[pos] = (entry["Name"], props, b.get("nbt"))
            if entry["Name"] == "minecraft:jigsaw":
                facing = props["orientation"].split("_")[0]
                self.jigsaws.append((pos, facing, b["nbt"]))


def rotate(pos, facing, turns, size):
    x, y, z = pos
    for _ in range(turns):
        x, z = -z, x
        facing = CW[facing]
    return (x, y, z), facing


class Placed:
    def __init__(self, template, turns, offset):
        self.t, self.turns, self.offset = template, turns, offset

    def world(self, pos):
        (x, y, z), _ = rotate(pos, "north", self.turns, self.t.size)
        return (x + self.offset[0], y + self.offset[1], z + self.offset[2])

    def box(self):
        pts = [self.world(p) for p in ((0, 0, 0), (self.t.size[0] - 1, self.t.size[1] - 1, self.t.size[2] - 1))]
        return tuple(min(a[i] for a in pts) for i in range(3)), tuple(max(a[i] for a in pts) for i in range(3))


def overlap(a, b):
    return all(a[0][i] <= b[1][i] and b[0][i] <= a[1][i] for i in range(3))


def assemble(seed):
    rng = random.Random(seed)
    cache = {}
    def tpl(n):
        if n not in cache: cache[n] = Template(n)
        return cache[n]
    start = Placed(tpl("hut"), rng.randrange(4), (0, 0, 0))
    placed, queue, problems = [start], [start], []
    while queue:
        parent = queue.pop(0)
        for pos, facing, nbt in parent.t.jigsaws:
            pool = nbt["pool"]
            if pool == "minecraft:empty": continue
            wpos = parent.world(pos)
            _, wfacing = rotate(pos, facing, parent.turns, parent.t.size)
            options = POOLS[pool]
            name = rng.choices([o[0] for o in options], [o[1] for o in options])[0]
            child_t = tpl(name)
            match = [(p, f, n) for p, f, n in child_t.jigsaws if n["name"] == nbt["target"]]
            if not match:
                problems.append(f"{parent.t.name}: no jigsaw named {nbt['target']} in {name}"); continue
            cpos, cfacing, cnbt = match[0]
            fitted = None
            for turns in range(4):
                rpos, rfacing = rotate(cpos, cfacing, turns, child_t.size)
                if rfacing != OPP[wfacing]: continue
                if nbt["joint"] == "aligned" and turns != parent.turns % 4: continue
                d = DIRS[wfacing]
                target = (wpos[0] + d[0], wpos[1] + d[1], wpos[2] + d[2])
                fitted = Placed(child_t, turns, (target[0] - rpos[0], target[1] - rpos[1], target[2] - rpos[2]))
                break
            if fitted is None:
                problems.append(f"{parent.t.name} -> {name}: no rotation faces back"); continue
            for other in placed:
                if overlap(fitted.box(), other.box()): problems.append(f"{name} overlaps {other.t.name}")
            placed.append(fitted)
            queue.append(fitted)
    world = {}
    for piece in placed:
        for pos, block in piece.t.blocks.items():
            world[piece.world(pos)] = block
    return placed, world, problems


def check(placed, world, problems):
    names = [p.t.name for p in placed]
    terminals = sum(1 for b in world.values() if b[2] and b[2].get("LootTable", "").endswith("/terminal"))
    if terminals != 1: problems.append(f"{terminals} research terminals")
    hub = next(p for p in placed if p.t.name == "hub")
    # Ladder: the hub's ladder column, hub floor+1 up to the hut floor, every block a ladder.
    ladders = [pos for pos, b in world.items() if b[0] == "minecraft:ladder"]
    columns = {}
    for x, y, z in ladders: columns.setdefault((x, z), []).append(y)
    if len(columns) != 1: problems.append(f"ladder is in {len(columns)} columns, not one")
    else:
        ys = sorted(next(iter(columns.values())))
        if ys != list(range(ys[0], ys[-1] + 1)): problems.append("ladder has a gap")
    # Doorways: each used horizontal jigsaw must have air (or the jigsaw) on both sides at floor+1 and floor+2.
    for piece in placed:
        for pos, facing, nbt in piece.t.jigsaws:
            if facing in ("up", "down"): continue
            w = piece.world(pos)
            _, wf = rotate(pos, facing, piece.turns, piece.t.size)
            d = DIRS[wf]
            beyond = (w[0] + d[0], w[1] + d[1] + 1, w[2] + d[2])
            if beyond in world and world[beyond][0] not in ("minecraft:air", "minecraft:jigsaw", "minecraft:cobweb"):
                problems.append(f"{piece.t.name} door at {w} is blocked by {world[beyond][0]}")
    return names, terminals


# ------------------------------------------------------------ drawing
COLORS = {"air": (24, 26, 30), "white_concrete": (225, 228, 230), "light_gray_concrete": (170, 172, 175), "smooth_quartz": (236, 230, 222),
          "polished_andesite": (132, 135, 134), "smooth_stone": (160, 160, 160), "sea_lantern": (170, 230, 220), "redstone_lamp": (120, 70, 50),
          "barrel": (196, 140, 60), "chest": (240, 190, 40), "ladder": (150, 110, 60), "iron_block": (210, 210, 215), "iron_bars": (110, 110, 115),
          "cobweb": (240, 240, 240), "stone_bricks": (120, 120, 120), "mossy_stone_bricks": (100, 120, 90), "cobblestone": (110, 110, 110),
          "mossy_cobblestone": (90, 115, 85), "jigsaw": (200, 60, 200), "glass": (160, 210, 230), "light_blue_stained_glass": (120, 180, 230),
          "white_bed": (230, 60, 60), "polished_blackstone": (40, 36, 44), "polished_blackstone_bricks": (48, 42, 50), "observer": (80, 80, 80),
          "lectern": (170, 120, 70), "gravel": (130, 124, 120), "tinted_glass": (60, 50, 70), "stone": (100, 100, 100)}
SPECIAL = {"/terminal": (255, 60, 60), "/specimen": (60, 200, 255), "/supply": (255, 200, 40)}


def color(block):
    if block is None: return (60, 52, 44)                 # untouched terrain
    name, props, nbt = block
    if nbt and "LootTable" in nbt:
        for k, c in SPECIAL.items():
            if nbt["LootTable"].endswith(k): return c
    return COLORS.get(name.split(":")[1].split("[")[0], (150, 150, 150))


def png(path, pixels, w, h, scale):
    rows = []
    for y in range(h * scale):
        row = bytearray([0])
        for x in range(w * scale):
            row += bytes(pixels[y // scale][x // scale])
        rows.append(bytes(row))
    raw = zlib.compress(b"".join(rows), 9)
    def chunk(kind, data): return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w * scale, h * scale, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", raw) + chunk(b"IEND", b""))


def draw(placed, world):
    hub = next(p for p in placed if p.t.name == "hub")
    floor_y = hub.box()[0][1]
    xs = [p[0] for p in world]; zs = [p[2] for p in world]; ys = [p[1] for p in world]
    x0, x1, z0, z1, y0, y1 = min(xs), max(xs), min(zs), max(zs), min(ys), max(ys)
    w, plan_h = x1 - x0 + 1, z1 - z0 + 1
    cut_h = y1 - y0 + 1
    gap = 2
    h = plan_h + gap + cut_h
    pixels = [[(18, 18, 22)] * w for _ in range(h)]
    for z in range(z0, z1 + 1):
        for x in range(x0, x1 + 1):
            b = world.get((x, floor_y + 1, z))
            if b and b[0] == "minecraft:air": b = world.get((x, floor_y + 2, z), b)
            if b and b[0] == "minecraft:air": b = world.get((x, floor_y, z), b)
            pixels[z - z0][x - x0] = color(b)
    # Side cut through the ladder column (x/z of the ladder), drawn along x.
    lad = next((pos for pos, b in world.items() if b[0] == "minecraft:ladder"), None)
    cz = lad[2] + 1 if lad else hub.world((8, 0, 8))[2]
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            pixels[plan_h + gap + (y1 - y)][x - x0] = color(world.get((x, y, cz)))
    png(OUT, pixels, w, h, 8)
    return OUT


def main():
    seeds = [int(a) for a in sys.argv[1:]] or list(range(12))
    failures = 0
    for seed in seeds:
        placed, world, problems = assemble(seed)
        names, terminals = check(placed, world, problems)
        status = "OK " if not problems else "BAD"
        print(f"{status} seed {seed}: {', '.join(names)}" + ("".join("\n    " + p for p in problems)))
        failures += bool(problems)
    placed, world, _ = assemble(seeds[0])
    print("preview:", os.path.normpath(draw(placed, world)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
