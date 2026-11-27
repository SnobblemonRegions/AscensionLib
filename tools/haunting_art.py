"""The ten Haunting icons (Halloween 2026): one 32x32 crest per Haunting.

Drawn at 4x (128x128) from smooth shapes (signed distance functions) that are lit like little reliefs, then reduced to 32x32 by taking the most common
palette colour in each 4x4 block, so the edges are clean and every pixel is a palette colour. Same toolkit as the item art (tools/pixel_art.py) for the
canvas, the outline and the PNG writer. A Common Haunting sits on a violet medallion; a Rare one on a dark one ringed in pumpkin gold with four sparks.
Writes art/icons/haunting/<id>.png (ids are Hauntings.java's).
usage: python -I tools/haunting_art.py            (writes the icons)
       python -I tools/haunting_art.py preview.png (also writes a contact sheet, 8x)
"""

import importlib.util
import math
import os
import sys
from collections import Counter

_spec = importlib.util.spec_from_file_location('pixel_art', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'pixel_art.py'))
pa = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pa)   # by path: this runs isolated (python -I), which does not put its own folder on the import path

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
OUT = os.path.join(ROOT, 'art', 'icons', 'haunting')

N = 128                 # working size: 4x the icon
SS = N // pa.SIZE
LIGHT = (-0.55, -0.65, 0.55)

VIOLET = pa.ramp('1d0f3a', '2e1a5c', '4a2a8a', '6a44b8', '9b7be0')
NIGHT = pa.ramp('0b0716', '150d2a', '22163e', '33235a', '4b3a82')
GOLD = pa.ramp('6a2f06', 'a85a0e', 'e8851a', 'ffb73a', 'ffe49a')
BONE = pa.ramp('6f7aa0', '9aa6c8', 'c4cde6', 'e6ebf8', 'ffffff')
PUMPKIN = pa.ramp('4a1c06', '8a3a0e', 'd8701c', 'ff9a3c', 'ffd08a')
GLOW = pa.ramp('c8560a', 'f08a1c', 'ffb83a', 'ffd96a', 'fff2b8')      # a lit face: light from inside
LEAF = pa.ramp('1a3a0e', '2f5a1c', '4a8a2c', '6ab03c', '9ad860')
WISP = pa.ramp('3a1f6b', '6a3fb0', '9b6bd9', 'c9a2ff', 'f3eaff')
STONE = pa.ramp('3a3f5a', '5a6285', '7f89ae', 'a6b0d2', 'ccd5ee')
WOOD = pa.ramp('2a160a', '4a2a12', '6e421e', '95622f', 'b8844a')
CLOAK = pa.ramp('0e0a1c', '1c1236', '2e1f58', '45308a', '6a4cc0')
STEEL = pa.ramp('4a5478', '7886b0', 'b0bde0', 'dce4fa', 'ffffff')
RED = pa.ramp('4a0a14', '86141f', 'c4222c', 'ee4a52', 'ff9aa0')


# ---- shapes: each returns d(x, y), negative inside -----------------------------------------------------------------------------------------

def circle(cx, cy, r): return lambda x, y: math.hypot(x - cx, y - cy) - r
def ellipse(cx, cy, rx, ry): return lambda x, y: (math.hypot((x - cx) / rx, (y - cy) / ry) - 1) * min(rx, ry)


