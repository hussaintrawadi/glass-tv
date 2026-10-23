"""Renders the app's icon and TV banner (ink, frosted glass, a white TV) and the README icon.
No text on the banner: the name on the TV is a setting (brand_name).
Usage: python3 tools/make_brand_art.py"""
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.join(os.path.dirname(__file__), "..")


def backdrop(w, h):
    """Ink (#1D1D1F) with a warm orange glow top right and a cool blue one bottom left."""
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    img = np.zeros((h, w, 3), np.float32)
    img[:] = (24, 24, 26)
    for cx, cy, r, color, s in ((0.92, 0.05, 0.85, (234, 107, 44), 0.85), (0.05, 1.0, 0.85, (63, 143, 247), 0.45),
                                (0.5, 0.45, 0.6, (60, 40, 30), 0.25)):
        d = ((x / w - cx) ** 2 + (y / h - cy) ** 2) / (r * r)
        img += np.exp(-d * 2.2)[..., None] * s * np.array(color, np.float32)
    img += np.random.default_rng(3).normal(0, 1.2, img.shape)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).convert("RGBA")


def glass_panel(size, box, radius):
    """A frosted panel: a light veil, a bright rim fading down, and a soft sheen on top."""
    w, h = size
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle(box, radius, fill=(255, 255, 255, 30))
    rim = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(rim).rounded_rectangle(box, radius, outline=(255, 255, 255, 150), width=max(2, w // 160))
    fade = Image.linear_gradient("L").resize(size).point(lambda v: 255 - int(v * 0.85))
    rim.putalpha(Image.composite(rim.getchannel("A"), Image.new("L", size, 0), fade))
    layer = Image.alpha_composite(layer, rim)
    sheen = Image.new("RGBA", size, (0, 0, 0, 0))
    x0, y0, x1, y1 = box
    ImageDraw.Draw(sheen).rounded_rectangle((x0, y0, x1, y0 + (y1 - y0) * 0.45), radius, fill=(255, 255, 255, 22))
    return Image.alpha_composite(layer, sheen.filter(ImageFilter.GaussianBlur(w // 120 + 1)))


def tv_glyph(size, cx, cy, scale):
    """A white TV outline with its stand. Clean: nothing inside the screen."""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    sw, sh = 1.0 * scale, 0.66 * scale
    stroke = max(3, int(scale * 0.07))
    d.rounded_rectangle((cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2), scale * 0.1, outline=(255, 255, 255, 245), width=stroke)
    d.rounded_rectangle((cx - sw * 0.28, cy + sh / 2 + stroke * 1.6, cx + sw * 0.28, cy + sh / 2 + stroke * 2.7),
                        stroke, fill=(255, 255, 255, 245))
    return layer


def icon(px):
    big = px * 4
    img = backdrop(big, big)
    m = big * 0.12
    img = Image.alpha_composite(img, glass_panel((big, big), (m, m, big - m, big - m), big * 0.2))
    img = Image.alpha_composite(img, tv_glyph((big, big), big / 2, big / 2 - big * 0.03, big * 0.5))
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, big - 1, big - 1), big * 0.22, fill=255)
    img.putalpha(mask)
    return img.resize((px, px), Image.LANCZOS)


def banner(w, h):
    big_w, big_h = w * 3, h * 3
    img = backdrop(big_w, big_h)
    pw = big_h * 0.62
    box = ((big_w - pw) / 2, (big_h - pw) / 2, (big_w + pw) / 2, (big_h + pw) / 2)
    img = Image.alpha_composite(img, glass_panel((big_w, big_h), box, pw * 0.22))
    img = Image.alpha_composite(img, tv_glyph((big_w, big_h), big_w / 2, big_h / 2 - big_h * 0.02, pw * 0.56))
    return img.resize((w, h), Image.LANCZOS)


def main():
    res = os.path.join(ROOT, "app", "res", "drawable-nodpi")
    icon(192).save(os.path.join(res, "icon.png"), optimize=True)
    banner(640, 360).convert("RGB").save(os.path.join(res, "banner.png"), optimize=True)
    icon(512).save(os.path.join(ROOT, ".github", "icon.png"), optimize=True)
    print("wrote icon.png, banner.png and .github/icon.png")


if __name__ == "__main__":
    main()
