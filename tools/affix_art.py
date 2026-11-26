"""The modifier (affix) icons: one 32x32 crest per modifier, drawn in code with the same toolkit as the item art (tools/pixel_art.py).

A crest is a plate filled with the modifier's theme colour with a pale glyph on it. The plate's SHAPE says the slot: a hexagon is a prefix (offense), a
shield is a suffix (defense and recovery). The theme colour says what it is about (type violet, power red, guard blue, recovery green, fire orange, venom
purple, bleed crimson, tempo yellow, tactic teal). A Unique power gets an eight-sided purple plate. The four type-bound modifiers (Type Focus, Mastery, Ward, Bulwark) also have one crest per Pokemon type, in that
type's colour, named <id>_<type>; the plain violet one is the fallback.

Used by tools/build_resourcepack.py, which writes icons/affix/<id>.png for the web pages. usage (preview): python -I tools/affix_art.py out.png
"""

import json
import math
import os
import sys

import importlib.util

_spec = importlib.util.spec_from_file_location('pixel_art', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'pixel_art.py'))
pa = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pa)   # by path: this runs isolated (python -I), which does not put its own folder on the import path

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
X = (0x14, 0x0e, 0x0b, 255)   # dark detail inside a glyph (eyes, cracks)


def hexc(h):
    return pa.rgb(h)


def mix(c, f):
    """Darken (f<1) or lighten (f>1) a colour."""
    if f <= 1:
        return (int(c[0] * f), int(c[1] * f), int(c[2] * f), 255)
    t = f - 1
    return (int(c[0] + (255 - c[0]) * t), int(c[1] + (255 - c[1]) * t), int(c[2] + (255 - c[2]) * t), 255)


class G:
    """Draws in a 20x20 local box, scaled and moved onto a canvas."""

    def __init__(self, c, ox, oy, sc):
        self.c, self.ox, self.oy, self.sc = c, ox, oy, sc

    def p(self, x, y):
        return (self.ox + x * self.sc, self.oy + y * self.sc)

    def poly(self, pts, col):
        self.c.poly([self.p(x, y) for x, y in pts], col)

    def disc(self, cx, cy, r, col):
        x, y = self.p(cx, cy)
        self.c.disc(x, y, max(0.9, r * self.sc), col)

    def ring(self, cx, cy, r, t, col, only=None):
        x, y = self.p(cx, cy)
        self.c.ring(x, y, r * self.sc, max(1.0, t * self.sc), col, only=only)

    def line(self, x1, y1, x2, y2, col, w=1.6):
        """A line w local units wide (a thin polygon, so it scales)."""
        dx, dy = x2 - x1, y2 - y1
        n = math.hypot(dx, dy) or 1
        nx, ny = -dy / n * w / 2, dx / n * w / 2
        self.poly([(x1 + nx, y1 + ny), (x2 + nx, y2 + ny), (x2 - nx, y2 - ny), (x1 - nx, y1 - ny)], col)

    def sub(self, lx, ly, sc):
        """A smaller glyph box inside this one."""
        return G(self.c, self.ox + lx * self.sc, self.oy + ly * self.sc, self.sc * sc)


# ---- glyphs: each draws with pal['k'] (main) and pal['a'] (accent) and X for dark detail ---------------------------------------------------

def g_sword(g, k, a):
    g.poly([(10, 0), (12.2, 2.5), (12.2, 13), (7.8, 13), (7.8, 2.5)], k)
    g.line(10, 2, 10, 12, a, 0.9)
    g.poly([(4, 13), (16, 13), (16, 15), (4, 15)], a)
    g.poly([(8.8, 15), (11.2, 15), (11.2, 18.5), (8.8, 18.5)], k)
    g.disc(10, 19, 1.4, a)


def g_burst(g, k, a):
    pts = []
    for i in range(16):
        r = 9.5 if i % 2 == 0 else 4.2
        ang = math.pi * i / 8 - math.pi / 2
        pts.append((10 + r * math.cos(ang), 10 + r * math.sin(ang)))
    g.poly(pts, k)
    g.disc(10, 10, 3, a)


def g_orb(g, k, a):
    g.disc(10, 10, 8.6, k)
    g.disc(11.2, 11.4, 5.6, mix(k, 0.78))
    g.disc(7.4, 7.2, 2.6, a)
    g.disc(6.6, 6.4, 1.1, mix(a, 1.4))


def g_heart(g, k, a):
    g.disc(6.3, 7, 4.6, k)
    g.disc(13.7, 7, 4.6, k)
    g.poly([(1.8, 8.6), (18.2, 8.6), (10, 18.5)], k)
    g.disc(5.5, 5.8, 1.4, a)


