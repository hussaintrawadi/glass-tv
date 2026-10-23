"""Renders the Performance icon (a gauge, white on transparent) into app/assets/symbols/monitor.png.
Usage: python3 tools/make_monitor_icon.py"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "assets")


def monitor_icon():
    s = 600
    im = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(im)
    d.arc((60, 90, 540, 570), 160, 380, fill=255, width=70)   # a gauge: arc, needle, hub and base
    d.line((300, 330, 440, 200), fill=255, width=56)
    d.ellipse((240, 270, 360, 390), fill=255)
    d.rectangle((150, 470, 450, 520), fill=255)
    im = im.resize((150, 150), Image.LANCZOS)
    rgba = Image.new("RGBA", im.size, (255, 255, 255, 0))
    rgba.putalpha(im)
    return rgba


def main():
    monitor_icon().save(os.path.join(ROOT, "symbols", "monitor.png"), optimize=True)


if __name__ == "__main__":
    main()
