"""Starlit Tide / 星潮玻璃小屋. Native original Blender geometry, no exterior.

Execute with Blender MCP or `blender --background --python this_file`.
Run in a clean Blender process; the generated file contains only this scene.
"""
import bpy, math, random, json, sys, re, ast
from pathlib import Path
from mathutils import Vector
import numpy as np

ROOT=Path('/home/neethu/projects/AIAvatar-SDK')
OUT=ROOT/'assets/dream_bedroom_2'
OUT.mkdir(parents=True,exist_ok=True)
(OUT/'textures').mkdir(exist_ok=True)
random.seed(271024)
for old in list(bpy.data.scenes):
    if old.name=='Starlit Tide • Glass Dream Cottage':
        for ob in list(old.objects): bpy.data.objects.remove(ob,do_unlink=True)
        bpy.data.scenes.remove(old)
S=bpy.data.scenes.new('Starlit Tide • Glass Dream Cottage')
bpy.context.window.scene=S
for other in list(bpy.data.scenes):
    if other != S:
        for ob in list(other.objects):bpy.data.objects.remove(ob,do_unlink=True)
        bpy.data.scenes.remove(other)
def enum(owner,prop,wanted):
    return next(i.identifier for i in owner.bl_rna.properties[prop].enum_items if i.identifier==wanted)
S.unit_settings.system=enum(S.unit_settings,'system','METRIC')
S.unit_settings.scale_length=1
S.world=bpy.data.worlds.new('Preview only • blue hour ambient')
S.world.use_nodes=True
bg=next(n for n in S.world.node_tree.nodes if n.type=='BACKGROUND')
bg.inputs['Color'].default_value=(.13,.22,.36,1)
bg.inputs['Strength'].default_value=.45

def material(name,color,rough=.6,metal=0,alpha=1,emission=0):
    m=bpy.data.materials.new('D2 • '+name);m.use_nodes=True
    p=next(n for n in m.node_tree.nodes if n.type=='BSDF_PRINCIPLED')
    p.inputs['Base Color'].default_value=(*color,alpha)
    p.inputs['Roughness'].default_value=rough;p.inputs['Metallic'].default_value=metal
    p.inputs['Alpha'].default_value=alpha
    if emission:
        p.inputs['Emission Color'].default_value=(*color,1);p.inputs['Emission Strength'].default_value=emission
    if alpha<1:
        m.surface_render_method=enum(m,'surface_render_method','BLENDED')
        m.use_transparent_shadow=True
        p.inputs['Specular IOR Level'].default_value=.12
    m.diffuse_color=(*color,alpha)
    return m

def texture(m,kind,base):
    n=512;yy,xx=np.mgrid[0:n,0:n]/n;rng=np.random.default_rng(30+len(bpy.data.materials))
    if kind=='wood':
        q=xx*40+np.sin(yy*8)*.24+np.sin(yy*26)*.08
        field=.85+.11*np.sin(q*math.tau)+.045*np.sin(q*3.7*math.tau)+rng.normal(0,.016,(n,n))
        rgb=np.stack([np.clip(field*c,0,1) for c in base],-1)
    elif kind=='wave':
        q=yy*6+1.3*np.sin(xx*8)+.23*np.sin(xx*29+yy*15)+.08*np.sin(xx*93+yy*47)
        f=(np.sin(q*math.tau)+1)/2
        foam=(f>.88).astype(float)*.85
        rgb=np.zeros((n,n,3))
        for c,(a,b) in enumerate(zip((.12,.30,.43),(.90,.85,.71))):rgb[:,:,c]=a*(1-foam)+b*foam
        rgb*=rng.uniform(.88,1.08,(n,n,1))
    else:
        field=.94+.035*np.sin(xx*n*math.pi)+.03*np.sin(yy*n*math.pi)+rng.normal(0,.018,(n,n))
        rgb=np.stack([np.clip(field*c,0,1) for c in base],-1)
    rgba=np.ones((n,n,4),np.float32);rgba[:,:,:3]=np.clip(rgb,0,1)
    im=bpy.data.images.new(m.name+' albedo',width=n,height=n);im.pixels.foreach_set(rgba.ravel())
    im.filepath_raw=str(OUT/'textures'/(m.name.replace('D2 • ','').replace(' ','_')+'.png'))
    im.file_format=enum(im,'file_format','PNG');im.save();im.pack()
    t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=im
    p=next(n for n in m.node_tree.nodes if n.type=='BSDF_PRINCIPLED')
    m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color'])
    return m

