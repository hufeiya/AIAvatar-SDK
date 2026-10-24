"""Rebuild the Chinese interaction SVG/PNG from the latest interactions.json and floorplan.svg. Requires CairoSVG and Noto Sans CJK SC."""
import json,re,math,html
from pathlib import Path
import cairosvg
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'assets'/'dream_bedroom'
meta=json.loads((OUT/'interactions.json').read_text())
base=(OUT/'floorplan.svg').read_text()
defs=re.search(r'<defs>.*?</defs>',base,re.S).group()
plan=base[base.index('<rect x="85" y="218"'):base.index('<text x="80" y="1095"')]
plan=re.sub(r'<text .*?</text>','',plan,flags=re.S)
plan=re.sub(r'<circle[^>]* r="16"[^>]*/>','',plan)
plan=re.sub(r'<line[^>]*stroke="#(?:ad7a35|7f8e77)"[^>]*/>','',plan)
S=[]
def put(s):S.append(s)
def text(x,y,t,size=18,fill='#294337',weight=400,anchor='start',family='Noto Sans CJK SC'):
    put(f'<text x="{x}" y="{y}" font-family="{family}, sans-serif" font-size="{size}" font-weight="{weight}" fill="{fill}" text-anchor="{anchor}">{html.escape(t)}</text>')
def rect(x,y,w,h,fill,stroke=None,sw=1,rx=0):
    put(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}"'+(f' stroke="{stroke}" stroke-width="{sw}"' if stroke else '')+'/>')
