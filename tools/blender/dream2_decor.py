"""Interior-only nautical decoration for the second Dream Bedroom.

Executed in build_dream_bedroom_2.py's construction namespace. All ceiling
ornaments follow CEILING; wall shelving follows WALL_WEST via FURN_BOOKCASE.
There are deliberately no sky, exterior, backdrop, or world-space star objects.
"""

def d2_curve(name, points, radius, material, parent, cyclic=False):
    ob = curve(name, points, radius, material, parent, cyclic)
    if ob and ob.type == 'CURVE':
        ob.data.bevel_resolution = 0
        ob.data.resolution_u = 1
    return ob


def d2_axis_cylinder(name, a, b, radius, material, parent, vertices=16,
                     radius2=None):
    a, b = Vector(a), Vector(b)
    ob = cylinder(name, tuple((a + b) * .5), radius, (b-a).length,
                  material, parent, vertices=vertices, radius2=radius2)
    ob.rotation_euler = (b-a).to_track_quat('Z', 'Y').to_euler()
    return ob


def d2_ring(name, centre, radius, material, parent, plane='XY', wire=.012,
            segments=24):
    cx, cy, cz = centre
    points = []
    for i in range(segments):
        a = math.tau * i / segments
        if plane == 'XZ':
            points.append((cx+radius*math.cos(a), cy, cz+radius*math.sin(a)))
        elif plane == 'YZ':
            points.append((cx, cy+radius*math.cos(a), cz+radius*math.sin(a)))
        else:
            points.append((cx+radius*math.cos(a), cy+radius*math.sin(a), cz))
    return d2_curve(name, points, wire, material, parent, True)


def d2_star(name, centre, radius, parent):
    """A solid, shallow ten-vertex brass ornament, suspended in the XZ plane."""
    cx, cy, cz = centre
    verts = []
    for yy in [-.007, .007]:
        for i in range(10):
            a = math.pi/2 + i*math.pi/5
            rr = radius if not i%2 else radius*.43
            verts.append((cx+rr*math.cos(a), cy+yy, cz+rr*math.sin(a)))
    faces = [tuple(reversed(range(10))), tuple(range(10,20))]
    faces += [(i, (i+1)%10, (i+1)%10+10, i+10) for i in range(10)]
    return mesh(name, verts, faces, brass, parent)


# Two airy fishing-net sails sit below the transparent roof, leaving its centre
# open. The net is a sparse open lattice, never a textured/opaque ceiling sheet.
net_group = empty('DECOR_CEILING_NETS', parent=ceiling_group)

def d2_net_point(x0, x1, y0, y1, u, v, sag):
    x = x0 + (x1-x0)*u
    y = y0 + (y1-y0)*v
    roof = 4.85 - .425*abs(x)
    z = roof - .15 - sag*math.sin(math.pi*u)*math.sin(math.pi*v)
    return (x, y, z)

for panel_id, bounds, sag in [
    (1, (-3.48, -.52, -.85, 3.10), .43),
    (2, (.18, 3.34, .50, 3.08), .31),
]:
    x0, x1, y0, y1 = bounds
    # Outer ropes make the construction readable from a human eye height.
    for fixed, edge_type in [(0, 'U'), (1, 'U'), (0, 'V'), (1, 'V')]:
        pts=[]
        for j in range(25):
            t=j/24
            u,v=(fixed,t) if edge_type=='U' else (t,fixed)
            pts.append(d2_net_point(x0,x1,y0,y1,u,v,sag))
        d2_curve('Netted sail %d border rope'%panel_id, pts, .011, cream, net_group)
    # The transverse lines form a low-density, slightly irregular fishing mesh.
    for i in range(1,10):
        u=i/10
        pts=[d2_net_point(x0,x1,y0,y1,u,j/16,sag) for j in range(17)]
        d2_curve('Netted sail %d warp'%panel_id,pts,.0035,cream,net_group)
    for j in range(1,12):
        v=j/12
        pts=[d2_net_point(x0,x1,y0,y1,i/16,v,sag) for i in range(17)]
        d2_curve('Netted sail %d weft'%panel_id,pts,.0035,cream,net_group)
    # Light glints follow one internal diagonal; no floating sky star field.
    pts=[]
    for j in range(49):
        t=j/48
        p=Vector(d2_net_point(x0,x1,y0,y1,t,.12+.78*t,sag))
        p.z-=.025
        pts.append(tuple(p))
    d2_curve('Netted sail %d fairy cable'%panel_id,pts,.005,brass,net_group)
    for j in range(21):
        t=j/20
        p=Vector(d2_net_point(x0,x1,y0,y1,t,.12+.78*t,sag));p.z-=.047
        sphere('Netted sail amber seed bulb',tuple(p),(.018,.018,.027),glow,
               net_group,segments=8,rings=4)

