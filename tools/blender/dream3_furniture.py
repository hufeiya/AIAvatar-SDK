"""Original Peach-Immortal Pavilion furniture, executed in the scene namespace.

All furniture is drawn in its own local coordinate system.  The open front of
the canopy and the empty tops of the sitting cushions are intentional: they
are the character interaction surfaces, not merely a display composition.
"""
import math
import random

_dream3_furniture_existing=set(S.objects)


def _smooth(obj):
    for polygon in obj.data.polygons:
        polygon.use_smooth = True
    return obj


def _ring(name, center, radius, material, parent, thickness=.008, count=48):
    x, y, z = center
    return curve(name, [(x+radius*math.cos(i*math.tau/count),
                         y+radius*math.sin(i*math.tau/count), z)
                        for i in range(count)], thickness, material, parent, True)


def _fret(name, x, y, z, w, h, parent, material=gold, thickness=.009):
    """A geometric longevity fret in the XZ plane, open at its centre."""
    pts = [(-.5,-.5),(-.5,.5),(.5,.5),(.5,-.5),(-.22,-.5),
           (-.22,.20),(.22,.20),(.22,-.15),(.02,-.15)]
    return curve(name, [(x+a*w,y,z+b*h) for a,b in pts], thickness, material, parent)


def _cloud_apron(name, width, y, z, parent, material=wood):
    # A low relief ruyi cloud edge, held beneath an uninterrupted table top.
    p=[]
    for i in range(33):
        x=-width/2+i*width/32
        t=abs(x)/(width/2)
        zz=z-.06*math.sin(math.pi*t)**2-.024*math.cos(3*math.pi*t)
        p.append((x,y,zz))
    curve(name,p,.022,material,parent)
    curve(name+' gilt edge',[(x,yy-.007,zz+.012) for x,yy,zz in p],.004,gold,parent)


def _pillow(name, center, dims, material, parent, angle=0):
    # Squared, inflated silk pillow with a sewn perimeter, rather than a box.
    w,d,h=dims;cx,cy,cz=center
    ca,sa=math.cos(angle),math.sin(angle)
    def point(x,y,z):
        return (cx+ca*x-sa*y,cy+sa*x+ca*y,cz+z)
    v=[];f=[];N=32;M=14
    for j in range(M+1):
        t=j/M*math.pi;r=math.sin(t)**.47
        for i in range(N):
            a=i/N*math.tau
            xx=w/2*math.copysign(abs(math.cos(a))**.40,math.cos(a))*r
            yy=d/2*math.copysign(abs(math.sin(a))**.40,math.sin(a))*r
            zz=h/2*math.cos(t)+.006*math.sin(a*8)*math.sin(t)**3
            v.append(point(xx,yy,zz))
    for j in range(M):
        for i in range(N):
            a=j*N+i;b=j*N+(i+1)%N
            f.append((a,b,b+N,a+N))
    obj=_smooth(mesh(name,v,f,material,parent))
    obj['contactSurface']=True
    seam=[]
    for i in range(80):
        a=i/80*math.tau
        xx=w*.498*math.copysign(abs(math.cos(a))**.40,math.cos(a))
        yy=d*.498*math.copysign(abs(math.sin(a))**.40,math.sin(a))
        seam.append(point(xx,yy,0))
    curve(name+' sewn piping',seam,.006,ivory,parent,True)
    return obj


def _table(name,label,loc,width,depth,top,parent=None):
    obj=group(name,label,loc,0,parent=parent)
    cube(label+' solid rosewood top',(0,0,top-.04),(width,depth,.08),wood,.022,obj)
    cube(label+' gold inlaid top line',(0,0,top-.083),(width-.025,depth-.025,.013),gold,.006,obj)
    cube(label+' recessed apron',(0,0,top-.14),(width-.12,depth-.12,.14),darkwood,.009,obj)
    for x in [-width*.40,width*.40]:
        for y in [-depth*.34,depth*.34]:
            s=1 if x>0 else -1;t=1 if y>0 else -1
            curve(label+' curved cabriole leg',[(x+s*.035,y+t*.015,.025),
                  (x+s*.014,y+t*.01,top*.30),(x-.015*s,y,top*.66),
                  (x,y,top-.12)],.035,wood,obj)
            cube(label+' hoof foot',(x+s*.035,y+t*.015,.035),(.10,.09,.06),darkwood,.014,obj)
            sphere(label+' leg brass collar',(x,y,top-.19),(.038,.038,.028),gold,obj,12,6)
    for y in [-depth*.42,depth*.42]:
        _cloud_apron(label+' carved ruyi apron',width*.76,y,top-.16,obj)
    return obj