oak=texture(material('Honey oak',(.5,.28,.12),.37),'wood',(.58,.34,.16))
darkwood=texture(material('Smoked teak',(.19,.10,.045),.43),'wood',(.32,.18,.085))
cream=texture(material('Ivory linen',(.83,.77,.62),.93),'cloth',(.87,.82,.69))
blue=texture(material('Washed ocean linen',(.17,.35,.47),.94),'cloth',(.27,.47,.59))
rose=texture(material('Dusty rose knit',(.61,.37,.32),.95),'cloth',(.72,.48,.40))
rugmat=texture(material('Woven tidal foam',(.2,.4,.5),1),'wave',None)
panel=material('Warm ivory painted timber',(.61,.51,.35),.72)
brass=material('Satin antique brass',(.55,.34,.11),.28,.74)
black=material('Ink blue enamel',(.018,.035,.048),.32,.25)
glass=material('Clear lightly blue glass',(.63,.82,.91),.12,0,alpha=.035)
glow=material('Warm candle light',(1,.65,.25),.27,emission=5)
white=material('Glazed ivory ceramic',(.85,.82,.72),.22)
rope=material('Natural flax rope',(.52,.39,.20),.88)
leafmats=[material('Leaf '+str(i),c,.8) for i,c in enumerate([(.09,.19,.11),(.16,.27,.13),(.25,.34,.16)])]
bookmats=[material('Book binding '+str(i),c,.85) for i,c in enumerate([(.12,.23,.29),(.42,.25,.14),(.55,.39,.24),(.52,.37,.33),(.24,.31,.26)])]
screen=material('Midnight display',(.015,.07,.095),.22,emission=.3)

def empty(name,loc=(0,0,0),parent=None,**props):
    o=bpy.data.objects.new(name,None);S.collection.objects.link(o);o.parent=parent;o.location=loc;o.empty_display_size=.12
    for k,v in props.items():o[k]=v
    return o
root=empty('DREAM_BEDROOM_2',units='meters',title='Starlit Tide / 星潮玻璃小屋',schemaVersion=1,noExterior=True)
arch=empty('ARCHITECTURE',parent=root)
floor_group=empty('FLOOR',parent=arch,role='floor',canHideForCamera=False)
wall_north=empty('WALL_NORTH',parent=arch,role='wall',canHideForCamera=True,planePointGltf=[0,0,-3.5],outwardNormalGltf=[0,0,-1])
wall_south=empty('WALL_SOUTH',parent=arch,role='wall',canHideForCamera=True,planePointGltf=[0,0,3.5],outwardNormalGltf=[0,0,1])
wall_east=empty('WALL_EAST',parent=arch,role='wall',canHideForCamera=True,planePointGltf=[4,0,0],outwardNormalGltf=[1,0,0])
wall_west=empty('WALL_WEST',parent=arch,role='wall',canHideForCamera=True,planePointGltf=[-4,0,0],outwardNormalGltf=[-1,0,0])
ceiling_group=empty('CEILING',parent=arch,role='ceiling',canHideForCamera=True,planePointGltf=[0,4.85,0],outwardNormalGltf=[0,1,0])
furnitures={}
def group(name,label,loc,yaw=0,nav=True,parent=None):
    o=empty(name,loc,parent or root,label=label,yawDeg=yaw,navObstacle=nav)
    o.rotation_euler.z=math.radians(yaw);furnitures[name]=o;return o
def finish(o,name,mat,parent):
    o.name=name;o.parent=parent
    if mat:o.data.materials.append(mat)
    return o
def cube(name,loc,dims,mat,bevel=.01,parent=None):
    bpy.ops.mesh.primitive_cube_add(size=1,location=loc)
    o=finish(bpy.context.object,name,mat,parent);o.dimensions=dims
    bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    if bevel:
        m=o.modifiers.new('Soft crafted edge','BEVEL');m.width=bevel;m.segments=2
        o.modifiers.new('Weighted corner normals','WEIGHTED_NORMAL')
    return o