def g_heart_crack(g, k, a):
    g_heart(g, k, a)
    g.poly([(10, 2.5), (8.4, 7), (11.2, 9.5), (9, 13), (10.4, 13), (12.4, 9.4), (9.6, 7), (11.4, 2.5)], X)


def g_spiral(g, k, a):
    pts = []
    for i in range(0, 61):
        t = i / 60
        r = 1 + 8 * t
        ang = t * 4.2 * math.pi
        pts.append((10 + r * math.cos(ang), 10 + r * math.sin(ang)))
    for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
        g.line(x1, y1, x2, y2, k, 2.4)
    g.disc(10, 10, 1.8, a)


def g_shield_shape(g, k, a):
    g.poly([(2.5, 1.5), (17.5, 1.5), (17.5, 10), (10, 19.5), (2.5, 10)], k)
    g.poly([(5, 3.5), (15, 3.5), (15, 9.5), (10, 16), (5, 9.5)], a)


def g_shield(g, k, a):
    g_shield_shape(g, k, mix(a, 0.55))


def g_wheel(g, k, a):
    g.ring(10, 10, 9, 2.2, k)
    for i in range(6):
        ang = math.pi * i / 3 - math.pi / 2
        g.disc(10 + 9 * math.cos(ang), 10 + 9 * math.sin(ang), 1.9, a)
    g.disc(10, 10, 3, k)


def g_wheel_focus(g, k, a):
    g_wheel(g, k, a)
    for dx, dy in ((0, -1), (0, 1), (1, 0), (-1, 0)):
        g.line(10 + dx * 3.5, 10 + dy * 3.5, 10 + dx * 7, 10 + dy * 7, X, 1.1)


def g_wheel_master(g, k, a):
    g_wheel(g, k, a)
    pts = []
    for i in range(10):
        r = 4.4 if i % 2 == 0 else 1.8
        ang = math.pi * i / 5 - math.pi / 2
        pts.append((10 + r * math.cos(ang), 10 + r * math.sin(ang)))
    g.poly(pts, a)


def g_bolt(g, k, a):
    g.poly([(12.5, 0), (3.5, 11), (8.8, 11), (6.5, 20), (16.5, 8), (10.8, 8)], k)
    g.poly([(11.8, 2.4), (6.8, 9.2), (9.6, 9.2), (9.2, 12)], a)


def g_drop(g, k, a):
    g.poly([(10, 0.5), (4.2, 10.5), (15.8, 10.5)], k)
    g.disc(10, 13, 6.2, k)
    g.disc(7.8, 11.4, 1.6, a)


def g_flame(g, k, a):
    g.poly([(10, 0), (15, 6.5), (17.5, 12.5), (14.5, 19), (5.5, 19), (2.5, 12.5), (5.5, 7.5), (8, 10), (8.2, 3.8)], k)
    g.poly([(10, 8), (13.5, 13), (12.5, 17.5), (7.5, 17.5), (6.5, 13)], a)


def g_skull(g, k, a):
    g.disc(10, 8.5, 7.5, k)
    g.poly([(5.5, 12), (14.5, 12), (13.5, 19), (6.5, 19)], k)
    g.disc(7, 8.5, 2.1, X)
    g.disc(13, 8.5, 2.1, X)
    g.poly([(10, 11), (8.8, 13.4), (11.2, 13.4)], X)
    for x in (8.2, 10, 11.8):
        g.line(x, 15.6, x, 18.4, X, 0.8)


def g_claw(g, k, a):
    for x0 in (3, 8, 13):
        g.poly([(x0, 1), (x0 + 3.5, 1.5), (x0 + 3, 12), (x0 + 0.8, 18.5), (x0 + 0.4, 12)], k)
    g.disc(5, 17.5, 1.5, a)
    g.disc(12.6, 19, 1.4, a)


def g_diamond(g, k, a):
    g.poly([(10, 0), (19, 7.5), (10, 19.5), (1, 7.5)], k)
    g.poly([(10, 0), (13.4, 7.5), (10, 19.5), (6.6, 7.5)], a)
    g.line(1.4, 7.5, 18.6, 7.5, X, 0.7)


def g_web(g, k, a):
    for i in range(8):
        ang = math.pi * i / 4
        g.line(10, 10, 10 + 9.5 * math.cos(ang), 10 + 9.5 * math.sin(ang), k, 1.2)
    for r in (3.4, 6.6, 9.4):
        pts = [(10 + r * math.cos(math.pi * i / 4), 10 + r * math.sin(math.pi * i / 4)) for i in range(9)]
        for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
            g.line(x1, y1, x2, y2, a, 1.1)


def g_chevrons(g, k, a):
    for i, (x, col) in enumerate(((1.5, a), (7, k), (12.5, k))):
        g.poly([(x, 2), (x + 4, 2), (x + 8, 10), (x + 4, 18), (x, 18), (x + 4, 10)], col)


