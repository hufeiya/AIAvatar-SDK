# Executed by build_dream_bedroom.py in its construction namespace.
bed=group('FURN_BED','双人床',(-2.35,1.1,0),0)
cube('Bed oak platform',(0,0,.28),(2.16,2.62,.24),oak,.075,bed)
cube('Bed floating recess',(0,0,.13),(1.8,2.22,.25),walnut,.03,bed)
for x in [-.86,.86]:
    for y in [-1.06,1.06]:cylinder('Bed turned leg',(x,y,.16),.065,.32,walnut,bed)
cube('Padded mattress',(0,0,.5),(2.02,2.43,.27),cream,.125,bed)
cube('Oak headboard',(0,1.27,.8),(2.23,.10,1.24),oak,.07,bed)
cube('Headboard upholstered inset',(0,1.205,.99),(1.99,.07,.63),velvet,.09,bed)
for x in [-.72,-.36,0,.36,.72]:
    curve('Headboard stitching',[(x,1.16,.72),(x,1.16,1.24)],.003,gold,bed)
# A draped, undulating duvet: detailed surface, hangs naturally at both sides.
nx,ny=48,60;v=[];f=[]
for j in range(ny+1):
    t=j/ny;y=-1.35+t*2.22
    for i in range(nx+1):
        u=-1.16+i/nx*2.32
        drop=max(0,(abs(u)-.92)/.24)**1.45*.33+max(0,(-y-1.08)/.27)*.15
        z=.725-drop+.023*math.sin(u*14+y*9)+.015*math.cos(u*24-y*10)+.012*math.sin(y*23+u*4)
        v.append((u,y,z))
for j in range(ny):
    for i in range(nx):a=j*(nx+1)+i;f.append((a,a+1,a+nx+2,a+nx+1))
o=mesh('Draped moss linen duvet',v,f,linen,bed)
for p in o.data.polygons:p.use_smooth=True
m=o.modifiers.new('Duvet thickness','SOLIDIFY');m.thickness=.018
cube('Turned duvet hem',(0,.76,.67),(1.98,.24,.055),cream,.035,bed)
for x,tilt in [(-.51,-.08),(.51,.08)]:
    o=cube('Large soft pillow',(x,.90,.79),(.88,.57,.24),cream,.115,bed);o.rotation_euler.z=tilt
    o=cube('Forest scatter pillow',(x,.67,.85),(.63,.40,.26),linen,.115,bed);o.rotation_euler.z=tilt*.7
o=cube('Bolster',(0,.43,.83),(.9,.24,.23),velvet,.10,bed)
# Draped narrow throw with fringe across foot.
v=[];f=[]
for j in range(11):
    for i in range(51):
        x=-1.2+i/50*2.4;y=-1.03+j/10*.43
        z=.811-.36*max(0,(abs(x)-.94)/.26)+.017*math.sin(x*34+y*16)
        v.append((x,y,z))
for j in range(10):
    for i in range(50):a=j*51+i;f.append((a,a+1,a+52,a+51))
o=mesh('Forest knitted throw',v,f,velvet,bed)
for p in o.data.polygons:p.use_smooth=True
for i in range(36):
    x=-1.15+i/35*2.3;z=.811-.36*max(0,(abs(x)-.94)/.26)
    curve('Throw tassel',[(x,-1.02,z),(x+.014,-1.12,z-.02)],.006,velvet,bed)

night=group('FURN_NIGHTSTAND','床头柜',(-.80,2.12,0),0)
cube('Bedside cabinet',(0,0,.33),(.58,.58,.61),oak,.045,night)
for z in [.2,.45]:
    cube('Bedside drawer',(0,-.299,z),(.49,.035,.2),walnut,.018,night)
    rod('Drawer brass pull',(-.09,-.33,z),(.09,-.33,z),.012,gold,night)

def lamp(parent,loc,height=.5):
    x,y,z=loc;cylinder('Lamp brass base',(x,y,z+.02),.15,.04,gold,parent)
    rod('Lamp stem',(x,y,z+.03),(x,y,z+height*.67),.018,gold,parent)
    cylinder('Lamp ivory shade',(x,y,z+height*.77),.22,height*.45,cream,parent,48,radius2=.13)
    sphere('Lamp warm bulb',(x,y,z+height*.65),(.055,.055,.055),glow,parent)
lamp(night,(0,0,.65),.48)

desk=group('FURN_DESK','窗边弧形书桌',(1.0,2.62,0),0)
# Local -Y is the use side, facing toward the centre of the room.
poly=[(-1.50,.42),(1.50,.42),(1.50,-.40),(1.20,-.50),(.8,-.47),(.3,-.37),(-.3,-.37),(-.8,-.47),(-1.2,-.5),(-1.5,-.4)]
prism('Sculpted desk top',poly,.755,.82,oak,desk)
for x in [-1.32,1.32]:
    cube('Desk trestle',(x,0,.37),(.08,.65,.74),walnut,.02,desk)
    cube('Desk foot',(x,0,.055),(.24,.75,.11),oak,.025,desk)
