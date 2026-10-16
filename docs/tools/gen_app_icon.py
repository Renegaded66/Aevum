#!/usr/bin/env python3
"""Aevum-App-Icon aus einem gelieferten Artwork generieren.

Der Generator ist reproduzierbar: Er schnipst das Artwork aus dem
schwarzen Umfeld (Schwelle 8), zentriert es und setzt es MIT RAND auf einen
dunklen Grund (Randfarbe aus dem Artwork gemittelt) — und erzeugt daraus:
  - Adaptive-Icon-Hintergrund-Layer (108dp-Raster) je Dichte
  - Legacy-Mipmaps ic_launcher(.png) (abgerundete Ecken) + ic_launcher_round (Kreis)
  - Play-Store-Icon 512 fuer docs/store-assets/
  - Vorschauen in ein temporaeres Verzeichnis fuer die Sichtpruefung
    (inkl. Simulation des aggressiven Kreis-Zuschnitts, s. u.)

Aufruf:
    python3 docs/tools/gen_app_icon.py [pfad/zum/artwork.jpg] [preview_dir]

M18.149 (Devon, 16.10.2026): Auf Devons Handy schnitt die Kreis-Maske des
Launchers genau auf dem Ring der zehn Lebensbereich-Icons ab (je Icon nur zur
Haelfte sichtbar). Messung am Artwork: Knoten-Aussenkante bei ~0.86 der halben
Breite (= 46 dp im 108er-Raster); beobachteter Zuschnitt bei ~0.385 der halben
Breite (= 41.6 dp). Deshalb liegt das Artwork jetzt auf ART_SCALE = 0.76
verkleinert auf dem vollen Raster — die Knoten landen bei ~35 dp und sind
unter jeder realistischen Maske vollstaendig sichtbar (>= 66dp-Kreis der
Android-Spezifikation ist damit zwar nicht ganz erreicht, aber der beobachtete
Worst-Case deutlich unterboten). Das Ergebnis sieht aus wie das Originalbild:
Artwork mit dunklem Rand.

Eigenschaften, die beim Nachfolge-Icon erhalten bleiben muessen:
  - Der Hintergrund-Layer traegt das KOMPONIERTE Bild (Artwork + Rand);
    ein zweiter, gefuellter Vordergrund-Layer wuerde bei Launcher-Parallaxe
    doppelt erscheinen — er bleibt transparent (ic_launcher_foreground_clear).
  - Legacy-PNGs bekommen Alpha-Ecken (Radius 22,5 % bzw. Kreis), damit sie
    auf alten/Launcher-Kontexten sauber schweben.
  - Der Rand um das Artwork MUSS bleiben (Skala nicht auf 1.0 stellen) —
    sonst schneiden aggressive Launcher-Masken den Icon-Ring wieder an.
"""
import os
import sys
import tempfile

from PIL import Image, ImageDraw

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(SCRIPT_DIR))
RES = f'{REPO}/app/src/main/res'
DOCS = f'{REPO}/docs/store-assets'

# M18.149: Anteil des Rasters, den das Artwork einnimmt (Rest = dunkler Rand).
ART_SCALE = 0.76
# Beobachteter Worst-Case-Zuschnitt auf Devons Handy (Anteil der halben Breite).
DEVICE_CUT_FRAC = 0.385

SRC = sys.argv[1] if len(sys.argv) > 1 else '/root/.hermes/cache/images/img_5ea2ac36bd6e.jpg'
SCRATCH = sys.argv[2] if len(sys.argv) > 2 else tempfile.mkdtemp(prefix='aevum-icon-preview-')

img = Image.open(SRC).convert('RGB')
resample = Image.Resampling.LANCZOS


def _not_black(v):
    return 255 if v >= 8 else 0


# 1) Artwork freistellen (Umgebung ist reines Schwarz; Schwelle 8 faengt
#    auch das sanfte Rand-Glow noch ein).
bbox = img.convert('L').point(_not_black).getbbox()
if bbox is None:
    raise SystemExit('Kein Artwork im Bild gefunden (alles schwarz?)')
l, t, r, b = bbox
side = max(r - l, b - t)
cx, cy = (l + r) / 2.0, (t + b) / 2.0
crop = (
    max(0, round(cx - side / 2)), max(0, round(cy - side / 2)),
    min(img.width, round(cx + side / 2)), min(img.height, round(cy + side / 2)),
)
art = img.crop(crop).resize((1024, 1024), resample)
print('crop', crop, '-> art 1024x1024')