def sphere(name,loc,scale,mat,parent=None,segments=20,rings=10):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=segments,ring_count=rings,radius=1,location=loc)
    o=finish(bpy.context.object,name,mat,parent);o.scale=scale
    for p in o.data.polygons:p.use_smooth=True
    return o
def cylinder(name,loc,radius,depth,mat,parent=None,vertices=24,radius2=None):
    if radius2 is None:bpy.ops.mesh.primitive_cylinder_add(vertices=vertices,radius=radius,depth=depth,location=loc)
    else:bpy.ops.mesh.primitive_cone_add(vertices=vertices,radius1=radius,radius2=radius2,depth=depth,location=loc)
    o=finish(bpy.context.object,name,mat,parent)
    for p in o.data.polygons:p.use_smooth=len(p.vertices)==4
    return o
def rod(name,a,b,r,mat,parent=None,vertices=10):
    a,b=Vector(a),Vector(b);o=cylinder(name,(a+b)/2,r,(b-a).length,mat,parent,vertices)
    o.rotation_euler=(b-a).to_track_quat('Z','Y').to_euler();return o
def curve(name,points,r,mat,parent=None,cyclic=False):
    c=bpy.data.curves.new(name,'CURVE');c.dimensions='3D';c.resolution_u=1;c.bevel_depth=r;c.bevel_resolution=1
    p=c.splines.new('POLY');p.points.add(len(points)-1)
    for a,b in zip(p.points,points):a.co=(*b,1)
    p.use_cyclic_u=cyclic;o=bpy.data.objects.new(name,c);S.collection.objects.link(o);finish(o,name,mat,parent);return o
def mesh(name,verts,faces,mat,parent=None):
    m=bpy.data.meshes.new(name);m.from_pydata(verts,[],faces);m.update()
    o=bpy.data.objects.new(name,m);S.collection.objects.link(o);finish(o,name,mat,parent)
    uv=m.uv_layers.new(name='UVMap')
    for p in m.polygons:
        for li in p.loop_indices:
            co=m.vertices[m.loops[li].vertex_index].co;uv.data[li].uv=(co.x/2,co.y/2)
    return o

# Continuous floor at z=0; individual boards provide fine material catches.
cube('Timber foundation',(0,0,-.13),(8.18,7.18,.24),darkwood,.025,floor_group)
for j in range(28):
    y=-3.5+(j+.5)*.25
    for i in range(6):
        a=max(-4,-5.3+i*1.85+(j%3)*.6);b=min(4,-3.45+i*1.85+(j%3)*.6)
        if b>a:cube('Oak floor plank',((a+b)/2,y,-.014),(b-a-.009,.241,.028),oak,.003,floor_group)
# West painted board wall. North is a full gabled window, east a glass sidewall.
cube('West timber shell',(-4.07,0,1.575),(.14,7.16,3.15),panel,.012,wall_west)
for y in np.arange(-3.45,3.5,.24):cube('West board seam',(-3.992,y,1.57),(.014,.013,3.12),cream,.002,wall_west)
cube('North dado',(0,3.57,.31),(8.15,.14,.62),panel,.01,wall_north)
cube('East dado',(4.07,0,.31),(.14,7.15,.62),panel,.01,wall_east)
cube('South wall right',(1.98,-3.57,1.575),(4.04,.14,3.15),panel,.01,wall_south)
cube('South wall left',(-3.0,-3.57,1.575),(2.0,.14,3.15),panel,.01,wall_south)
cube('Entry lintel',(-1.0,-3.57,2.75),(2.0,.14,.8),panel,.01,wall_south)
mesh('South gable',[(-4,-3.57,3.15),(4,-3.57,3.15),(0,-3.57,4.85)],[(0,1,2)],panel,wall_south)
for x in [-2,0]:cube('Entry upright',(x,-3.48,1.2),(.10,.14,2.4),oak,.012,wall_south)
cube('Entry threshold',(-1,-3.5,.015),(1.9,.22,.03),brass,.005,wall_south)
entry=group('PORTAL_ENTRY','入口',(-.9,-3.5,0),180,False)
for g,y in [(wall_north,3.48),(wall_south,-3.48)]:
    for x in [-4,4]:rod('Gable rafter',(x,y,3.15),(0,y,4.85),.085,oak,g,12)
    rod('Gable tie beam',(-4,y,3.15),(4,y,3.15),.065,oak,g,12)
