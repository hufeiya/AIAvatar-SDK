# -*- coding: utf-8 -*-
"""VFX 粒子贴图程序化生成器（docs/vfx-feasibility.md §五，先例 icon-gen.py）。

用法: python3 tools/vfx-samples/gen_textures.py [输出目录=app/src/main/assets/vfx/textures]

6 张 128² 级小贴图（flame 256²）：
  soft_dot.png   柔边圆点（雪/烟/星云底版；高斯衰减 alpha）
  spark.png      四芒星（星光/烟花/应援）
  streak.png     竖向渐变细条（雨丝；顶部亮尾部淡）
  petal.png      花瓣剪影（风吹花瓣；淡粉着色，顶点色白）
  confetti.png   纸屑方块（团播彩带；白底，颜色走顶点色调色板）
  flame_4x4.png  4×4 火焰序列帧（flipbook；白色火苗剪影，颜色走 ColorOverLife）

全部白色/素色 RGB + alpha 形状——材质里 tex × 顶点色，色调统一由预设管。
确定性种子（固定 random.seed），可重复生成对拍。许可证：自制 CC0。
"""
import math
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "vfx", "textures")

SIZE = 128


def new_canvas(w=SIZE, h=SIZE):
    """直通 alpha 画布（RGBA 白底 alpha 0；贴图不做 premultiply）。"""
    img = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    return img, ImageDraw.Draw(img)


def save(img, name):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    img.save(path)
    print(f"wrote {path} {img.size[0]}x{img.size[1]}")


# ── soft_dot：高斯衰减柔边圆点 ────────────────────────────────────────────
def gen_soft_dot():
    img = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 0))
    px = img.load()
    half = (SIZE - 1) / 2.0
    for y in range(SIZE):
        for x in range(SIZE):
            dx = (x - half) / half
            dy = (y - half) / half
            r2 = dx * dx + dy * dy
            a = 0 if r2 >= 1.0 else math.exp(-4.5 * r2)
            px[x, y] = (255, 255, 255, int(a * 255 + 0.5))
    save(img, "soft_dot.png")


# ── spark：四芒星（长芒 + 辉光晕 + 中心亮点）────────────────────────────
def gen_spark():
    img = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 0))
    c = (SIZE - 1) / 2.0
    # 辉光底层：柔边圆
    glow = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([c - 34, c - 34, c + 34, c + 34], fill=(255, 255, 255, 90))
    glow = glow.filter(ImageFilter.GaussianBlur(14))
    img.alpha_composite(glow)
    # 四芒：细长菱形（横竖两根），尖端渐细用多段宽度逼近
    d = ImageDraw.Draw(img)
    for axis in ("h", "v"):
        L = 58.0
        w = 3.2
        for i in range(24):
            t = i / 23.0
            ww = w * (1.0 - t) ** 1.5 + 0.4
            if axis == "h":
                for sx in (+1, -1):
                    x0 = c + sx * t * L
                    d.polygon([(x0, c - ww), (x0 + sx * L / 23 + sx * 0.5, c),
                               (x0, c + ww)], fill=(255, 255, 255, 235))
            else:
                for sy in (+1, -1):
                    y0 = c + sy * t * L
                    d.polygon([(c - ww, y0), (c, y0 + sy * L / 23 + sy * 0.5),
                               (c + ww, y0)], fill=(255, 255, 255, 235))
    # 中心亮点
    d.ellipse([c - 6, c - 6, c + 6, c + 6], fill=(255, 255, 255, 255))
    d.ellipse([c - 10, c - 10, c + 10, c + 10], outline=(255, 255, 255, 160), width=2)
    # 45° 短芒
    for sx, sy in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        for i in range(10):
            t = i / 9.0
            ww = 1.8 * (1.0 - t) + 0.3
            x0 = c + sx * t * 26
            y0 = c + sy * t * 26
            d.polygon([(x0 - ww, y0), (x0 + sx * 3, y0 + sy * 3), (x0 + ww, y0 + sy * 1.5)],
                      fill=(255, 255, 255, 150))
    save(img, "spark.png")


# ── streak：竖向渐变雨丝（顶部雨滴头亮，向下收细渐淡）────────────────────
def gen_streak():
    W, H = 64, 256
    img = Image.new("RGBA", (W, H), (255, 255, 255, 0))
    px = img.load()
    cx = (W - 1) / 2.0
    for y in range(H):
        t = y / (H - 1.0)
        # 纵向：头部（t=0，雨滴）亮，尾部渐淡
        fade = (1.0 - t) ** 1.35
        head = 1.0 if t < 0.10 else 1.0
        a_row = 255 * fade * head
        # 横向：软边条（宽度上宽下窄），中心最亮
        half_w = 3.2 * (1.0 - 0.55 * t)
        for x in range(W):
            dx = abs(x - cx) / max(half_w, 1e-3)
            if dx >= 1.0:
                continue
            core = math.exp(-3.2 * dx * dx)
            a = a_row * core
            if a > 0.5:
                px[x, y] = (255, 255, 255, int(a + 0.5))
    # 雨滴头部：小圆点加重
    d = ImageDraw.Draw(img)
    d.ellipse([cx - 4, 0, cx + 4, 12], fill=(255, 255, 255, 235))
    save(img, "streak.png")