def border_fill_color(source, band=6):
    """Mittlere Farbe des aeussersten Artwork-Rands — als Fuellfarbe fuer den
    Rand, damit der Uebergang unsichtbar ist (alles nahezu schwarz)."""
    w, h = source.size
    px = source.load()
    samples = []
    for x in range(0, w, 3):
        for yy in range(band):
            samples.append(px[x, yy])
            samples.append(px[x, h - 1 - yy])
    for y in range(0, h, 3):
        for xx in range(band):
            samples.append(px[xx, y])
            samples.append(px[w - 1 - xx, y])
    n = len(samples)
    return tuple(sum(p[i] for p in samples) // n for i in range(3))


FILL = border_fill_color(art)
print('Rand-Fuellfarbe (gemittelt):', FILL)


def composed(size):
    """Artwork zentriert in ART_SCALE auf dunklem Grund (Rand = FILL)."""
    canvas = Image.new('RGB', (size, size), FILL)
    inner = max(1, round(size * ART_SCALE))
    off = (size - inner) // 2
    canvas.paste(art.resize((inner, inner), resample), (off, off))
    return canvas


def rounded_mask(size, radius_frac, circ=False):
    ss = 4  # Supersampling gegen Treppchen
    m = Image.new('L', (size * ss, size * ss), 0)
    d = ImageDraw.Draw(m)
    if circ:
        d.ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, size * ss - 1, size * ss - 1),
                            radius=int(size * ss * radius_frac), fill=255)
    return m.resize((size, size), resample)


def device_circle_mask(size, frac=DEVICE_CUT_FRAC):
    """Kreis-Maske wie auf Devons Handy (Zuschnitt statt vollem Raster)."""
    ss = 4
    m = Image.new('L', (size * ss, size * ss), 0)
    d = ImageDraw.Draw(m)
    rad = size * ss * frac
    mid = size * ss / 2.0
    d.ellipse((mid - rad, mid - rad, mid + rad, mid + rad), fill=255)
    return m.resize((size, size), resample)


# 2) Adaptive-Icon: Hintergrund-Layer, Artwork mit Rand (108dp-Raster).
dens = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}
for d, s in dens.items():
    os.makedirs(f'{RES}/mipmap-{d}', exist_ok=True)
    composed(s).save(f'{RES}/mipmap-{d}/ic_launcher_background.png', optimize=True)

# 3) Legacy-PNGs (Fallback + direkte Ressourcen-Nutzung)
leg = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
for d, s in leg.items():
    base = composed(s)
    sq = base.copy()
    sq.putalpha(rounded_mask(s, 0.225))
    sq.save(f'{RES}/mipmap-{d}/ic_launcher.png', optimize=True)
    rd = base.copy()
    rd.putalpha(rounded_mask(s, 0, circ=True))
    rd.save(f'{RES}/mipmap-{d}/ic_launcher_round.png', optimize=True)

# 4) Play-Store-Icon 512 (deckend — Play maskiert selbst)
os.makedirs(DOCS, exist_ok=True)
composed(512).save(f'{DOCS}/icon-512.png', optimize=True)

# 5) Vorschauen fuer die Sichtpruefung
os.makedirs(SCRATCH, exist_ok=True)
full = composed(432)
full.save(f'{SCRATCH}/preview_composite_432.png')

# So schneidet Devons Handy zu — vorher (altes vollflaechiges Artwork) vs neu.
old_cut = art.resize((432, 432), resample).convert('RGBA')
old_cut.putalpha(device_circle_mask(432))
old_cut.save(f'{SCRATCH}/preview_phone_old_432.png')
new_cut = full.convert('RGBA')
new_cut.putalpha(device_circle_mask(432))
new_cut.save(f'{SCRATCH}/preview_phone_new_432.png')

side_by_side = Image.new('RGB', (880, 432), (0, 0, 0))
side_by_side.paste(old_cut, (4, 0), old_cut)
side_by_side.paste(new_cut, (444, 0), new_cut)
side_by_side.save(f'{SCRATCH}/preview_vorher_nachher.png')

for s in (192, 48):
    p = composed(s)
    p.putalpha(rounded_mask(s, 0.225))
    p.save(f'{SCRATCH}/preview_legacy_{s}.png')

print('OK — Vorschauen in', SCRATCH)
