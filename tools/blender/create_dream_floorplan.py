"""Rebuild the Chinese furniture floorplan SVG/PNG. Requires CairoSVG and Noto Sans CJK SC. Run this before create_interaction_map.py."""
from pathlib import Path
from math import sin,cos,radians
import cairosvg
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'assets'/'dream_bedroom'
OUT.mkdir(parents=True,exist_ok=True)
W,H=1760,1280
s=110; ox=525; oy=603
svg=[]
def put(x): svg.append(x)
def text(x,y,t,size=18,fill='#294337',weight=400,anchor='start',family='Noto Sans CJK SC'):
    put(f'<text x="{x}" y="{y}" font-family="{family}, sans-serif" font-size="{size}" font-weight="{weight}" fill="{fill}" text-anchor="{anchor}">{t}</text>')
def rect(x,y,w,h,fill,stroke=None,sw=1,rx=0,extra=''):
    put(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}"'+(f' stroke="{stroke}" stroke-width="{sw}"' if stroke else '')+f' {extra}/>')
def line(x1,y1,x2,y2,color='#768c78',sw=1,extra=''):
    put(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{sw}" {extra}/>')
def circle(x,y,r,fill,stroke=None,sw=1):
    put(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}"'+(f' stroke="{stroke}" stroke-width="{sw}"' if stroke else '')+'/>')
def xy(x,y):return ox+x*s,oy-y*s
def worldbox(x,y,w,d,fill,stroke='#8d7758',rx=8):
    u,v=xy(x,y);rect(u-w*s/2,v-d*s/2,w*s,d*s,fill,stroke,1.6,rx)
def badge(x,y,n):
    circle(x,y,16,'#254936','#f7f4e8',2)
    text(x,y+5,str(n).zfill(2),13,'#ffffff',700,'middle')
def arrow(x,y,yaw,length=55,color='#ad7a35'):
    a=radians(yaw);line(x,y,x+sin(a)*length,y+cos(a)*length,color,3,'marker-end="url(#arrow)"')
put(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">')
put('''<defs>
  <marker id="arrow" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto" markerUnits="strokeWidth"><path d="M0,0 L6,3 L0,6" fill="none" stroke="#ad7a35" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></marker>
  <pattern id="wood" width="25" height="90" patternUnits="userSpaceOnUse"><rect width="25" height="90" fill="#e7dbc4"/><path d="M0 0V90 M25 0V90 M0 45H25" fill="none" stroke="#cdbd9f" stroke-width=".7" opacity=".6"/><path d="M7 3Q13 23 7 42 M18 48Q13 68 19 85" fill="none" stroke="#d1c09f" stroke-width=".7" opacity=".6"/></pattern>
  <pattern id="woven" width="6" height="6" patternUnits="userSpaceOnUse"><rect width="6" height="6" fill="#f3eedc"/><path d="M0 1H6 M1 0V6" fill="none" stroke="#dbd2b4" stroke-width=".6"/></pattern>
  <filter id="shadow" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="5" stdDeviation="5" flood-color="#193d25" flood-opacity=".12"/></filter>
</defs>''')
rect(0,0,W,H,'#f5f4ec')
rect(0,0,W,9,'#294d37')
text(72,66,'FOREST REVERIE',17,'#7b8d72',600)
text(72,122,'森林梦境 · 卧室空间定位图',42,'#264634',700)
text(74,164,'8.00 × 7.00 m  /  GLB · 米制 · Y 向上  /  虚拟人场景导入参考',19,'#6d7e6d')
rect(1426,62,260,68,'#e7eddf',None,rx=15)
text(1556,89,'空间原点：室内地面中心',16,'#50684e',500,'middle')
text(1556,114,'56 m²   ·   净高约 3.4 m',16,'#50684e',500,'middle')
# Plan dimension lines
line(85,196,965,196,'#80917a');line(85,189,85,203,'#80917a');line(965,189,965,203,'#80917a')
rect(464,183,122,25,'#f5f4ec');text(525,202,'8.00 m',18,'#697d65',500,'middle')
line(57,218,57,988,'#80917a');line(50,218,64,218,'#80917a');line(50,988,64,988,'#80917a')
put('<g transform="translate(44 603) rotate(-90)">');rect(-60,-17,120,27,'#f5f4ec');text(0,2,'7.00 m',18,'#697d65',500,'middle');put('</g>')
# Floor and room shell
rect(85,218,880,770,'url(#wood)','#31513c',9,10)
# Rug
u,v=xy(-.1,-.65);circle(u,v,1.60*s,'url(#woven)','#b8b995',2);circle(u,v,1.51*s,'none','#70815c',2);circle(u,v,1.46*s,'none','#70815c',1);circle(u,v,1.37*s,'none','#e1d9bd',2)
for xoff in [-1,1]:
    put(f'<path d="M{u+xoff*120} {v-65} Q{u+xoff*141} {v} {u+xoff*120} {v+65}" fill="none" stroke="#9da77d" stroke-width="2"/>')
# Bed
worldbox(-2.35,1.1,2.32,2.70,'#b98d57','#806645',12)
u,v=xy(-2.35,1.1)
rect(u-112,v-118,224,245,'#f2ebd4','#d1c7a8',1,12)
rect(u-108,v-64,216,183,'#8ca67a','#637d59',1,10)
rect(u-108,v-70,216,20,'#e6e4cb',None,rx=4)
rect(u-109,v+70,218,41,'#4e7657',None,rx=4)
for xx in [-51,51]:
    rect(u+xx-42,v-110,84,45,'#f7f0da','#c8c8a8',1,12)
    rect(u+xx-31,v-88,62,40,'#719664','#557b57',1,10)
rect(u-121,v-146,242,17,'#927247','#755735',1,5)
badge(u-80,v+7,1);text(u+10,v+13,'双人床',21,'#24402f',600,'middle');arrow(u,v+60,0,43)
text(u,v+157,'床头 −Z  ·  床尾 +Z',15,'#67715b',500,'middle')
# Nightstand
worldbox(-.8,2.12,.58,.58,'#be9d69')
u,v=xy(-.8,2.12);circle(u,v,18,'#f7eed3','#aa8f58',2)
# Desk
u,v=xy(1,2.62)
put(f'<path d="M{u-165} {v-46} H{u+165} V{v+44} Q{u+131} {v+62} {u+88} {v+52} Q{u} {v+33} {u-88} {v+52} Q{u-131} {v+62} {u-165} {v+44} Z" fill="#bd945d" stroke="#886a44" stroke-width="2"/>')
rect(u-39,v-23,108,10,'#263e36',None,rx=3);rect(u-27,v+8,67,23,'#657568','#304d3f',1,3)
circle(u-125,v-5,20,'#ede7cf','#a78850',2);circle(u+99,v+18,8,'#fff9e9','#b9a47d',1)
badge(u-129,v+30,6);text(u+63,v+10,'书桌',17,'#503e29',600,'middle')
arrow(u+174,v+1,0,44)
# Desk chair at yaw 180 - seat with back below
u,v=xy(1,1.55)
rect(u-32,v-29,64,63,'#ba9c6c','#856c45',2,10);rect(u-28,v-26,56,47,'#eee5ca','#b4ac8f',1,9);rect(u-32,v+24,64,12,'#917144',None,rx=5)
badge(u+43,v+5,3);arrow(u,v-22,180,40)
# Reading chair, rotation -yaw=65
u,v=xy(2.83,-.12)
put(f'<g transform="translate({u} {v}) rotate(65)">')
rect(-51,-50,102,103,'#c9b796','#9b8a68',2,14);rect(-43,-36,86,79,'#f0e7ce','#c2b89b',1,15)
rect(-49,-53,98,23,'#e7ddbe','#b8ac8b',1,10)
rect(-58,-38,20,88,'#dfd3b3','#b8ac8b',1,9);rect(38,-38,20,88,'#dfd3b3','#b8ac8b',1,9)
rect(-30,-27,60,28,'#7d9a6c',None,rx=7)
put('</g>');badge(u+39,v-49,4);arrow(u-26,v+13,-65,57)
text(u-57,v+94,'阅读 · −65°',16,'#66785f',500,'middle')
# Side table
u,v=xy(3.15,-1.30);circle(u,v,.33*s,'#be9a65','#8b724e',2);rect(u-15,v-12,30,25,'#6a805d','#455d42',1,2)
# Bookcase
worldbox(-3.64,-1.66,.37,1.42,'#ac8757','#795d3b',3)
u,v=xy(-3.64,-1.66)
for j in range(13):rect(u-14,v-67+j*10,29,7,['#62755b','#8b5d40','#c5ad72','#596c70'][j%4],None,rx=1)
badge(u+48,v,12)
text(u+48,v+35,'书架',15,'#65735a',500,'middle')
# TV cabinet, tv, console
worldbox(.3,-2.99,2.65,.52,'#b9925c','#846944',5)
u,v=xy(.3,-3.36);rect(u-108,v-7,216,14,'#243d33',None,rx=5);badge(u-133,v,8)
arrow(u-60,v-8,180,50)
u,v=xy(1.1,-2.96);rect(u-13,v-16,26,32,'#f6f4e9','#77857a',1,4);rect(u-3,v-13,6,27,'#354339',None,rx=2);line(u-3,v-11,u-3,v+4,'#7cc2bb',2)
badge(u+36,v-43,9);line(u+26,v-29,u+8,v-9,'#7f8e77',1)
u,v=xy(.55,-2.94);put(f'<path d="M{u-15} {v-7} Q{u} {v-15} {u+15} {v-7} L{u+18} {v+8} Q{u+12} {v+13} {u+6} {v+4} H{u-6} Q{u-12} {v+13} {u-18} {v+8} Z" fill="#f4f1e4" stroke="#71826f" stroke-width="1.5"/>');badge(u-24,v-63,10);line(u-20,v-48,u,v-11,'#7f8e77',1)
u,v=xy(.3,-2.99);badge(u-132,v-52,11);line(u-121,v-40,u-108,v-15,'#7f8e77',1)
# Window glazing and jambs
line(90,218,960,218,'#285346',14);line(90,218,960,218,'#b6e2ce',7)
line(963,220,963,724,'#285346',14);line(963,220,963,724,'#b6e2ce',7)
for x in [-1.4,1.35]:u,v=xy(x,3.48);rect(u-3,211,6,16,'#315344')
for y in [1.05]:u,v=xy(3.98,y);rect(956,v-3,15,6,'#315344')
badge(521,239,5);text(556,245,'北侧全景玻璃',17,'#3e715a',500);arrow(521,264,0,33)
badge(930,470,7);text(914,504,'东窗',16,'#527b63',500,'middle');arrow(946,546,-90,48)
# Entry gap and inward opening indication
line(151,988,294,988,'#f5f4ec',13);line(151,988,151,848,'#8d977f',3)
put('<path d="M151 845 A143 143 0 0 1 294 988" fill="none" stroke="#8c987f" stroke-width="1.5" stroke-dasharray="6 6"/>')
text(222,1035,'入口 / ENTRY',17,'#56714f',500,'middle')
# Centre datum and carpet annotation
u,v=xy(0,0);line(u-12,v,u+12,v,'#8a9b84',1.5);line(u,v-12,u,v+12,'#8a9b84',1.5);circle(u,v,5,'#f5f4ec','#74896c',1.4)
text(u+16,v-9,'原点 (0,0,0)',13,'#798d70')
u,v=xy(-.1,-.65);badge(u,v+18,2);text(u,v+54,'圆形地毯',23,'#506547',500,'middle');text(u,v+82,'Ø 3.20 m',17,'#7a8769',400,'middle')
# Axis inset and map note
text(80,1095,'GLB 世界坐标 / WORLD',18,'#36593e',600)
line(173,1182,254,1182,'#ad7a35',2.6,'marker-end="url(#arrow)"');text(264,1187,'+X',17,'#65775e',600)
line(173,1182,173,1118,'#ad7a35',2.6,'marker-end="url(#arrow)"');text(145,1132,'−Z',17,'#65775e',600,'middle')
circle(173,1182,7,'#f5f4ec','#526d49',1.5);circle(173,1182,2,'#526d49');text(80,1220,'⊙ +Y 向上',16,'#65775e',500)
text(350,1100,'朝向规则',19,'#36593e',600)
text(350,1136,'箭头表示家具正面 / 使用面；床箭头指向床尾。',16,'#6c7d63')
text(350,1168,'Yaw 以 +Z 为 0°；+90° 朝 +X，180° 朝 −Z。',16,'#6c7d63')
text(350,1200,'窗玻璃箭头指向室内；圆地毯的朝向无需限制。',16,'#6c7d63')
# Right information panel
rect(1030,218,658,824,'#fbfaf5','#e0e4d6',1,18)
text(1060,263,'物件位置与方向',27,'#2d5038',700)
text(1060,294,'位置为 GLB 语义节点原点 (X, Y, Z)，单位：米',16,'#7a8b70')
text(1060,323,'编号 / 物件',14,'#89977f',500)
text(1333,323,'GLB 位置',14,'#89977f',500)
text(1635,323,'Yaw',14,'#89977f',500,'end')
line(1060,339,1658,339,'#dfe5d7')
rows=[
('双人床','FURN_BED','(−2.35, 0, −1.10)','0°'),
('圆形地毯','FURN_RUG','(−0.10, 0, +0.65)','0°'),
('书桌椅','FURN_CHAIR_DESK','(+1.00, 0, −1.55)','180°'),
('阅读休闲椅','FURN_CHAIR_READING','(+2.83, 0, +0.12)','−65°'),
('北侧全景玻璃','GLASS_PANORAMA_NORTH','(0, +0.70, −3.48)','0°'),
('窗边弧形书桌','FURN_DESK','(+1.00, 0, −2.62)','0°'),
('东侧全景玻璃','GLASS_PANORAMA_EAST','(+3.98, +0.70, −1.18)','−90°'),
('电视','FURN_TV','(+0.30, +1.55, +3.36)','180°'),
('游戏机','FURN_GAME_CONSOLE','(+1.10, +0.575, +2.96)','180°'),
('游戏手柄','PROP_CONTROLLER','(+0.55, +0.60, +2.94)','180°'),
('电视矮柜','FURN_MEDIA_CONSOLE','(+0.30, 0, +2.99)','180°'),
('矮书架','FURN_BOOKCASE','(−3.64, 0, +1.66)','90°'),
]
for i,(name,node,coord,yaw) in enumerate(rows):
    y=368+i*55
    if i%2==0:rect(1047,y-22,625,52,'#f0f3e8',None,rx=7)
    circle(1072,y-2,13,'#e0e8d6');text(1072,y+2,str(i+1).zfill(2),12,'#3f6045',700,'middle')
    text(1096,y+1,name,17,'#34553b',600)
    text(1096,y+20,node,10,'#83937b',500,family='DejaVu Sans Mono')
    text(1315,y+2,coord,14,'#586f51',400,family='DejaVu Sans Mono')
    text(1651,y+2,yaw,15,'#9b773c',600,'end')
# Integration note
rect(1030,1064,658,170,'#e6ecdF',None,rx=16)
text(1060,1102,'虚拟人接入说明',20,'#34563c',600)
text(1060,1135,'坐下 / 躺下 / 站立请读取 interactions.json 中的锚点。',16,'#667c5d')
text(1060,1163,'此图标注家具原点；家具原点不等于脚底或骨盆落点。',16,'#667c5d')
text(1060,1191,'坐标换算：GLB (X,Y,Z) = Blender (X,Z,−Y)。',16,'#667c5d')
text(1060,1216,'平面图为位置示意；实际几何边界与坐标以导出模型为准。',13,'#839479')
put('</svg>')
(OUT/'floorplan.svg').write_text('\n'.join(svg),encoding='utf-8')
cairosvg.svg2png(url=str(OUT/'floorplan.svg'),write_to=str(OUT/'floorplan.png'),output_width=W,output_height=H)
print(str(OUT/'floorplan.png'))