def g_arrow_speed(g, k, a):
    g.poly([(8, 2), (19, 10), (8, 18), (8, 13), (1.5, 13), (1.5, 7), (8, 7)], k)
    g.line(0.5, 3, 7, 3, a, 1.2)
    g.line(0.5, 17, 7, 17, a, 1.2)


def g_cross(g, k, a):
    g.poly([(7, 1), (13, 1), (13, 7), (19, 7), (19, 13), (13, 13), (13, 19), (7, 19), (7, 13), (1, 13), (1, 7), (7, 7)], k)
    g.poly([(8.4, 2.6), (11.6, 2.6), (11.6, 8.4), (17.4, 8.4), (17.4, 9.6), (8.4, 9.6)], a)


def g_crown(g, k, a):
    g.poly([(1.5, 16.5), (1.5, 5), (6, 10.2), (10, 1.5), (14, 10.2), (18.5, 5), (18.5, 16.5)], k)
    g.poly([(1.5, 14), (18.5, 14), (18.5, 18), (1.5, 18)], a)
    for x in (1.5, 10, 18.5):
        g.disc(x, 2.8 if x == 10 else 4.4, 1.5, a)


def g_rings(g, k, a):
    g.ring(7, 10, 6.2, 2.2, k)
    g.ring(13, 10, 6.2, 2.2, a)
    g.ring(7, 10, 6.2, 2.2, k, only=lambda x, y: x < 10 and y > 11)


def g_axe(g, k, a):
    g.line(4, 18.5, 13.5, 3.5, a, 2.2)
    g.poly([(10.5, 1.5), (18.5, 4.5), (17.5, 12.5), (12, 9)], k)
    g.line(11.5, 2.8, 16.8, 4.9, mix(k, 1.45), 1.0)


def g_arrow_up(g, k, a):
    g.poly([(10, 0.5), (19, 9.5), (13, 9.5), (13, 19.5), (7, 19.5), (7, 9.5), (1, 9.5)], k)
    g.poly([(10, 3.5), (15, 8.6), (10, 8.6)], a)


def g_fortress(g, k, a):
    g.poly([(3, 8), (17, 8), (17, 19), (3, 19)], k)
    for x in (3, 8.2, 13.4):
        g.poly([(x, 2.5), (x + 3.6, 2.5), (x + 3.6, 8), (x, 8)], k)
    g.poly([(7.5, 19), (7.5, 13), (10, 11), (12.5, 13), (12.5, 19)], X)
    g.line(3, 8.2, 17, 8.2, a, 1.0)


def g_scales(g, k, a):
    cells = ((6.2, 5.4), (13.8, 5.4), (2.4, 12), (10, 12), (17.6, 12), (6.2, 18.6), (13.8, 18.6))
    for cx, cy in cells:
        g.poly([(cx + 4.7 * math.cos(math.pi / 3 * i + math.pi / 6), cy + 4.7 * math.sin(math.pi / 3 * i + math.pi / 6)) for i in range(6)], X)
    for cx, cy in cells:
        g.poly([(cx + 3.7 * math.cos(math.pi / 3 * i + math.pi / 6), cy + 3.7 * math.sin(math.pi / 3 * i + math.pi / 6)) for i in range(6)], k)
        g.disc(cx - 0.9, cy - 1.0, 1.0, a)


def g_gem(g, k, a):
    g.poly([(10, 0.5), (17, 5), (17, 14), (10, 19.5), (3, 14), (3, 5)], k)
    g.poly([(10, 0.5), (13.5, 5), (13.5, 14), (10, 19.5)], a)
    g.line(10, 6, 10, 15, X, 1.0)
    g.line(7, 9, 13, 9, X, 1.0)
    g.line(7.5, 12.5, 12.5, 12.5, X, 1.0)


def g_anchor(g, k, a):
    g.ring(10, 3.2, 2.7, 1.6, k)
    g.line(10, 5.5, 10, 17.5, k, 2.2)
    g.line(5.5, 8.4, 14.5, 8.4, k, 2.0)
    g.ring(10, 11.5, 7.5, 2.2, k, only=lambda x, y: y > g.p(0, 12.2)[1])
    g.poly([(1.5, 11.5), (4.8, 10), (4.5, 14)], k)
    g.poly([(18.5, 11.5), (15.2, 10), (15.5, 14)], k)


def g_unique(g, k, a):
    pts = []
    for i in range(10):
        r = 9.6 if i % 2 == 0 else 3.9
        ang = math.pi * i / 5 - math.pi / 2
        pts.append((10 + r * math.cos(ang), 10 + r * math.sin(ang)))
    g.poly(pts, k)
    g.disc(10, 10, 2.4, a)