def line(x1,y1,x2,y2,color,sw=1,extra=''):
    put(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{sw}" {extra}/>')
def circle(x,y,r,fill,stroke=None,sw=1):
    put(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}"'+(f' stroke="{stroke}" stroke-width="{sw}"' if stroke else '')+'/>')
def xy(p):return 525+p[0]*110,603+p[2]*110
def fmt(v):
    v=round(v,6)
    if v==0:return '0'
    return f'{v:.6f}'.rstrip('0').rstrip('.').replace('-','−')
def coord(p):return '('+', '.join(fmt(v) for v in p)+')'
def color(a):
    return {'buttock_surface':'#bc8138','back_hip_surface':'#557b9b','head_surface':'#557b9b','hand_grip':'#88608e'}.get(a['contact'],'#2f7354')
def type_name(a):
    return {'buttock_surface':'臀部接触','back_hip_surface':'背/髋接触','head_surface':'头部接触','hand_grip':'手部抓取'}.get(a['contact'],'脚底落点')
put('<svg xmlns="http://www.w3.org/2000/svg" width="1960" height="1470" viewBox="0 0 1960 1470">')
put(defs)
put('<defs>')
for c in ['2f7354','bc8138','557b9b','88608e']:
    put(f'<marker id="a{c}" markerWidth="7" markerHeight="7" refX="5.5" refY="3" orient="auto" markerUnits="strokeWidth"><path d="M0,0 L5.5,3 L0,6" fill="none" stroke="#{c}" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></marker>')
put('</defs>')
rect(0,0,1960,1470,'#f5f4ec');rect(0,0,1960,9,'#294d37')
text(72,66,'FOREST REVERIE / INTERACTION ATLAS',17,'#7b8d72',600)
text(72,122,'森林梦境 · 虚拟人交互锚点图',42,'#264634',700)
text(74,164,'18 个接触锚点  /  GLB 世界坐标  /  位置、朝向与姿态对齐',19,'#6d7e6d')
rect(1626,62,260,68,'#e7eddf',rx=15)
text(1756,89,'数据来源：interactions.json',16,'#50684e',500,'middle')
text(1756,114,'1 Blender unit = 1 m',16,'#50684e',500,'middle')
line(85,196,965,196,'#80917a');line(85,189,85,203,'#80917a');line(965,189,965,203,'#80917a')
rect(464,183,122,25,'#f5f4ec');text(525,202,'8.00 m',18,'#697d65',500,'middle')
line(57,218,57,988,'#80917a');line(50,218,64,218,'#80917a');line(50,988,64,988,'#80917a')
put('<g transform="translate(44 603) rotate(-90)">');rect(-60,-17,120,27,'#f5f4ec');text(0,2,'7.00 m',18,'#697d65',500,'middle');put('</g>')
put('<g opacity=".57">'+plan+'</g>')
text(252,601,'双人床',20,'#89927c',500,'middle');text(740,345,'书桌',20,'#7c8873',500,'middle')
text(869,690,'阅读椅',18,'#829176',500,'middle');text(558,1023,'电视',17,'#829176',500,'middle')
text(525,245,'北侧全景玻璃',17,'#7f9b85',500,'middle');text(222,1035,'入口 / ENTRY',17,'#56714f',500,'middle')
# Diagram graph, using only the edges supplied by metadata.
anchors={a['id']:a for a in meta['anchors']}
for p,q in meta['navigation']['edges']:
    x1,y1=xy(anchors[p]['gltfPosition']);x2,y2=xy(anchors[q]['gltfPosition'])
    line(x1,y1,x2,y2,'#93aa8a',2.4,'stroke-dasharray="5 7" opacity=".9"')
# Show the linkage of the bed longitudinal contact references.
x1,y1=xy(anchors['BED_LIE']['gltfPosition']);x2,y2=xy(anchors['BED_HEAD']['gltfPosition'])
line(x1,y1,x2,y2,'#819aae',1.7,'stroke-dasharray="3 5"')
for i,a in enumerate(meta['anchors'],1):
    x,y=xy(a['gltfPosition']);c=color(a);angle=math.radians(a['yawDeg'])
    length=34 if a['id']=='AISLE_EAST_LOWER' else 42
    dx,dy=math.sin(angle),math.cos(angle)
    # Arrow begins outside the contact dot; dot centre remains exact.
    line(x+dx*17,y+dy*17,x+dx*length,y+dy*length,c,2.7,f'marker-end="url(#a{c[1:]})"')
    circle(x,y,17,'#fbfaf5',c,2.7)
    text(x,y+5,f'{i:02d}',14,c,700,'middle')
# Legend / axes below plan.
text(80,1090,'图例与坐标',22,'#36593e',700)
for x,y,c,t in [(92,1128,'#2f7354','脚底 / 站立 / 接近 / 通道'),(92,1164,'#bc8138','臀部 / 座面'),(442,1128,'#557b9b','躺卧 / 头部表面'),(442,1164,'#88608e','手部 / 抓取参考')]:
    circle(x,y-5,9,'#fbfaf5',c,2);text(x+22,y,t,17,'#667b5e',500)
line(80,1211,141,1211,'#93aa8a',2.4,'stroke-dasharray="5 7"');text(157,1217,'导航连接（需结合项目碰撞与动画处理）',17,'#667b5e')
# Compact orientation compass.
line(821,1152,883,1152,'#ad7a35',2.4,'marker-end="url(#arrow)"');text(893,1158,'+X',16,'#65775e',600)
line(821,1152,821,1107,'#ad7a35',2.4,'marker-end="url(#arrow)"');text(821,1097,'−Z',16,'#65775e',600,'middle')
circle(821,1152,6,'#f5f4ec','#526d49',1.5);circle(821,1152,2,'#526d49');text(821,1190,'⊙ +Y 向上',15,'#65775e',500,'middle')
rect(80,1250,885,166,'#e6ecdf',rx=16)
text(106,1289,'先匹配接触部位，再平移虚拟人根节点',21,'#34563c',700)
text(106,1323,'图中圆点＝精确接触点；箭头＝ yaw 朝向。位置不等于 Avatar Root。',17,'#667c5d')
text(106,1354,'躺卧点 05 的箭头表示脚→头方向；需使用躺卧动画及骨骼接触对齐。',17,'#667c5d')
text(106,1385,'坐下 / 躺下 / 抓取点不作为导航终点，应先走到对应的「站立点」。',17,'#667c5d')
# Coordinate table: directly derived from exported metadata; no rounding beyond its precision.
rect(1030,218,856,1198,'#fbfaf5','#e0e4d6',1,18)
text(1060,263,'18 个锚点 · GLB (X, Y, Z) 米',27,'#2d5038',700)
text(1060,296,'Yaw 绕 +Y；0° = +Z，90° = +X，180° = −Z',16,'#7a8b70')
text(1060,327,'编号 / 交互用途',14,'#89977f',500);text(1404,327,'接触位置 · GLB',14,'#89977f',500);text(1849,327,'Yaw',14,'#89977f',500,'end')
line(1060,341,1856,341,'#dfe5d7')
# Slightly shorter display labels are unique and retain full semantic distinction.
labels={
'SPAWN':'中央出生 / 站立', 'RUG_WATCH_TV':'地毯观看电视', 'BED_APPROACH':'床边站立', 'BED_SIT':'床沿坐姿接触',
'BED_LIE':'床上躺卧髋部接触', 'BED_HEAD':'枕头头部接触', 'DESK_APPROACH':'书桌椅前站立', 'DESK_SEAT':'书桌椅座面中心',
'READING_APPROACH':'阅读椅前站立', 'READING_SEAT':'阅读椅座面中心', 'WINDOW_VIEW':'窗前看森林', 'TV_APPROACH':'电视柜前站立',
'GAME_APPROACH':'游戏机前站立', 'GAME_PICKUP':'游戏手柄抓取参考', 'ENTRY':'门内入口', 'AISLE_SOUTH':'床尾通道',
'AISLE_EAST_LOWER':'东侧通道转折', 'AISLE_EAST_UPPER':'东侧窗边通道'}
for i,a in enumerate(meta['anchors']):
    y=372+i*55
    if i%2==0:rect(1047,y-22,822,53,'#f0f3e8',rx=7)
    c=color(a);circle(1071,y,13,'#fbfaf5',c,1.5);text(1071,y+4,f'{i+1:02d}',12,c,700,'middle')
    text(1095,y+2,labels[a['id']],17,'#34553b',600)
    text(1095,y+21,a['id']+' · '+type_name(a),11,'#83937b',500)
    text(1396,y+2,coord(a['gltfPosition']),15,'#586f51',400,family='DejaVu Sans Mono')
    text(1849,y+2,fmt(a['yawDeg'])+'°',16,c,600,'end')
line(1060,1364,1856,1364,'#dfe5d7')
text(1060,1394,'SDK 场景偏移另见 metadata：sdkWorld = GLB + (0, −1, −4)。',15,'#7e8f73')
text(80,1450,'编号仅对应本图的交互锚点；家具编号见 floorplan.png。原始字段与小数精度以 interactions.json 为准。',14,'#8a987f')
put('</svg>')
(OUT/'interaction_map.svg').write_text('\n'.join(S),encoding='utf-8')
cairosvg.svg2png(url=str(OUT/'interaction_map.svg'),write_to=str(OUT/'interaction_map.png'))
print('Generated 18 anchors from interactions.json:', OUT/'interaction_map.png')
# Validate furniture values supplied in the original floorplan against the metadata.
expected={
'FURN_BED':([-2.35,0,-1.1],0), 'FURN_RUG':([-.1,0,.65],0), 'FURN_CHAIR_DESK':([1,0,-1.55],180),
'FURN_CHAIR_READING':([2.83,0,.12],-65), 'GLASS_PANORAMA_NORTH':([0,.7,-3.48],0), 'FURN_DESK':([1,0,-2.62],0),
'GLASS_PANORAMA_EAST':([3.98,.7,-1.18],-90), 'FURN_TV':([.3,1.55,3.36],180), 'FURN_GAME_CONSOLE':([1.1,.575,2.96],180),
'PROP_CONTROLLER':([.55,.6,2.94],180), 'FURN_MEDIA_CONSOLE':([.3,0,2.99],180), 'FURN_BOOKCASE':([-3.64,0,1.66],90)}
objects={o['node']:o for o in meta['objects']}
for node,(p,yaw) in expected.items():
    assert all(abs(a-b)<1e-6 for a,b in zip(p,objects[node]['gltfOrigin'])),node
    assert yaw==objects[node]['yawDeg'],node
assert len(meta['anchors'])==18
print('Verified all 12 original floorplan furniture coordinates and yaw values; original files untouched.')