# Four-post carved canopy bed.  Its silhouette stays within the bed footprint
# at walking height; every edge of the central access area remains open.
bed=group('FURN_BED','桃华流霞架子床',(2.05,1.05,0),0)
cube('Bed recessed plinth',(0,0,.16),(2.16,2.82,.22),darkwood,.035,bed)
cube('Bed carved rosewood platform',(0,0,.37),(2.35,3.0,.20),wood,.035,bed)
cube('Bed gold platform stringing',(0,-1.507,.395),(2.22,.012,.014),gold,.002,bed)
for x in [-1.0,1.0]:
    for y in [-1.28,1.28]:
        cube('Bed short hoof leg',(x,y,.11),(.16,.17,.20),wood,.025,bed)
        cube('Bed bronze leg shoe',(x,y,.035),(.17,.18,.045),gold,.009,bed)
for x in [-.83,-.42,0,.42,.83]:
    _fret('Bed foot apron longevity inlay',x,-1.514,.30,.25,.105,bed,gold,.005)
cube('Bed foot apron recessed field',(0,-1.512,.285),(2.13,.022,.13),darkwood,.008,bed)
for x in [-.84,-.42,0,.42,.84]:
    _fret('Bed foot carved window',x,-1.529,.295,.26,.09,bed,gold,.004)
for x in [-1.18,1.18]:
    cube('Bed side rail',(x,0,.39),(.065,2.91,.15),wood,.012,bed)
    curve('Bed side gold hairline',[(x, -1.43,.43),(x,1.43,.43)],.004,gold,bed)
cube('Bed mattress lower silk edge',(0,0,.573),(2.21,2.86,.25),ivory,.095,bed)['contactSurface']=True
cube('Bed mattress soft top',(0,0,.705),(2.18,2.84,.14),linen,.065,bed)['contactSurface']=True
cube('Bed tall carved headboard',(0,1.47,.98),(2.30,.115,1.22),wood,.028,bed)
cube('Bed headboard recessed plum panel',(0,1.399,1.13),(1.98,.035,.75),darkwood,.022,bed)
for x in [-.83,-.415,0,.415,.83]:
    _fret('Bed headboard lattice',x,1.374,1.18,.30,.51,bed,gold,.009)
curve('Bed headboard upper arch',[(-1.13,1.46,1.54),(-.75,1.46,1.59),
      (0,1.46,1.67),(.75,1.46,1.59),(1.13,1.46,1.54)],.045,wood,bed)

# A folded, draped duvet with actual side and foot falls.  The flat top and
# lower side edge are continuous, so exports retain the smooth fabric folds.
nx,ny=42,51;v=[];f=[]
for j in range(ny+1):
    y=-1.56+j/ny*2.39
    for i in range(nx+1):
        x=-1.22+i/nx*2.44
        sx=max(0,(abs(x)-1.01)/.21)
        foot=max(0,(-y-1.31)/.25)
        z=.805-.34*sx**1.20-.23*foot**1.2
        z+=.013*math.sin(19*x+3*y)+.009*math.sin(29*y-8*x)
        z+=.016*math.cos(8*x+5*y)*math.sin((y+1.6)*1.3)**2
        v.append((x,y,z))
for j in range(ny):
    for i in range(nx):
        a=j*(nx+1)+i;f.append((a,a+1,a+nx+2,a+nx+1))
duvet=_smooth(mesh('Bed blush silk duvet with flowing folds',v,f,rose,bed))
duvet['contactSurface']=True
solid=duvet.modifiers.new('Duvet stitched thickness','SOLIDIFY');solid.thickness=.012
for x in [-1.215,1.215]:
    pts=[]
    for j in range(56):
        y=-1.53+j/55*2.35
        sx=max(0,(abs(x)-1.01)/.21);foot=max(0,(-y-1.31)/.25)
        z=.805-.34*sx**1.20-.23*foot**1.2+.013*math.sin(19*x+3*y)+.009*math.sin(29*y-8*x)
        pts.append((x,y,z+.008))
    curve('Bed duvet ivory edge piping',pts,.0055,ivory,bed)