def g_arrow_down(g, k, a):
    g.poly([(10, 19.5), (1.5, 10), (7, 10), (7, 0.5), (13, 0.5), (13, 10), (18.5, 10)], k)
    g.poly([(10, 16.5), (5.2, 11.2), (10, 11.2)], a)


def g_sparkle(g, k, a):
    g.poly([(10, 0), (12, 8), (20, 10), (12, 12), (10, 20), (8, 12), (0, 10), (8, 8)], k)
    g.disc(10, 10, 2, a)


# ---- glyphs of the affix expansion (docs/AFFIX-EXPANSION-DESIGN.md) ------------------------------------------------------------------------

def g_eye(g, k, a):
    g.poly([(0.5, 10), (5, 4.5), (10, 3), (15, 4.5), (19.5, 10), (15, 15.5), (10, 17), (5, 15.5)], k)
    g.disc(10, 10, 4.6, a)
    g.disc(10, 10, 2.3, X)
    g.disc(8.6, 8.6, 0.9, mix(a, 1.4))


def g_fist(g, k, a):
    g.poly([(2.5, 3.5), (17.5, 3.5), (17.5, 11), (2.5, 11)], k)
    for x in (6.3, 10, 13.7):
        g.line(x, 3.2, x, 9.4, X, 1.1)
    g.poly([(4, 11), (16, 11), (15, 19), (5, 19)], k)
    g.poly([(2.5, 9.5), (10.5, 9.5), (10.5, 12.8), (2.5, 12.8)], a)
    g.line(2.5, 12.9, 10.5, 12.9, X, 0.9)




def g_crosshair(g, k, a):
    g.ring(10, 10, 8, 1.8, k)
    for x1, y1, x2, y2 in ((10, 0.5, 10, 6.5), (10, 13.5, 10, 19.5), (0.5, 10, 6.5, 10), (13.5, 10, 19.5, 10)):
        g.line(x1, y1, x2, y2, k, 1.8)
    g.disc(10, 10, 2.2, a)


def g_giant(g, k, a):
    g.poly([(10, 0.5), (19, 19), (1, 19)], k)
    g.poly([(10, 0.5), (13, 6.5), (7, 6.5)], a)
    g.poly([(10.6, 7.5), (8.6, 11.5), (11.4, 13.2), (9.4, 18.8), (10.8, 18.8), (13, 12.8), (10.2, 11.2), (12, 7.5)], X)


def g_repeat(g, k, a):
    g.ring(10, 10, 8, 2.4, k, only=lambda x, y: y < g.p(0, 9.2)[1])
    g.ring(10, 10, 8, 2.4, a, only=lambda x, y: y > g.p(0, 10.8)[1])
    g.poly([(14.6, 6.4), (19.8, 6.4), (17.2, 11.4)], k)
    g.poly([(5.4, 13.6), (0.2, 13.6), (2.8, 8.6)], a)


def g_broken_shield(g, k, a):
    g.poly([(2.5, 1.5), (9.2, 1.5), (7.6, 7), (10.4, 10.6), (8.4, 18.6), (2.5, 10)], k)
    g.poly([(11.2, 1.5), (17.5, 1.5), (17.5, 10), (11.4, 18), (12.8, 10.6), (9.8, 7)], a)


def g_hammer(g, k, a):
    g.line(5, 18.5, 12.5, 8, a, 2.2)
    g.poly([(8.2, 1.6), (18.8, 9.2), (15.6, 13.6), (5, 6)], k)
    g.line(9, 3, 17.4, 9.1, mix(k, 1.45), 0.9)


def g_target(g, k, a):
    g.ring(10, 10, 9.2, 2.0, k)
    g.ring(10, 10, 5.6, 2.0, a)
    g.disc(10, 10, 2.2, k)


def g_ember(g, k, a):
    g.poly([(9, 3), (13.5, 8.5), (15.5, 13.5), (12.8, 19), (5.2, 19), (2.6, 13.5), (5, 9.5), (7.2, 11.6), (7.4, 6.5)], k)
    g.poly([(9, 10), (12, 14), (11, 17.6), (7, 17.6), (6.2, 14)], a)
    for cx, cy, r in ((16.2, 3.2, 1.4), (18.4, 7.6, 1.0), (14.2, 0.9, 0.9)):
        g.disc(cx, cy, r, a)


def g_fang(g, k, a):
    g.poly([(1, 1.5), (19, 1.5), (19, 5), (1, 5)], k)
    g.poly([(3, 5), (8, 5), (5.5, 15)], k)
    g.poly([(12, 5), (17, 5), (14.5, 15)], k)
    g.disc(5.5, 17.4, 1.5, a)
    g.disc(14.5, 17.4, 1.5, a)