for g,x in [(wall_west,-3.95),(wall_east,3.95)]:
    cube('Eave beam',(x,0,3.15),(.15,7.1,.17),oak,.018,g)
    cube('Skirting',(x,0,.10),(.10,7,.2),darkwood,.008,g)
for x in [-3.96,-1.4,1.4,3.96]:
    top=4.85-.425*abs(x)
    cube('North window mullion',(x,3.47,(.67+top)/2),(.065,.085,top-.67),oak,.009,wall_north)
cube('North sill',(0,3.4,.66),(8,.30,.10),oak,.018,wall_north)
cube('East sill',(3.9,0,.66),(.30,7,.10),oak,.018,wall_east)
for y in [-3.45,-1.2,1.2,3.45]:cube('East window mullion',(3.98,y,1.9),(.08,.065,2.5),oak,.009,wall_east)
northglass=group('GLASS_PANORAMA_NORTH','北侧山墙玻璃',(0,3.49,.7),0,False,parent=wall_north)
mesh('Gabled clear glazing',[(-3.94,0,0),(3.94,0,0),(3.94,0,2.45),(0,0,4.12),(-3.94,0,2.45)],[(0,1,2,3,4)],glass,northglass)
eastglass=group('GLASS_PANORAMA_EAST','东侧全景玻璃',(3.99,0,.7),-90,False,parent=wall_east)
cube('Side clear glazing',(0,0,1.21),(6.88,.012,2.42),glass,0,eastglass)
roof=group('GLASS_ROOF','全景双坡玻璃屋顶',(0,0,0),0,False,parent=ceiling_group)
for side in [-1,1]:
    x=side*3.98
    mesh('Roof glass slope '+str(side),[(0,-3.49,4.85),(x,-3.49,3.1585),(x,3.49,3.1585),(0,3.49,4.85)],[(0,1,2,3) if side>0 else (3,2,1,0)],glass,roof)
    for y in [-3.5,-1.18,1.18,3.5]:rod('Roof timber glazing bar',(0,y,4.85),(side*4,y,3.15),.047,oak,ceiling_group,10)
    rod('Roof longitudinal glazing bar',(side*2,-3.5,4.0),(side*2,3.5,4.0),.026,brass,ceiling_group,8)
rod('Ridge cap',(0,-3.57,4.85),(0,3.57,4.85),.08,oak,ceiling_group,12)

# Boat bed: six individually curved clinker planks and a padded oval mattress.
bed=group('FURN_BED','橡木船形双人床',(-2.15,1.05,0),0,True)
N=64
def hull_ring(z,scale):
    return [(1.325*scale*math.sin(i*math.tau/N),1.75*scale*math.cos(i*math.tau/N),z) for i in range(N)]
for band in range(6):
    z0=.12+band*.106;z1=z0+.102;s0=.72+band*.047;s1=.72+(band+1)*.047
    vs=hull_ring(z0,s0)+hull_ring(z1,s1)
    ob=mesh('Clinker hull plank %02d'%band,vs,[(i,(i+1)%N,(i+1)%N+N,i+N) for i in range(N)],oak,bed)
    sol=ob.modifiers.new('Solid timber','SOLIDIFY');sol.thickness=.035
    curve('Fine brass hull seam',hull_ring(z1,s1),.008,brass,bed,True)
    for i in range(0,N,4):sphere('Hull brass rivet',vs[N+i],(.013,.013,.013),brass,bed,8,4)
curve('Rounded teak gunwale',hull_ring(.78,1.01),.047,darkwood,bed,True)
sphere('Plump oval mattress',(0,0,.73),(1.235,1.63,.16),cream,bed,48,16)
curve('Mattress piping',[(1.235*math.sin(i*math.tau/80),1.63*math.cos(i*math.tau/80),.78) for i in range(80)],.012,cream,bed,True)
# Soft pillows are flattened rounded volumes with explicit welt seams.
def pillow(name,loc,scale,mat,angle=0):
    o=sphere(name,loc,scale,mat,bed,24,12);o.rotation_euler=(.15,0,angle)
    for v in o.data.vertices:
        for axis,power in [(0,.55),(1,.55),(2,.7)]:v.co[axis]=math.copysign(abs(v.co[axis])**power,v.co[axis])
    def signed(n):return math.copysign(abs(n)**.55,n)
    pts=[(loc[0]+scale[0]*signed(math.sin(t))*math.cos(angle)-scale[1]*signed(math.cos(t))*math.sin(angle),loc[1]+scale[0]*signed(math.sin(t))*math.sin(angle)+scale[1]*signed(math.cos(t))*math.cos(angle),loc[2]) for t in np.linspace(0,math.tau,65)]
    curve(name+' welt',pts,.009,cream,bed,True)