cube('Bed turned ivory duvet fold',(0,.72,.829),(2.02,.26,.052),ivory,.025,bed)
for x,a in [(-.55,-.045),(.55,.045)]:
    _pillow('Bed embroidered silk pillow',(x,1.05,.887),(1.01,.63,.245),linen,bed,a)
    # Gold embroidered branch and five simple plum petals rest on the pillow.
    curve('Bed pillow gold embroidery',[(x-.27,.97,.997),(x-.11,1.01,1.018),
          (x+.05,1.10,1.020),(x+.25,1.15,1.000)],.0035,gold,bed)
    for a2 in range(5):
        t=a2*math.tau/5
        sphere('Bed pillow embroidered plum',(x+.05+.022*math.cos(t),1.10+.022*math.sin(t),1.024),
               (.018,.012,.0025),rose,bed,10,5)
_pillow('Bed small plum bolster',(0,.71,.927),(.94,.26,.22),plum,bed)

# An embroidered ivory runner tumbles over the right side of the bed, leaving
# the left sitting area and the sleeping contact free of decorative geometry.
v=[];f=[];nr=31;nc=10
for j in range(nr+1):
    y=-1.48+j/nr*.83
    for i in range(nc+1):
        x=.64+i/nc*.61
        drop=max(0,(x-1.015)/.235)
        z=.855-.39*drop**1.15+.008*math.cos(31*y+12*x)
        v.append((x,y,z))
for j in range(nr):
    for i in range(nc):
        a=j*(nc+1)+i;f.append((a,a+1,a+nc+2,a+nc+1))
_smooth(mesh('Bed ivory embroidered draped runner',v,f,linen,bed))
for j in range(19):
    y=-1.46+j*.043
    curve('Bed runner hand knotted fringe',[(1.24,y,.465),(1.27,y+.007,.405)],.0035,ivory,bed)

for x in [-1.30,1.30]:
    for y in [-1.60,1.60]:
        cylinder('Canopy octagonal rosewood column',(x,y,1.51),.052,2.90,wood,bed,8)
        for z in [.12,.43,2.39,2.78,2.92]:
            cylinder('Canopy column gilded collar',(x,y,z),.067,.035,gold,bed,12)
        sphere('Canopy lotus finial',(x,y,3.015),(.075,.075,.10),gold,bed,16,8)
for y in [-1.60,1.60]:
    cube('Canopy transverse carved beam',(0,y,2.85),(2.72,.13,.20),wood,.025,bed)
    cube('Canopy transverse gold inlay',(0,y-.073,2.91),(2.58,.012,.014),gold,.002,bed)
    for x in [-1.04,-.69,-.345,0,.345,.69,1.04]:
        _fret('Canopy pierced longevity frieze',x,y-.075,2.78,.26,.15,bed,gold,.007)
for x in [-1.30,1.30]:
    cube('Canopy longitudinal carved beam',(x,0,2.85),(.13,3.28,.20),wood,.025,bed)
    for y in [-1.16,-.58,0,.58,1.16]:
        # Raised gold bamboo corners along the side rails.
        curve('Canopy side fret',[(x-.072,y-.20,2.76),(x-.072,y+.20,2.76),
              (x-.072,y+.20,2.90),(x-.072,y+.01,2.90),
              (x-.072,y+.01,2.82)],.006,gold,bed)

# Pleated pink front panels gathered high and wide at the posts.  These are
# single thin sheets, not a stack of transparency planes.
def _front_curtain(side):
    verts=[];faces=[];rows=32;cols=18
    for j in range(rows+1):
        t=j/rows;z=2.72*(1-t)+.055
        # A drawn-back curve from the inner upper edge to the tied post.
        gather=math.exp(-((t-.59)/.22)**2)
        outer=1.315+.07*math.sin(t*math.pi)
        inner=.40+.89*math.sin(min(t/.60,1)*math.pi/2)
        if t>.60:inner=1.23-.13*(t-.60)/.40
        for i in range(cols+1):
            s=i/cols;x=side*(inner+(outer-inner)*s)
            y=-1.575+.043*math.sin(s*7*math.pi+t*2.2)*(1-.60*gather)
            y+=.035*math.sin(math.pi*t)
            zz=z+.025*math.sin(s*math.pi*7)*(1-gather)
            verts.append((x,y,zz))
    for j in range(rows):
        for i in range(cols):
            a=j*(cols+1)+i;faces.append((a,a+1,a+cols+2,a+cols+1))
    _smooth(mesh('Canopy drawn blush gauze '+str(side),verts,faces,silk,bed))
    for edge in [0,cols]:
        curve('Canopy curtain embroidered border',[verts[j*(cols+1)+edge] for j in range(rows+1)],.007,rose,bed)
    x=side*1.30
    curve('Canopy curtain golden tie',[(x-side*.07,-1.63,1.20),(x-side*.10,-1.66,1.16),
          (x+side*.065,-1.64,1.12),(x+side*.10,-1.58,1.18)],.012,gold,bed)
    curve('Canopy trailing silk tie',[(x,-1.64,1.15),(x-side*.07,-1.65,.93),
          (x-side*.04,-1.62,.73)],.018,rose,bed)
    sphere('Canopy tie jade bead',(x-side*.04,-1.62,.72),(.029,.029,.04),jade,bed,12,6)
    for k in range(5):
        curve('Canopy tie tassel',[(x-side*.04+(k-2)*.008,-1.62,.69),
              (x-side*.05+(k-2)*.009,-1.62,.53)],.004,rose,bed)

