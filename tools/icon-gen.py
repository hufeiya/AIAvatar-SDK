# -*- coding: utf-8 -*-
"""AIAvatar 启动图标生成器 v2：霓虹虚拟人头像（波波头剪影版）"""
import cairosvg, sys

# 用法: python3 tools/icon-gen.py [输出目录]
# 渲染 4 张母版: fg.png(自适应前景) bg.png(自适应背景) full.png(整图→logo/旧版图标) mono.png(单色)
OUT = sys.argv[1] if len(sys.argv) > 1 else "build/icon"

NEON = ('<linearGradient id="neon" x1="0" y1="0" x2="0" y2="1">'
        '<stop offset="0" stop-color="#55F2FF"/>'
        '<stop offset="0.52" stop-color="#8A7CFF"/>'
        '<stop offset="1" stop-color="#FF7ACD"/></linearGradient>')
BG = ('<linearGradient id="bgg" x1="0" y1="0" x2="1" y2="1">'
      '<stop offset="0" stop-color="#060A24"/>'
      '<stop offset="0.45" stop-color="#10173F"/>'
      '<stop offset="1" stop-color="#331B72"/></linearGradient>'
      '<radialGradient id="halo" cx="0.5" cy="0.47" r="0.46">'
      '<stop offset="0" stop-color="#7C5CFF" stop-opacity="0.30"/>'
      '<stop offset="1" stop-color="#7C5CFF" stop-opacity="0"/></radialGradient>')

def glow(path, w, color="url(#neon)", op1=0.14, op2=0.36):
    return (f'<path d="{path}" fill="none" stroke="{color}" stroke-width="{w*2.6:.0f}" '
            f'stroke-linecap="round" stroke-linejoin="round" opacity="{op1}"/>'
            f'<path d="{path}" fill="none" stroke="{color}" stroke-width="{w*1.6:.0f}" '
            f'stroke-linecap="round" stroke-linejoin="round" opacity="{op2}"/>'
            f'<path d="{path}" fill="none" stroke="{color}" stroke-width="{w}" '
            f'stroke-linecap="round" stroke-linejoin="round"/>')

# 波波头发型：闭合剪影（外轮廓 → 发梢 → 内缘刘海线）
HAIR = ("M 302 620 C 276 558 266 496 268 428 C 272 306 372 222 512 222 "
        "C 652 222 752 306 756 428 C 758 496 748 558 722 620 "
        "C 704 598 676 560 668 486 C 664 444 662 400 640 370 "
        "C 606 340 566 330 512 330 C 458 330 418 340 384 370 "
        "C 362 400 360 444 356 486 C 348 560 320 598 302 620 Z")
HAIR_EDGE   = HAIR[:-2]  # 去掉 Z 的开放路径用于描边
AHOGE = "M 500 224 C 492 194 504 168 536 156 C 516 176 512 200 520 224"
FACE   = ("M 512 344 C 584 344 640 380 644 444 C 646 484 632 512 610 542 "
          "C 592 566 556 586 512 596 C 468 586 432 566 414 542 "
          "C 392 512 378 484 380 444 C 384 380 440 344 512 344")
EYE_R  = "M 550 470 C 560 482 580 484 594 472"
EYE_L  = "M 474 470 C 464 482 444 484 430 472"
LIPS   = "M 490 546 Q 512 560 534 546"
NECK_R = "M 544 606 C 543 628 547 646 556 658"
NECK_L = "M 480 606 C 481 628 477 646 468 658"
SHOULDER = "M 350 762 C 392 700 446 672 512 672 C 578 672 632 700 674 762"