def box(x0, y0, x1, y1):
    cx, cy, hx, hy = (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2
    def d(x, y):
        dx, dy = abs(x - cx) - hx, abs(y - cy) - hy
        return math.hypot(max(dx, 0), max(dy, 0)) + min(max(dx, dy), 0)
    return d


def capsule(x1, y1, x2, y2, r):
    vx, vy = x2 - x1, y2 - y1
    ll = vx * vx + vy * vy
    def d(x, y):
        t = max(0.0, min(1.0, ((x - x1) * vx + (y - y1) * vy) / ll)) if ll else 0.0
        return math.hypot(x - (x1 + vx * t), y - (y1 + vy * t)) - r
    return d


def polygon(pts):
    n = len(pts)
    def d(x, y):
        best = 1e9
        inside = False
        for i in range(n):
            ax, ay = pts[i]
            bx, by = pts[(i + 1) % n]
            ex, ey, wx, wy = bx - ax, by - ay, x - ax, y - ay
            t = max(0.0, min(1.0, (wx * ex + wy * ey) / (ex * ex + ey * ey)))
            best = min(best, math.hypot(wx - ex * t, wy - ey * t))
            if (ay <= y < by or by <= y < ay) and ax + (y - ay) * (bx - ax) / (by - ay) > x:
                inside = not inside
        return -best if inside else best
    return d


def union(*fs): return lambda x, y: min(f(x, y) for f in fs)
def inter(a, b): return lambda x, y: max(a(x, y), b(x, y))
def sub(a, b): return lambda x, y: max(a(x, y), -b(x, y))
def outline(f, t): return lambda x, y: abs(f(x, y)) - t


def smooth_union(a, b, k):
    def d(x, y):
        da, db = a(x, y), b(x, y)
        h = max(k - abs(da - db), 0) / k
        return min(da, db) - h * h * k * 0.25
    return d


def ngon_pts(cx, cy, r, n, rot=-90):
    return [(cx + r * math.cos(math.radians(rot + 360 * i / n)), cy + r * math.sin(math.radians(rot + 360 * i / n))) for i in range(n)]


# ---- the canvas -----------------------------------------------------------------------------------------------------------------------

class Art:
    def __init__(self):
        self.px = [[None] * N for _ in range(N)]

    def layer(self, shape, ramp, depth=14.0, flat=None, inset=False, fn=None, box_hint=None):
        """Fills `shape` with the ramp, lit as a relief `depth` (working pixels) deep; `flat` is a single ramp index (no lighting); `fn(x, y, tone, color)`
        may return another colour for a pixel (stripes, a spot); `inset` lights it as a dent instead of a bump."""
        x0, y0, x1, y1 = box_hint or (0, 0, N, N)
        top = len(ramp) - 1
        for y in range(max(0, y0), min(N, y1)):
            row = self.px[y]
            for x in range(max(0, x0), min(N, x1)):
                px, py = x + 0.5, y + 0.5
                d = shape(px, py)
                if d > 0:
                    continue
                if flat is not None:
                    tone = flat
                else:
                    gx = shape(px + 1, py) - shape(px - 1, py)
                    gy = shape(px, py + 1) - shape(px, py - 1)
                    g = math.hypot(gx, gy) or 1.0
                    h = min(1.0, -d / depth)
                    nx, ny = gx / g * (1 - h), gy / g * (1 - h)
                    nz = h * 0.9 + 0.1
                    ln = math.sqrt(nx * nx + ny * ny + nz * nz)
                    lam = (nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / ln
                    if inset:
                        lam = 1 - lam
                    tone = max(0, min(top, int(lam * (top + 1) * 0.95 + 0.25)))
                color = ramp[tone]
                if fn is not None:
                    color = fn(px, py, tone, color) or color
                row[x] = color

    def reduce(self):
        """32x32: the most common colour in each block when at least half of it is drawn, else clear."""
        out = pa.Canvas()
        for y in range(pa.SIZE):
            for x in range(pa.SIZE):
                votes = Counter()
                for yy in range(y * SS, y * SS + SS):
                    for xx in range(x * SS, x * SS + SS):
                        c = self.px[yy][xx]
                        if c is not None:
                            votes[c] += 1
                if sum(votes.values()) * 2 >= SS * SS:
                    out.px[y][x] = votes.most_common(1)[0][0]
        return out


def sparkle(a, cx, cy, size, ramp):
    """A four-pointed star."""
    pts = [(cx, cy - size), (cx + size * .22, cy - size * .22), (cx + size, cy), (cx + size * .22, cy + size * .22),
           (cx, cy + size), (cx - size * .22, cy + size * .22), (cx - size, cy), (cx - size * .22, cy - size * .22)]
    a.layer(polygon(pts), ramp, flat=4, box_hint=(int(cx - size) - 1, int(cy - size) - 1, int(cx + size) + 2, int(cy + size) + 2))


def medallion(rare):
    a = Art()
    rim = GOLD if rare else VIOLET
    a.layer(circle(64, 64, 60), rim, depth=7)                                   # the bevelled rim
    a.layer(circle(64, 64, 52), NIGHT if rare else VIOLET, depth=16, inset=True)   # the dished face
    if rare:
        a.layer(outline(circle(64, 64, 47), 1.6), GOLD, flat=1)
        for x, y in ((11, 11), (117, 11), (11, 117), (117, 117)):
            sparkle(a, x, y, 8, GOLD)
    return a


# ---- the glyphs (drawn on the 128 grid, 64,64 is the middle) -------------------------------------------------------------------------------

def eyes(a, y=70, gap=13, h=8):
    for sx in (-1, 1):
        a.layer(ellipse(64 + sx * gap, y, 5.5, h), NIGHT, flat=0, box_hint=(40, y - 12, 90, y + 12))


def glyph_wisp(a):
    body = smooth_union(circle(62, 80, 31), circle(58, 22, 6), 46)
    tail = smooth_union(body, capsule(84, 94, 108, 112, 6), 16)
    a.layer(tail, BONE, depth=26, box_hint=(24, 10, 118, 120))
    a.layer(smooth_union(circle(66, 74, 14), circle(70, 50, 3), 30), BONE, depth=12, flat=4, box_hint=(44, 36, 92, 96))
    eyes(a, 82, 13, 8)
    a.layer(ellipse(62, 100, 5, 6.5), VIOLET, flat=0, box_hint=(50, 90, 76, 112))


def glyph_hex(a):
    t1, t2 = polygon(ngon_pts(64, 66, 44, 3)), polygon(ngon_pts(64, 66, 44, 3, 90))
    a.layer(outline(circle(64, 66, 46), 2.6), BONE, depth=3)
    a.layer(outline(t1, 3.4), BONE, depth=4)
    a.layer(outline(t2, 3.4), STEEL, depth=4)
    a.layer(circle(64, 66, 12), GOLD, depth=12)
    a.layer(ellipse(64, 66, 3.4, 8), NIGHT, flat=0)
    for x, y in ((64, 14), (64, 118), (14, 38), (114, 38), (14, 94), (114, 94)):
        a.layer(circle(x, y, 3), GOLD, flat=3)


def glyph_veil(a):
    wave = lambda x, y: (y - (96 + 7 * math.cos((x - 64) / 17.0 * math.pi))) * 0.9
    sheet = inter(union(circle(64, 56, 32), box(32, 56, 96, 104)), wave)
    a.layer(sheet, BONE, depth=26, box_hint=(24, 18, 104, 112))
    a.layer(sub(circle(40, 76, 9), box(0, 0, 40, 128)), BONE, depth=6, box_hint=(24, 60, 52, 92))        # a raised arm
    for fx in (46, 64, 82):
        a.layer(capsule(fx, 70, fx - 2, 96, 1.5), BONE, flat=1, box_hint=(fx - 8, 64, fx + 8, 104))        # folds
    eyes(a, 56, 12, 8)
    a.layer(ellipse(64, 72, 5, 6.5), VIOLET, flat=0, box_hint=(54, 62, 76, 84))
    for x, y, s in ((24, 30, 7), (104, 38, 6), (100, 100, 5)):
        sparkle(a, x, y, s, BONE)


def dim(x, y, tone, col): return PUMPKIN[max(0, tone - 1)]      # a darker pumpkin: the lit face then stands out


def face_glow(a, cx, cy, k=1.0):
    for sx in (-1, 1):
        a.layer(polygon([(cx + sx * 15 * k, cy - 13 * k), (cx + sx * 25 * k, cy + 3 * k), (cx + sx * 6 * k, cy + 3 * k)]), GLOW, flat=4,
                box_hint=(int(cx - 30 * k), int(cy - 18 * k), int(cx + 30 * k), int(cy + 8 * k)))
    zig = [(cx - 26, cy + 14), (cx - 16, cy + 24), (cx - 8, cy + 15), (cx, cy + 25), (cx + 8, cy + 15), (cx + 16, cy + 24), (cx + 26, cy + 14), (cx + 18, cy + 12),
           (cx + 8, cy + 18), (cx, cy + 12), (cx - 8, cy + 18), (cx - 18, cy + 12)]
    zig = [(cx + (x - cx) * k, cy + (y - cy) * k) for x, y in zig]
    a.layer(polygon(zig), GLOW, flat=4, box_hint=(int(cx - 30 * k), int(cy + 8 * k), int(cx + 30 * k), int(cy + 30 * k)))


def glyph_pumpkin(a):
    for cx, cy, rx, ry in ((40, 82, 21, 29), (88, 82, 21, 29)):
        a.layer(ellipse(cx, cy, rx, ry), PUMPKIN, depth=18, fn=dim, box_hint=(10, 40, 118, 120))
    for cx, cy, rx, ry in ((52, 82, 24, 32), (76, 82, 24, 32)):
        a.layer(ellipse(cx, cy, rx, ry), PUMPKIN, depth=20, fn=dim, box_hint=(10, 40, 118, 120))
    a.layer(ellipse(64, 82, 17, 33), PUMPKIN, depth=20, fn=dim, box_hint=(36, 40, 92, 120))
    face_glow(a, 64, 76)
    a.layer(polygon([(58, 44), (70, 44), (72, 54), (56, 54)]), LEAF, depth=8, box_hint=(50, 36, 80, 60))
    a.layer(capsule(66, 44, 78, 34, 3), LEAF, depth=4, box_hint=(56, 26, 90, 54))
    sparkle(a, 100, 34, 9, BONE)
    sparkle(a, 24, 40, 6, WISP)


def glyph_candy(a):
    stripes = lambda x, y, tone, col: (BONE[min(4, tone + 1)] if int((x + y) / 11) % 2 == 0 else None)
    a.layer(ellipse(64, 66, 27, 20), RED, depth=18, fn=stripes, box_hint=(30, 40, 98, 92))
    for sx in (-1, 1):
        a.layer(polygon([(64 + sx * 24, 66), (64 + sx * 50, 40), (64 + sx * 56, 46), (64 + sx * 46, 66), (64 + sx * 56, 86), (64 + sx * 50, 92)]), GOLD, depth=10,
                box_hint=(4, 30, 124, 100))
        a.layer(capsule(64 + sx * 28, 66, 64 + sx * 50, 66, 1.2), GOLD, flat=1, box_hint=(4, 56, 124, 76))
    for x, y, s in ((30, 24, 7), (100, 100, 6), (104, 26, 5)):
        sparkle(a, x, y, s, BONE)


def glyph_cobweb(a):
    strand = lambda ang: capsule(64, 64, 64 + 54 * math.cos(ang), 64 + 54 * math.sin(ang), 2.3)
    for k in range(8):
        a.layer(strand(k * math.pi / 4), BONE, flat=3)

    def rings(radius):
        def d(x, y):
            dx, dy = x - 64, y - 64
            r = math.hypot(dx, dy)
            sag = 0.5 + 0.5 * math.cos(8 * math.atan2(dy, dx))
            return abs(r - (radius - 8 * sag)) - 2.0
        return d
    for radius in (18, 34, 50):
        a.layer(rings(radius), BONE, flat=2)
    a.layer(capsule(98, 84, 98, 62, 0.9), STEEL, flat=2)
    a.layer(circle(98, 96, 7), NIGHT, depth=6, box_hint=(84, 82, 112, 112))
    a.layer(circle(98, 88, 4.2), NIGHT, depth=4, box_hint=(88, 80, 108, 96))
    for sx in (-1, 1):
        for dy, rx in ((-6, 14), (-1, 16), (5, 15), (11, 12)):
            a.layer(capsule(98 + sx * 4, 94, 98 + sx * rx, 94 + dy, 1.1), NIGHT, flat=3, box_hint=(80, 80, 120, 112))
    a.layer(circle(96, 87, 1.1), RED, flat=3)
    a.layer(circle(100, 87, 1.1), RED, flat=3)


def glyph_grave(a):
    stone = union(circle(64, 58, 28), box(36, 58, 92, 106))
    a.layer(ellipse(64, 108, 44, 8), LEAF, depth=6, box_hint=(14, 96, 114, 124))
    a.layer(stone, STONE, depth=18, box_hint=(28, 24, 100, 112))
    a.layer(box(61, 40, 67, 74), NIGHT, flat=1, box_hint=(54, 34, 74, 80))
    a.layer(box(50, 50, 78, 56), NIGHT, flat=1, box_hint=(44, 44, 84, 62))
    for x1, y1, x2, y2 in ((44, 82, 54, 92), (84, 96, 76, 84)):
        a.layer(capsule(x1, y1, x2, y2, 1.3), NIGHT, flat=1, box_hint=(36, 74, 96, 104))      # cracks
    for x in (30, 40, 90, 100):
        a.layer(polygon([(x - 4, 108), (x, 94 - (x % 3) * 2), (x + 4, 108)]), LEAF, depth=4, box_hint=(20, 84, 110, 112))
    for x, y, s in ((22, 28, 7), (106, 26, 8), (108, 70, 5), (18, 66, 5)):
        sparkle(a, x, y, s, STEEL)


def glyph_howl(a):
    a.layer(circle(58, 46, 38), BONE, depth=18, box_hint=(16, 2, 108, 96))
    for cx, cy, r in ((42, 34, 6), (74, 60, 5.5), (54, 62, 3.5), (80, 28, 3.5)):
        a.layer(circle(cx, cy, r), BONE, flat=1, box_hint=(30, 16, 100, 76))
    a.layer(ellipse(64, 134, 72, 26), NIGHT, depth=14, box_hint=(0, 104, 128, 128))
    wolf = [(18, 90), (26, 98), (36, 100), (46, 96), (58, 92), (66, 82), (70, 68), (70, 56), (68, 46), (74, 50), (78, 38), (82, 50), (88, 46), (96, 34), (104, 22),
            (110, 24), (108, 34), (100, 44), (106, 46), (96, 54), (86, 62), (84, 74), (88, 88), (86, 106), (82, 118), (70, 118), (70, 104), (58, 106), (46, 108),
            (42, 120), (30, 120), (32, 104), (24, 98)]
    wolf = [(88 + (x - 88) * 1.12 - 16, 56 + (y - 56) * 1.12 + 2) for x, y in wolf]
    a.layer(polygon(wolf), NIGHT, depth=12, box_hint=(0, 0, 128, 128))
    a.layer(circle(80, 46, 2.2), GOLD, flat=3)
    for x, y, s in ((14, 24, 7), (114, 84, 6), (112, 12, 5)):
        sparkle(a, x, y, s, BONE)


def glyph_rider(a):
    cloak = [(10, 122), (14, 92), (30, 72), (48, 66), (80, 66), (98, 72), (114, 92), (118, 122), (104, 112), (92, 124), (78, 112), (64, 124), (50, 112), (36, 124), (22, 112)]
    a.layer(polygon(cloak), CLOAK, depth=22, box_hint=(4, 56, 124, 128))
    a.layer(polygon([(46, 66), (64, 92), (82, 66), (74, 62), (54, 62)]), VIOLET, depth=10, box_hint=(40, 56, 90, 98))
    a.layer(circle(64, 86, 6), GOLD, depth=6, box_hint=(54, 76, 74, 96))
    for sx in (-1, 1):                                                      # ghost fire licking up beside the head
        a.layer(smooth_union(circle(64 + sx * 34, 36, 7), circle(64 + sx * 42, 14, 2), 30), WISP, depth=8, box_hint=(10, 6, 118, 52))
    a.layer(ellipse(64, 38, 33, 29), PUMPKIN, depth=22, fn=dim, box_hint=(24, 4, 104, 72))
    a.layer(ellipse(64, 38, 15, 29), PUMPKIN, depth=14, fn=dim, box_hint=(44, 6, 84, 70))
    face_glow(a, 64, 34, 1.15)
    a.layer(capsule(90, 98, 112, 56, 2.6), STEEL, depth=3, box_hint=(80, 46, 124, 108))      # the sword it carries
    a.layer(capsule(86, 92, 98, 98, 2.4), GOLD, depth=3, box_hint=(78, 84, 108, 106))


def glyph_reaper(a):
    k = 0.7                                                  # the whole scythe is drawn smaller so it sits inside the medallion, nothing touching the rim
    def T(x, y): return (64 + (x - 64) * k, 66 + (y - 64) * k)
    def hint(x0, y0, x1, y1): return (int(T(x0, y0)[0]), int(T(x0, y0)[1]), int(T(x1, y1)[0]) + 1, int(T(x1, y1)[1]) + 1)
    h0, h1 = T(84, 122), T(80, 16)
    a.layer(capsule(h0[0], h0[1], h1[0], h1[1], 6.5 * k), WOOD, depth=6, box_hint=hint(60, 4, 104, 128))
    blade = [(84, 10), (60, 3), (34, 9), (14, 27), (5, 50), (7, 70), (14, 86), (24, 62), (30, 44), (44, 34), (62, 32), (84, 40)]
    a.layer(polygon([T(x, y) for x, y in blade]), STEEL, depth=11, box_hint=hint(0, 0, 100, 92))
    c = T(82, 26)
    a.layer(circle(c[0], c[1], 8 * k), GOLD, depth=6, box_hint=hint(68, 12, 98, 42))
    a.layer(polygon([T(22, 90), T(46, 90), T(52, 112), T(16, 112)]), GOLD, depth=9, box_hint=hint(8, 82, 60, 118))    # the bell that tolls
    c = T(34, 117)
    a.layer(circle(c[0], c[1], 5 * k), GOLD, depth=4, box_hint=hint(24, 108, 44, 128))
    t0, t1 = T(34, 90), T(34, 78)
    a.layer(capsule(t0[0], t0[1], t1[0], t1[1], 2 * k), STEEL, flat=2, box_hint=hint(26, 70, 44, 96))
    for x, y, s in ((100, 100, 7), (24, 100, 5)):
        sparkle(a, x, y, s, GOLD)


GLYPHS = {
    'wisp': (False, glyph_wisp), 'hex': (False, glyph_hex), 'spectral_veil': (False, glyph_veil), 'pumpkin_lantern': (False, glyph_pumpkin),
    'trick_or_treat': (False, glyph_candy), 'cobweb': (False, glyph_cobweb), 'graveyard_chill': (False, glyph_grave), 'midnight_howl': (False, glyph_howl),
    'headless_rider': (True, glyph_rider), 'reapers_toll': (True, glyph_reaper),
}


def render(name):
    rare, glyph = GLYPHS[name]
    a = medallion(rare)
    glyph(a)
    for y in range(N):                                   # nothing may spill past the medallion's rim
        for x in range(N):
            if math.hypot(x + 0.5 - 64, y + 0.5 - 64) > 60:
                a.px[y][x] = None
    c = a.reduce()
    c.outline()
    return c.rows()


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    for name in GLYPHS:
        with open(os.path.join(OUT, name + '.png'), 'wb') as f:
            f.write(pa.png_bytes(render(name)))
    print('wrote', len(GLYPHS), 'icons to', os.path.normpath(OUT))
    if len(sys.argv) > 1:
        scale, names = 8, list(GLYPHS)
        sheet = []
        grids = {n: render(n) for n in names}
        for y in range(pa.SIZE * scale):
            row = []
            for n in names:
                for x in range(pa.SIZE * scale):
                    p = grids[n][y // scale][x // scale]
                    row.append(p if p[3] else (13, 34, 48, 255))
            sheet.append(row)
        with open(sys.argv[1], 'wb') as f:
            f.write(pa.png_bytes(sheet))