_front_curtain(-1);_front_curtain(1)
# Back curtain falls against the carved headboard; the middle is open.
for side in [-1,1]:
    v=[];f=[];nyc=20;nzc=24
    for j in range(nzc+1):
        t=j/nzc;z=2.74-t*1.90
        for i in range(nyc+1):
            s=i/nyc;x=side*(.75+.53*s+.08*math.sin(math.pi*t))
            y=1.54+.025*math.sin(s*9*math.pi+t*2)
            v.append((x,y,z+.01*math.sin(s*9*math.pi)))
    for j in range(nzc):
        for i in range(nyc):
            a=j*(nyc+1)+i;f.append((a,a+1,a+nyc+2,a+nyc+1))
    _smooth(mesh('Canopy rear gauze folded panel',v,f,silk,bed))
    curve('Canopy rear silk border',[v[j*(nyc+1)] for j in range(nzc+1)],.006,rose,bed)
# Scalloped fabric valance at the front, light enough to reveal the fretwork.
v=[];f=[]
for j in range(5):
    t=j/4
    for i in range(65):
        x=-1.27+i/64*2.54
        scallop=.11*abs(math.sin((x+1.27)/2.54*3*math.pi))
        v.append((x,-1.58+.012*math.sin(x*37),2.72-t*(.12+scallop)))
for j in range(4):
    for i in range(64):
        a=j*65+i;f.append((a,a+1,a+66,a+65))
_smooth(mesh('Canopy rose scalloped valance',v,f,rose,bed))
curve('Canopy valance gold sewn hem',[v[4*65+i] for i in range(65)],.005,gold,bed)
for x in [-.84,0,.84]:
    sphere('Canopy valance jade pendant',(x,-1.59,2.45),(.023,.023,.034),jade,bed,12,6)
    for k in range(5):
        curve('Canopy valance fine tassels',[(x+(k-2)*.006,-1.59,2.42),
              (x+(k-2)*.008,-1.59,2.31)],.0028,rose,bed)

# The long elevations carry matching pairs of drawn-back gauze panels.
# At bed height every panel is gathered beside an end post, leaving the
# entire central side opening available for sitting and sleeping contacts.
def _side_curtain(side,end):
    verts=[];faces=[];rows=24;cols=12
    for j in range(rows+1):
        t=j/rows;z=2.735*(1-t)+.055
        gathered=math.exp(-((t-.58)/.20)**2)
        # At the rail: a broad 1.55 m curtain.  At the tie: a narrow bundle.
        inner=.025+1.47*math.sin(min(t/.60,1)*math.pi/2)
        if t>.60:
            inner=1.495-.225*(t-.60)/.40
        outer=1.575+.045*math.sin(math.pi*t)
        for i in range(cols+1):
            s=i/cols
            y=end*(inner+(outer-inner)*s)
            x=side*(1.315+.055*math.sin(math.pi*t)
                    +.032*math.sin(s*8*math.pi+t*2)*(1-.72*gathered))
            zz=z+.015*math.sin(s*8*math.pi)*(1-gathered)
            verts.append((x,y,zz))
    for j in range(rows):
        for i in range(cols):
            a=j*(cols+1)+i
            faces.append((a,a+1,a+cols+2,a+cols+1))
    _smooth(mesh('Canopy billowing side gauze '+str(side)+' '+str(end),verts,faces,silk,bed))
    curve('Canopy side curtain rose sewn opening',
          [verts[j*(cols+1)] for j in range(rows+1)],.005,rose,bed)
    curve('Canopy side curtain floating hem',verts[rows*(cols+1):],.0045,rose,bed)
    x=side*1.355;y=end*1.54
    curve('Canopy side gathered gold tie',[(x,y-end*.065,1.22),
          (x+side*.035,y-end*.085,1.18),(x+side*.045,y+end*.045,1.16),
          (x-side*.025,y+end*.065,1.22),(x,y-end*.065,1.22)],.009,gold,bed)
    curve('Canopy side flowing silk ribbon',[(x+side*.01,y,1.175),
          (x+side*.045,y-end*.035,1.02),(x+side*.025,y-end*.025,.86),
          (x+side*.04,y-end*.06,.77)],.012,rose,bed)
    sphere('Canopy side tie jade bead',(x+side*.04,y-end*.06,.77),
           (.024,.024,.034),jade,bed,10,5)