def g_siphon(g, k, a):
    g.poly([(10, 0.5), (3.8, 11), (16.2, 11)], k)
    g.disc(10, 13, 6.6, k)
    g.disc(8.2, 12.2, 2.0, a)
    g.disc(11.8, 12.2, 2.0, a)
    g.poly([(6.3, 13), (13.7, 13), (10, 17.4)], a)


def g_thorns(g, k, a):
    g.ring(10, 10, 6.8, 2.4, k)
    for i in range(8):
        ang = math.pi * i / 4 + math.pi / 8
        bx, by = 10 + 6.2 * math.cos(ang), 10 + 6.2 * math.sin(ang)
        tx, ty = 10 + 10.4 * math.cos(ang), 10 + 10.4 * math.sin(ang)
        nx, ny = -math.sin(ang) * 2.2, math.cos(ang) * 2.2
        g.poly([(bx + nx, by + ny), (bx - nx, by - ny), (tx, ty)], a)



def g_feather(g, k, a):
    g.poly([(17.5, 1), (12, 3.5), (5, 11), (3.5, 16), (8.5, 15), (15.5, 8)], k)
    g.line(17.5, 1, 2, 19, a, 1.1)
    for i in range(4):
        x = 13 - i * 2.6
        g.line(x, 4 + i * 2.6, x - 2.4, 4 + i * 2.6 - 0.4, X, 0.6)


def g_helm(g, k, a):
    g.poly([(3, 9), (5, 3.5), (10, 1), (15, 3.5), (17, 9), (17, 18.5), (12, 18.5), (12, 12), (8, 12), (8, 18.5), (3, 18.5)], k)
    g.poly([(5.5, 8), (14.5, 8), (14.5, 10.2), (5.5, 10.2)], X)
    g.line(10, 1.5, 10, 7.4, a, 1.4)


def g_wall(g, k, a):
    rows = ((2, (1, 7, 13)), (7.2, (4, 10, 16)), (12.4, (1, 7, 13)))
    for y, xs in rows:
        for x in xs:
            g.poly([(x, y), (x + 5.4, y), (x + 5.4, y + 4.6), (x, y + 4.6)], k)
            g.line(x + 0.6, y + 0.8, x + 4.8, y + 0.8, a, 0.8)
    g.poly([(1, 17.6), (19, 17.6), (19, 19.4), (1, 19.4)], a)


def g_hourglass(g, k, a):
    g.poly([(3, 0.8), (17, 0.8), (17, 2.8), (3, 2.8)], k)
    g.poly([(3, 17.2), (17, 17.2), (17, 19.2), (3, 19.2)], k)
    g.poly([(4.4, 2.8), (15.6, 2.8), (10.9, 10), (15.6, 17.2), (4.4, 17.2), (9.1, 10)], k)
    g.poly([(6.6, 13.4), (13.4, 13.4), (14.6, 16.4), (5.4, 16.4)], a)
    g.poly([(7.2, 4.4), (12.8, 4.4), (10, 8.2)], a)


def g_pillar(g, k, a):
    g.poly([(2.5, 0.8), (17.5, 0.8), (17.5, 3.6), (2.5, 3.6)], k)
    g.poly([(4.5, 3.6), (15.5, 3.6), (15.5, 16.4), (4.5, 16.4)], k)
    for x in (7.2, 10, 12.8):
        g.line(x, 4.6, x, 15.4, X, 0.7)
    g.poly([(2.5, 16.4), (17.5, 16.4), (17.5, 19.2), (2.5, 19.2)], a)


def g_bubble(g, k, a):
    g.ring(10, 10, 9, 2.0, k)
    g.disc(10, 10, 6.4, mix(k, 0.7))
    g.ring(10, 10, 6.4, 1.2, a, only=lambda x, y: x < g.p(9, 0)[0] and y < g.p(0, 9)[1])
    g.disc(6.8, 6.6, 1.3, mix(a, 1.4))


def g_layers(g, k, a):
    for i, col in enumerate((k, a, k)):
        y = 2 + i * 6
        x0 = 3 - i * 1.2
        g.poly([(x0 + 1.2, y), (17 + i * 1.2 - 1.2, y), (18 + i * 1.2 - 1.2, y + 4.6), (x0, y + 4.6)], col)
        g.line(x0 + 0.4, y + 4.6, 18 + i * 1.2 - 1.6, y + 4.6, X, 0.7)



# ---- mini marks drawn inside a shield (the shield is the base of the defensive family) ------------------------------------------------------

def shield_with(mark):
    def draw(g, k, a):
        g_shield_shape(g, k, mix(a, 0.3))
        mark(g.sub(4.4, 3.4, 0.62), a, mix(a, 1.4))
    return draw


