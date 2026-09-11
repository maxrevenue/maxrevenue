#!/usr/bin/env python3
"""Generate installer/roatz.ico — the app icon for the Roatz launcher.

Committed so the icon is reproducible rather than an unexplained binary: edit the
palette or the monogram here and re-run, instead of hand-painting a new .ico.

    python installer/make-icon.py

Each size is rendered natively (not downscaled from one master) so the monogram
stays legible at 16px, which is where an icon actually gets judged — in the
taskbar and the file list.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# Palette — mirrors com.sun.java.fontmgr.Theme.
CARD = (30, 33, 38, 255)      # Theme.CARD_BG
GOLD = (255, 195, 60, 255)    # Theme.ACCENT_GOLD
TITLE = (38, 42, 48, 255)     # Theme.TITLE_BG

SIZES = [16, 24, 32, 48, 64, 128, 256]

FONT_CANDIDATES = [
    Path(r"C:\Windows\Fonts\segoeuib.ttf"),   # Segoe UI Bold
    Path(r"C:\Windows\Fonts\arialbd.ttf"),    # Arial Bold
    Path(r"C:\Windows\Fonts\segoeui.ttf"),
]

OUT = Path(__file__).resolve().parent / "roatz.ico"


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if path.is_file():
            return ImageFont.truetype(str(path), size)
    raise SystemExit("No usable Windows font found; tried: "
                     + ", ".join(str(p) for p in FONT_CANDIDATES))


def render(size: int) -> Image.Image:
    """One icon frame: dark rounded square, gold monogram, centred by ink bounds."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    radius = max(2, round(size * 0.22))
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=CARD)

    # A hairline gold ring reads as deliberate at 32px+ and turns to mud below
    # that, so only draw it where it survives.
    if size >= 32:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius,
                            outline=GOLD, width=max(1, round(size * 0.03)))

    # Monogram: scale to the ink box, not the em box, or the glyph sits low.
    # Tiny sizes get a smaller monogram so the letter does not run into the edges.
    scale = 0.60 if size < 24 else (0.64 if size < 32 else 0.66)
    font = load_font(max(6, round(size * scale)))
    box = d.textbbox((0, 0), "R", font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    x = (size - w) / 2 - box[0]
    y = (size - h) / 2 - box[1]
    d.text((x, y), "R", font=font, fill=GOLD)

    return img


def main() -> None:
    frames = [render(s) for s in SIZES]
    # base + append_images keeps every size native instead of resampling one master.
    frames[-1].save(OUT, format="ICO", sizes=[(s, s) for s in SIZES],
                    append_images=frames[:-1])
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
