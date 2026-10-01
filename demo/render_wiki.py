"""
Renders the Kagami wiki page for the demo frame as a PNG.

The layout mirrors what the Compose WikiScreen composes on the phone: hero
banner with a confidence dial, the "frame found" card, the searched still, the
series facts and the cast grid. It is a static renderer on purpose - the point
is to show the result the app produces for this exact input.
"""
import json
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

BASE = os.path.dirname(os.path.abspath(__file__))
IMG = os.path.join(BASE, "img")
DATA = json.load(open(os.path.join(BASE, "anilist-138459.json"), encoding="utf-8"))["data"]["Media"]

W, H = 900, 2450
INK = (11, 11, 20)
SURFACE = (21, 21, 31)
VIOLET = (124, 92, 255)
CYAN = (62, 214, 208)
AMBER = (255, 180, 84)
MUTED = (185, 180, 204)
WHITE = (236, 235, 245)

FONT_DIR = r"C:\Windows\Fonts"


def font(name, size):
    return ImageFont.truetype(os.path.join(FONT_DIR, name), size)


F_BOLD = lambda s: font("seguisb.ttf", s)
F_REG = lambda s: font("segoeui.ttf", s)
F_BOLD2 = lambda s: font("segoeuib.ttf", s)
F_MONO = lambda s: font("consola.ttf", s)


def rounded(img, box, radius, fill=None, outline=None, width=1):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)
    img.alpha_composite(layer)


def cover(path, size, radius=0):
    im = Image.open(path).convert("RGB")
    sw, sh = size
    scale = max(sw / im.width, sh / im.height)
    im = im.resize((max(1, int(im.width * scale)), max(1, int(im.height * scale))), Image.LANCZOS)
    left = (im.width - sw) // 2
    top = (im.height - sh) // 2
    im = im.crop((left, top, left + sw, top + sh))
    if radius:
        mask = Image.new("L", size, 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, sw, sh), radius=radius, fill=255)
        out = Image.new("RGBA", size, (0, 0, 0, 0))
        out.paste(im, (0, 0), mask)
        return out
    return im.convert("RGBA")


def chip(d, x, y, text, color, fnt=F_BOLD(20)):
    w = d.textlength(text, font=fnt) + 28
    rounded(img, (x, y, x + w, y + 40), 20, fill=color + (36,))
    d.text((x + 14, y + 9), text, font=fnt, fill=color)
    return x + w + 10


def wrap(d, text, fnt, max_w):
    words, lines, cur = text.split(), [], ""
    for w in words:
        trial = (cur + " " + w).strip()
        if d.textlength(trial, font=fnt) <= max_w:
            cur = trial
        else:
            if cur:
                lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


img = Image.new("RGBA", (W, H), INK + (255,))
d = ImageDraw.Draw(img)

# ---------------------------------------------------------------- hero
HERO_H = 430
hero = cover(os.path.join(IMG, "banner.jpg"), (W, HERO_H))
img.alpha_composite(hero, (0, 0))
scrim = Image.new("RGBA", (W, HERO_H), (0, 0, 0, 0))
sd = ImageDraw.Draw(scrim)
for i in range(HERO_H):
    a = 0 if i < HERO_H // 3 else int(235 * ((i - HERO_H // 3) / (HERO_H * 2 / 3)) ** 1.4)
    sd.line((0, i, W, i), fill=(11, 11, 20, a))
img.alpha_composite(scrim, (0, 0))

d.text((36, 250), "КАДР НАЙДЕН ЗА 1,2 С", font=F_BOLD(22), fill=CYAN)
d.text((36, 286), DATA["title"]["english"], font=F_BOLD2(52), fill=WHITE)
d.text((36, 348), DATA["title"]["native"], font=F_REG(24), fill=(200, 196, 215))

x = 36
x = chip(d, x, 384, "7 апреля 2022", WHITE)
x = chip(d, x, 384, "TV", WHITE)
x = chip(d, x, 384, "12 серий", WHITE)
x = chip(d, x, 384, "Finished", CYAN)

