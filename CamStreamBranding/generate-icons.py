from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(__file__).resolve().parent / "Jyrodoro.png"
APP_RES = ROOT / "CamStreamApp/app/src/main/res"
DESKTOP_ICONS = ROOT / "CamStreamDesktop/build-assets"


def make_artwork():
    image = Image.open(SOURCE).convert("RGBA")
    ImageDraw.floodfill(image, (0, 0), (255, 255, 255, 0), thresh=12)
    bounds = image.getchannel("A").getbbox()
    if bounds is None:
        raise RuntimeError("The supplied artwork has no visible pixels")
    return image.crop(bounds)


def fit_canvas(artwork, size, scale=1.0):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    max_side = int(size * scale)
    art = artwork.copy()
    art.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)
    canvas.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
    return canvas


artwork = make_artwork()

# The launcher foreground stays inside Android's adaptive-icon safe zone.
adaptive = fit_canvas(artwork, 432, 0.64)
adaptive.save(APP_RES / "drawable-nodpi/jyrocam_launcher_foreground.png")

# Android masks the system splash artwork to a circle. Keep the entire character
# inside that safe area; the drawable itself is still centered on black.
fit_canvas(artwork, 1024, 0.54).save(APP_RES / "drawable-nodpi/jyrocam_splash_logo.png")

# The app's own loading activity uses an unmasked drawable so no artwork is cropped.
fit_canvas(artwork, 1024, 0.92).save(APP_RES / "drawable-nodpi/jyrocam_splash_full.png")

# Legacy launcher icons for pre-adaptive-icon Android launchers.
for density, size in {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}.items():
    art = artwork.copy()
    art.thumbnail((int(size * 0.94), int(size * 0.94)), Image.Resampling.LANCZOS)
    icon = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    icon.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
    out_dir = APP_RES / f"mipmap-{density}"
    icon.save(out_dir / "ic_launcher.png")
    icon.save(out_dir / "ic_launcher_round.png")

# Windows executable icon: transparent background, multiple shell-friendly sizes.
windows_icon = fit_canvas(artwork, 256, 0.96)
windows_icon.save(
    DESKTOP_ICONS / "jyrocam.ico",
    format="ICO",
    sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
)

print(f"Generated JyroCam icon assets from {SOURCE}")