pillow('Left ivory back pillow',(-.62,1.12,.94),(.54,.40,.20),cream,-.10)
pillow('Right ivory back pillow',(.55,1.12,.94),(.54,.40,.20),cream,.13)
pillow('Ocean cushion',(-.54,.88,1.06),(.40,.26,.22),blue,-.2)
pillow('Blush cushion',(.40,.91,1.06),(.38,.28,.23),rose,.18)
pillow('Small striped lumbar',(0,.66,1.02),(.32,.17,.17),cream,.03)
nx,ny=44,52;verts=[];faces=[]
for j in range(ny+1):
    y=-1.51+j/ny*2.1;w=1.21*math.sqrt(max(.05,1-(abs(y)/1.68)**6))
    for i in range(nx+1):
        u=i/nx;x=(u*2-1)*w
        z=.956+.022*math.sin(x*14+y*8)+.018*math.sin(y*26+x*5)+.07*math.exp(-((y-.50)/.13)**2)-.12*abs(2*u-1)**12
        verts.append((x,y,z))
for j in range(ny):
    for i in range(nx):a=j*(nx+1)+i;faces.append((a,a+1,a+nx+2,a+nx+1))
ob=mesh('Rumpled ocean duvet',verts,faces,blue,bed)
for p in ob.data.polygons:p.use_smooth=True
sol=ob.modifiers.new('Duvet thickness','SOLIDIFY');sol.thickness=.035
for j in [7,18,29,40]:curve('Duvet quilt stitch',[verts[j*(nx+1)+i] for i in range(nx+1)],.004,cream,bed)
# Rose throw drapes over the bow, with handmade fringe.
v=[];f=[]
for j in range(26):
    t=j/25
    y=-.65-1.05*min(t/.58,1)+.23*max(0,(t-.58)/.42)
    z=.98-.69*max(0,(t-.58)/.42)
    for i in range(17):
        x=.15+(i/16-.5)*.75
        yy=y+.015*math.sin(i*2)
        zz=z+.025*math.sin(i*2.3+t*5)
        if t>.58:
            hs=.72+.282*max(0,min(1,(zz-.12)/.636))
            yy=-1.75*hs*math.sqrt(max(.01,1-(x/(1.325*hs))**2))-.055
        if t<=.58 and yy>=-1.51:
            width=1.21*math.sqrt(max(.05,1-(abs(yy)/1.68)**6))
            zz=.956+.022*math.sin(x*14+yy*8)+.018*math.sin(yy*26+x*5)+.07*math.exp(-((yy-.50)/.13)**2)-.12*abs(x/width)**12+.035+.008*math.sin(i*2.3+t*5)
        v.append((x,yy,zz))
for j in range(25):
    for i in range(16):a=j*17+i;f.append((a,a+1,a+18,a+17))
ob=mesh('Rose knitted draped throw',v,f,rose,bed)
for p in ob.data.polygons:p.use_smooth=True
for i in range(25):
    x=.15+(i/24-.5)*.75;hs=.72+.282*(.29-.12)/.636
    yy=-1.75*hs*math.sqrt(max(.01,1-(x/(1.325*hs))**2))-.055
    curve('Throw tassel',[(x,yy,.30),(x+.01,yy-.01,.20),(x-.007,yy-.02,.15)],.006,rose,bed)
# Decorative bow porthole lies on the hull, no exterior scenery is modelled.
rod('Bow porthole rim',(0,-1.51,.43),(0,-1.56,.43),.17,brass,bed,36)
rod('Bow porthole blue glass',(0,-1.565,.43),(0,-1.571,.43),.136,blue,bed,36)