for _side in [-1,1]:
    for _end in [-1,1]:
        _side_curtain(_side,_end)

# A single translucent canopy ceiling reads as suspended silk through the
# carved frame.  Its slight catenary sag and low waves avoid a rigid box lid.
v=[];f=[];nx=16;ny=20
for j in range(ny+1):
    y=-1.56+j/ny*3.12
    for i in range(nx+1):
        x=-1.265+i/nx*2.53
        sag=.055*(1-(x/1.265)**2)*(1-(y/1.56)**2)
        z=2.815-sag+.010*math.sin(13*x+5*y)
        v.append((x,y,z))
for j in range(ny):
    for i in range(nx):
        a=j*(nx+1)+i;f.append((a,a+1,a+nx+2,a+nx+1))
_smooth(mesh('Canopy overhead sagging sheer silk',v,f,silk,bed))

# Side scallops continue the front valance, emphasising the canopy silhouette
# in the hero view while keeping the open space below visually lightweight.
for side in [-1,1]:
    v=[];f=[];cols=36;rows=3
    for j in range(rows+1):
        t=j/rows
        for i in range(cols+1):
            y=-1.56+i/cols*3.12
            scallop=.095*abs(math.sin((y+1.56)/3.12*4*math.pi))
            x=side*(1.315+.011*math.sin(y*27))
            v.append((x,y,2.745-t*(.11+scallop)))
    for j in range(rows):
        for i in range(cols):
            a=j*(cols+1)+i;f.append((a,a+1,a+cols+2,a+cols+1))
    _smooth(mesh('Canopy longitudinal gauze scalloped valance',v,f,silk,bed))
    curve('Canopy side scalloped fine gold hem',v[rows*(cols+1):],.0045,gold,bed)


# Bedside cabinet: a miniature carved chest with two working-looking drawers.
nightstand=group('FURN_NIGHTSTAND','鎏金床头小柜',(3.6,1.9,0),0)
night=nightstand
cube('Nightstand rosewood carcass',(0,0,.34),(.58,.58,.55),wood,.022,nightstand)
cube('Nightstand overhanging top',(0,0,.65),(.64,.64,.07),wood,.02,nightstand)
cube('Nightstand thin gold inlay',(0,0,.612),(.60,.60,.012),gold,.003,nightstand)
for x in [-.22,.22]:
    for y in [-.22,.22]:cube('Nightstand foot',(x,y,.055),(.09,.09,.11),darkwood,.015,nightstand)
for z in [.23,.46]:
    cube('Nightstand drawer face',(0,-.299,z),(.49,.038,.19),darkwood,.012,nightstand)
    for x in [-.17,.17]:_fret('Nightstand carved drawer corner',x,-.322,z,.10,.11,nightstand,gold,.004)
    sphere('Nightstand brass drawer boss',(0,-.329,z),(.027,.012,.027),gold,nightstand,16,8)
    curve('Nightstand round drop pull',[(.035*math.cos(i*math.tau/24),-.346,z-.028+.035*math.sin(i*math.tau/24))
          for i in range(24)],.005,gold,nightstand,True)

# Scholar's desk and traditional yoke-back chair.  Desk decoration is provided
# by the separate decor script; these tops are intentionally clear.
desk=_table('FURN_DESK','听雨书案',(-2.65,2.75,0),1.8,.70,.82)
cube('Desk narrow front drawer',(0,-.31,.67),(.62,.055,.13),wood,.008,desk)
rod('Desk small brass pull',(-.07,-.345,.67),(.07,-.345,.67),.008,gold,desk)
for x in [-.69,.69]:
    curve('Desk carved spandrel',[(x,-.29,.68),(x*.83,-.29,.61),
          (x*.76,-.29,.69)],.015,wood,desk)