# confidence dial
dial_cx, dial_cy, dial_r = W - 110, 110, 62
d.ellipse((dial_cx - dial_r, dial_cy - dial_r, dial_cx + dial_r, dial_cy + dial_r), fill=(0, 0, 0, 120))
d.arc((dial_cx - dial_r, dial_cy - dial_r, dial_cx + dial_r, dial_cy + dial_r), -90, 269, fill=CYAN, width=9)
d.text((dial_cx - 52, dial_cy - 26), "99.6%", font=F_BOLD2(40), fill=CYAN)
d.text((dial_cx - 46, dial_cy + 22), "сходство", font=F_REG(18), fill=MUTED)

# ------------------------------------------------------- frame card
y = HERO_H + 26
rounded(img, (24, y, W - 24, y + 168), 28, fill=SURFACE + (255,))
d.text((48, y + 24), "Кадр найден", font=F_BOLD(30), fill=WHITE)
d.text((W - 210, y + 30), "trace.moe", font=F_MONO(20), fill=CYAN)
x = 48
x = chip(d, x, y + 70, "Серия 8 / 12", AMBER)
x = chip(d, x, y + 70, "Таймкод 11:36", AMBER)
x = chip(d, x, y + 70, "Сходство 99.6%", CYAN)
d.text((48, y + 124), "SauceNAO: 92.35% по индексу AniDB", font=F_REG(20), fill=MUTED)

# ---------------------------------------------------------- still
y += 196
still = cover(os.path.join(IMG, "frame-preview.jpg"), (W - 48, 430), radius=28)
img.alpha_composite(still, (24, y))
d.text((48, y + 18), "СОВПАВШИЙ КАДР ИЗ СЕРИИ", font=F_BOLD(20), fill=(11, 11, 20))
y += 430 + 26

# ------------------------------------------------------------ facts
rounded(img, (24, y, W - 24, y + 300), 28, fill=SURFACE + (255,))
d.text((48, y + 24), "Факты о серии", font=F_BOLD(30), fill=WHITE)
rows = [
    ("Жанры", "Drama · Music · Slice of Life"),
    ("Студия", "Lay-duce"),
    ("Длительность", "24 мин · 12 серий"),
    ("Оценка AniList", "71 / 100"),
    ("Популярность", "24 893"),
    ("Синоним", "To Become a Real Heroine!"),
]
ry = y + 74
for label, value in rows:
    d.text((48, ry), label, font=F_REG(20), fill=MUTED)
    d.text((250, ry), value, font=F_BOLD(20), fill=WHITE)
    ry += 36
y += 300 + 26

# ------------------------------------------------------------- cast
rounded(img, (24, y, W - 24, y + 460), 28, fill=SURFACE + (255,))
d.text((48, y + 24), "Персонажи серии", font=F_BOLD(30), fill=WHITE)
d.text((48, y + 62), "Видеокадр не содержит тегов персонажа — выберите вручную", font=F_REG(19), fill=AMBER)
gx, gy = 48, y + 108
for i, edge in enumerate(DATA["characters"]["edges"][:8]):
    node = edge["node"]
    img_url = node.get("image")
    if not img_url:
        continue
    path = os.path.join(IMG, f"char{i + 1}.jpg")
    if not os.path.exists(path):
        continue
    col, row = i % 4, i // 4
    cx = gx + col * 200
    cy = gy + row * 170
    portrait = cover(path, (150, 150), radius=75)
    img.alpha_composite(portrait, (cx, cy))
    name = node["name"]["full"] or node["name"]["native"]
    for line in wrap(d, name, F_BOLD(19), 170)[:2]:
        d.text((cx, cy + 156), line, font=F_BOLD(19), fill=WHITE)
        cy += 22
    if edge["role"] == "MAIN":
        d.text((cx, cy + 2), "главный", font=F_REG(17), fill=CYAN)

y += 460 + 26

# ---------------------------------------------------------- sources
rounded(img, (24, y, W - 24, y + 150), 28, fill=SURFACE + (255,))
d.text((48, y + 24), "Источники", font=F_BOLD(30), fill=WHITE)
d.text((48, y + 72), "trace.moe · AniList · MyAnimeList · AniDB · Wikimedia Commons", font=F_REG(20), fill=MUTED)
d.text((48, y + 104), "Кадр: CC BY 3.0, HoneyWorks OFFICIAL", font=F_MONO(18), fill=MUTED)

img.convert("RGB").save(os.path.join(BASE, "amps-wiki-demo.png"), quality=95)
print("saved", os.path.join(BASE, "amps-wiki-demo.png"))
