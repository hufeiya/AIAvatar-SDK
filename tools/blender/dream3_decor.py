"""Peachveil Sanctuary: original modeled interior decoration.

Executed in build_dream_bedroom_3.py's namespace. All hanging decoration is a
child of CEILING, with no sky, backdrop, landscape, or outside particle field.
Blossoms are linked native mesh variants; no downloaded asset is used.
"""
from mathutils import Vector, Matrix
import math, random

_d3_rng = random.Random(31024)


def d3_mat(name, rgb, roughness=.55, metallic=0., emission=0.):
    m = bpy.data.materials.new('D3 · ' + name)
    m.use_nodes = True
    p = next(n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
    p.inputs['Base Color'].default_value = (*rgb, 1)
    p.inputs['Roughness'].default_value = roughness
    p.inputs['Metallic'].default_value = metallic
    if emission:
        p.inputs['Emission Color'].default_value = (*rgb, 1)
        p.inputs['Emission Strength'].default_value = emission
    m.diffuse_color = (*rgb, 1)
    return m


_d3_paper = d3_mat('Honey-lit rice paper', (1., .78, .48), .9, emission=.36)
_d3_garnet = d3_mat('Peach-red braided silk', (.46, .075, .105), .66)
_d3_tea = d3_mat('Amber jasmine tea', (.15, .055, .015), .18)
_d3_peachgold = d3_mat('Peach flower pollen', (.94, .52, .11), .48, .15)
_d3_brushwhite = d3_mat('Goat-hair brush tip', (.81, .71, .53), .9)
_d3_papermat = d3_mat('Calligraphy xuan paper', (.89, .81, .63), .96)
_d3_enamel = d3_mat('Celadon pale porcelain', (.42, .67, .57), .22)


def d3_curve(name, points, radius, material, parent, cyclic=False):
    o = curve(name, points, radius, material, parent, cyclic)
    o.data.resolution_u = 1
    o.data.bevel_resolution = 0
    return o


def d3_ring(name, centre, radius, material, parent, plane='XY', wire=.008,
            segments=32):
    cx, cy, cz = centre
    pts = []
    for k in range(segments):
        a = math.tau * k / segments
        if plane == 'XZ':
            pts.append((cx+radius*math.cos(a), cy, cz+radius*math.sin(a)))
        elif plane == 'YZ':
            pts.append((cx, cy+radius*math.cos(a), cz+radius*math.sin(a)))
        else:
            pts.append((cx+radius*math.cos(a), cy+radius*math.sin(a), cz))
    return d3_curve(name, pts, wire, material, parent, True)


def d3_lathe(name, loc, profile, material, parent, sides=24):
    """Hollow rims and ceramic profiles, built directly as a native mesh."""
    vs = [(r*math.cos(math.tau*i/sides), r*math.sin(math.tau*i/sides), z)
          for r,z in profile for i in range(sides)]
    faces = []
    for row in range(len(profile)-1):
        for i in range(sides):
            j = (i+1) % sides
            faces.append((row*sides+i, row*sides+j,
                          (row+1)*sides+j, (row+1)*sides+i))
    o = mesh(name, vs, faces, material, parent)
    o.location = loc
    for p in o.data.polygons:
        p.use_smooth = True
    return o


# Five petals curve upward at the rim, around a small raised pollen centre.
# Four hand-varied masters are linked to all blossoms, keeping geometry small.
_d3_flower_meshes = []
for variant in range(4):
    vs, fs, inds = [], [], []
    for p in range(5):
        a = math.tau*p/5 + .03*variant
        # Petal axes: narrow at the heart and gently notched at the outer tip.
        local = [(0., .14, .045), (-.21, .30, .06), (-.32, .60, .12),
                 (-.21, .86, .18), (-.055, .93, .14), (0., .87, .14),
                 (.055, .93, .14), (.21, .86, .18), (.32, .60, .12),
                 (.21, .30, .06)]
        start = len(vs)
        for x,y,z in local:
            vs.append((x*math.cos(a)-y*math.sin(a),
                       x*math.sin(a)+y*math.cos(a), z))
        vs.append((-.43*math.sin(a), .43*math.cos(a), -.035))
        for k in range(len(local)):
            fs.append((start+k, start+(k+1)%len(local), start+len(local)))
            inds.append((p+variant)%len(petal_mats))
    # A small faceted pollen dome, plus five bright stamen tips.
    for k in range(7):
        a = math.tau*k/7
        vs.append((.105*math.cos(a), .105*math.sin(a), .10))
    ci = len(vs); vs.append((0,0,.20))
    for k in range(7):
        fs.append((ci-7+k,ci-7+(k+1)%7,ci));inds.append(len(petal_mats))
    for k in range(5):
        a = math.tau*k/5
        cx, cy = .16*math.cos(a), .16*math.sin(a)
        start=len(vs)
        vs += [(cx-.028,cy,.10),(cx+.028,cy,.10),(cx,cy-.028,.10),
               (cx,cy+.028,.10),(cx,cy,.22)]
        fs += [(start,start+2,start+4),(start+2,start+1,start+4),
               (start+1,start+3,start+4),(start+3,start,start+4)]
        inds += [len(petal_mats)]*4
    data=bpy.data.meshes.new('Sculpted five-petal peach blossom master %d'%variant)
    data.from_pydata(vs,[],fs);data.update()
    for m in list(petal_mats)+[_d3_peachgold]:data.materials.append(m)
    for p,mi in zip(data.polygons,inds):p.material_index=mi;p.use_smooth=True
    _d3_flower_meshes.append(data)


def d3_blossom(parent, loc, size=.075, normal=(0,-1,.3), variant=0):
    o=bpy.data.objects.new('Peach blossom · sculpted five petals',
                          _d3_flower_meshes[variant%4])
    S.collection.objects.link(o);o.parent=parent;o.location=loc
    o.scale=(size,size,size)
    o.rotation_euler=Vector(normal).normalized().to_track_quat('Z','Y').to_euler()
    o.rotation_euler.rotate_axis('Z', _d3_rng.uniform(-math.pi,math.pi))
    return o


def d3_leaf(parent, loc, size, angle, material):
    o=mesh('Young peach leaf',[(0,0,0),(-.25,.52,.10),(0,1.,0),
                             (.25,.52,.10),(0,.46,.16)],
           [(0,1,4),(1,2,4),(2,3,4),(3,0,4)],material,parent)
    o.location=loc;o.scale=(size,size,size)
    o.rotation_euler=(.5,_d3_rng.uniform(-.6,.6),angle)
    return o


# Full-bodied hexagonal paper lanterns with tapered shoulders, bamboo ribs,
# tiny painted botanical silhouettes, knotted beads, and separate silk strands.
lanterns=empty('DECOR_HEXAGONAL_PAPER_LANTERNS',parent=ceiling_group)


def d3_lantern(x,y,drop,rr,index):
    beam_z=4.65-.35*abs(x)-.10
    centre_z=beam_z-drop-.34
    g=empty('Hexagonal blossom lantern %02d'%index,(x,y,centre_z),lanterns)
    d3_curve('Lantern suspension silk',[(0,0,beam_z-centre_z),(0,0,.36)],
             .005,darkwood,g)
    d3_ring('Lantern hanger loop',(0,0,.355),.026,gold,g,'XZ',.006,12)
    # Six luminous individual paper panes leave visible wooden seams.
    profile=[(rr*.75,-.29),(rr,-.23),(rr,.23),(rr*.72,.30)]
    for k in range(6):
        a=math.tau*k/6; b=math.tau*(k+1)/6
        pts=[]
        for r,z in profile:
            pts.extend([(r*math.cos(a),r*math.sin(a),z),
                        (r*math.cos(b),r*math.sin(b),z)])
        mesh('Lantern warm translucent paper panel',pts,
             [(0,1,3,2),(2,3,5,4),(4,5,7,6)],_d3_paper,g)
        d3_curve('Carved lantern bamboo upright',
                 [(r*math.cos(a),r*math.sin(a),z) for r,z in profile],
                 .008,wood,g)
    for r,z in profile:
        d3_curve('Lantern hexagonal wood rim',
                 [(r*math.cos(math.tau*k/6),r*math.sin(math.tau*k/6),z)
                  for k in range(6)],.011,darkwood,g,True)
        d3_curve('Lantern hexagonal gold inset',
                 [(r*1.025*math.cos(math.tau*k/6),r*1.025*math.sin(math.tau*k/6),z+.012)
                  for k in range(6)],.0035,gold,g,True)
    cylinder('Lantern crown finial',(0,0,.327),rr*.20,.045,gold,g,vertices=12,
             radius2=rr*.10)
    cylinder('Lantern pendant neck',(0,0,-.322),rr*.16,.045,gold,g,vertices=12)
    sphere('Lantern pendant porcelain bead',(0,0,-.37),(.027,.027,.042),
           ivory,g,segments=10,rings=5)
    d3_curve('Lantern tassel knot',[(0,0,-.40),(.023,0,-.425),(0,0,-.45),
                                 (-.023,0,-.425),(0,0,-.40)],.007,_d3_garnet,g)
    # Ten subtly bowed threads give a lightweight, silky silhouette.
    for k in range(10):
        a=math.tau*k/10
        d3_curve('Long peach silk lantern tassel',
                 [(.010*math.cos(a),.010*math.sin(a),-.46),
                  (.022*math.cos(a),.022*math.sin(a),-.57),
                  (.026*math.cos(a)+.018,.026*math.sin(a),-.76)],
                 .0028,rose,g)
    cylinder('Tassel gilt binding',(0,0,-.477),.017,.025,gold,g,vertices=10)
    # Flat botanical branch and petal marks on three alternate paper faces.
    for face in [0,2,4]:
        a=math.tau*(face+.5)/6
        n=Vector((math.cos(a),math.sin(a),0));u=Vector((-math.sin(a),math.cos(a),0))
        base=n*(rr*math.cos(math.pi/6)+.002)
        def facept(h,z):return tuple(base+u*h+Vector((0,0,z)))
        d3_curve('Lantern painted peach twig',
                 [facept(-rr*.28,-.17),facept(-rr*.05,-.06),
                  facept(rr*.05,.045),facept(rr*.32,.16)],.0022,darkwood,g)
        for j,(h,z) in enumerate([(-.16,-.11),(.02,.02),(.21,.12)]):
            h*=rr
            p=Vector(facept(h,z))
            # Five small graphic petals sit just above the paper pane.
            for q in range(5):
                ang=math.tau*q/5
                v=u*math.cos(ang)+Vector((0,0,1))*math.sin(ang)
                c=p+v*.014
                tangent=u*(-math.sin(ang))+Vector((0,0,1))*math.cos(ang)
                vs=[tuple(p),tuple(c+tangent*.010),tuple(p+v*.030),
                    tuple(c-tangent*.010)]
                mesh('Lantern painted pink blossom petal',vs,[(0,1,2,3)],rose,g)
    return g


for idx,(x,y,drop,rr) in enumerate([
    (-3.15,1.15,.37,.20),(-2.60,-2.30,.34,.17),
    (-.25,2.78,.55,.22),(.05,-.45,.67,.235),
    (3.10,1.65,.37,.19),(3.12,-2.55,.30,.17)],1):
    d3_lantern(x,y,drop,rr,idx)


# Deliberate indoor branches track the upper north gable and east eave. They
# remain inside all four walls and belong to CEILING for camera cutaways.
blossom_beams=empty('DECOR_PEACH_BRANCHES_UNDER_BEAMS',parent=ceiling_group)
_north_path=[(-3.62,3.09,3.17),(-2.90,3.06,3.41),(-2.1,3.07,3.73),
             (-1.16,3.10,4.05),(-.25,3.08,4.26),(.62,3.08,4.13),
             (1.55,3.06,3.88),(2.50,3.08,3.53),(3.55,3.08,3.20)]
d3_curve('Old peach branch along north gable',_north_path,.035,wood,blossom_beams)
_east_path=[(3.60,-2.82,3.12),(3.57,-1.8,3.18),(3.58,-.70,3.24),
            (3.55,.40,3.25),(3.48,1.3,3.29),(3.57,2.62,3.20)]
d3_curve('Peach branch bound to east eave',_east_path,.029,wood,blossom_beams)


def d3_branch_spray(parent,path,count,side=0):
    for i in range(count):
        t=(i+.45)/count*(len(path)-1)
        j=min(int(t),len(path)-2);f=t-j
        start=Vector(path[j]).lerp(Vector(path[j+1]),f)
        # Twigs alternate into the room and downwards, staying below roof.
        delta=Vector((_d3_rng.uniform(-.22,.22),
                      _d3_rng.uniform(-.42,-.10) if not side else _d3_rng.uniform(-.19,.19),
                      _d3_rng.uniform(-.34,-.04)))
        if side:delta.x=-_d3_rng.uniform(.18,.47)
        end=start+delta
        mid=start.lerp(end,.58)+Vector((0,.025,.045))
        d3_curve('Peach flowering lateral twig',[tuple(start),tuple(mid),tuple(end)],
                 .0065,wood,parent)
        for q in [.48,.76,1.0]:
            p=start.lerp(end,q)+Vector((_d3_rng.uniform(-.04,.04),
                                      _d3_rng.uniform(-.025,.025),
                                      _d3_rng.uniform(-.035,.035)))
            normal=(-.4 if side else .1,-1.,_d3_rng.uniform(.05,.75))
            d3_blossom(parent,tuple(p),_d3_rng.uniform(.055,.088),normal,i+int(q*10))
        if i%2==0:
            d3_leaf(parent,tuple(mid),.15,_d3_rng.uniform(-2,2),leafmats[i%len(leafmats)])


d3_branch_spray(blossom_beams,_north_path,25)
d3_branch_spray(blossom_beams,_east_path,15,1)


# Irregular, larger clusters make the flower canopy read as a flowering peach
# branch at room scale.  The clusters follow living wood rather than a line of
# evenly spaced ornaments; their outer petals remain below the curved roof.
def d3_roof_limit(x):
    return 3.25+1.4*max(0.,1-abs(x)/4.2)**1.3

def d3_bloom_cluster(path,t,side,cluster_id):
    j=min(int(t),len(path)-2);f=t-j
    origin=Vector(path[j]).lerp(Vector(path[j+1]),f)
    # Branch tips turn inward; every centre remains within the room's walls.
    inward=Vector((-.23 if side else .04,-.04 if side else -.24,-.10))
    tip=origin+inward
    d3_curve('Flower-cluster supporting peach twig',
             [tuple(origin),tuple(origin.lerp(tip,.48)+Vector((0,0,.035))),tuple(tip)],
             .007,wood,blossom_beams)
    for k in range(5):
        a=math.tau*k/5+.37*cluster_id
        # A compact asymmetric bouquet, with several forward-facing flowers.
        delta=Vector((.13*math.cos(a),.07*math.sin(a),.10*math.sin(a+.4)))
        if side:delta=Vector((-.07*math.cos(a),.14*math.sin(a),.10*math.cos(a+.2)))
        point=tip+delta
        size=_d3_rng.uniform(.085,.119)
        point.x=max(-3.74,min(3.74,point.x))
        point.y=max(-3.24,min(3.22,point.y))
        # Reserve the flower's full radius, not only the centre, under roof.
        outer_x=min(4.2,abs(point.x)+size)
        point.z=min(point.z,d3_roof_limit(outer_x)-size-.055)
        normal=Vector((-.50 if side else .1,-1.,.15+(.35 if k%2 else 0)))
        d3_curve('Peach blossom cluster fine pedicel',[tuple(tip),tuple(point)],
                 .0035,wood,blossom_beams)
        d3_blossom(blossom_beams,tuple(point),size,tuple(normal),cluster_id+k)
    d3_leaf(blossom_beams,tuple(tip-Vector((0,0,.08))),.16,
            -.7+cluster_id*.8,leafmats[cluster_id%len(leafmats)])

# Thirteen north-gable clusters and seven east-eave clusters: 100 blossoms.
for i,t in enumerate([.25,.57,1.2,1.48,2.18,2.48,3.3,4.1,4.47,5.3,6.25,6.62,7.5]):
    d3_bloom_cluster(_north_path,t,0,i)
for i,t in enumerate([.30,.68,1.65,2.2,2.72,3.55,4.55]):
    d3_bloom_cluster(_east_path,t,1,13+i)


# One substantial celadon floor vase, entirely inside the north-west corner.
vase=group('FURN_PEACH_VASE','青瓷桃枝花器',(-3.55,2.0,0),0,True)
d3_lathe('Celadon vase with open mouth',(0,0,0),
         [(.09,.02),(.15,.04),(.22,.17),(.24,.34),(.20,.49),(.12,.59),
          (.10,.68),(.14,.70),(.14,.725),(.115,.725),(.084,.68),(.085,.60)],
         _d3_enamel,vase,32)
d3_ring('Vase fine gilt lip',(0,0,.723),.128,gold,vase,wire=.004,segments=28)
d3_ring('Vase foot band',(0,0,.05),.154,gold,vase,wire=.004,segments=28)
for i in range(5):
    a=-1.2+i*.61
    end=Vector((math.cos(a)*(.33+i*.025),math.sin(a)*.35,1.46+i*.12))
    start=Vector((.02*math.cos(a),.02*math.sin(a),.65))
    bend=start.lerp(end,.53)+Vector((-.05,.05,.04))
    d3_curve('Vase long peach stem',[tuple(start),tuple(bend),tuple(end)],.009,wood,vase)
    for k in range(5):
        t=.42+k*.13
        p=start.lerp(end,t)
        p+=Vector(((-1 if k%2 else 1)*.09,.025*math.sin(k),.015))
        d3_curve('Vase peach fine twig',[tuple(start.lerp(end,max(0,t-.12))),tuple(p)],
                 .004,wood,vase)
        d3_blossom(vase,tuple(p),.07 if k%2 else .085,
                   (.35,-1,.3),i+k)
    if i%2==0:d3_leaf(vase,tuple(bend),.17,a,leafmats[i%len(leafmats)])


# A celadon gongfu tea service is arranged on a raised carved timber tray.
service=empty('DECOR_GONGFU_TEA_SERVICE',parent=tea)
cube('Gongfu tea tray',(0,0,.510),(.82,.49,.045),darkwood,.018,service)
for x in [-.396,.396]:cube('Tea tray raised side',(x,0,.545),(.022,.49,.045),wood,.005,service)
for y in [-.232,.232]:cube('Tea tray raised end',(0,y,.545),(.78,.022,.045),wood,.005,service)
for j in range(7):cube('Tea tray fine drainage slat',(0,-.17+j*.055,.535),(.69,.012,.006),gold,.001,service)
# Teapot: bulbous body, fitted lid, jade bead, spout, loop handle.
d3_lathe('Celadon gongfu teapot',(-.13,.035,.542),
         [(.055,0),(.10,.018),(.13,.08),(.11,.145),(.07,.16),(.062,.17)],
         _d3_enamel,service,28)
d3_lathe('Teapot fitted lid',(-.13,.035,.712),
         [(0,.018),(.075,.008),(.085,0),(.071,-.006)],_d3_enamel,service,24)
sphere('Teapot jade lid bead',(-.13,.035,.742),(.019,.019,.022),jade,service,12,6)
d3_curve('Curved celadon pouring spout',[(-.025,.035,.59),(.035,.035,.61),(.070,.035,.69)],
         .024,_d3_enamel,service)
d3_curve('Celadon teapot generous loop handle',
         [(-.242,.035,.675),(-.32,.035,.70),(-.35,.035,.64),
          (-.31,.035,.584),(-.245,.035,.60)],.014,_d3_enamel,service)
for i,(x,y) in enumerate([(.17,-.10),(.29,.115),(-.08,-.15)]):
    d3_lathe('Porcelain tea tasting cup %d'%i,(x,y,.544),
             [(.026,0),(.035,.008),(.049,.061),(.049,.067),(.043,.067),
              (.035,.026),(.027,.018)],_d3_enamel,service,20)
    cylinder('Tea warm amber surface',(x,y,.590),.040,.002,_d3_tea,service,vertices=20)
    d3_ring('Teacup gilded lip',(x,y,.611),.047,gold,service,wire=.0017,segments=20)
    cylinder('Tea cup porcelain saucer',(x,y,.541),.067,.009,ivory,service,vertices=20)
# A little tea-leaf jar and its gilt lid sit behind the tray.
d3_lathe('Celadon tea leaf caddy',(.43,.12,.485),
         [(.055,0),(.065,.025),(.064,.145),(.046,.165)],jade,tea,24)
cylinder('Tea caddy gilt lid',(.43,.12,.658),.050,.015,gold,tea,vertices=20)
sphere('Tea caddy lid jade knob',(.43,.12,.676),(.014,.014,.012),jade,tea,10,5)


# Xuan paper with hand-built ink strokes, scroll rollers, an inkstone, brush
# rest, and two tipped brushes are all supported by the writing desk surface.
writing=empty('DECOR_CALLIGRAPHY_WRITING_TOOLS',parent=desk)
cube('Unrolled xuan paper',(-.16,-.02,.828),(.65,.40,.007),_d3_papermat,.003,writing)
for x in [-.491,.171]:
    rod('Scroll dark sandalwood roller',(x,-.235,.84),(x,.195,.84),.019,darkwood,writing,12)
    for y in [-.247,.207]:sphere('Scroll roller jade knob',(x,y,.84),(.025,.018,.025),jade,writing,10,5)
# Abstract original calligraphy, using a little family of tapered strokes.
for col in range(4):
    x=-.37+col*.14
    for row in range(3):
        y=.105-row*.105
        paths=[[(x-.035,y+.025,.835),(x+.028,y+.020,.835)],
               [(x-.016,y+.043,.835),(x-.021,y-.033,.835)],
               [(x-.040,y-.005,.835),(x+.034,y-.019,.835)],
               [(x+.005,y+.015,.835),(x+.038,y-.045,.835)]]
        for p in paths:d3_curve('Hand-painted calligraphy ink stroke',p,.0023,ink,writing)
# Vermilion seal block and stamp impression.
cube('Calligraphy cinnabar seal impression',(.11,-.17,.835),(.026,.028,.001),_d3_garnet,.001,writing)
cube('Carved jade seal stamp',(.44,.12,.865),(.055,.055,.075),jade,.008,writing)
cube('Seal vermilion base',(.44,.12,.828),(.054,.054,.006),_d3_garnet,.002,writing)
d3_lathe('Calligraphy carved inkstone',(.40,-.10,.825),
         [(.04,0),(.10,.012),(.105,.028),(.095,.045),(.071,.045),(.065,.030)],
         ink,writing,24)
cylinder('Inkstone ink pool',(.40,-.10,.854),.064,.003,ink,writing,vertices=24)
# Small mountain-shaped brush rest.
rest=mesh('Jade mountain brush rest',[(-.10,-.025,0),(.10,-.025,0),(.10,-.025,.026),
             (.05,-.025,.035),(.025,-.025,.075),(0,-.025,.04),
             (-.03,-.025,.065),(-.06,-.025,.025),(-.10,-.025,.035),
             (-.10,.025,0),(.10,.025,0),(.10,.025,.026),(.05,.025,.035),
             (.025,.025,.075),(0,.025,.04),(-.03,.025,.065),(-.06,.025,.025),(-.10,.025,.035)],
          [tuple(range(8,-1,-1)),tuple(range(9,18))]+[(i,(i+1)%9,(i+1)%9+9,i+9) for i in range(9)],jade,writing)
rest.location=(.02,.27,.825)
for i in range(2):
    a=Vector((-.14+i*.08,.27,.882));b=Vector((.13+i*.08,.27,.89))
    rod('Bamboo calligraphy brush shaft',tuple(a),tuple(b),.007,wood,writing,10)
    tip=b+Vector((.09,0,-.008))
    o=rod('Brush soft ivory tip',tuple(b),tuple(tip),.012,_d3_brushwhite,writing,10)
    o=rod('Brush black ink point',tuple(tip),tuple(tip+Vector((.027,0,-.004))),.005,ink,writing,8)


# Traditional bronze tripod censer on the cultivation altar, with cloud-like
# wire handles and a pierced dome. No particle smoke or world-space effects.
censer=empty('DECOR_TRIPOD_INCENSE_CENSER',(-.20,-.02,.825),altar)
for k in range(3):
    a=math.tau*k/3
    rod('Censer curled tripod foot',(.12*math.cos(a),.12*math.sin(a),.02),
        (.09*math.cos(a),.09*math.sin(a),.11),.016,gold,censer,10)
d3_lathe('Bronze censer rounded bowl',(0,0,0),
         [(.06,.07),(.13,.10),(.16,.17),(.15,.22),(.155,.24),
          (.138,.24),(.135,.22),(.10,.16)],gold,censer,28)
d3_ring('Censer dark waist inlay',(0,0,.165),.157,darkwood,censer,wire=.004,segments=28)
# Eight disconnected decorative ribs allow light through the fitted lid.
for k in range(8):
    a=math.tau*k/8
    pts=[]
    for j in range(9):
        t=j/8;r=.135*(1-t);z=.245+.08*math.sin(t*math.pi/2)
        pts.append((r*math.cos(a),r*math.sin(a),z))
    d3_curve('Pierced censer lid curved rib',pts,.010,gold,censer)
d3_ring('Censer lid jade finial setting',(0,0,.323),.024,gold,censer,wire=.005,segments=16)
sphere('Censer jade finial',(0,0,.342),(.024,.024,.024),jade,censer,12,6)
for side in [-1,1]:
    d3_curve('Censer cloud scroll handle',[(side*.14,0,.19),(side*.21,0,.26),
             (side*.24,0,.23),(side*.23,0,.17),(side*.17,0,.16)],.012,gold,censer)
# Incense sticks in a separate little porcelain ash cup.
d3_lathe('Jade incense holder',(.27,.11,.825),
         [(.065,0),(.08,.02),(.08,.055),(.065,.068),(.06,.06)],jade,altar,20)
for i in range(3):
    rod('Fine incense stick',(.25+i*.017,.11,.87),(.24+i*.025,.11,1.17+i*.018),
        .0025,wood,altar,6)
    sphere('Incense glowing ember',(.24+i*.025,.11,1.17+i*.018),(.003,.003,.004),
           glow,altar,6,3)


# Restrained cultivation symbolism: a low openwork jade-and-brass halo rises
# from the cushion's wooden plinth, with no particles above the walking route.
ritual=empty('DECOR_MEDITATION_LOTUS_HALO',parent=meditation)
for r in [.54,.61]:d3_ring('Meditation lotus gilt inlay',(0,0,.175),r,gold,ritual,wire=.005,segments=48)
for k in range(8):
    a=math.tau*k/8
    radial=Vector((math.cos(a),math.sin(a),0));tan=Vector((-math.sin(a),math.cos(a),0))
    pts=[tuple(radial*.41+Vector((0,0,.179))),
         tuple(radial*.52+tan*.035+Vector((0,0,.179))),
         tuple(radial*.58+Vector((0,0,.179))),
         tuple(radial*.52-tan*.035+Vector((0,0,.179)))]
    d3_curve('Meditation lotus petal brass inlay',pts,.004,gold,ritual,True)


# Eight small library clusters rest on the actual shelf tops. The cabinet
# opens along local +X, so the colored stitched spines and scroll ends face
# into the room rather than into the back panel.
def d3_manual_stack(y,shelf,count,index):
    base=shelf+.033
    cluster=empty('Library stitched-manual cluster %02d'%index,
                  (.005,y,base),bookcase)
    for j in range(count):
        height=.044
        book=empty('Library horizontal stitched manual',(0,0,height/2+j*.050),cluster)
        book.rotation_euler.z=(-1 if j%2 else 1)*.035
        cover=rose if (j+index)%3==0 else jade if (j+index)%3==1 else darkwood
        cube('Library manual cloth cover',(0,0,0),(.39,.28,height),cover,.003,book)
        cube('Library manual xuan leaves',(.004,0,0),(.369,.256,height-.014),_d3_papermat,0,book)
        cube('Library visible stitched spine',(.196,0,0),(.009,.28,height),cover,0,book)
        cube('Library ivory manual title slip',(.202,.015,0),(.002,.115,.027),ivory,0,book)
        for dy in [-.10,-.045,.045,.10]:
            d3_curve('Library manual silk stitch',[(.202,dy,-.013),(.202,dy,.013)],
                     .0018,_d3_garnet,book)
    return cluster

def d3_standing_manuals(y,shelf,count,index):
    base=shelf+.033
    cluster=empty('Library upright-manual cluster %02d'%index,(0,y,base),bookcase)
    for j in range(count):
        width=.056;height=.26+.025*((j+index)%3)
        yy=(j-(count-1)/2)*.064
        cover=jade if j%3==0 else rose if j%3==1 else darkwood
        cube('Library standing manual binding',(0,yy,height/2),(.37,width,height),cover,.002,cluster)
        cube('Library standing manual leaves',(.002,yy,height/2),(.345,width-.014,height-.017),
             _d3_papermat,0,cluster)
        cube('Library standing manual colored spine',(.187,yy,height/2),(.010,width,height),cover,0,cluster)
        cube('Library standing manual ivory title',(.194,yy,height*.66),(.002,width*.59,.13),ivory,0,cluster)
        for z in [.037,height-.035]:
            cube('Library manual spine gilt band',(.195,yy,z),(.002,width*.82,.007),gold,0,cluster)
    return cluster

def d3_scroll_bundle(y,shelf,index):
    cluster=empty('Library rolled-scroll cluster %02d'%index,(0,y,shelf+.033),bookcase)
    for k,(yy,z) in enumerate([(-.063,.058),(.063,.058),(0,.157)]):
        rod('Library rolled xuan scroll',(-.19,yy,z),(.17,yy,z),.055,_d3_papermat,cluster,14)
        for x in [-.204,.184]:
            rod('Library jade scroll end',(x-.010,yy,z),(x+.010,yy,z),.058,jade,cluster,14)
        d3_ring('Scroll peach silk binding',(0,yy,z),.056,rose,cluster,'YZ',.003,14)
        # A little visible spiral line on the front paper end.
        pts=[]
        for n in range(20):
            a=n/19*math.pi*3;r=.008+.032*n/19
            pts.append((.197,yy+r*math.cos(a),z+r*math.sin(a)))
        d3_curve('Scroll end paper spiral',pts,.0018,darkwood,cluster)
    return cluster

d3_manual_stack(-.46,.12,4,1)
d3_standing_manuals(.31,.12,5,2)
d3_manual_stack(-.43,.60,4,3)
d3_scroll_bundle(.40,.60,4)
d3_standing_manuals(-.60,1.08,3,5)
d3_scroll_bundle(.02,1.08,6)
d3_manual_stack(-.52,1.52,3,7)
d3_scroll_bundle(.50,1.52,8)


# A small jade bottle and three cut plum twigs fit the bedside chest top.
# The flowers are well above the cabinet and away from the mattress contacts.
night_vase=empty('DECOR_BEDSIDE_JADE_PLUM_VASE',(.035,.025,.685),nightstand)
d3_lathe('Bedside jade bottle',(0,0,0),
         [(.046,0),(.075,.018),(.095,.11),(.073,.20),(.035,.25),
          (.033,.31),(.043,.32),(.043,.333),(.030,.333),(.026,.30)],jade,night_vase,24)
d3_ring('Bedside bottle gilt rim',(0,0,.331),.036,gold,night_vase,wire=.0025,segments=20)
for i,end in enumerate([(-.15,.02,.68),(.11,-.02,.75),(.02,.12,.62)]):
    d3_curve('Bedside slender plum twig',[(0,0,.29),(.025*i-.02,.02,.43),end],
             .004,wood,night_vase)
    for k,t in enumerate([.72,.88,1.]):
        p=Vector((0,0,.30)).lerp(Vector(end),t)
        p+=Vector((.035*(-1 if k%2 else 1),-.025,0))
        d3_blossom(night_vase,tuple(p),.048 if k%2 else .060,(.15,-1.,.3),i+k)


# Fine squared meander corners and cloud-scroll motifs inlaid into tabletops.
# These remain flush with the furniture, so they never add a navigation shape.
for parent,half_x,half_y,z in [(desk,.67,.31,.824),(altar,.55,.28,.824)]:
    for sx in [-1,1]:
        for sy in [-1,1]:
            p=[(sx*(half_x-.01),sy*(half_y-.12),z),
               (sx*(half_x-.01),sy*(half_y-.01),z),
               (sx*(half_x-.16),sy*(half_y-.01),z),
               (sx*(half_x-.16),sy*(half_y-.065),z),
               (sx*(half_x-.08),sy*(half_y-.065),z)]
            d3_curve('Tabletop auspicious meander corner inlay',p,.0027,gold,parent)
# A pendant-shaped gold ruyi motif on the visible front altar apron.
for sx in [-1,1]:
    pts=[(sx*.20,-.315,.66),(sx*.12,-.315,.70),(sx*.08,-.315,.68),
         (sx*.07,-.315,.63),(sx*.02,-.315,.63),(0,-.315,.66)]
    d3_curve('Altar gilt ruyi cloud apron ornament',pts,.005,gold,altar)


# Ninety separate fallen petals lie on exposed wood floor, concentrated below
# the eaves and beside the bed. Each is an explicitly cupped four-triangle
# mesh, only millimetres high: no fake flattened spheres or particle system.
# Keep every petal clear of interaction contacts, furniture footprints, rug.
import sys
if str(ROOT/'tools/blender') not in sys.path:
    sys.path.insert(0,str(ROOT/'tools/blender'))
from dream3_metadata import ANCHORS as _d3_anchor_contract
bpy.context.view_layer.update()
_d3_floor_exclusions=[]
for name,parent in furnitures.items():
    if not name.startswith('FURN_'):continue
    projected=[]
    for ob in parent.children_recursive:
        if ob.type not in {'MESH','CURVE'}:continue
        projected.extend([ob.matrix_world@Vector(p) for p in ob.bound_box])
    if projected:
        _d3_floor_exclusions.append((min(p.x for p in projected)-.035,
                                     max(p.x for p in projected)+.035,
                                     min(p.y for p in projected)-.035,
                                     max(p.y for p in projected)+.035))
_d3_anchor_xy=[(a['position'][0],-a['position'][2]) for a in _d3_anchor_contract]
fallen=empty('DECOR_FALLEN_PEACH_PETALS',parent=floor_group)
_d3_petal_centres=[]
_d3_petal_hotspots=[(-.2,3.18,.95,.11),(2.1,3.16,.95,.11),
                    (3.73,.65,.10,1.25),(.39,.95,.17,1.15),
                    (-1.0,1.07,.85,.13),(3.48,-2.95,.25,.13)]
for attempt in range(8000):
    if len(_d3_petal_centres)>=90:break
    cx,cy,sx,sy=_d3_petal_hotspots[_d3_rng.randrange(len(_d3_petal_hotspots))]
    x=_d3_rng.gauss(cx,sx);y=_d3_rng.gauss(cy,sy)
    if not (-3.88<x<3.88 and -3.30<y<3.30):continue
    if any(ax-.03<x<bx+.03 and ay-.03<y<by+.03 for ax,bx,ay,by in _d3_floor_exclusions):continue
    if any((x-ax)**2+(y-ay)**2<.225**2 for ax,ay in _d3_anchor_xy):continue
    if any((x-ax)**2+(y-ay)**2<.034**2 for ax,ay in _d3_petal_centres):continue
    length=_d3_rng.uniform(.021,.044);width=length*_d3_rng.uniform(.47,.67)
    z=_d3_rng.uniform(.0034,.0060)
    vertices=[(0,-length*.42,0),(-width*.5,0,.0005),(0,length*.58,.0008),
              (width*.5,0,.0004),(0,length*.035,.0015)]
    ob=mesh('Fallen peach petal · 4 triangles',vertices,
            [(0,1,4),(1,2,4),(2,3,4),(3,0,4)],
            petal_mats[len(_d3_petal_centres)%len(petal_mats)],fallen)
    ob.location=(x,y,z);ob.rotation_euler.z=_d3_rng.uniform(-math.pi,math.pi)
    _d3_petal_centres.append((x,y))
fallen['petalCount']=len(_d3_petal_centres)
fallen['maximumHeightMeters']=.0075
fallen['anchorClearanceMeters']=.18
assert len(_d3_petal_centres)==90, 'Could not place all floor petals safely'