nightstand=group('FURN_NIGHTSTAND','床头小柜',(-.45,2.15,0))
cube('Nightstand carcass',(0,0,.32),(.55,.55,.64),oak,.025,nightstand)
for z in [.19,.44]:
    cube('Nightstand drawer',(0,-.281,z),(.49,.025,.20),darkwood,.009,nightstand)
    sphere('Drawer brass pull',(0,-.31,z),(.04,.02,.02),brass,nightstand,12,6)
desk=group('FURN_DESK','窗边书桌',(3.35,.55,0),90)
cube('Desk top',(0,0,.79),(1.5,.65,.085),oak,.035,desk)
for x in [-.62,.62]:
    for y in [-.22,.22]:rod('Desk turned leg',(x,y,.02),(x,y,.75),.028,darkwood,desk)
cube('Desk apron',(0,.22,.65),(1.32,.05,.18),oak,.009,desk)
chair=group('FURN_CHAIR_DESK','书桌椅',(2.42,.55,0),90)
reading=group('FURN_CHAIR_READING','蓝色阅读扶手椅',(2.7,-1.55,0),-90)
def upholstered_chair(g,w,d,seat,back,mat):
    for x in [-w*.34,w*.34]:
        for y in [-d*.32,d*.32]:rod('Splayed oak chair leg',(x*1.12,y*1.12,.03),(x,y,seat-.06),.028,oak,g)
    cube('Chair seat',(0,0,seat),(w,d,.16),mat,.075,g)
    backob=cube('Cushioned chair back',(0,d*.39,(seat+back)/2),(w,.16,back-seat),mat,.075,g);backob.rotation_euler.x=.08
    if w>.7:
        for x in [-w*.49,w*.49]:cube('Upholstered arm',(x,0,seat+.19),(.15,d*.94,.25),mat,.07,g)
upholstered_chair(chair,.58,.62,.49,1.05,cream)
upholstered_chair(reading,.86,.90,.48,1.15,blue)
bench=group('FURN_WINDOW_BENCH','窗下长书柜',(1.8,3.1,0))
cube('Window bench top',(0,0,.58),(3.5,.56,.08),oak,.018,bench)
cube('Window bench base',(0,0,.07),(3.5,.54,.10),darkwood,.008,bench)
for x in [-1.71,-.86,0,.86,1.71]:cube('Window cubby upright',(x,0,.33),(.055,.53,.48),oak,.006,bench)
bookcase=group('FURN_BOOKCASE','床头悬挂藏书架',(-3.93,1.4,1.3),90,False)
sidetable=group('FURN_SIDE_TABLE','黄铜束带木桶边几',(3,-2.65,0))
cylinder('Barrel side table',(0,0,.31),.36,.60,oak,sidetable,32)
for z in [.11,.49]:
    curve('Barrel brass hoop',[(.362*math.cos(t),.362*math.sin(t),z) for t in np.linspace(0,math.tau,49)],.018,brass,sidetable,True)
cylinder('Barrel polished top',(0,0,.63),.38,.055,darkwood,sidetable,32)
chest=group('FURN_CHEST','航海黄铜收纳箱',(-2.65,-2.65,0))
cube('Treasure trunk',(0,0,.32),(1.3,.75,.64),oak,.045,chest)
cube('Trunk lid',(0,0,.67),(1.34,.79,.10),darkwood,.04,chest)
for x in [-.49,.49]:
    cube('Brass trunk band',(x,0,.73),(.035,.79,.015),brass,.002,chest)
    cube('Brass trunk front strap',(x,-.381,.34),(.035,.016,.62),brass,.002,chest)
cube('Trunk clasp',(0,-.40,.5),(.12,.035,.18),brass,.01,chest)
telescope=group('FURN_TELESCOPE','黄铜天文望远镜',(2.35,2.4,0),-25)
rug=group('FURN_RUG','蓝白潮汐编织地毯',(0,-.7,0),0,False)
rugmesh=cube('Thick woven wave rug',(0,0,.022),(3,3.5,.044),rugmat,.018,rug)
for loop in rugmesh.data.loops:
    co=rugmesh.data.vertices[loop.vertex_index].co
    rugmesh.data.uv_layers.active.data[loop.index].uv=(co.x/3+.5,co.y/3.5+.5)
for side in [-1,1]:
    for i in range(100):
        x=-1.48+i*2.96/99;curve('Ivory rug fringe',[(x,side*1.73,.035),(x+.008,side*1.82,.025),(x-.005,side*1.88,.019)],.008,cream,rug)