def shield_double(mark):
    def draw(g, k, a):
        g.poly([(1, 0.5), (19, 0.5), (19, 10.5), (10, 20), (1, 10.5)], mix(k, 1.5))
        g.poly([(2.6, 2), (17.4, 2), (17.4, 10), (10, 18.2), (2.6, 10)], mix(k, 0.8))
        g.poly([(4.4, 3.6), (15.6, 3.6), (15.6, 9.4), (10, 15.6), (4.4, 9.4)], mix(a, 0.3))
        mark(g.sub(5.2, 4.2, 0.5), a, mix(a, 1.4))
    return draw


def mark_arrow_down(g, k, a):
    g.poly([(3, 4), (7, 4), (10, 10), (13, 4), (17, 4), (10, 16.5)], k)


def mark_ring_ticks(g, k, a):
    g_wheel(g, k, a)


GLYPHS = {
    'sword': g_sword, 'burst': g_burst, 'orb': g_orb, 'heart': g_heart, 'heart_crack': g_heart_crack, 'spiral': g_spiral,
    'shield': g_shield, 'wheel': g_wheel, 'wheel_focus': g_wheel_focus, 'wheel_master': g_wheel_master, 'bolt': g_bolt, 'drop': g_drop,
    'flame': g_flame, 'skull': g_skull, 'claw': g_claw, 'diamond': g_diamond, 'web': g_web, 'chevrons': g_chevrons, 'arrow_speed': g_arrow_speed,
    'cross': g_cross, 'crown': g_crown, 'rings': g_rings, 'axe': g_axe, 'arrow_up': g_arrow_up, 'fortress': g_fortress, 'scales': g_scales,
    'gem': g_gem, 'anchor': g_anchor, 'sparkle': g_sparkle, 'unique': g_unique, 'arrow_down': g_arrow_down,
    'eye': g_eye, 'fist': g_fist, 'crosshair': g_crosshair, 'giant': g_giant, 'repeat': g_repeat, 'broken_shield': g_broken_shield,
    'hammer': g_hammer, 'target': g_target, 'ember': g_ember, 'fang': g_fang, 'siphon': g_siphon, 'thorns': g_thorns, 'feather': g_feather,
    'purge': lambda g, k, a: (g_sparkle(g, k, a), g.disc(15.5, 4.5, 1.6, a), g.disc(4.5, 15.5, 1.3, a)), 'helm': g_helm, 'wall': g_wall, 'hourglass': g_hourglass, 'pillar': g_pillar, 'bubble': g_bubble, 'layers': g_layers,
    'shield_heart': shield_with(g_heart), 'shield_crack': shield_with(g_heart_crack), 'shield_spiral': shield_with(g_spiral),
    'shield_bolt': shield_with(g_bolt), 'shield_fist': shield_with(g_sword), 'shield_star': shield_with(g_sparkle),
    'shield_wheel': shield_with(mark_ring_ticks), 'shield_wheel2': shield_double(mark_ring_ticks), 'shield_down': shield_with(mark_arrow_down),
}

# affix id -> (glyph, theme). Themes: type, power, guard, life, fire, venom, blood, tempo, tactic, edge
# A suffix crest is already a shield, so a shield glyph is drawn as just its mark, larger.
MARK_ONLY = {'shield_heart': 'heart', 'shield_crack': 'heart_crack', 'shield_spiral': 'spiral', 'shield_bolt': 'bolt', 'shield_fist': 'sword',
             'shield_star': 'orb', 'shield_wheel': 'wheel', 'shield_wheel2': 'wheel_master', 'shield_down': 'arrow_down'}

