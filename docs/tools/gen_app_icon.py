#!/usr/bin/env python3
"""M18.147: Aevum-App-Icon aus einem gelieferten Artwork generieren.

Der Generator ist reproduzierbar: Er schnipst das Artwork aus dem
schwarzen Umfeld (Schwelle 8), zentriert es auf ein Quadrat und erzeugt:
  - Adaptive-Icon-Hintergrund-Layer (108dp-Raster) je Dichte
  - Legacy-Mipmaps ic_launcher(.png) (abgerundete Ecken) + ic_launcher_round (Kreis)
  - Play-Store-Icon 512 fuer docs/store-assets/
  - Vorschauen in ein temporaeres Verzeichnis fuer die Sichtpruefung

Aufruf:
    python3 docs/tools/gen_app_icon.py [pfad/zum/artwork.jpg] [preview_dir]

Eigenschaften, die beim Nachfolge-Icon erhalten bleiben muessen:
  - Der Hintergrund-Layer traegt das Artwork VOLLFLAECHIG; die Launcher-Maske
    schneidet die Ecken (dort ist das Artwork randlos dunkel). Ein zweiter,
    gefuellter Vordergrund-Layer wuerde bei Launcher-Parallaxe doppelt
    erscheinen — er bleibt transparent (ic_launcher_foreground_clear).
  - Legacy-PNGs bekommen Alpha-Ecken (Radius 22,5 % bzw. Kreis), damit sie
    auf alten/Launcher-Kontexten sauber schweben.
"""
import os
import sys
import tempfile

from PIL import Image, ImageDraw

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(SCRIPT_DIR))
RES = f'{REPO}/app/src/main/res'
DOCS = f'{REPO}/docs/store-assets'

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


# 2) Adaptive-Icon: Hintergrund-Layer, vollflaechig (108dp-Raster).
dens = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}
for d, s in dens.items():
    os.makedirs(f'{RES}/mipmap-{d}', exist_ok=True)
    art.resize((s, s), resample).save(f'{RES}/mipmap-{d}/ic_launcher_background.png', optimize=True)

# 3) Legacy-PNGs (Fallback + direkte Ressourcen-Nutzung)
leg = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
for d, s in leg.items():
    base = art.resize((s, s), resample)
    sq = base.copy()
    sq.putalpha(rounded_mask(s, 0.225))
    sq.save(f'{RES}/mipmap-{d}/ic_launcher.png', optimize=True)
    rd = base.copy()
    rd.putalpha(rounded_mask(s, 0, circ=True))
    rd.save(f'{RES}/mipmap-{d}/ic_launcher_round.png', optimize=True)

# 4) Play-Store-Icon 512
os.makedirs(DOCS, exist_ok=True)
p512 = art.resize((512, 512), resample)
p512.putalpha(rounded_mask(512, 0.225))
p512.save(f'{DOCS}/icon-512.png', optimize=True)

# 5) Vorschauen (Kreis = Worst-Case-Maske) fuer die Sichtpruefung
os.makedirs(SCRATCH, exist_ok=True)
for name, mask in [
    ('preview_art_squircle_432.png', rounded_mask(432, 0.225)),
    ('preview_art_circle_432.png', rounded_mask(432, 0, circ=True)),
]:
    p = art.resize((432, 432), resample)
    p.putalpha(mask)
    p.save(f'{SCRATCH}/{name}')
for s in (192, 48):
    p = art.resize((s, s), resample)
    p.putalpha(rounded_mask(s, 0.225))
    p.save(f'{SCRATCH}/preview_legacy_{s}.png')

print('OK — Vorschauen in', SCRATCH)