media=group('FURN_MEDIA_CONSOLE','南侧影音矮柜',(1.65,-3.12,0),180)
cube('Media console case',(0,0,.29),(2.4,.55,.58),oak,.025,media)
for x in [-.83,0,.83]:
    cube('Console woven drawer',(x,-.287,.31),(.73,.025,.40),cream,.018,media)
    rod('Console handle',(x-.1,-.32,.33),(x+.1,-.32,.33),.012,brass,media)
tv=group('FURN_TV','壁挂显示屏',(1.65,-3.34,1.7),180,False,parent=wall_south)
cube('Brass television frame',(0,0,0),(1.75,.07,1.02),brass,.025,tv)
cube('Television screen',(0,-.042,0),(1.65,.012,.92),screen,.012,tv)
console=group('FURN_GAME_CONSOLE','游戏主机',(2.1,-3.1,.58),180,False)
cube('Game console body',(0,0,.13),(.15,.26,.26),white,.02,console)
cube('Console ink slot',(0,-.135,.14),(.04,.012,.20),black,.003,console)
controller=group('PROP_CONTROLLER','游戏手柄',(1.5,-3.05,.635),180,False)
ob=sphere('Game controller body',(0,0,.03),(.13,.075,.045),black,controller,20,10)
for x in [-.09,.09]:sphere('Gamepad grip',(x,.018,0),(.05,.07,.055),black,controller,16,8)
for x in [-.055,.055]:cylinder('Controller thumbstick',(x,-.014,.075),.018,.015,brass,controller,12)

exec((ROOT/'tools/blender/dream2_decor.py').read_text(),globals())

# Preview lights are separate from the runtime asset and are not exported.
lights=empty('PREVIEW_LIGHTS')
def area(name,loc,target,power,color,size):
    d=bpy.data.lights.new(name,'AREA');d.energy=power;d.color=color;d.shape=enum(d,'shape','DISK');d.size=size
    o=bpy.data.objects.new(name,d);S.collection.objects.link(o);o.parent=lights;o.location=loc;o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler()
area('Blue hour roof softbox',(0,1,6.4),(0,0,0),700,(.56,.72,1),6)
area('Warm interior bounce',(-2.3,-.5,2.9),(-1,1,0),350,(1,.69,.38),3)
area('Window cool fill',(4.8,1.5,3),(0,0,1),300,(.65,.80,1),4)
area('Front photographic fill',(0,-4,3),(0,1,1),180,(1,.82,.61),4)
for pos,power in [((-.5,2.15,1),25),((-3.35,2,2.1),35),((3,-2.65,1),25)]:
    d=bpy.data.lights.new('Lantern warm bounce','POINT');d.energy=power;d.color=(1,.58,.22);d.shadow_soft_size=.3
    o=bpy.data.objects.new(d.name,d);S.collection.objects.link(o);o.location=pos;o.parent=lights
def camera(name,loc,target,lens):
    d=bpy.data.cameras.new(name);d.lens=lens;d.clip_start=.05;d.clip_end=100
    o=bpy.data.objects.new(name,d);S.collection.objects.link(o);o.location=loc;o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler();return o
hero=camera('D2_CAM_HERO',(2,-3.28,1.78),(-.8,1.25,2.0),19)
roofcam=camera('D2_CAM_GLASS_ROOF',(.2,-2.85,1.35),(-.5,1,3.05),16)
overview=camera('D2_CAM_OVERVIEW',(10,-13,11),(0,0,1.3),48)
S.camera=hero
try:S.render.engine='CYCLES'
except TypeError as err:raise RuntimeError(str(err))
S.cycles.samples=40;S.cycles.use_denoising=True
S.render.resolution_x=1440;S.render.resolution_y=1080;S.render.resolution_percentage=100
S.render.image_settings.file_format=enum(S.render.image_settings,'file_format','PNG')
# Keep Blender's configured display transform; dynamic color-management enums
# may not list their items through RNA.
S.render.film_transparent=False

bpy.ops.wm.save_as_mainfile(filepath='/tmp/dream2_unmerged.blend')
exec((ROOT/'tools/blender/export_dream_bedroom_2.py').read_text(),globals())
