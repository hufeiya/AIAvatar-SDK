# -*- coding: utf-8 -*-
"""VFX 粒子贴图程序化生成器（docs/vfx-feasibility.md §五，先例 icon-gen.py）。

用法: python3 tools/vfx-samples/gen_textures.py [输出目录=app/src/main/assets/vfx/textures]

画质升级版（2026-10-17，docs/vfx-quality-upgrade.md §4）：
  soft_dot.png   柔边圆点（雪/烟/星云底版；高斯衰减 alpha）
  spark.png      四芒星（星光/烟花/应援）
  streak.png     竖向渐变细条（雨丝；顶部亮尾部淡）
  petal.png      花瓣剪影（风吹花瓣；淡粉着色，顶点色白）
  confetti.png   纸屑方块（团播彩带；白底，颜色走顶点色调色板）
  flame_4x4.png  4×4 火焰序列帧（旧版，保留作降级资产）
  flame_8x8.png  8×8=64 帧 1024² 火焰序列帧（fBm 噪声火舌，时序连续）
  snowflake.png  六角雪晶（近景 CROSS 翻滚雪花）
  smoke.png      分形烟团（灰度形状，色调走顶点色）

全部白色/素色 RGB + alpha 形状——材质里 tex × 顶点色，色调统一由预设管。
确定性种子（固定 random.seed / numpy seed），可重复生成对拍。许可证：自制 CC0。
"""
import math
import os
import random
import sys

import numpy as np
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


# ── flame_8x8：高质量火焰序列帧（fBm 噪声火舌，64 帧时序连续）───────────
def _value_noise(w, h, freq, rng):
    """双线性上采样的值噪声网格（freq×freq 格 → w×h），float 0..1。"""
    grid = (rng.random((freq + 1, freq + 1)) * 255).astype("uint8")
    img = Image.fromarray(grid).resize((w, h), Image.BILINEAR)
    return np.asarray(img).astype(np.float32) / 255.0


def _fbm(w, h, rng, base_freq, octaves):
    total = np.zeros((h, w), dtype=np.float32)
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        total += amp * _value_noise(w, h, base_freq * (2 ** o), rng)
        norm += amp
        amp *= 0.55
    return total / norm