# A second soft festoon sweeps around the north gable underneath the glazing.
festoon=empty('DECOR_ROOF_FESTOON',parent=ceiling_group)
festoon_points=[]
for i in range(65):
    t=i/64;x=-3.55+7.10*t
    z=4.85-.425*abs(x)-.19-.42*math.sin(math.pi*t)**2
    festoon_points.append((x,2.95,z))
d2_curve('Gable drooping brass light cable',festoon_points,.008,brass,festoon)
for i in range(27):
    t=i/26;x=-3.55+7.10*t
    z=4.85-.425*abs(x)-.215-.42*math.sin(math.pi*t)**2
    sphere('Gable warm pearl bulb',(x,2.95,z),(.020,.020,.030),glow,
           festoon,segments=8,rings=4)

# Five hand-blown glass floats and little brass stars, hung inside the room.
# Their cords are individually tied to the glass-roof beams.
float_group=empty('DECOR_HANGING_GLASS_FLOATS',parent=ceiling_group)
for i,(x,y,r,drop) in enumerate([
    (-3.10,-.45,.16,.46),(-1.72,1.25,.12,.63),
    (.50,2.72,.14,.57),(2.85,1.42,.18,.48),(1.18,-1.30,.11,.56)
]):
    top=4.85-.425*abs(x)-.07
    z=top-drop-r
    d2_curve('Glass float suspension cord',[(x,y,top),(x,y,z+r+.03)],
             .006,cream,float_group)
    d2_ring('Float brass hanging eye',(x,y,z+r+.04),.028,brass,float_group,'XZ',.007,12)
    cylinder('Float brass neck',(x,y,z+r*.92),r*.24,.045,brass,float_group,vertices=12)
    sphere('Suspended clear sea-glass globe',(x,y,z),(r,r,r),glass,float_group,
           segments=16,rings=8)
    # An interior small luminous bead and a gold equator retain a readable globe
    # in real-time alpha blending without relying on transmission shaders.
    sphere('Sea-glass globe warm heart',(x,y,z-r*.28),(r*.18,r*.18,r*.18),
           glow,float_group,segments=8,rings=4)
    d2_ring('Sea-glass float equatorial binding',(x,y,z),r*1.015,
            brass,float_group,wire=.0045,segments=20)
    sx=x+(.23 if i%2 else -.25);sz=z-.12
    d2_curve('Hanging star fine chain',[(sx,y,top-.03),(sx,y,sz+.10)],
             .003,brass,float_group)
    d2_star('Suspended brass star',(sx,y,sz),.10 if i%2 else .12,float_group)


# Three floating oak shelves. FURN_BOOKCASE is centred at world z=1.30m;
# local -Y faces the interior after its 90-degree yaw.
shelf_levels=[-.25,.45,1.10]
for z in shelf_levels:
    cube('Floating oak library shelf',(0,-.125,z),(2.76,.39,.075),oak,.018,bookcase)
    for x in [-1.16,1.16]:
        rod('Shelf brass diagonal bracket',(x,.015,z-.22),(x,-.255,z-.045),
            .012,brass,bookcase,vertices=8)
        rod('Shelf brass wall bracket',(x,.015,z-.22),(x,.015,z-.045),
            .010,brass,bookcase,vertices=8)


def d2_standing_book(parent, x, z, width, height, material, lean=0):
    g=empty('Library individual volume',(x,-.115,z+.04),parent)
    g.rotation_euler.y=lean
    cube('Book linen cover',(0,0,height*.5),(width,.228,height),material,.005,g)
    cube('Book cream page block',(.006,-.010,height*.5),(width*.77,.214,height-.025),cream,.003,g)
    cube('Book coloured spine',(0,-.122,height*.5),(width,.018,height),material,.003,g)
    for dz in [.065,height-.060]:
        cube('Book gilt spine band',(0,-.134,dz),(width*.83,.003,.010),brass,.001,g)
    return g

