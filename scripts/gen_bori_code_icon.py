#!/usr/bin/env python3
"""Generate the Bori - Code launcher icon layers (and Play art) as Android vectors and SVG.

The mark is the Sol de Jayuya between code brackets: `< sun >`, or `< sun />` with --slash. The sun
is the one the user approved for the Bori habit tracker's Borikén edition (TainoGlyphs.Sun in
Bori's ui/components/Taino.kt, 24-unit grid): eight rounded lobes with a dot in each, a ringed face,
ringed eyes, a nose ending in two nostril curls and an open mouth. Its geometry is copied here
unchanged and only scaled; the lines are heavier so they hold up at launcher size. It is a
petroglyph, not a smiley: keep it that way.

Colours are the celeste flag's: celeste field (#4FB4F2, the same as Bori's icon), white sun,
red brackets. No stripes, triangle or star; the flag's colours, not a drawing of it.

Below the sun sits a small coquí, as on the Bori habit tracker's icon: the same petroglyph, with its
strokes copied from Bori's scripts/gen_bori_icon.py (traced off Bori's source art), in Bori's coquí
blue. Here each stroke is drawn in binary: upright 0s and 1s placed along it, spelling "BORI" in
ASCII, and the hands and eyes are 0s. Keep the figure's shape faithful; only the medium changes.

    python3 scripts/gen_bori_code_icon.py            # write the drawables
    python3 scripts/gen_bori_code_icon.py --slash    # the `< sun />` layout
    python3 scripts/gen_bori_code_icon.py --preview DIR [--slash]   # SVGs only, nothing written to res/

Launcher layers use a 108-unit canvas whose guaranteed-visible area is the circle of radius 33
around (54,54); the script fails if anything pokes out of it.
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable")

COLORS = {"bg": "#4FB4F2", "sun": "#FFFFFF", "code": "#D10002", "coqui": "#0E4F8A", "mono": "#000000"}

# Coquí petroglyph in Bori's 1024px source coordinates (copied from Bori's gen_bori_icon.py).
COQUI_STROKES = [
    [(491, 813), (481, 824), (484, 834), (513, 836)],                # left arm, up to the hand
    [(513, 836), (540, 834), (548, 829)],                            # right arm
    [(513, 836), (513, 826)],                                        # head
    [(513, 836), (511, 852), (511, 865)],                            # spine
    [(511, 865), (497, 852), (480, 855), (472, 866), (482, 877), (495, 882), (494, 890)],  # left leg
    [(511, 865), (527, 852), (545, 856), (552, 868), (546, 878), (537, 884), (538, 890)],  # right leg
]
COQUI_DOTS = [(491, 812), (548, 829), (506, 817), (526, 821)]  # hands, eyes
COQUI_BITS = "".join(f"{ord(c):08b}" for c in "BORI")
SAFE_R = 33.0


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


class Layout:
    def __init__(self, slash):
        self.slash = slash
        if slash:
            self.sun_cx, self.scale = 49.0, 1.32
            self.left_apex, self.left_arm = 24.0, 30.5
            self.right_apex, self.right_arm = 84.5, 77.5
            self.slash_line = ((66.5, 62.5), (71.5, 45.5))
        else:
            self.sun_cx, self.scale = 54.0, 1.6
            self.left_apex, self.left_arm = 25.0, 32.5
            self.right_apex, self.right_arm = 83.0, 75.5
            self.slash_line = None
        self.sun_cy = 49.5
        # The coquí under the sun: centre x, top y and height in icon units; digit size and spacing.
        self.coqui_cx, self.coqui_top, self.coqui_h = 54.0, 71.5, 13.5
        # Digit size, spacing along a stroke and line weight; the hands' and eyes' 0s relative size.
        self.digit_h, self.digit_w, self.digit_gap, self.digit_line = 1.45, 0.82, 1.08, 0.42
        self.hand_size, self.eye_size = 1.3, 0.62
        self.bracket_half = 8.5
        self.code_w = 4.2
        # Glyph-unit line weights (the 24-unit sun): rim = lobes and face, fine = eyes, nose, mouth.
        self.rim_w, self.fine_w = 0.95, 0.75

    def p(self, x, y):
        """Sun glyph coordinates (24-unit grid, centre 12,12) to icon units."""
        return self.sun_cx + (x - 12) * self.scale, self.sun_cy + (y - 12) * self.scale


def sun_paths(L):
    """[(kind, d, glyph_width)] — kind is 'stroke' or 'fill'."""
    out = []
    s = L.scale

    def circle_d(cx, cy, r):
        x, y = L.p(cx, cy); r *= s
        return f"M{f(x - r)},{f(y)} a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0 a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0 z"

    inner, outer = 6.6, 10.6
    for i in range(8):
        a0 = math.radians(i * 45.0 - 90.0 - 17.0)
        a1 = math.radians(i * 45.0 - 90.0 + 17.0)
        am = math.radians(i * 45.0 - 90.0)
        pts = [L.p(12 + math.cos(a) * r, 12 + math.sin(a) * r)
               for a, r in ((a0, inner), (a0, outer), (a1, outer), (a1, inner))]
        d = f"M{f(pts[0][0])},{f(pts[0][1])} C{f(pts[1][0])},{f(pts[1][1])} {f(pts[2][0])},{f(pts[2][1])} {f(pts[3][0])},{f(pts[3][1])}"
        out.append(("stroke", d, L.rim_w))
        # Dots sit at 8.1 (8.5 in Taino.kt): with the heavier icon line they would touch the lobe edge.
        out.append(("fill", circle_d(12 + math.cos(am) * 8.1, 12 + math.sin(am) * 8.1, 0.65), 0))
    out.append(("stroke", circle_d(12, 12, 5.6), L.rim_w))
    for ex in (9.9, 14.1):
        out.append(("stroke", circle_d(ex, 10.6, 1.15), L.fine_w))
        out.append(("fill", circle_d(ex, 10.6, 0.45), 0))
    n0, n1 = L.p(12, 10.4), L.p(12, 12.6)
    ql, ql2 = L.p(11.1, 12.9), L.p(11.3, 12.2)
    qr, qr2 = L.p(12.9, 12.9), L.p(12.7, 12.2)
    out.append(("stroke",
                f"M{f(n0[0])},{f(n0[1])} L{f(n1[0])},{f(n1[1])} "
                f"M{f(n1[0])},{f(n1[1])} Q{f(ql[0])},{f(ql[1])} {f(ql2[0])},{f(ql2[1])} "
                f"M{f(n1[0])},{f(n1[1])} Q{f(qr[0])},{f(qr[1])} {f(qr2[0])},{f(qr2[1])}", L.fine_w))
    m0, m1 = L.p(10.4, 14.6), L.p(13.6, 14.6)
    rx, ry = 1.6 * s, 0.75 * s
    out.append(("stroke",
                f"M{f(m0[0])},{f(m0[1])} A{f(rx)},{f(ry)} 0 1,1 {f(m1[0])},{f(m1[1])} "
                f"A{f(rx)},{f(ry)} 0 1,1 {f(m0[0])},{f(m0[1])} z", L.fine_w))
    return out


def coqui_map(L):
    xs = [x for st in COQUI_STROKES for x, _ in st]; ys = [y for st in COQUI_STROKES for _, y in st]
    k = L.coqui_h / (max(ys) - min(ys))
    ox = (min(xs) + max(xs)) / 2
    return lambda x, y: (L.coqui_cx + (x - ox) * k, L.coqui_top + (y - min(ys)) * k)


def digit_d(ch, x, y, L, angle=0.0, size=1.0):
    """A 0 or 1 centred on (x, y) as a stroke path, its baseline turned to [angle] (radians),
    so a run of digits follows a stroke like text on a path."""
    h, w = L.digit_h * size, L.digit_w * size
    ca, sa = math.cos(angle), math.sin(angle)
    # Local (u along the baseline, v up) to icon coordinates; screen y grows downward.
    pt = lambda u, v: (x + u * ca + v * sa, y + u * sa - v * ca)
    if ch == "0":
        ring = [pt(w / 2 * math.cos(t), h / 2 * math.sin(t)) for t in (i * math.pi / 8 for i in range(16))]
        return "M" + " L".join(f"{f(px)},{f(py)}" for px, py in ring) + " z"
    pts = [pt(-w * 0.42, h * 0.22), pt(0, h / 2), pt(0, -h / 2)]
    return "M" + " L".join(f"{f(px)},{f(py)}" for px, py in pts)


def coqui_digits(L):
    """[(char, x, y, angle, size)]: digits written along each stroke, then a large 0 on each hand and
    a small one for each eye. Stroke by stroke they spell "BORI" in ASCII."""
    m = coqui_map(L)
    out, seen = [], []
    bits = iter(COQUI_BITS * 8)
    for st in COQUI_STROKES:
        placed = []
        pts = [m(*p) for p in st]
        segs = [(a, b) for a, b in zip(pts, pts[1:]) if math.dist(a, b) > 0]
        length = sum(math.dist(a, b) for a, b in segs)
        n = max(1, round(length / L.digit_gap))
        for i in range(n + 1):
            t = i * length / n
            for a, b in segs:
                d = math.dist(a, b)
                if t <= d + 1e-9 or (a, b) == segs[-1]:
                    t = min(t, d)
                    x, y = a[0] + (b[0] - a[0]) * t / d, a[1] + (b[1] - a[1]) * t / d
                    ang = math.atan2(b[1] - a[1], b[0] - a[0])
                    break
                t -= d
            # Keep text upright: a run going right-to-left would otherwise be upside down.
            if math.cos(ang) < -1e-6:
                ang += math.pi
            # Strokes meet at the shoulders and hips: skip a digit that would sit on another stroke's.
            if any(math.dist((x, y), q) < L.digit_h * 0.9 for q in seen):
                continue
            placed.append((x, y))
            out.append((next(bits), x, y, ang, 1.0))
        seen += placed
    for i, p in enumerate(COQUI_DOTS):
        x, y = m(*p)
        size = L.hand_size if i < 2 else L.eye_size
        out = [d for d in out if math.dist((d[1], d[2]), (x, y)) >= L.digit_gap * 0.9]
        out.append(("0", x, y, 0.0, size))
    return out


def code_paths(L):
    cy, h = L.sun_cy, L.bracket_half
    out = [
        f"M{f(L.left_arm)},{f(cy - h)} L{f(L.left_apex)},{f(cy)} L{f(L.left_arm)},{f(cy + h)}",
        f"M{f(L.right_arm)},{f(cy - h)} L{f(L.right_apex)},{f(cy)} L{f(L.right_arm)},{f(cy + h)}",
    ]
    if L.slash_line:
        (x0, y0), (x1, y1) = L.slash_line
        out.append(f"M{f(x0)},{f(y0)} L{f(x1)},{f(y1)}")
    return out


def check_safe(L):
    pts = [(L.left_apex, L.sun_cy), (L.left_arm, L.sun_cy - L.bracket_half), (L.left_arm, L.sun_cy + L.bracket_half),
           (L.right_apex, L.sun_cy), (L.right_arm, L.sun_cy - L.bracket_half), (L.right_arm, L.sun_cy + L.bracket_half)]
    if L.slash_line:
        pts += list(L.slash_line)
    worst = max(math.hypot(x - 54, y - 54) + L.code_w / 2 for x, y in pts)
    sun_r = (10.6 * 0.75 + 6.6 * 0.25 + L.rim_w / 2) * L.scale
    worst = max(worst, math.hypot(L.sun_cx - 54, L.sun_cy - 54) + sun_r)
    digits = coqui_digits(L)
    reach = max(math.hypot(x - 54, y - 54) + math.hypot(L.digit_w, L.digit_h) * z / 2 for _, x, y, _, z in digits)
    worst = max(worst, reach)
    if worst > SAFE_R:
        sys.exit(f"art leaves the safe circle: {worst:.2f} > {SAFE_R}")
    # The coquí must not touch the sun's lobes.
    sun_tip = L.sun_cy + (10.6 + L.rim_w / 2) * L.scale
    coqui_top = min(y - L.digit_h * z / 2 for _, _, y, _, z in digits)
    if coqui_top - sun_tip < 1.0:
        sys.exit(f"coquí touches the sun (gap {coqui_top - sun_tip:.2f})")
    # Brackets must not touch the sun.
    gap_l = (L.sun_cx - sun_r) - (L.left_arm + L.code_w / 2)
    if gap_l < 2:
        sys.exit(f"left bracket touches the sun (gap {gap_l:.2f})")
    if L.slash_line:
        gap_s = min(x for x, _ in L.slash_line) - L.code_w / 2 - (L.sun_cx + sun_r)
        if gap_s < 1.5:
            sys.exit(f"slash touches the sun (gap {gap_s:.2f})")
    return worst


def vector(body, comment):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!--\n  {comment}\n  Generated by scripts/gen_bori_code_icon.py — edit the script, not this file.\n-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
        + body + "</vector>\n"
    )


def xml_layer(L, sun_color, code_color, coqui_color):
    s = L.scale
    lines = ["    <!-- Code brackets -->\n"]
    for d in code_paths(L):
        lines.append(f'    <path android:strokeColor="{code_color}" android:strokeWidth="{f(L.code_w)}" '
                     f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}" />\n')
    lines.append("    <!-- Sol de Jayuya (TainoGlyphs.Sun, scaled) -->\n")
    for kind, d, w in sun_paths(L):
        if kind == "stroke":
            lines.append(f'    <path android:strokeColor="{sun_color}" android:strokeWidth="{f(w * s)}" '
                         f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}" />\n')
        else:
            lines.append(f'    <path android:fillColor="{sun_color}" android:pathData="{d}" />\n')
    lines.append('    <!-- Coquí in binary ("BORI" in ASCII) -->\n')
    d = " ".join(digit_d(ch, x, y, L, a, z) for ch, x, y, a, z in coqui_digits(L))
    lines.append(f'    <path android:strokeColor="{coqui_color}" android:strokeWidth="{f(L.digit_line)}" '
                 f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}" />\n')
    return "".join(lines)


def svg(L, size=512, with_bg=True, sun_color=COLORS["sun"], code_color=COLORS["code"], coqui_color=COLORS["coqui"]):
    s = L.scale
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">']
    if with_bg:
        parts.append(f'<rect width="108" height="108" fill="{COLORS["bg"]}"/>')
    for d in code_paths(L):
        parts.append(f'<path d="{d}" fill="none" stroke="{code_color}" stroke-width="{f(L.code_w)}" '
                     'stroke-linecap="round" stroke-linejoin="round"/>')
    for kind, d, w in sun_paths(L):
        if kind == "stroke":
            parts.append(f'<path d="{d}" fill="none" stroke="{sun_color}" stroke-width="{f(w * s)}" '
                         'stroke-linecap="round" stroke-linejoin="round"/>')
        else:
            parts.append(f'<path d="{d}" fill="{sun_color}"/>')
    d = " ".join(digit_d(ch, x, y, L, a, z) for ch, x, y, a, z in coqui_digits(L))
    parts.append(f'<path d="{d}" fill="none" stroke="{coqui_color}" stroke-width="{f(L.digit_line)}" '
                 'stroke-linecap="round" stroke-linejoin="round"/>')
    parts.append("</svg>")
    return "\n".join(parts)


def main():
    args = sys.argv[1:]
    L = Layout(slash="--slash" in args)
    worst = check_safe(L)
    name = "< sun />" if L.slash else "< sun >"
    if "--preview" in args:
        out = args[args.index("--preview") + 1]
        os.makedirs(out, exist_ok=True)
        tag = "slash" if L.slash else "plain"
        open(os.path.join(out, f"icon-{tag}.svg"), "w").write(svg(L))
        open(os.path.join(out, f"icon-{tag}-mono.svg"), "w").write(
            svg(L, with_bg=False, sun_color="#202020", code_color="#202020", coqui_color="#202020"))
        print(f"{name}: furthest point {worst:.2f} of {SAFE_R}; previews in {out}")
        return
    write = lambda n, t: open(os.path.join(RES, n), "w").write(t)
    write("ic_launcher_background.xml", vector(
        f'    <path android:fillColor="{COLORS["bg"]}" android:pathData="M0,0 h108 v108 h-108 z" />\n',
        "Bori - Code launcher background: celeste blue from the flag, the same as the Bori habit tracker's icon."))
    write("ic_launcher_foreground.xml", vector(
        xml_layer(L, COLORS["sun"], COLORS["code"], COLORS["coqui"]),
        f"Bori - Code launcher foreground: the Sol de Jayuya between code brackets ({name}), a binary coquí below. White sun, red brackets, blue coquí."))
    write("ic_launcher_monochrome.xml", vector(
        xml_layer(L, COLORS["mono"], COLORS["mono"], COLORS["mono"]),
        "Bori - Code themed-icon layer: the same mark in one colour; the launcher tints it."))
    print(f"{name}: furthest point {worst:.2f} of {SAFE_R}; drawables written")


if __name__ == "__main__":
    main()