cube('Desk modesty',(0,.34,.57),(2.65,.045,.31),walnut,.018,desk)
cube('Desk drawer',(1.03,-.02,.65),(.48,.63,.16),oak,.025,desk)
rod('Desk drawer handle',(.92,-.35,.65),(1.14,-.35,.65),.01,gold,desk)
cube('Monitor base',(.13,.12,.84),(.43,.28,.035),black,.045,desk)
rod('Monitor stand',(.13,.20,.85),(.13,.20,1.09),.034,black,desk)
cube('Monitor frame',(.13,.20,1.25),(.98,.06,.56),black,.035,desk)
cube('Monitor screen',(.13,.164,1.25),(.91,.012,.49),screen,.02,desk)
textmesh('Monitor title','FOREST / STUDIO',(.13,.153,1.29),.053,cream,desk)
textmesh('Monitor subtitle','make room for wonder',(.13,.152,1.19),.025,cream,desk)
cube('Keyboard',(0,-.24,.847),(.6,.2,.035),black,.018,desk)
for row in range(4):
    for col in range(12):cube('Keycap',(-.267+col*.048,-.31+row*.044,.87),(.039,.033,.012),cream,.004,desk)
sphere('Wireless mouse',(.52,-.23,.86),(.047,.076,.026),black,desk)
cube('Desk blotter',(0,-.16,.823),(1.48,.54,.009),panel,.025,desk)
lamp(desk,(-1.14,.05,.82),.62)
cylinder('Tea mug',(.88,-.20,.89),.065,.14,white,desk)
cylinder('Tea',(.88,-.20,.963),.052,.003,walnut,desk)
curve('Mug handle',[(.94,-.2,.94),(.99,-.2,.94),(1.01,-.2,.89),(.99,-.2,.85),(.94,-.2,.85)],.012,white,desk)

chair=group('FURN_CHAIR_DESK','书桌椅',(1.0,1.55,0),180)
cube('Chair oak seat',(0,0,.46),(.58,.57,.065),oak,.07,chair)
cube('Chair cream cushion',(0,-.015,.512),(.53,.50,.075),cream,.08,chair)
for x in [-.22,.22]:
    for y in [-.21,.21]:rod('Chair splayed leg',(x*1.20,y*1.20,.03),(x,y,.46),.026,walnut,chair)
for x in [-.24,.24]:rod('Chair back upright',(x,.23,.44),(x,.24,1.02),.024,walnut,chair)
cube('Chair curved back',(0,.25,.86),(.58,.09,.28),oak,.07,chair)
cube('Chair padded back',(0,.19,.86),(.49,.055,.20),cream,.06,chair)

rug=group('FURN_RUG','中央椭圆地毯',(-.1,-.65,0),0)
o=cylinder('Woven oval rug',(0,0,.014),1,.028,rugmat,rug,128);o.scale=(1.60,1.60,1)
for r,ma in [(1.51,velvet),(1.46,velvet),(1.37,cream)]:
    curve('Rug woven concentric border',[(r*math.cos(a),r*math.sin(a),.031) for a in np.linspace(0,math.tau,192,endpoint=False)],.008,ma,rug,True)
for i in range(56):
    a=i/56*math.tau;r=1.57
    rod('Rug fringe',(r*math.cos(a),r*math.sin(a),.017),((r+.07)*math.cos(a),(r+.07)*math.sin(a),.017),.008,rugmat,rug,8)
# Botanical stitched motif near border, kept flat so the carpet remains walkable.
for side in [-1,1]:
    pts=[(side*(.96+.11*math.sin(t*math.pi)),t*.7,.032) for t in np.linspace(-1,1,24)]
    curve('Rug botanical stem',pts,.007,velvet,rug)
    for j in range(7):
        yy=-.6+j*.2;xx=side*(.96+.1*math.sin(yy/.7*math.pi))
        curve('Rug botanical leaf',[(xx,yy,.032),(xx+side*.15,yy+.06,.032),(xx,yy+.10,.032)],.006,velvet,rug)

reading=group('FURN_CHAIR_READING','阅读休闲椅',(2.83,-.12,0),-65)
for x in [-.35,.35]:
    for y in [-.32,.32]:rod('Lounge tapered leg',(x*1.06,y*1.06,.04),(x,y,.35),.035,walnut,reading)
cube('Lounge oak base',(0,0,.34),(.92,.86,.16),oak,.065,reading)
cube('Lounge seat cushion',(0,-.03,.48),(.87,.80,.16),cream,.12,reading)
o=cube('Lounge padded back',(0,.34,.79),(.94,.20,.77),cream,.11,reading);o.rotation_euler.x=-.12
for x in [-.46,.46]:
    cube('Lounge arm',(x,.0,.66),(.15,.88,.18),cream,.07,reading)
cube('Lounge moss pillow',(0,.20,.82),(.57,.19,.47),linen,.09,reading)