rng=random.Random(241024)
for row,z in enumerate(shelf_levels):
    cursor=-1.20
    # Deliberate gaps permit a little lantern/nautical model and trailing leaves.
    intervals=[(-1.20,-.18),(.34,1.16)] if row==0 else [(-.91,.49)] if row==1 else [(-1.10,-.20),(.34,1.13)]
    for left,right in intervals:
        cursor=left
        while cursor<right-.075:
            w=rng.uniform(.055,.095);h=rng.uniform(.25,.40)
            d2_standing_book(bookcase,cursor+w*.5,z,w,h,
                             bookmats[rng.randrange(len(bookmats))],rng.uniform(-.035,.035))
            cursor+=w+.014


def d2_book_stack(parent, loc, count=3, angle=0):
    x,y,z=loc
    g=empty('Stack of travel journals',(x,y,z),parent)
    g.rotation_euler.z=angle
    for i in range(count):
        w=.29-rng.uniform(0,.035);d=.22-rng.uniform(0,.02);h=.045+rng.uniform(0,.015)
        zz=i*.059+h*.5
        material=bookmats[(i+2)%len(bookmats)]
        cube('Journal cloth binding',(0,0,zz),(w,d,h),material,.005,g)
        cube('Journal visible cream pages',(.007,-.006,zz),(w-.022,d-.012,h-.016),cream,.002,g)
    return g