def art(neon=True, mono=False):
    eye = "#9FF8FF" if neon else "#FFFFFF"
    s = []
    # 轨道环 + 轨道点
    if neon:
        s.append('<circle cx="512" cy="500" r="318" fill="none" stroke="url(#neon)" '
                 'stroke-width="6" stroke-dasharray="700 296" stroke-dashoffset="-330" '
                 'stroke-linecap="round" opacity="0.55"/>')
        for cx in (240, 784):
            s.append(f'<circle cx="{cx}" cy="344" r="20" fill="{eye}" opacity="0.16"/>')
            s.append(f'<circle cx="{cx}" cy="344" r="9" fill="{eye}" opacity="0.9"/>')
    else:
        s.append('<circle cx="512" cy="500" r="318" fill="none" stroke="#FFFFFF" '
                 'stroke-width="10" stroke-dasharray="700 296" stroke-dashoffset="-330" '
                 'stroke-linecap="round"/>')
    # 头发：深色填充块 + 霓虹描边
    if mono:
        s.append(f'<path d="{HAIR}" fill="#FFFFFF" fill-opacity="0.95"/>')
    else:
        s.append('<linearGradient id="hairg" x1="0" y1="0" x2="0" y2="1">'
                 '<stop offset="0" stop-color="#6E4FE8" stop-opacity="0.50"/>'
                 '<stop offset="1" stop-color="#241352" stop-opacity="0.92"/></linearGradient>')
        s.append(f'<path d="{HAIR}" fill="url(#hairg)"/>')
        s.append(f'<path d="{HAIR}" fill="url(#neon)" fill-opacity="0.10"/>')
    s.append(glow(HAIR_EDGE, 22, "url(#neon)" if neon else "#FFFFFF"))
    s.append(glow(AHOGE, 13, "url(#neon)" if neon else "#FFFFFF"))
    # 五官与身体
    s.append(glow(FACE, 19, "url(#neon)" if neon else "#FFFFFF"))
    s.append(glow(EYE_R, 14, eye))
    s.append(glow(EYE_L, 14, eye))
    s.append(glow(LIPS, 11, "#FF9BDD" if neon else "#FFFFFF"))
    s.append(glow(NECK_R, 18, "url(#neon)" if neon else "#FFFFFF"))
    s.append(glow(NECK_L, 18, "url(#neon)" if neon else "#FFFFFF"))
    s.append(glow(SHOULDER, 24, "url(#neon)" if neon else "#FFFFFF"))
    # 腮红
    if neon:
        for cx in (438, 586):
            s.append(f'<circle cx="{cx}" cy="520" r="13" fill="#FF7ACD" opacity="0.30"/>')
            s.append(f'<circle cx="{cx}" cy="520" r="24" fill="#FF7ACD" opacity="0.12"/>')
    # 漂浮数据粒子
    if neon:
        pts = [(318,304,7,'#55F2FF',0.8),(716,274,5,'#FF7ACD',0.8),(772,474,9,'#8A7CFF',0.45),
               (256,478,8,'#8A7CFF',0.45),(585,206,5,'#FFFFFF',0.7),(302,704,5,'#FF7ACD',0.5),
               (722,704,5,'#55F2FF',0.5)]
        for x,y,r,c,o in pts:
            s.append(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{c}" opacity="{o}"/>')
        for x,y in ((250,256),(778,604)):
            s.append(f'<path d="M {x-16} {y} h 32 M {x} {y-16} v 32" stroke="#FFFFFF" '
                     f'stroke-width="7" stroke-linecap="round" opacity="0.5"/>')
    return "\n".join(s)

def fg_svg():
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
<defs>{NEON}</defs>
<g transform="translate(512 512) scale(0.955) translate(-512 -516)">
{art()}
</g></svg>'''

def bg_svg():
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
<defs>{BG}</defs>
<rect width="1024" height="1024" fill="url(#bgg)"/>
<rect width="1024" height="1024" fill="url(#halo)"/>
</svg>'''

def full_svg():
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
<defs>{NEON}{BG}</defs>
<rect width="1024" height="1024" fill="url(#bgg)"/>
<rect width="1024" height="1024" fill="url(#halo)"/>
<g transform="translate(512 512) scale(0.955) translate(-512 -516)">
{art()}
</g></svg>'''

def mono_svg():
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
<g transform="translate(512 512) scale(0.955) translate(-512 -516)">
{art(neon=False, mono=True)}
</g></svg>'''

def render(svg, path, size):
    cairosvg.svg2png(bytestring=svg.encode(), write_to=path, output_width=size, output_height=size)

render(fg_svg(),   f"{OUT}/fg.png", 1024)
render(bg_svg(),   f"{OUT}/bg.png", 1024)
render(full_svg(), f"{OUT}/full.png", 1024)
render(mono_svg(), f"{OUT}/mono.png", 512)
print("v2 rendered")