chairdesk=group('FURN_CHAIR_DESK','如意圈背书椅',(-2.65,1.65,0),180)
chair=chairdesk
cube('Chair rosewood seat frame',(0,0,.425),(.65,.65,.08),wood,.026,chairdesk)
cube('Chair woven silk seat cushion',(0,-.005,.475),(.565,.555,.05),linen,.042,chairdesk)['contactSurface']=True
for x in [-.245,.245]:
    for y in [-.245,.245]:
        rod('Chair gently splayed leg',(x*1.13,y*1.13,.035),(x,y,.425),.025,wood,chairdesk,12)
        cylinder('Chair brass foot ferrule',(x*1.13,y*1.13,.05),.027,.07,gold,chairdesk,12)
for x in [-.25,.25]:
    curve('Chair back upright',[(x,.25,.42),(x,.26,.80),(x*1.12,.23,1.13)],.025,wood,chairdesk)
    curve('Chair arm bow',[(x,-.19,.71),(x*1.05,-.02,.77),(x,.25,.84)],.025,wood,chairdesk)
    rod('Chair front arm support',(x,-.19,.46),(x,-.19,.71),.018,wood,chairdesk)
curve('Chair graceful yoke back crest',[(-.35,.24,1.12),(-.30,.23,1.17),
      (-.16,.27,1.18),(0,.29,1.16),(.16,.27,1.18),(.30,.23,1.17),(.35,.24,1.12)],.033,wood,chairdesk)
curve('Chair yoke gold inlay',[(-.28,.207,1.166),(0,.258,1.147),(.28,.207,1.166)],.004,gold,chairdesk)
cube('Chair central curved splat',(0,.275,.867),(.12,.045,.46),wood,.02,chairdesk)
_fret('Chair back jade longevity carving',0,.246,.92,.075,.16,chairdesk,jade,.006)
for y,z in [(-.25,.19),(.25,.24)]:rod('Chair lower stretcher',(-.25,y,z),(.25,y,z),.018,wood,chairdesk)
for x in [-.25,.25]:rod('Chair side stretcher',(x,-.25,.20),(x,.25,.25),.018,wood,chairdesk)

tea=_table('FURN_TEA_TABLE','花间矮茶案',(-2,-.75,0),1.20,.85,.48)
cube('Tea table recessed jade tray inlay',(0,0,.481),(.98,.66,.008),darkwood,.025,tea)
for y in [-.315,.315]:
    curve('Tea table thin gilt rim',[(-.46,y,.488),(.46,y,.488)],.003,gold,tea)


def _tea_cushion(name,label,loc,yaw):
    obj=group(name,label,loc,yaw)
    cube(label+' low rosewood platform',(0,0,.055),(.70,.70,.09),wood,.06,obj)
    cube(label+' jade platform line',(0,0,.100),(.665,.665,.015),jade,.025,obj)
    _pillow(label+' silk sitting cushion',(0,0,.157),(.665,.665,.126),rose,obj)
    for x in [-.28,.28]:
        for y in [-.28,.28]:
            sphere(label+' knotted silk corner',(x,y,.13),(.027,.027,.018),plum,obj,10,5)
    return obj

tea_north=_tea_cushion('FURN_TEA_CUSHION_NORTH','茶席北蒲团',(-2,.15,0),0)
tea_south=_tea_cushion('FURN_TEA_CUSHION_SOUTH','茶席南蒲团',(-2,-1.65,0),180)

# A flat, hand woven carpet.  Golden key-fret edging and stylised plum sprigs
# are actual low relief geometry, never tall enough to catch a walking avatar.
rug=group('FURN_RUG','桃花织锦地毯',(-1.5,-.75,0),0,nav=False)
cube('Rug blush woven ground',(0,0,.013),(3,3.3,.026),rose,.02,rug)
for x in [-1.39,1.39]:cube('Rug ivory long border',(x,0,.029),(.15,3.08,.004),ivory,.006,rug)
for y in [-1.54,1.54]:cube('Rug ivory end border',(0,y,.029),(2.92,.15,.004),ivory,.006,rug)
for x in [-1.28,1.28]:curve('Rug gold inner line',[(x,-1.44,.033),(x,1.44,.033)],.004,gold,rug)
for y in [-1.44,1.44]:curve('Rug gold inner line',[(-1.28,y,.033),(1.28,y,.033)],.004,gold,rug)
for x in [-1.40,1.40]:
    for i in range(13):
        y=-1.35+i*.225
        curve('Rug interlocked geometric weave',[(x-.045,y-.07,.034),(x+.045,y-.07,.034),
              (x+.045,y+.055,.034),(x-.012,y+.055,.034),
              (x-.012,y-.005,.034)],.0045,plum,rug)