# A small original sailboat, sculpted as wood hull plus stitched ivory sails.
ship=empty('Shelf miniature sailboat',(.84,-.10,.50),bookcase)
mesh('Miniature carved ship hull',[(-.29,-.07,0),(.29,-.07,0),(.24,.07,0),(-.24,.07,0),(-.20,-.045,-.09),(.18,-.045,-.09),(.18,.045,-.09),(-.20,.045,-.09)],
     [(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(0,3,2,1),(4,5,6,7)],oak,ship)
rod('Model ship mast',(0,0,0),(0,0,.43),.009,brass,ship,vertices=8)
mesh('Model ship cream mainsail',[(.018,0,.40),(.018,0,.06),(.25,0,.06)],[(0,1,2)],cream,ship)
mesh('Model ship blue jib',[(-.018,0,.35),(-.018,0,.06),(-.20,0,.06)],[(0,1,2)],blue,ship)
d2_curve('Model ship rigging',[(-.24,0,0),(0,0,.43),(.25,0,0)],.003,cream,ship)
cube('Ship model display plinth',(0,0,-.13),(.47,.19,.045),darkwood,.012,ship)
d2_book_stack(bookcase,(.07,-.12,-.20),2,.07)


# Bronze candle lanterns: luminous interiors remain visible in GLB.
def d2_lantern(parent,loc,scale=1):
    g=empty('Nautical brass candle lantern',loc,parent)
    g.scale=(scale,scale,scale)
    cylinder('Lantern bronze foot',(0,0,.025),.115,.05,brass,g,vertices=16)
    cylinder('Lantern dark pedestal',(0,0,.060),.090,.023,darkwood,g,vertices=16)
    cylinder('Lantern transparent glazing',(0,0,.190),.088,.25,glass,g,vertices=12)
    cylinder('Lantern ivory wax candle',(0,0,.145),.033,.15,cream,g,vertices=12)
    sphere('Lantern warm flame',(0,0,.238),(.019,.019,.046),glow,g,segments=8,rings=4)
    for i in range(4):
        a=math.pi/4+i*math.pi/2
        x,y=.089*math.cos(a),.089*math.sin(a)
        rod('Lantern brass corner rib',(x,y,.073),(x,y,.318),.009,brass,g,vertices=8)
    cylinder('Lantern cap rim',(0,0,.322),.112,.034,brass,g,vertices=16)
    cylinder('Lantern tapered weather hood',(0,0,.368),.105,.070,brass,g,vertices=16,radius2=.040)
    sphere('Lantern cap finial',(0,0,.417),(.023,.023,.027),brass,g,segments=8,rings=4)
    pts=[(.070*math.cos(math.pi*i/16),0,.427+.085*math.sin(math.pi*i/16)) for i in range(17)]
    d2_curve('Lantern swing carrying handle',pts,.009,brass,g)
    return g

# Heights are the actual furniture top surfaces, rather than arbitrary floats.
d2_lantern(nightstand,(0,.015,.64),1.04)
d2_book_stack(chest,(-.12,.0,.72),3,-.10)
d2_lantern(chest,(.30,.03,.72),1.00)
d2_book_stack(sidetable,(.02,.02,.66),2,.13)
d2_lantern(sidetable,(.0,.03,.779),.86)
d2_lantern(bookcase,(-1.08,-.10,.50),.66)

# A ceramic cup and a folded navigation chart occupy the window writing desk.
cube('Desk folded nautical chart',(-.15,-.08,.839),(.53,.36,.010),cream,.004,desk)
for j in range(4):
    d2_curve('Chart blue contour',[
        (-.37+i*.045,-.17+j*.063+.023*math.sin(i*.8+j),.846)
        for i in range(11)],.0018,blue,desk)
cylinder('Desk teacup body',(.38,-.16,.912),.061,.14,white,desk,vertices=16)
cylinder('Desk tea surface',(.38,-.16,.985),.050,.004,darkwood,desk,vertices=16)
d2_curve('Desk teacup loop handle',[(.430,-.16,.963),(.481,-.16,.965),(.493,-.16,.918),(.476,-.16,.882),(.432,-.16,.884)],.009,white,desk)
cylinder('Desk teacup saucer',(.38,-.16,.845),.096,.013,white,desk,vertices=20)
d2_book_stack(desk,(-.56,.02,.834),2,-.12)
# Compact brass reading lamp, a visibly solid shade instead of many bulbs.
cylinder('Desk reading lamp weighted base',(.65,.03,.848),.125,.03,brass,desk,vertices=16)
d2_curve('Desk reading lamp swan neck',[(.65,.03,.864),(.65,.03,1.194),(.54,.03,1.284),(.45,.03,1.244)],.014,brass,desk)
sphere('Desk reading lamp enamel shade',(.43,.03,1.234),(.15,.10,.065),blue,desk,segments=16,rings=8)
sphere('Desk reading lamp warm lens',(.43,.03,1.194),(.104,.070,.013),glow,desk,segments=12,rings=4)


# Broad folded leaves have a central ridge and a pointed silhouette. They remain
# intentionally low poly so this scene is practical as a mobile-room asset.
def d2_leaf(parent,base,tip,width,material):
    a,b=Vector(base),Vector(tip)
    length=b-a
    side=length.cross(Vector((0,0,1)))
    if side.length<.001:side=Vector((1,0,0))
    side.normalize();mid=a.lerp(b,.50)
    ridge=mid+Vector((0,0,width*.18))
    return mesh('Pointed sculpted green leaf',[tuple(a),tuple(mid+side*width),tuple(b),tuple(mid-side*width),tuple(ridge)],
                [(0,1,4),(1,2,4),(2,3,4),(3,0,4)],material,parent)


def d2_plant(parent,loc,size=.65,trailing=False):
    g=empty('Interior leafy ceramic planter',loc,parent)
    pr=size*.14;ph=size*.27
    cylinder('Ceramic tapered pot',(0,0,ph*.5),pr*.73,ph,cream,g,vertices=16,radius2=pr)
    cylinder('Ceramic pot rolled lip',(0,0,ph),pr*1.06,.028,blue,g,vertices=16)
    cylinder('Planter dark soil',(0,0,ph+.004),pr*.90,.01,darkwood,g,vertices=16)
    for i in range(7):
        a=i*2.399+.2;reach=size*rng.uniform(.20,.38)
        tip=Vector((math.cos(a)*reach,math.sin(a)*reach,size*rng.uniform(.55,.95)))
        base=Vector((0,0,ph));mid=base.lerp(tip,.48)
        d2_curve('Plant curved petiole',[tuple(base),tuple(mid+Vector((0,0,.04))),tuple(tip)],.004,leafmats[0],g)
        d2_leaf(g,tuple(base.lerp(tip,.36)),tuple(tip),size*.075,leafmats[i%len(leafmats)])
    if trailing:
        for k in range(3):
            xx=(k-1)*pr*.9
            pts=[(xx+.035*math.sin(t*6+k),-.09-t*.12,ph-.70*t*size) for t in [i/18 for i in range(19)]]
            d2_curve('Trailing indoor vine stem',pts,.004,leafmats[0],g)
            for j in range(7):
                t=(j+1)/8
                base=Vector((xx+.035*math.sin(t*6+k),-.09-t*.12,ph-.70*t*size))
                side=-1 if (j+k)%2 else 1
                tip=base+Vector((side*.12*size,-.040,.012-.08*size))
                d2_leaf(g,tuple(base),tuple(tip),size*.050,leafmats[(j+k)%len(leafmats)])
    return g

# Shelving foliage stays with the wall when the room is cut away.
d2_plant(bookcase,(-1.13,-.12,1.15),.68,True)
d2_plant(bookcase,(1.12,-.12,1.15),.52,True)
d2_plant(bench,(1.34,.02,.62),.70,False)


# Collected volumes sit inside the window-bench cubbies, leaving its top open.
for left,right in [(-1.60,-1.00),(-.73,-.14),(.12,.73),(.98,1.57)]:
    cursor=left
    while cursor<right-.07:
        w=rng.uniform(.055,.092)
        d2_standing_book(bench,cursor+w/2,.10,w,rng.uniform(.25,.35),
                         bookmats[rng.randrange(len(bookmats))],rng.uniform(-.035,.035))
        cursor+=w+.022


# A functional-looking brass refractor and its splayed three-leg wood tripod.
# The optical axis is aimed toward the NE window, never toward the floor.
mount_z=1.20
for i in range(3):
    a=math.radians(20)+i*math.tau/3
    foot=Vector((.51*math.cos(a),.51*math.sin(a),.035))
    knee=Vector((.17*math.cos(a),.17*math.sin(a),.86))
    top=Vector((0,0,mount_z))
    rod('Telescope polished tripod wood leg',tuple(foot),tuple(knee),.025,oak,telescope,vertices=10)
    rod('Telescope brass telescoping leg',tuple(knee),tuple(top),.018,brass,telescope,vertices=10)
    sphere('Telescope rubber foot',tuple(foot),(.038,.038,.023),black,telescope,segments=8,rings=4)
    rod('Telescope tripod spreader',tuple(foot.lerp(top,.42)),(0,0,.55),.010,brass,telescope,vertices=8)
cylinder('Telescope azimuth mount',(0,0,1.225),.11,.080,brass,telescope,vertices=16)
rod('Telescope vertical cradle',(0,0,1.23),(0,0,1.43),.028,black,telescope,vertices=12)
axis=Vector((.69,.55,.37)).normalized()
centre=Vector((0,0,1.51));back=centre-axis*.43;front=centre+axis*.56
d2_axis_cylinder('Telescope long brass optical tube',back,front,.080,brass,telescope,vertices=24)
# Three dark bands, the rolled objective rim, lens, and a fine brass focus tube.
for t in [-.29,.14,.51]:
    p=centre+axis*t
    d2_axis_cylinder('Telescope black optical collar',p-axis*.028,p+axis*.028,.086,black,telescope,vertices=20)
d2_axis_cylinder('Telescope brass objective hood',front-axis*.08,front+axis*.04,.105,brass,telescope,vertices=24)
d2_axis_cylinder('Telescope blue glass objective',front+axis*.041,front+axis*.047,.091,glass,telescope,vertices=24)
d2_axis_cylinder('Telescope dark lens interior',front+axis*.033,front+axis*.037,.087,blue,telescope,vertices=20)
d2_axis_cylinder('Telescope rear focus drawtube',back-axis*.14,back,.038,black,telescope,vertices=16)
d2_axis_cylinder('Telescope brass ocular rim',back-axis*.17,back-axis*.12,.045,brass,telescope,vertices=16)
rod('Telescope altitude adjustment handle',(.01,-.01,1.28),(-.24,-.30,1.19),.012,brass,telescope,vertices=8)
sphere('Telescope adjustment grip',(-.24,-.30,1.19),(.031,.045,.030),darkwood,telescope,segments=8,rings=4)
# Small finder scope sits above the main body, parallel to its optical axis.
fcentre=centre+Vector((0,0,.135))
d2_axis_cylinder('Telescope finder scope',fcentre-axis*.19,fcentre+axis*.21,.025,black,telescope,vertices=12)
for t in [-.11,.13]:
    p=fcentre+axis*t
    rod('Telescope finder bracket',tuple(p-Vector((0,0,.09))),tuple(p),.007,brass,telescope,vertices=8)

print('DREAM BEDROOM 2 decor: interior nets, warm festoons, glass floats, library, plants and telescope complete.')