side=group('FURN_SIDE_TABLE','阅读边几',(3.15,-1.30,0),0)
cylinder('Side table top',(0,0,.54),.33,.07,oak,side,64)
cylinder('Side table pedestal',(0,0,.28),.055,.51,gold,side)
cylinder('Side table foot',(0,0,.045),.24,.06,walnut,side,48)
cube('Reading book',(0,0,.60),(.31,.24,.05),books[0],.009,side)
cube('Book pages',(0,-.004,.603),(.29,.225,.035),cream,.005,side)
textmesh('Book cover','BOTANICA',(0,.025,.63),.037,gold,side,rot=(0,0,0))

tv=group('FURN_TV','电视',(0.3,-3.36,1.55),180)
tv.parent=wall_south
cube('TV slim body',(0,0,0),(1.96,.07,1.14),black,.04,tv)
cube('TV glass screen',(0,-.041,0),(1.87,.014,1.05),screen,.025,tv)
# Original graphical screen artwork built from geometry, not an external image.
for i in range(3):
    poly=[(-.925,-.045,-.50),(-.925,-.045,-.25+i*.1)]
    pts=[(-.93,-.053-i*.002,-.48)]+[(x,-.053-i*.002,-.31+i*.13+.10*math.sin(x*5+i*2)+.04*math.sin(x*11+i)) for x in np.linspace(-.93,.93,40)]+[(.93,-.053-i*.002,-.48)]
    mesh('Screen layered hills',pts,[tuple(range(len(pts)))],leaves[i],tv)
sphere('Screen moon',(.48,-.066,.22),(.145,.009,.145),cream,tv)
textmesh('TV art title','FOREST REVERIE',(-.20,-.075,.18),.082,cream,tv)
textmesh('TV art subtitle','slow living / ambient play',(-.19,-.075,.06),.035,cream,tv)
cube('TV status LED',(.83,-.045,-.54),(.018,.008,.005),glow,.002,tv)
cabinet=group('FURN_MEDIA_CONSOLE','电视矮柜',(.3,-2.99,0),180)
cube('Media console',(0,0,.31),(2.65,.52,.52),oak,.04,cabinet)
cube('Media console recessed front',(0,-.272,.32),(2.51,.022,.38),walnut,.012,cabinet)
for x in [-.97,0,.97]:
    cube('Media door',(x,-.29,.32),(.72,.03,.36),oak,.01,cabinet)
    for i in range(9):cube('Media door fluting',(x-.32+i*.08,-.312,.32),(.018,.015,.32),walnut,.006,cabinet)
for x in [-1.05,1.05]:
    for y in [-.17,.17]:cylinder('Media foot',(x,y,.065),.033,.13,gold,cabinet)
console=group('FURN_GAME_CONSOLE','游戏机',(1.10,-2.96,.575),180)
cube('Console dark core',(0,0,.18),(.18,.25,.36),black,.035,console)
for x in [-.095,.095]:
    o=cube('Console ivory side',(x,0,.19),(.035,.28,.40),white,.025,console);o.rotation_euler.y=x*.4
cube('Console blue status',(0,-.13,.25),(.018,.008,.16),blue,.004,console)
cube('Console disk slot',(.044,-.135,.12),(.008,.006,.13),black,.002,console)
controller=group('PROP_CONTROLLER','游戏手柄',(.55,-2.94,.60),180)
sphere('Controller body',(0,0,0),(.14,.082,.035),white,controller)
for x in [-.09,.09]:sphere('Controller grip',(x,-.045,-.013),(.052,.09,.035),white,controller)
for x in [-.047,.047]:cylinder('Controller thumbstick',(x,-.015,.035),.020,.016,black,controller,20)
for x,y in [(.086,.032),(.108,.012),(.064,.012),(.086,-.008)]:sphere('Game button',(x,y,.03),(.008,.008,.006),black,controller,12,8)
cube('Controller dpad',(-.084,.025,.037),(.046,.012,.008),black,.003,controller)
cube('Controller dpad',(-.084,.025,.037),(.012,.046,.008),black,.003,controller)

# Low shelf at the foot of the bed, clear of the central passage.
shelf=group('FURN_BOOKCASE','矮书架',(-3.64,-1.66,0),90)
cube('Bookcase rear',(0,.16,.54),(1.38,.035,1.05),walnut,.01,shelf)
for z in [.08,.51,1.05]:cube('Bookcase shelf',(0,0,z),(1.42,.37,.055),oak,.01,shelf)
for x in [-.69,.69]:cube('Bookcase side',(x,0,.55),(.045,.37,1.06),oak,.01,shelf)
for level in [.11,.54]:
    for i in range(15):
        h=random.uniform(.21,.35);x=-.60+i*.08
        cube('Book spine',(x,-.015,level+h/2),(.055,.24,h),books[i%len(books)],.006,shelf)
        for z in [level+.045,level+h-.035]:cube('Book gilt line',(x,-.138,z),(.043,.005,.006),gold,.001,shelf)

print('STAGE 2 • furniture:',len(S.objects),'objects')