PLAN = {
    'type_focus': ('wheel_focus', 'type'), 'physical_force': ('sword', 'power'), 'special_force': ('orb', 'power'),
    'healthy_force': ('heart', 'power'), 'last_stand': ('heart_crack', 'power'), 'defiant_force': ('spiral', 'power'),
    'type_ward': ('shield_wheel', 'type'), 'healthy_guard': ('shield_heart', 'guard'), 'steadfast_guard': ('shield_crack', 'guard'),
    'status_guard': ('shield_spiral', 'guard'), 'opening_guard': ('shield_bolt', 'guard'), 'restorative': ('cross', 'life'),
    'type_mastery': ('wheel_master', 'type'), 'overwhelming_force': ('burst', 'power'), 'kindred_force': ('rings', 'tactic'),
    'executioner': ('axe', 'blood'), 'opening_strike': ('bolt', 'tempo'), 'super_effective_force': ('arrow_up', 'power'),
    'iron_resolve': ('fortress', 'guard'), 'type_bulwark': ('shield_wheel2', 'type'), 'resilient_hide': ('scales', 'guard'),
    'triumphant': ('crown', 'life'), 'physical_bulwark': ('shield_fist', 'guard'), 'special_bulwark': ('shield_star', 'guard'),
    'smoldering': ('flame', 'fire'), 'venomous': ('drop', 'venom'), 'rending': ('claw', 'blood'), 'keen_edge': ('diamond', 'edge'),
    'ensnaring': ('web', 'tactic'), 'momentum': ('chevrons', 'tempo'), 'swift_strike': ('arrow_speed', 'tempo'),
    'bracing_entry': ('shield_down', 'guard'), 'wardstone': ('gem', 'tactic'), 'stubborn': ('anchor', 'guard'),
    # the affix expansion (docs/AFFIX-EXPANSION-DESIGN.md)
    'ambush': ('eye', 'tempo'), 'brawler': ('fist', 'power'), 'sharpshooter': ('crosshair', 'power'), 'giant_slayer': ('giant', 'blood'),
    'relentless': ('repeat', 'power'), 'exploit_weakness': ('broken_shield', 'tactic'), 'armorbreak': ('hammer', 'tactic'),
    'pressure_point': ('target', 'edge'), 'searing_touch': ('ember', 'fire'), 'venom_touch': ('fang', 'venom'),
    'siphon': ('siphon', 'life'), 'thorns': ('thorns', 'blood'), 'purging': ('purge', 'life'), 'fresh_legs': ('feather', 'tempo'),
    'stalwart': ('helm', 'guard'), 'unyielding': ('pillar', 'guard'), 'watchful': ('hourglass', 'tempo'), 'resolute': ('wall', 'guard'),
    'insulated': ('bubble', 'venom'), 'hardening': ('layers', 'guard'),
}
UNIQUE = ('unique', 'unique')

THEME = {   # main, accent
    'type': ('b07ae6', 'e6d0ff'), 'power': ('e2553b', 'ffb199'), 'guard': ('4a92e0', 'b7dcff'), 'life': ('4fc46c', 'c4f5cf'),
    'fire': ('f08a24', 'ffd36b'), 'venom': ('a45fd8', 'e0b8ff'), 'blood': ('c8283f', 'ff8e9b'), 'tempo': ('f0cc3a', 'fff3a8'),
    'tactic': ('3fbfb0', 'b4f2ea'), 'edge': ('e8e6f0', 'ffffff'), 'unique': ('9a62d9', 'e6d0ff'),
}
# The 18 Pokemon types in the order of domain Rules.TYPES, with the colour each one's modifier crests take.
TYPES = ['normal', 'fire', 'water', 'electric', 'grass', 'ice', 'fighting', 'poison', 'ground', 'flying', 'psychic', 'bug', 'rock', 'ghost', 'dragon',
         'dark', 'steel', 'fairy']
TYPE_COLOR = {'normal': 'a8a77a', 'fire': 'f08030', 'water': '4f86ee', 'electric': 'f8d030', 'grass': '78c850', 'ice': '8fd6d6', 'fighting': 'c03028',
              'poison': 'a040a0', 'ground': 'd8b45c', 'flying': 'a890f0', 'psychic': 'f85888', 'bug': 'a8b820', 'rock': 'b8a038', 'ghost': '705898',
              'dragon': '7038f8', 'dark': '705848', 'steel': 'b8b8d0', 'fairy': 'ee99ac'}

SLOT = {'prefix': ('e8892b', 'ffc77a'), 'suffix': ('49b85a', 'a8ecb3')}

SIZE = pa.SIZE


def bevel(layer, mapping):
    """Lit from the top left: edge pixels of a mapped colour take its light tone above/left and its dark tone below/right."""
    out = [row[:] for row in layer.px]
    for y in range(SIZE):
        for x in range(SIZE):
            col = layer.px[y][x]
            if col not in mapping:
                continue
            dark, light = mapping[col]
            up, left = layer.get(x, y - 1), layer.get(x - 1, y)
            down, right = layer.get(x, y + 1), layer.get(x + 1, y)
            if up is None or left is None:
                out[y][x] = light
            elif down is None or right is None:
                out[y][x] = dark
    layer.px = out


def glyph_layer(name, main, accent, sc=0.92, ox=None, oy=None):
    layer = pa.Canvas()
    box = 20 * sc
    ox = (SIZE - box) / 2 if ox is None else ox
    oy = (SIZE - box) / 2 if oy is None else oy
    GLYPHS[name](G(layer, ox, oy, sc), main, accent)
    bevel(layer, {main: (mix(main, 0.62), mix(main, 1.28)), accent: (mix(accent, 0.7), mix(accent, 1.15))})
    return layer


def paste(dst, src):
    for y in range(SIZE):
        for x in range(SIZE):
            if src.px[y][x] is not None:
                dst.px[y][x] = src.px[y][x]