for y in [-1.54,1.54]:
    for i in range(12):
        x=-1.26+i*.23
        curve('Rug end geometric weave',[(x-.07,y-.044,.034),(x-.07,y+.044,.034),
              (x+.055,y+.044,.034),(x+.055,y-.012,.034),
              (x-.005,y-.012,.034)],.0045,plum,rug)
for y in [-1.67,1.67]:
    sy=1 if y>0 else -1
    for i in range(49):
        x=-1.43+i*2.86/48
        rod('Rug ivory knotted fringe',(x,y-sy*.018,.018),(x+.008,y+sy*.065,.014),.004,ivory,rug,6)
for sx in [-1,1]:
    for sy in [-1,1]:
        # Modest corner embroidery preserves an uncluttered central field.
        xx=sx*1.06;yy=sy*1.18
        curve('Rug woven plum branch',[(xx,yy,.034),(xx-sx*.13,yy-sy*.18,.034),
              (xx-sx*.27,yy-sy*.24,.034),(xx-sx*.31,yy-sy*.44,.034)],.006,plum,rug)
        for dx,dy in [(0,0),(-.15,-.18),(-.28,-.29)]:
            for k in range(5):
                a=k*math.tau/5
                sphere('Rug tiny woven plum petals',(xx+sx*dx+.025*math.cos(a),yy+sy*dy+.025*math.sin(a),.035),
                       (.027,.018,.0018),ivory,rug,8,4)

# A quiet meditation platform, with a completely clear sitting centre.
meditation=group('FURN_MEDITATION','凝神白玉莲台',(2.35,-2.15,0),0)
cube('Meditation dark rosewood dais',(0,0,.055),(1.65,1.65,.11),darkwood,.07,meditation)
cube('Meditation jade edged top',(0,0,.115),(1.58,1.58,.026),jade,.045,meditation)
cube('Meditation ivory centre',(0,0,.131),(1.45,1.45,.012),ivory,.045,meditation)
for x in [-.70,.70]:
    for y in [-.70,.70]:
        # Small gold corner inlay sits below the cushion plane.
        curve('Meditation corner ruyi inlay',[(x*.77,y,.14),(x,y,.14),(x,y*.77,.14)],.008,gold,meditation)
cylinder('Meditation cushion lower jade piping',(0,0,.166),.565,.057,jade,meditation,64)
meditation_cushion=sphere('Meditation round silk sitting cushion',(0,0,.210),(.55,.55,.070),rose,meditation,64,16)
meditation_cushion['contactSurface']=True
# A continuous sewn rim surrounds the round cushion without a raised centre.
_ring('Meditation cushion gold outer seam',(0,0,.204),.535,gold,meditation,.005,64)
for i in range(12):
    a=i*math.tau/12;r=.59
    # Petal relief is confined to the platform outside the sitting cushion.
    pts=[]
    for j in range(13):
        t=j/12*math.tau;rr=r+.072*math.cos(t)
        tang=.032*math.sin(t)
        pts.append((rr*math.cos(a)-tang*math.sin(a),rr*math.sin(a)+tang*math.cos(a),.142))
    curve('Meditation lotus gold petal inlay',pts,.0045,gold,meditation,True)

# Book cabinet faces +X into the room.  Sparse shelves hold scroll cubbies,
# with the actual books and ceramics left to the decor script.
bookcase=group('FURN_BOOKCASE','藏经小书阁',(-3.65,-.2,0),0)
cube('Bookcase solid back',(-.247,0,.97),(.045,1.65,1.75),darkwood,.015,bookcase)
for y in [-.79,.79]:
    cube('Bookcase tall side',(-.005,y,.98),(.53,.065,1.76),wood,.014,bookcase)
for z in [.12,.60,1.08,1.52,1.86]:
    cube('Bookcase shelf',(0,0,z),(.55,1.67,.065),wood,.012,bookcase)
    cube('Bookcase shelf golden lip',(.282,0,z+.01),(.013,1.58,.012),gold,.003,bookcase)
for y in [-.78,.78]:
    for x in [-.17,.17]:cube('Bookcase carved feet',(x,y,.045),(.10,.11,.09),wood,.016,bookcase)
for y in [-.26,.27]:
    cube('Bookcase upper scroll cubby divider',(0,y,1.70),(.48,.035,.30),wood,.006,bookcase)
