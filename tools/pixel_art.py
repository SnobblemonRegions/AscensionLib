"""The art for AscensionLib's reskinned items, drawn in code: 32x32 pixel art for the wallet currencies, the Tower Mark and the AscensionDex.

Used by tools/build_resourcepack.py (pure Python, no libraries, so a clean checkout reproduces the same pictures byte for byte). Each icon is a function
that draws on a Canvas; nothing is random except seeded grain, so the output never changes between runs. The look is shared: a dark warm outline
(the same 1b120d as the old art), a five-step colour ramp per material lit from the top left, a light rim and a few bright specular pixels.

usage (preview): python -I tools/pixel_art.py out.png     writes every icon side by side at 8x for a quick look
"""
import math
import random
import struct
import sys
import zlib

SIZE = 32
OUTLINE = (0x1b, 0x12, 0x0d, 255)


def rgb(hex_text):
    return (int(hex_text[0:2], 16), int(hex_text[2:4], 16), int(hex_text[4:6], 16), 255)


def ramp(*hexes):
    """Dark to light."""
    return [rgb(h) for h in hexes]


class Canvas:
    def __init__(self):
        self.px = [[None] * SIZE for _ in range(SIZE)]

    def set(self, x, y, color):
        if 0 <= x < SIZE and 0 <= y < SIZE:
            self.px[y][x] = color

    def get(self, x, y):
        return self.px[y][x] if 0 <= x < SIZE and 0 <= y < SIZE else None

    def poly(self, points, color):
        """Fills a polygon (even-odd), sampling at pixel centres."""
        ys = [p[1] for p in points]
        for y in range(max(0, int(min(ys))), min(SIZE, int(math.ceil(max(ys))) + 1)):
            cy = y + 0.5
            xs = []
            for i in range(len(points)):
                (x1, y1), (x2, y2) = points[i], points[(i + 1) % len(points)]
                if (y1 <= cy < y2) or (y2 <= cy < y1):
                    xs.append(x1 + (cy - y1) * (x2 - x1) / (y2 - y1))
            xs.sort()
            for i in range(0, len(xs) - 1, 2):
                for x in range(int(math.floor(xs[i] + 0.5)), int(math.floor(xs[i + 1] + 0.5))):
                    self.set(x, y, color)

    def sphere(self, cx, cy, r, colors, light=(-0.55, -0.65), ry=None):
        """A lit ball: five quantised tones by a simple lambert term, so it reads as round."""
        ry = ry or r
        lx, ly = light
        lz = math.sqrt(max(0.0, 1 - lx * lx - ly * ly))
        for y in range(SIZE):
            for x in range(SIZE):
                dx, dy = (x + 0.5 - cx) / r, (y + 0.5 - cy) / ry
                d2 = dx * dx + dy * dy
                if d2 > 1:
                    continue
                nz = math.sqrt(1 - d2)
                lam = max(0.0, dx * lx + dy * ly + nz * lz)
                tone = min(len(colors) - 1, int(lam * len(colors) * 0.95 + 0.15))
                self.set(x, y, colors[tone])

    def disc(self, cx, cy, r, color, ry=None):
        ry = ry or r
        for y in range(SIZE):
            for x in range(SIZE):
                if ((x + 0.5 - cx) / r) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1:
                    self.set(x, y, color)

    def ring(self, cx, cy, r, thickness, color, ry=None, only=None):
        """An annulus; `only` is a function (x, y) -> bool to draw just a part of it (the half behind a ball, say)."""
        ry = ry or r
        for y in range(SIZE):
            for x in range(SIZE):
                d = math.sqrt(((x + 0.5 - cx) / r) ** 2 + ((y + 0.5 - cy) / ry) ** 2)
                if 1 - thickness / r <= d <= 1 and (only is None or only(x, y)):
                    self.set(x, y, color)

    def line(self, x1, y1, x2, y2, color):
        steps = int(max(abs(x2 - x1), abs(y2 - y1))) * 2 + 1
        for i in range(steps + 1):
            t = i / steps
            self.set(int(round(x1 + (x2 - x1) * t)), int(round(y1 + (y2 - y1) * t)), color)

    def sparkle(self, x, y, color, arm=2, core=None):
        self.set(x, y, core or color)
        for i in range(1, arm + 1):
            for dx, dy in ((i, 0), (-i, 0), (0, i), (0, -i)):
                self.set(x + dx, y + dy, color)

    def outline(self, color=OUTLINE):
        """A one pixel dark edge around everything drawn, so every icon reads against any background."""
        marks = []
        for y in range(SIZE):
            for x in range(SIZE):
                if self.px[y][x] is None:
                    if any(self.get(x + dx, y + dy) is not None and self.get(x + dx, y + dy) != color for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                        marks.append((x, y))
        for x, y in marks:
            self.px[y][x] = color

    def rows(self):
        return [[p if p is not None else (0, 0, 0, 0) for p in row] for row in self.px]


# ---- shared helpers ----------------------------------------------------------------------------------------------------------------------

def speckle(c, seed, region, light, dark, count):
    """A few grains of lighter and darker colour inside what is already drawn, for a worked, grainy surface."""
    rnd = random.Random(seed)
    x0, y0, x1, y1 = region
    for _ in range(count):
        x, y = rnd.randint(x0, x1), rnd.randint(y0, y1)
        if c.get(x, y) is not None and c.get(x, y) != OUTLINE:
            c.set(x, y, light if rnd.random() < 0.5 else dark)


# ---- the icons ---------------------------------------------------------------------------------------------------------------------------

GOLD = ramp('7a4f12', 'b07a1e', 'd9a441', 'f2c765', 'fff3b0')


def resonance_dust():
    c = Canvas()
    rnd = random.Random(101)
    # a low, wide mound of glowing grains, each grain lit by its height and by how far left it sits
    for row in range(7):
        width = 20 - row * 3
        for i in range(width):
            x = 16 - width // 2 + i
            y = 26 - row * 2 + rnd.choice((0, 0, 1))
            lit = 2 + (1 if row >= 2 else 0) + (1 if x < 13 else 0) - (1 if x > 19 else 0) - (1 if row == 0 else 0)
            c.set(x, y, GOLD[max(0, min(4, lit))])
            c.set(x, y + 1, GOLD[max(0, min(4, lit - 1))])
    # a scatter of loose grains around the base
    for (x, y, t) in [(5, 27, 2), (27, 26, 2), (8, 28, 1), (24, 28, 1), (11, 29, 2)]:
        c.set(x, y, GOLD[t])
    # motes rising from the top, and two small sparkles
    for (x, y, t) in [(12, 12, 3), (20, 11, 4), (15, 7, 3), (23, 16, 3), (9, 17, 2), (18, 15, 4)]:
        c.set(x, y, GOLD[t])
    c.sparkle(16, 10, GOLD[3], arm=2, core=GOLD[4])
    c.sparkle(24, 6, GOLD[3], arm=1, core=GOLD[4])
    c.outline()
    return c


BLUE = ramp('1d3f6e', '2f6fb5', '4f9ad9', '8cc6f2', 'e6f6ff')


def facet():
    c = Canvas()
    # a brilliant cut seen from the side: table and crown on top, pavilion below the girdle
    crown = [
        ([(11, 7), (21, 7), (19, 12), (13, 12)], 3),
        ([(8, 12), (11, 7), (13, 12)], 2),
        ([(21, 7), (24, 12), (19, 12)], 1),
    ]
    for pts, tone in crown:
        c.poly(pts, BLUE[tone])
    pav = [
        ([(8, 12), (13, 12), (16, 27)], 1),
        ([(13, 12), (16, 12), (16, 27)], 3),
        ([(16, 12), (19, 12), (16, 27)], 2),
        ([(19, 12), (24, 12), (16, 27)], 0),
    ]
    for pts, tone in pav:
        c.poly(pts, BLUE[tone])
    # girdle line and facet edges
    for x in range(8, 25):
        c.set(x, 12, BLUE[1])
    c.line(13, 12, 11, 7, BLUE[1]); c.line(19, 12, 21, 7, BLUE[0])
    # light along the table and a glint
    for x in range(12, 21):
        c.set(x, 8, BLUE[4])
    c.set(10, 10, BLUE[4]); c.set(9, 11, BLUE[3])
    c.sparkle(23, 5, BLUE[3], arm=2, core=BLUE[4])
    c.outline()
    return c


CORE = ramp('5b140c', '8f2f1c', 'c9501f', 'e8793c', 'ffb98a')


def ascension_core():
    c = Canvas()
    ring_col = ramp('7a4f12', 'b07a1e', 'd9a441', 'f2c765', 'fff3b0')

    def behind(x, y):
        return y < 16.5

    def front(x, y):
        return y >= 16.5

    # the orbit ring: its far half goes behind the ball, its near half in front
    c.ring(16, 16, 14, 1.6, ring_col[1], ry=5, only=behind)
    c.sphere(16, 16, 9.5, CORE)
    # a hot heart showing through, and a light rim
    c.sphere(14.5, 14.5, 3.4, ramp('ffb98a', 'ffd2ae', 'ffe6cc', 'fff3e4', 'ffffff'))
    c.ring(16, 16, 9.5, 1.0, CORE[2], only=lambda x, y: x > 17 and y > 17)
    c.ring(16, 16, 14, 1.8, ring_col[3], ry=5, only=front)
    c.ring(16, 16, 14, 0.9, ring_col[4], ry=5, only=lambda x, y: front(x, y) and x < 14)
    c.sparkle(25, 8, ring_col[3], arm=2, core=ring_col[4])
    c.outline()
    return c


PURPLE = ramp('2b1850', '4d2f7a', '8c63c7', 'b690ea', 'efe0ff')


def unique_fragment():
    c = Canvas()

    def shard(cx, top, bottom, half, tones):
        mid = top + (bottom - top) * 0.28
        c.poly([(cx, top), (cx - half, mid), (cx, mid + 2)], PURPLE[tones[0]])
        c.poly([(cx, top), (cx, mid + 2), (cx + half, mid)], PURPLE[tones[1]])
        c.poly([(cx - half, mid), (cx, mid + 2), (cx, bottom)], PURPLE[tones[2]])
        c.poly([(cx, mid + 2), (cx + half, mid), (cx, bottom)], PURPLE[tones[3]])
        c.line(cx, top, cx, bottom, PURPLE[tones[1]])

    shard(21, 10, 29, 4.5, (3, 2, 1, 0))      # the small shard behind, right
    shard(13, 3, 28, 6.5, (4, 3, 2, 1))        # the big one in front, left
    c.line(11, 8, 10, 14, PURPLE[4])
    c.sparkle(23, 7, PURPLE[3], arm=2, core=PURPLE[4])
    c.set(18, 18, PURPLE[4])
    c.outline()
    return c


MAGENTA = ramp('5a1e55', '8e2f86', 'c64fb8', 'ec86dd', 'ffd6f6')


def unique_catalyst():
    c = Canvas()
    # an eight pointed star with a bright heart, a small cage of light around it
    def star(r_out, r_in, points=8, rot=-math.pi / 2):
        pts = []
        for i in range(points * 2):
            r = r_out if i % 2 == 0 else r_in
            a = rot + i * math.pi / points
            pts.append((16 + r * math.cos(a), 16 + r * math.sin(a)))
        return pts

    c.poly(star(14, 5.2), MAGENTA[1])
    c.poly(star(12, 4.4), MAGENTA[2])
    c.poly(star(9.5, 3.8, rot=-math.pi / 2 + math.pi / 8), MAGENTA[3])
    c.sphere(16, 16, 5.2, ramp('ffd6f6', 'ffe6fa', 'fff0fc', 'fff8fe', 'ffffff'))
    # lit edges on the upper left arms, shaded lower right
    for (x, y) in [(16, 3), (15, 4), (6, 8), (5, 9), (3, 15), (4, 14)]:
        c.set(x, y, MAGENTA[4])
    c.sparkle(25, 6, MAGENTA[3], arm=2, core=MAGENTA[4])
    c.sparkle(7, 25, MAGENTA[3], arm=1, core=MAGENTA[4])
    c.outline()
    return c


STEEL = ramp('3a4a52', '5d7480', '8ca2ad', 'bccdd6', 'f2f8fb')
LENS = ramp('1f4a2a', '2f6f3f', '4f9a52', '8fd18a', 'dcffd2')


def scouter():
    c = Canvas()
    # the earpiece the lens hangs from: a small steel box with a lamp, and a stem up to the bezel
    c.poly([(2, 22), (9, 22), (9, 29), (2, 29)], STEEL[1])
    for x in range(2, 10): c.set(x, 22, STEEL[3])
    for y in range(22, 30): c.set(2, y, STEEL[3])
    for x in range(2, 10): c.set(x, 29, STEEL[0])
    c.disc(5.5, 25.5, 1.6, LENS[1]); c.set(5, 25, LENS[4])
    c.poly([(8, 20), (12, 19), (14, 23), (10, 24)], STEEL[2])
    c.line(9, 23, 13, 20, STEEL[0])
    # lens: a steel bezel, the glass lit green, a reticle and glare
    c.sphere(19, 14, 11, STEEL)
    c.sphere(19, 14, 8, LENS, light=(-0.45, -0.7))
    c.ring(19, 14, 5.2, 0.9, LENS[0])
    c.line(19, 8, 19, 20, LENS[0]); c.line(13, 14, 25, 14, LENS[0])
    c.disc(19, 14, 1.6, LENS[4])
    c.poly([(14, 9), (17, 7), (18, 8), (15, 11)], LENS[4])
    c.set(15, 8, (255, 255, 255, 255))
    for (x, y) in [(19, 4), (19, 24), (9, 14), (29, 14)]:
        c.set(x, y, STEEL[4]); c.set(x, y + 1, STEEL[0])
    c.sparkle(27, 5, LENS[3], arm=2, core=LENS[4])
    c.outline()
    return c


def ascension_sigil():
    c = Canvas()
    c.sphere(16, 16, 13.5, GOLD, light=(-0.4, -0.7))
    c.disc(16, 16, 10.8, GOLD[0])
    c.sphere(16, 16, 10, ramp('6a420e', '8a5a14', 'a8741c', 'c4902b', 'd9a441'), light=(0.4, 0.7))
    c.ring(16, 16, 10, 0.9, GOLD[3], only=lambda x, y: x + y < 32)

    def star(outer, inner):
        pts = []
        for i in range(8):
            r = outer if i % 2 == 0 else inner
            a = -math.pi / 2 + i * math.pi / 4
            pts.append((16 + r * math.cos(a), 16 + r * math.sin(a)))
        return pts

    c.poly(star(9, 3.2), GOLD[0])           # a dark engraved edge
    c.poly(star(7.6, 2.8), GOLD[3])         # the star itself, bright
    # the lit top-left arms and shaded lower-right ones
    for (x, y) in [(16, 9), (16, 10), (9, 16), (10, 16), (11, 11), (12, 12)]:
        c.set(x, y, GOLD[4])
    for (x, y) in [(16, 22), (16, 23), (22, 16), (23, 16), (20, 20), (21, 21)]:
        c.set(x, y, GOLD[2])
    c.sphere(16, 16, 2.8, ramp('7a2a12', 'b0501f', 'e8793c', 'ffb98a', 'ffffff'))
    for k in range(12):
        a = k * math.pi / 6
        x, y = 16 + 12.2 * math.cos(a), 16 + 12.2 * math.sin(a)
        c.set(int(x), int(y), GOLD[0] if k % 2 else GOLD[4])
    c.outline()
    return c


STONE = ramp('6e6648', 'a89f7e', 'c9c19f', 'e8e1c9', 'fffbe8')


def tower_mark():
    c = Canvas()
    # a bevelled stone seal: light along the top and left, shadow along the bottom and right
    c.poly([(4, 7), (7, 4), (25, 4), (28, 7), (28, 25), (25, 28), (7, 28), (4, 25)], STONE[2])
    c.poly([(5, 8), (8, 5), (24, 5), (27, 8), (27, 24), (24, 27), (8, 27), (5, 24)], STONE[3])
    for i in range(8, 25): c.set(i, 5, STONE[4])
    for i in range(8, 25): c.set(i, 27, STONE[1])
    for j in range(8, 25): c.set(5, j, STONE[4]); c.set(27, j, STONE[1])
    c.set(8, 6, STONE[4]); c.set(6, 8, STONE[4]); c.set(24, 26, STONE[0]); c.set(26, 24, STONE[0])
    # the tower, engraved: battlements, tower body, a window and a door, on a base
    ink = STONE[0]
    for (x0, x1) in [(10, 12), (14, 16), (18, 20), (22, 24)]:
        for x in range(x0, x1 + 1):
            for y in range(9, 12): c.set(x, y, ink)
    for x in range(10, 25):
        for y in range(12, 14): c.set(x, y, ink)
    for x in range(11, 24):
        for y in range(14, 24): c.set(x, y, ink)
    for x in range(14, 20):
        for y in range(16, 20): c.set(x, y, STONE[3])   # window
    c.set(14, 16, STONE[4]); c.set(15, 16, STONE[4])
    for x in range(14, 20):
        for y in range(20, 24): c.set(x, y, STONE[3])
    for x in range(14, 20): c.set(x, 20, STONE[4])    # door
    for x in range(9, 26):
        for y in range(24, 26): c.set(x, y, ink)
    # a worn, grainy stone
    speckle(c, 7, (6, 6, 26, 26), STONE[4], STONE[1], 26)
    c.outline()
    return c


def ascension_dex():
    c = Canvas()
    RED = ramp('5c1620', '8a1f2b', 'b72e3d', 'd23a46', 'ff9aa0')
    SCR = ramp('1a3e5c', '2a79a8', '56c1f4', '9ee0ff', 'eaf8ff')
    # body: a rounded handheld, lit from the top left
    c.poly([(4, 4), (28, 4), (29, 5), (29, 27), (28, 28), (4, 28), (3, 27), (3, 5)], RED[3])
    for x in range(4, 29): c.set(x, 5, RED[4]); c.set(x, 27, RED[1])
    for y in range(5, 28): c.set(4, y, RED[4]); c.set(28, y, RED[1])
    for x in range(4, 29): c.set(x, 28, RED[0])
    # the lens (top left): a ring and a glowing blue light
    c.disc(9, 9, 3.6, (60, 60, 75, 255))
    c.sphere(9, 9, 2.8, SCR)
    c.set(8, 8, SCR[4])
    # three lamps
    for x, col in ((16, 'f2cf3a'), (20, 'ff5a4a'), (24, '52c24a')):
        c.disc(x, 8, 1.6, rgb('2c2c3a')); c.disc(x, 8, 1.0, rgb(col))
    # the screen: dark frame, glass, a glare and a star
    c.poly([(7, 13), (25, 13), (25, 24), (7, 24)], rgb('1b2330'))
    for y in range(14, 23):
        for x in range(8, 25):
            t = 2 + (1 if y < 17 else 0) - (1 if y > 20 else 0)
            c.set(x, y, SCR[max(0, min(4, t))])
    c.poly([(8, 14), (13, 14), (9, 19), (8, 19)], SCR[4])
    c.sparkle(19, 17, SCR[4], arm=2)
    for x in range(8, 25): c.set(x, 22, SCR[1])
    # d-pad and two keys under the screen
    for (x, y) in [(10, 26), (9, 26), (11, 26)]: c.set(x, y, RED[0])
    c.set(10, 25, RED[0]); c.set(10, 27, RED[0])
    c.disc(20, 26, 1.1, rgb('2c2c3a')); c.disc(24, 26, 1.1, rgb('2c2c3a'))
    c.outline()
    return c


VIOLET = ramp('3a1f6b', '6a3fb0', '9b6bd9', 'c9a2ff', 'f3eaff')
GLASS = ramp('4a6a86', '7fa3c4', 'aecbe3', 'd8ebf7', 'ffffff')
BRASS = ramp('5a3c10', '8a5a14', 'b98a2c', 'e3b84f', 'fff0b0')


def rank_respec():
    c = Canvas()
    # The Hourglass of Undoing: brass caps, a glass body, and violet sand that rises: a full upper bulb, a thin stream
    # going up through the neck, and grains lifting off a nearly empty lower bulb.
    top = [(9, 6), (23, 6), (17.5, 15), (14.5, 15)]
    bottom = [(14.5, 17), (17.5, 17), (23, 26), (9, 26)]
    c.poly(top, GLASS[2]); c.poly(bottom, GLASS[2])
    c.poly([(10, 7), (14, 7), (14.5, 14), (13.5, 14)], GLASS[4])           # a lit strip down the left of the upper bulb
    # sand in the upper bulb (lit from the left, shaded to the right)
    for y in range(8, 15):
        half = 6.0 - (y - 6) * 0.65
        for x in range(int(16 - half), int(16 + half) + 1):
            tone = 3 if x < 14 else 2 if x < 18 else 1
            if y > 9 or (x > 12 and x < 20): c.set(x, y, VIOLET[tone])
    # the stream through the neck and the lower bulb: a little sand, and grains rising
    c.line(16, 15, 16, 17, VIOLET[3])
    for (x, y, t) in [(14, 24, 2), (15, 24, 3), (16, 24, 3), (17, 24, 2), (18, 24, 1), (15, 23, 3), (16, 23, 3), (17, 23, 2), (16, 22, 3)]:
        c.set(x, y, VIOLET[t])
    for (x, y, t) in [(12, 21, 3), (19, 20, 4), (14, 19, 4), (18, 18, 3)]:
        c.set(x, y, VIOLET[t])
    # brass caps and two posts
    for y in range(3, 6):
        for x in range(8, 25): c.set(x, y, BRASS[3 if y == 3 else 2 if y == 4 else 1])
    for y in range(27, 30):
        for x in range(8, 25): c.set(x, y, BRASS[3 if y == 27 else 2 if y == 28 else 1])
    for y in range(6, 27):
        c.set(8, y, BRASS[1]); c.set(24, y, BRASS[0])
    for x in range(9, 24): c.set(x, 3, BRASS[4]) if x < 13 else None
    c.sparkle(26, 7, VIOLET[3], arm=2, core=VIOLET[4])
    c.outline()
    return c


PRISM = ramp('7a5a14', 'b98a2c', 'e3b84f', 'ffe08a', 'fffbe0')
SPEC = [rgb('ff5a4a'), rgb('ff9a3c'), rgb('f2d83a'), rgb('52c24a'), rgb('4ab8f0'), rgb('9b6bd9')]


def unique_respec():
    c = Canvas()
    # The Unbinding Prism: a triangular prism cracked down the middle, the halves a pixel apart, a spectrum fanning out of the right side.
    left = [(14, 4), (14, 27), (4, 27)]
    right = [(16, 3), (28, 27), (16, 27)]
    c.poly([(14.5, 4), (14.5, 27), (4, 27)], PRISM[3])
    c.poly([(14.5, 4), (14.5, 27), (9, 27)], PRISM[4])
    c.poly([(5, 27), (9, 27), (14.5, 12)], PRISM[2])
    c.poly([(16.5, 3), (28, 27), (16.5, 27)], PRISM[1])
    c.poly([(16.5, 3), (22, 27), (16.5, 27)], PRISM[2])
    # the crack: a dark zigzag between the halves
    for (x, y) in [(15, 4), (15, 5), (16, 6), (15, 7), (15, 8), (16, 9), (16, 10), (15, 11), (15, 12), (16, 13), (16, 14), (15, 15), (15, 16),
                   (16, 17), (16, 18), (15, 19), (15, 20), (16, 21), (16, 22), (15, 23), (15, 24), (16, 25), (16, 26)]:
        c.set(x, y, PRISM[0])
    # a spectrum leaving the right face, one thin line per colour, fanning out
    for i, col in enumerate(SPEC):
        c.line(22, 18 + (i - 2.5) * 0.6, 30, 10 + i * 3, col)
    c.sparkle(7, 8, PRISM[3], arm=2, core=PRISM[4])
    c.set(13, 10, PRISM[4]); c.set(12, 14, PRISM[4]); c.set(11, 18, PRISM[4])
    c.outline()
    return c


PUMPKIN = ramp('5a2408', '9a4310', 'd8701c', 'ff9a3c', 'ffd08a')
WISP = ramp('3a1f6b', '6a3fb0', '9b6bd9', 'c9a2ff', 'f3eaff')
LEAF = ramp('1f3d12', '2f5a1c', '4a8a2c')


def spooky_essence():
    c = Canvas()
    # Spooky Essence: a small carved pumpkin lantern with a ghost-violet flame curling up out of its stem.
    c.sphere(16, 20, 10.5, PUMPKIN, ry=8.8)
    for x in (9, 12, 16, 20, 23):                                   # the ribs: darker vertical bands
        for y in range(14, 27):
            if c.get(x, y) is not None: c.set(x, y, PUMPKIN[1] if x < 16 else PUMPKIN[0])
    # the carved face: two triangle eyes and a jagged smile, glowing from within
    for (x, y) in [(11, 17), (10, 18), (11, 18), (12, 18), (20, 17), (19, 18), (20, 18), (21, 18)]:
        c.set(x, y, PUMPKIN[0])
    for (x, y) in [(11, 19), (20, 19)]: c.set(x, y, PUMPKIN[4])
    for (x, y) in [(11, 22), (12, 23), (13, 22), (14, 23), (15, 22), (16, 23), (17, 22), (18, 23), (19, 22), (20, 22)]:
        c.set(x, y, PUMPKIN[0]); c.set(x, y + 1, PUMPKIN[4]) if y == 23 else None
    # the stem
    for y in range(10, 13):
        for x in (15, 16, 17): c.set(x, y, LEAF[1] if x == 16 else LEAF[0])
    c.set(15, 10, LEAF[2]); c.set(17, 11, LEAF[0])
    # the ghost flame: a wavering violet ribbon rising from the stem, fading to white at the tip
    for (x, y, t) in [(16, 9, 1), (17, 8, 2), (17, 7, 2), (16, 6, 3), (16, 5, 3), (17, 4, 4), (18, 3, 4), (17, 3, 3), (16, 4, 2)]:
        c.set(x, y, WISP[t])
    c.set(15, 8, WISP[0]); c.set(18, 6, WISP[1])
    c.sparkle(24, 8, WISP[3], arm=2, core=WISP[4])
    c.set(7, 10, WISP[4]); c.set(26, 14, WISP[3])
    c.outline()
    return c


# Crowned Crest (balance phase 1): three teal crystals in a gold crown with a red gem. Drawn as a palette and a pixel map rather than
# primitives because it is the owner-approved concept art pixel for pixel; 'O' is the shared outline.
APEX_PALETTE = {'a': '9ff0e0', 'b': '0f4a50', 'c': 'f0fffb', 'd': '3cc2b2', 'e': '208a86', 'f': 'fff0b0', 'g': 'f2c765', 'h': 'b07a1e', 'i': 'ff6a7a', 'j': 'd6334a', 'k': 'ffd0d6', 'l': '7a1424', 'm': 'd9a441', 'n': '7a4f12'}
APEX_PIXELS = [
    '............................O...',
    '................O..........OaO..',
    '...............ObO........OOcOO.',
    '..............OcdbO......OacccaO',
    '..............OcdbO.......OOcOO.',
    '.............OdcdebO.......OaO..',
    '............OdacdeebO.......O...',
    '............OdacdeebO...........',
    '.......O...OdaacdeeebO...O......',
    '......ObO..OdaacdeeebO..ObO.....',
    '......OdbO.OdaacdeeebO.OcbO.....',
    '.....OcdebOOdaacdeeebOOcddbO....',
    '.....OcdebOOdaacdeeebOOcddbO....',
    '....OdcddebOdaacdeeebOdcddebO...',
    '....OdcddeebdaacOeeebdacdeebO...',
    '...OdaOddeebdaaOfOeebdacdeOebO..',
    '...OdOfOdeebdaOfffOebdacdOfObO..',
    '...OOfffOeebdaOfffOebdacOfffOO..',
    '...OOgfgOeebdOggfggObdacOgfhOO..',
    '...OggfggOOOOOggfggOOOOOggfhhO..',
    '..OgggfgggggggggfgggggggggfhhhO.',
    '..OffffffffffffOOfffffffffffhfO.',
    '..OgggggggggggOijOggggggggghhhO.',
    '..OggggggggggOikijOgggggggghhhO.',
    '..OgggaeggggOikijjlOggggggaehhO.',
    '..OmmmmmmmmmmOjjjlOmmmmmmmmhhhO.',
    '..OmmmmmmmmmmmOllOmmmmmmmmmhhhO.',
    '..OnnnnnnnnnnnnOOnnnnnnnnnnnnnO.',
    '..OhhhhhhhhhhhhhhhhhhhhhhhhhhhO.',
    '...OOOOOOOOOOOOOOOOOOOOOOOOOOO..',
    '................................',
    '................................',
]


def apex_shard():
    c = Canvas()
    for y, row in enumerate(APEX_PIXELS):
        for x, ch in enumerate(row):
            if ch != '.':
                c.set(x, y, OUTLINE if ch == 'O' else rgb(APEX_PALETTE[ch]))
    return c


PAPER = ramp('8f8668', 'c4bb9b', 'e8e1c9', 'f6f1df', 'ffffff')
WAX = ramp('5e1018', '8a1f2b', 'b72e3d', 'd23a46', 'ff9aa0')


def voucher(name):
    """A tradable item's voucher: its icon with a folded paper tag and a red seal, so a voucher is never mistaken for loot that joins the wallet."""
    c = ICONS[name]()
    # clear a little room so the tag sits in front of the icon with its own dark edge
    c.poly([(16, 20), (31, 20), (31, 31), (16, 31)], PAPER[2])
    for x in range(16, 31): c.set(x, 20, PAPER[4]); c.set(x, 30, PAPER[0])
    for y in range(20, 31): c.set(16, y, PAPER[3]); c.set(30, y, PAPER[0])
    c.poly([(26, 20), (31, 20), (31, 25)], PAPER[1])              # a folded corner
    for x in (18, 19, 20, 21, 22, 23): c.set(x, 23, PAPER[0])      # ruled lines
    for x in (18, 19, 20, 21): c.set(x, 26, PAPER[0])
    c.sphere(25, 26, 3.6, WAX, light=(-0.5, -0.6))                   # the wax seal
    c.set(24, 25, WAX[4])
    # a dark edge round the tag
    for x in range(15, 32):
        c.set(x, 19, OUTLINE); c.set(x, 31, OUTLINE)
    for y in range(19, 32):
        c.set(15, y, OUTLINE); c.set(31, y, OUTLINE) if y > 25 else None
    c.set(31, 25, OUTLINE)
    c.outline()
    return c


def render_voucher(name):
    return voucher(name).rows()


ICONS = {
    'resonance_dust': resonance_dust,
    'facet': facet,
    'ascension_core': ascension_core,
    'unique_fragment': unique_fragment,
    'unique_catalyst': unique_catalyst,
    'scouter': scouter,
    'ascension_sigil': ascension_sigil,
    'tower_mark': tower_mark,
    'ascension_dex': ascension_dex,
    'rank_respec': rank_respec,
    'unique_respec': unique_respec,
    'spooky_essence': spooky_essence,
    'apex_shard': apex_shard,
}


VOUCHER_OFFSET = 1000   # a voucher's custom model number is its loot-form number plus this (CurrencyItems.VOUCHER_MODEL_OFFSET)


def render(name):
    return ICONS[name]().rows()


def mod_icon(scale=4):
    """128x128: the Ascension Sigil enlarged on the dark tablet colour with a one-pixel bezel, for fabric.mod.json."""
    sigil = render('ascension_sigil')
    size = SIZE * scale
    back = (0x07, 0x13, 0x1c, 255)
    bezel = (0x1f, 0x6b, 0x86, 255)
    out = []
    for y in range(size):
        row = []
        for x in range(size):
            edge = x < 4 or y < 4 or x >= size - 4 or y >= size - 4
            pixel = sigil[y // scale][x // scale]
            row.append(bezel if edge else (pixel if pixel[3] else back))
        out.append(row)
    return out


def png_bytes(pixels):
    """A PNG of an RGBA grid, with no libraries."""
    h, w = len(pixels), len(pixels[0])
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b''))


if __name__ == '__main__':
    scale = 8
    names = list(ICONS)
    sheet = []
    for y in range(SIZE * scale):
        row = []
        for n in names:
            grid = render(n)
            for x in range(SIZE * scale):
                p = grid[y // scale][x // scale]
                row.append(p if p[3] else (40, 70, 45, 255))
        sheet.append(row)
    open(sys.argv[1] if len(sys.argv) > 1 else 'icons-preview.png', 'wb').write(png_bytes(sheet))