# ── petal：花瓣剪影（泪滴形，淡粉着色，边缘微透）─────────────────────────
def gen_petal():
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 花瓣：贝塞尔泪滴（尖端在下，圆头在上），自带淡粉色（顶点色为白）
    pink = (255, 214, 224)
    body = [(64, 14), (98, 40), (100, 84), (64, 116), (28, 84), (30, 40)]
    d.polygon(body, fill=pink + (235,))
    # 中脉：淡色细线增加剪影可读性
    d.line([(64, 26), (64, 108)], fill=(255, 255, 255, 140), width=3)
    # 柔化边缘 1px（抗锯齿观感）
    img = img.filter(ImageFilter.GaussianBlur(0.8))
    # 重新压实 alpha 边缘（blur 后边缘半透明属预期）
    save(img, "petal.png")


# ── confetti：纸屑方块（白底微圆角，颜色由顶点色调色板提供）───────────────
def gen_confetti():
    img = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 0))
    d = ImageDraw.Draw(img)
    m = 18
    d.rounded_rectangle([m, m, SIZE - 1 - m, SIZE - 1 - m], radius=10,
                        fill=(255, 255, 255, 255))
    # 中央亮折痕（纸片质感）
    d.line([(m, m), (SIZE - 1 - m, SIZE - 1 - m)], fill=(255, 255, 255, 210), width=4)
    img = img.filter(ImageFilter.GaussianBlur(0.6))
    save(img, "confetti.png")


# ── flame_4x4：火焰序列帧（白色火苗剪影，逐帧摇曳上升）───────────────────
def gen_flame():
    N = 4
    F = 256
    cell = F // N
    img = Image.new("RGBA", (F, F), (255, 255, 255, 0))
    random.seed(20261017)  # 确定性：可重复生成对拍
    for f in range(N * N):
        col, row = f % N, f // N
        x0, y0 = col * cell, row * cell
        frame = Image.new("RGBA", (cell, cell), (255, 255, 255, 0))
        fd = ImageDraw.Draw(frame)
        # 火苗 = 沿中轴堆叠的横椭圆（底宽顶尖）+ 内芯 + 底盘光斑；
        # 相位=f：幼焰矮胖 → 老焰瘦高摇曳，随机项让相邻帧形状可分
        random.seed(1000 + f)
        sway = math.sin(f * 2.1) * 5.0 + random.uniform(-2.0, 2.0)
        base_y = cell * 0.82
        height = cell * (0.50 + 0.08 * math.sin(f * 1.3))
        width = cell * 0.22
        # 外焰：12 段堆叠，顶段收窄到 12% + 风摆随高度放大（火舌弯腰）
        steps = 12
        for i in range(steps):
            t = i / (steps - 1.0)          # 0=底 1=尖
            taper = (1.0 - t) ** 1.6        # 尖端快速收窄
            cw = width * (0.35 + 0.65 * taper)
            cy = base_y - height * t
            ox = sway * (t ** 1.5)
            a = int(235 * (1.0 - 0.55 * t ** 2))
            fd.ellipse([cell / 2 + ox - cw, cy - cw * 0.9,
                        cell / 2 + ox + cw, cy + cw * 0.9],
                       fill=(255, 255, 255, a))
        # 内芯（亮实心，中下部）
        cw = width * 0.5
        fd.ellipse([cell / 2 + sway * 0.15 - cw, base_y - height * 0.42 - cw,
                    cell / 2 + sway * 0.15 + cw, base_y - height * 0.42 + cw * 1.3],
                   fill=(255, 255, 255, 250))
        # 底盘光斑
        fd.ellipse([cell / 2 - width * 1.1, base_y - cell * 0.05,
                    cell / 2 + width * 1.1, base_y + cell * 0.05],
                   fill=(255, 255, 255, 130))
        frame = frame.filter(ImageFilter.GaussianBlur(1.0))
        img.paste(frame, (x0, y0))
    save(img, "flame_4x4.png")


if __name__ == "__main__":
    gen_soft_dot()
    gen_spark()
    gen_streak()
    gen_petal()
    gen_confetti()
    gen_flame()
    print("done.")