for y in [-.40,.38]:
    cube('Bookcase middle cubby divider',(0,y,1.30),(.48,.035,.36),wood,.006,bookcase)
cube('Bookcase carved top crown',(0,0,1.92),(.59,1.74,.08),wood,.023,bookcase)
for y in [-.55,0,.55]:
    # A restrained positive-X-facing cloud cartouche.
    curve('Bookcase crown ruyi',[(.302,y-.14,1.924),(.302,y-.07,1.946),
          (.302,y,1.925),(.302,y+.07,1.946),(.302,y+.14,1.924)],.006,gold,bookcase)

altar=_table('FURN_ALTAR','凝香供案',(-2.65,-2.9,0),1.8,.50,.82)
for x in [-.75,.75]:
    # Upturned ends, characteristic of the scholar's incense table.
    curve('Altar upturned end',( (x,-.23,.82),(x*1.10,-.23,.84),(x*1.14,-.23,.89)),.022,wood,altar)
    curve('Altar upturned back end',((x,.23,.82),(x*1.10,.23,.84),(x*1.14,.23,.89)),.022,wood,altar)

# Display sword and carved trestle.  The jade blade is a closed bevelled
# diamond prism with a clear ridge, not a flat triangle or modern katana.
swordstand=group('FURN_SWORD_STAND','青霄灵剑架',(3.45,-.95,0),0)
cube('Sword stand stepped foot',(0,0,.065),(.50,.55,.12),wood,.045,swordstand)
cube('Sword stand gilt foot edge',(0,0,.123),(.45,.50,.013),gold,.008,swordstand)
for y in [-.17,.17]:
    curve('Sword stand carved upright',[(0,y,.13),(.045,y,.28),(.025,y,.47),(0,y,.70)],.025,wood,swordstand)
    curve('Sword stand jade ruyi cradle',[(-.16,y,.67),(-.14,y,.71),(-.08,y,.70),
          (0,y,.64),(.08,y,.70),(.14,y,.71),(.16,y,.67)],.018,jade,swordstand)
# Sword points upwards; its centreline leans slightly towards the open room.
sw=empty('PROP_JADE_SWORD',(-.015,0,.25),swordstand)
sw.rotation_euler[1]=-.10
v=[(-.052,0,.48),(0,-.018,.48),(.052,0,.48),(0,.018,.48),
   (-.044,0,1.22),(0,-.014,1.22),(.044,0,1.22),(0,.014,1.22),(0,0,1.47)]
f=[(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),
   (4,5,8),(5,6,8),(6,7,8),(7,4,8),(0,3,2,1)]
mesh('Sword luminous jade double edged blade',v,f,jade,sw)
curve('Sword blade central gold inscription',[(0,-.019,.61),(0,-.017,1.20)],.003,gold,sw)
for z in [.73,.85,.97,1.09]:
    curve('Sword small cloud inscription',[(-.012,-.020,z+.012),(.010,-.020,z),(-.006,-.020,z-.015)],.002,gold,sw)
curve('Sword winged gilded crossguard',[(-.19,0,.50),(-.15,0,.55),
      (-.07,0,.56),(0,0,.53),(.07,0,.56),(.15,0,.55),(.19,0,.50)],.027,gold,sw)
sphere('Sword guard central jade jewel',(0,-.026,.54),(.045,.018,.029),jade,sw,16,8)
cylinder('Sword wrapped hilt',(0,0,.31),.035,.34,plum,sw,12)
for z in [.17,.21,.25,.29,.33,.37,.41,.45]:
    _ring('Sword hilt golden winding',(0,0,z),.036,gold,sw,.004,16)
sphere('Sword jade pommel',(0,0,.115),(.052,.044,.055),jade,sw,16,8)
curve('Sword tied red tassel',[(.035,0,.13),(.12,-.015,.09),(.18,-.015,.16),(.22,-.015,.10),
      (.19,-.015,-.015)],.010,rose,sw)
for k in range(7):
    curve('Sword fine tassel strands',[(.19+(k-3)*.007,-.015,-.015),
          (.20+(k-3)*.008,-.015,-.11)],.003,rose,sw)

# Tiny embroidery and inlay do not need an eight-sided tube.  Four sides are
# indistinguishable at room distance, and keep the mobile GLB economical.
for _obj in S.objects:
    if _obj not in _dream3_furniture_existing and _obj.type=='CURVE' and _obj.data.bevel_depth<=.014:
        _obj.data.bevel_resolution=0

print('DREAM3 furniture complete:',len(S.objects),'objects')