def plate_shape(kind):
    """The filled plate outline as a function of (x, y) -> bool, for the three plate silhouettes."""
    def rounded(x, y):
        cx, cy = x + 0.5 - 16, y + 0.5 - 16
        return abs(cx) <= 14.5 and abs(cy) <= 14.5 and not (abs(cx) > 12.5 and abs(cy) > 12.5)

    def hexagon(x, y):
        cx, cy = abs(x + 0.5 - 16), abs(y + 0.5 - 16)
        return cx <= 14 and cy + cx * 0.536 <= 15

    def shield(x, y):
        cx, cy = abs(x + 0.5 - 16), y + 0.5
        if cy < 2 or cy > 30:
            return False
        if cy <= 15:
            return cx <= 13.5
        return cx <= 13.5 * (30 - cy) / 15

    def octagon(x, y):
        cx, cy = abs(x + 0.5 - 16), abs(y + 0.5 - 16)
        return cx <= 14.5 and cy <= 14.5 and cx + cy <= 21

    return {'rounded': rounded, 'hexagon': hexagon, 'shield': shield, 'octagon': octagon}[kind]


def fill_shape(canvas, inside, color):
    for y in range(SIZE):
        for x in range(SIZE):
            if inside(x, y):
                canvas.px[y][x] = color


def crest(affix_id, slot, type_name=None):
    glyph, theme = UNIQUE if affix_id == 'unique' else PLAN[affix_id]
    glyph = MARK_ONLY.get(glyph, glyph)
    main, accent = hexc(THEME[theme][0]), hexc(THEME[theme][1])
    if type_name:   # a type-bound modifier takes its chosen type's colour
        main = hexc(TYPE_COLOR[type_name])
        accent = mix(main, 1.5)
    c = pa.Canvas()
    shape = 'octagon' if affix_id == 'unique' else 'hexagon' if slot == 'prefix' else 'shield'
    inside = plate_shape(shape)
    edge = mix(main, 1.3)
    body = mix(main, 0.5)
    fill_shape(c, inside, edge)
    fill_shape(c, lambda x, y: inside(x, y) and all(inside(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (2, 0), (-2, 0), (0, 2), (0, -2))), body)
    # a soft darker lower half so the plate has some depth
    for y in range(16, SIZE):
        for x in range(SIZE):
            if c.px[y][x] == body:
                c.px[y][x] = mix(main, 0.42)
    pale, pale_acc = hexc('f4efe0'), mix(main, 1.5)
    yoff = -0.5 if shape == 'shield' else 0
    paste(c, glyph_layer(glyph, pale, pale_acc, sc=0.84, oy=(SIZE - 20 * 0.84) / 2 + yoff - (1.5 if shape == 'shield' else 0)))
    c.outline()
    return c


def load_affixes():
    return json.load(open(os.path.join(ROOT, 'design', 'affixes.json'), encoding='utf-8'))['affixes']


def affix_id_of(name):
    """The id a modifier's display name maps to ("Type Focus" is type_focus); the pages derive it the same way."""
    return ''.join(ch if ch.isalnum() else '_' for ch in name.lower())


def check():
    ids = {a['id'] for a in load_affixes()}
    if ids != set(PLAN):
        sys.exit('PLAN does not match design/affixes.json: ' + ', '.join(sorted(ids ^ set(PLAN))))
    for a in load_affixes():
        if affix_id_of(a['name']) != a['id']:
            sys.exit('the page derives the icon from the name, but %r does not map to %r' % (a['name'], a['id']))


def render_all():
    """{icon id: RGBA grid} for every modifier and for 'unique'."""
    check()
    icons = {a['id']: crest(a['id'], a['slot']).rows() for a in load_affixes()}
    icons['unique'] = crest('unique', 'prefix').rows()
    for a in load_affixes():
        if a.get('parameter'):   # type_focus, type_mastery, type_ward, type_bulwark: one crest per type, <id>_<type>
            for type_name in TYPES:
                icons[a['id'] + '_' + type_name] = crest(a['id'], a['slot'], type_name).rows()
    return icons


if __name__ == '__main__':
    icons = render_all()
    scale, cols = 5, 7
    if '--types' not in sys.argv:
        icons = {k: v for k, v in icons.items() if k == 'unique' or not any(k.endswith('_' + t) for t in TYPES)}
    cell = pa.SIZE * scale + 8
    names = list(icons)
    rows = -(-len(names) // cols)
    bg = (0x22, 0x26, 0x2b, 255)
    px = [[bg] * (cols * cell) for _ in range(rows * cell)]
    for i, name in enumerate(names):
        x0, y0 = (i % cols) * cell + 4, (i // cols) * cell + 4
        for y in range(pa.SIZE * scale):
            for x in range(pa.SIZE * scale):
                p = icons[name][y // scale][x // scale]
                if p[3]:
                    px[y0 + y][x0 + x] = p
    open(next((x for x in sys.argv[1:] if not x.startswith('--')), 'affix-icons-preview.png'), 'wb').write(pa.png_bytes(px))
