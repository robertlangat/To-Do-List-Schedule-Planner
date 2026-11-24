"""Turns raw simulator screenshots into App Store marketing screenshots (same pixel size):
gradient background, headline at the top, the screenshot below with rounded corners and a shadow.
Usage: python3 compose.py RAW_DIR OUT_DIR   (RAW_DIR has phone/ (Android), iphone/ or ipad/ subfolders)"""
import os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
BOLD = os.path.join(HERE, "fonts", "Inter-Bold.otf")
MED = os.path.join(HERE, "fonts", "Inter-Medium.otf")

# Order = App Store order. scene -> (headline, subline)
CAPTIONS = [
    ("today", "Know what to do next", "Today, overdue and done - at a glance"),
    ("focus", "Focus on your top 3", "A calm view with big checkboxes"),
    ("calendar", "See your whole month", "Day, week and month views"),
    ("planner", "Plan your day in seconds", "Turn a rough list into a schedule"),
    ("editor", "Reminders & repeats", "Daily, weekly, monthly, yearly - your way"),
    ("mine", "Track your progress", "Today, this week and by list"),
]
TOP, BOTTOM = (0x3D, 0x5A, 0xFE), (0x6A, 0x3D, 0xE8)

def gradient(w, h):
    small = Image.new("RGB", (64, 64))
    p = small.load()
    for y in range(64):
        for x in range(64):
            t = (x * 0.4 + y) / (63 * 1.4)
            p[x, y] = tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
    return small.resize((w, h), Image.BICUBIC)

def fit_text(draw, text, font_path, max_w, size):
    while size > 20:
        f = ImageFont.truetype(font_path, size)
        if draw.textlength(text, font=f) <= max_w:
            return f
        size -= 4
    return ImageFont.truetype(font_path, size)

def compose(raw_path, out_path, headline, sub):
    shot = Image.open(raw_path).convert("RGB")
    W, H = shot.size
    canvas = gradient(W, H)
    d = ImageDraw.Draw(canvas)
    margin = int(W * 0.07)
    hf = fit_text(d, headline, BOLD, W - 2 * margin, int(W * 0.085))
    sf = fit_text(d, sub, MED, W - 2 * margin, int(W * 0.042))
    y = int(H * 0.055)
    d.text((W / 2, y), headline, font=hf, fill="white", anchor="mt")
    y += hf.size + int(H * 0.012)
    d.text((W / 2, y), sub, font=sf, fill=(225, 230, 255), anchor="mt")
    top = y + sf.size + int(H * 0.035)
    # screenshot scaled to fit below the text, bottom bleeds slightly off the canvas
    scale = min((W * 0.84) / W, (H - top + int(H * 0.04)) / H)
    sw, sh = int(W * scale), int(H * scale)
    small = shot.resize((sw, sh), Image.LANCZOS)
    radius = int(sw * 0.075)
    mask = Image.new("L", (sw, sh), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, sw - 1, sh - 1), radius=radius, fill=255)
    x = (W - sw) // 2
    shadow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shadow).rounded_rectangle((x, top + 30, x + sw, top + sh + 30), radius=radius, fill=140)
    canvas.paste((15, 15, 60), (0, 0), shadow.filter(ImageFilter.GaussianBlur(40)))
    # thin device-like border
    border = int(sw * 0.012)
    ImageDraw.Draw(canvas).rounded_rectangle((x - border, top - border, x + sw + border, top + sh + border),
                                             radius=radius + border, fill=(20, 22, 40))
    canvas.paste(small, (x, top), mask)
    canvas.save(out_path, optimize=True)

def main(raw_dir, out_dir):
    for device in ("phone", "iphone", "ipad"):
        src = os.path.join(raw_dir, device)
        if not os.path.isdir(src):
            continue
        dst = os.path.join(out_dir, device)
        os.makedirs(dst, exist_ok=True)
        for i, (scene, head, sub) in enumerate(CAPTIONS, start=1):
            raw = os.path.join(src, f"{scene}.png")
            if os.path.exists(raw):
                compose(raw, os.path.join(dst, f"{i:02d}-{scene}.png"), head, sub)
                print("composed", device, scene)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