def gen_flame_8x8():
    """64 帧 128px/帧：火苗 = 「形状势场 − fBm 噪声」阈值化，噪声域随帧号
    向上滚动（火舌上升）+ 轻微水平摆；白 RGB，颜色走 ColorOverLife。
    噪声在 256² 大域上按帧滚动采样，保证 64 帧时序连续。"""
    N, CELL = 8, 128
    F = N * CELL
    rng = np.random.default_rng(20261017)
    NOISE = 256
    scroll_speed = 0.9      # 帧/格：噪声域每帧上移量（× CELL 换算）
    img = Image.new("RGBA", (F, F), (255, 255, 255, 0))
    # 三个八度的大域噪声（一次生成，按帧滚动采样）
    n1 = _value_noise(NOISE, NOISE, 7, rng)
    n2 = _value_noise(NOISE, NOISE, 13, rng)
    n3 = _value_noise(NOISE, NOISE, 25, rng)

    for f in range(N * N):
        col, row = f % N, f // N
        t = f / float(N * N)                      # 0..1 循环相位
        # 噪声采样窗口：y 向上滚（火舌上升），x 慢移
        oy = int((t * N * CELL * scroll_speed) % (NOISE - CELL))
        ox = int((t * 14.0) % (NOISE - CELL))

        def crop_tall(n, x0, y0):
            # 取矮窗口再拉伸到 CELL：特征纵向拉长（火舌形态）
            win = n[y0:y0 + int(CELL * 0.55), x0:x0 + CELL]
            return np.asarray(
                Image.fromarray((win * 255).astype("uint8")).resize((CELL, CELL), Image.BILINEAR)
            ).astype(np.float32) / 255.0

        noise = 0.50 * crop_tall(n1, ox, oy) + 0.33 * crop_tall(n2, ox, oy) + 0.17 * crop_tall(n3, ox, oy)

        xs = (np.arange(CELL) + 0.5) / CELL * 2.0 - 1.0     # -1..1
        ys = 1.0 - (np.arange(CELL) + 0.5) / CELL * 2.0     # 顶=+1 底=-1
        X, Y = np.meshgrid(xs, ys)

        # 形状势场：底宽顶尖（凹边 profile），轴线由噪声驱动游移（火舌甩动）
        base_y = -0.84
        tip = 0.72 + 0.08 * (float(noise.mean()) - 0.5)
        hgt = np.clip((Y - base_y) / (tip - base_y), 0.0, 1.15)   # 0=底 1=尖
        wander = float(noise[CELL // 2, CELL // 2]) - 0.5
        sway = 0.30 * wander * hgt ** 1.2
        ax = np.abs(X - sway)
        radius = 0.64 * np.clip(1.0 - hgt, 0.0, None) ** 1.1 + 0.05
        shape = 1.0 - ax / radius - hgt * 0.34

        # 锐利噪声蚀刻（火舌分叉/舔舐）+ 内芯增亮
        d = shape - (noise - 0.5) * 0.85
        alpha = np.clip((d - 0.08) / 0.14, 0.0, 1.0)
        core = np.clip((d - 0.32) / 0.30, 0.0, 1.0)
        alpha = np.clip(alpha * (0.55 + 0.45 * core), 0.0, 1.0)

        # 尖端处理：主体在 hgt→1 处纵向淡出（消细针），之上叠独立滚动的
        # 第二噪声场阈值化出的「分离烟舌块」（时有时无，打破锥形轮廓）
        body_fade = np.clip((0.99 - hgt) * 5.0, 0.0, 1.0)
        oy2 = int((t * N * CELL * scroll_speed * 0.62 + 37.0) % (NOISE - int(CELL * 0.55)))
        lick_n = crop_tall(n2, ox, oy2)
        upper = np.clip((Y - 0.10) * 2.6, 0.0, 1.0) * np.clip((1.05 - Y) * 3.0, 0.0, 1.0)
        column = np.clip(1.0 - (ax - 0.18 * wander) / 0.30, 0.0, 1.0)
        licks = np.clip((lick_n - 0.63) * 6.0, 0.0, 1.0) * upper * column
        alpha = np.clip(alpha * body_fade + licks * 0.9, 0.0, 1.0)
        alpha = (alpha * 255).astype("uint8")

        frame = np.zeros((CELL, CELL, 4), dtype="uint8")
        frame[..., 0] = 255
        frame[..., 1] = 255
        frame[..., 2] = 255
        frame[..., 3] = alpha
        # 1px 柔化 + 缩入 6% 格内留白（mipmap 串格防线：REPEAT 寻址下
        # 相邻格火焰在高 mip 互渗，真机目验出现矩形亮边）
        fr = Image.fromarray(frame, "RGBA").filter(ImageFilter.GaussianBlur(0.5))
        pad = int(CELL * 0.06)
        inner = CELL - 2 * pad
        fr = fr.resize((inner, inner), Image.BILINEAR)
        img.paste(fr, (col * CELL + pad, row * CELL + pad))
    save(img, "flame_8x8.png")


# ── snowflake：六角雪晶（近景 CROSS 翻滚雪花，白色形状）─────────────────
def gen_snowflake():
    S = 64
    img = Image.new("RGBA", (S, S), (255, 255, 255, 0))
    d = ImageDraw.Draw(img)
    c = (S - 1) / 2.0
    L = 26.0
    for k in range(6):
        ang = math.pi / 3.0 * k
        dx, dy = math.cos(ang), math.sin(ang)
        # 主枝
        d.line([(c, c), (c + dx * L, c + dy * L)], fill=(255, 255, 255, 235), width=3)
        # 侧枝（两段，向旋转 60° 方向）
        for frac, blen in ((0.45, 9.0), (0.72, 6.0)):
            bx, by = c + dx * L * frac, c + dy * L * frac
            for sgn in (-1, 1):
                bang = ang + sgn * math.pi / 3.0
                d.line([(bx, by), (bx + math.cos(bang) * blen, by + math.sin(bang) * blen)],
                       fill=(255, 255, 255, 210), width=2)
    # 中心六边形小板
    hexpts = [(c + 5.5 * math.cos(math.pi / 3 * k + math.pi / 6),
               c + 5.5 * math.sin(math.pi / 3 * k + math.pi / 6)) for k in range(6)]
    d.polygon(hexpts, fill=(255, 255, 255, 245))
    img = img.filter(ImageFilter.GaussianBlur(0.6))
    save(img, "snowflake.png")


# ── smoke：分形烟团（灰度形状起伏，色调走顶点色/起始色）─────────────────
def gen_smoke():
    S = 128
    rng = np.random.default_rng(20261018)
    n = _fbm(S, S, rng, 4, 4)
    xs = (np.arange(S) + 0.5) / S * 2.0 - 1.0
    X, Y = np.meshgrid(xs, xs)
    r2 = X * X + Y * Y
    radial = np.clip(1.0 - r2, 0.0, 1.0) ** 0.9          # 圆形包络
    alpha = np.clip((n - 0.34) / 0.5, 0.0, 1.0) * radial
    alpha = (alpha * 235).astype("uint8")
    frame = np.zeros((S, S, 4), dtype="uint8")
    frame[..., 0] = 255
    frame[..., 1] = 255
    frame[..., 2] = 255
    frame[..., 3] = alpha
    img = Image.fromarray(frame, "RGBA").filter(ImageFilter.GaussianBlur(1.2))
    save(img, "smoke.png")


# ── magic_circle：地面魔法阵（P2 贴花；同心圆环+刻度+符文点+六芒星，
#    白色 RGB+alpha，色调走 vfx_decal 顶点色；图案含在 UV 圆内）────────────
def gen_magic_circle():
    S = 512
    img = Image.new("RGBA", (S, S), (255, 255, 255, 0))
    d = ImageDraw.Draw(img)
    c = (S - 1) / 2.0
    white = (255, 255, 255, 255)

    def ring(radius, width, alpha):
        d.ellipse([c - radius, c - radius, c + radius, c + radius],
                  outline=(255, 255, 255, alpha), width=width)

    # 多重同心圆环
    ring(244, 6, 235)
    ring(224, 2, 200)
    ring(150, 3, 215)
    ring(96, 2, 190)

    # 外环刻度（60 根短径向线）
    for k in range(60):
        ang = 2.0 * math.pi * k / 60.0
        r0, r1 = 232.0, 242.0
        d.line([(c + r0 * math.cos(ang), c + r0 * math.sin(ang)),
                (c + r1 * math.cos(ang), c + r1 * math.sin(ang))],
               fill=(255, 255, 255, 170), width=2)

    # 中环符文点（12 个小菱形——弱方向性「符文」，避免具象文字）
    for k in range(12):
        ang = 2.0 * math.pi * k / 12.0 + 0.13
        rx, ry = c + 187.0 * math.cos(ang), c + 187.0 * math.sin(ang)
        s = 7.0
        d.polygon([(rx, ry - s), (rx + s, ry), (rx, ry + s), (rx - s, ry)],
                  fill=(255, 255, 255, 225))

    # 内区六芒星（两个错相三角）
    for phase in (0.0, math.pi / 3.0):
        pts = [(c + 144.0 * math.cos(2.0 * math.pi * k / 3.0 + phase - math.pi / 2.0),
                c + 144.0 * math.sin(2.0 * math.pi * k / 3.0 + phase - math.pi / 2.0))
               for k in range(3)]
        d.polygon(pts, outline=(255, 255, 255, 230), width=4)

    # 中心小板 + 光晕点
    d.ellipse([c - 14, c - 14, c + 14, c + 14], outline=white, width=3)
    d.ellipse([c - 4, c - 4, c + 4, c + 4], fill=white)

    img = img.filter(ImageFilter.GaussianBlur(1.0))
    save(img, "magic_circle.png")


# ── shield：护盾穹顶贴图（P2 贴花；横向可平铺六角网+竖向渐隐——u=方位角
#    0..1 环绕，左右边周期一致；明暗靠 CPU Fresnel 顶点色。底光晕必须给足
#    alpha——真机首验：细线低 alpha 在 additive 下不可辨，穹顶「隐身」）────
def gen_shield():
    S = 256
    # 底光晕层：竖向渐隐的整体薄罩（穹顶读作半透明泡，Fresnel 在其上叠亮边）
    ys = (np.arange(S, dtype="float32") + 0.5) / S
    grad = (1.0 - 0.85 * ys)  # v=0（仰角0°=底缘）→ 1.0，v=1（顶缘）→ 0.15
    fill = np.zeros((S, S, 4), dtype="float32")
    fill[..., 0] = 255
    fill[..., 1] = 255
    fill[..., 2] = 255
    fill[..., 3] = 105.0 * grad[:, None]
    img = Image.fromarray(fill.astype("uint8"), "RGBA")

    # 六角网线层（比底晕亮一档）：8 列整除宽度、行错位半个周期（u 周期严格 = S）
    overlay = Image.new("RGBA", (S, S), (255, 255, 255, 0))
    od = ImageDraw.Draw(overlay)
    hex_w = S / 8.0
    hex_h = hex_w * math.sqrt(3.0)
    rows = int(math.ceil(S / (hex_h / 2.0))) + 1
    for row in range(rows):
        y = row * hex_h / 2.0
        xoff = (hex_w / 2.0) if row % 2 else 0.0
        col = -1
        while True:
            x = col * hex_w + xoff
            if x - hex_w / 2.0 > S:
                break
            pts = [(x + (hex_w / 2.0) * 0.92 * math.cos(math.pi / 3.0 * k + math.pi / 6.0),
                    y + (hex_w / 2.0) * 0.92 * math.sin(math.pi / 3.0 * k + math.pi / 6.0))
                   for k in range(6)]
            od.polygon(pts, outline=(255, 255, 255, 210), width=3)
            col += 1
    img = Image.alpha_composite(img, overlay)
    save(img, "shield.png")


if __name__ == "__main__":
    gen_soft_dot()
    gen_spark()
    gen_streak()
    gen_petal()
    gen_confetti()
    # gen_flame()  # 旧 4×4 图集退役（8×8 高密度版替代；mipmap 串格+分辨率不足）
    gen_flame_8x8()
    gen_snowflake()
    gen_smoke()
    gen_magic_circle()
    gen_shield()
    print("done.")
