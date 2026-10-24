"""桃隐仙居 / Peachveil Sanctuary. Original native Blender geometry.

Run through Blender MCP or `blender --background --python this_file`.
Creates an indoor-only GLB, editable .blend and embedded procedural PBR textures.
The two companion scripts contain furniture and decorative modeling.
"""
import bpy, math, random, sys, json, re, ast
from pathlib import Path
from mathutils import Vector
import numpy as np

ROOT=Path(__file__).resolve().parents[2] if '__file__' in globals() else Path('/home/neethu/projects/AIAvatar-SDK')
OUT=ROOT/'assets/dream_bedroom_3'
OUT.mkdir(parents=True,exist_ok=True)
(OUT/'textures').mkdir(exist_ok=True)
random.seed(31827)
# Preserve unsaved work before replacing the working scene. Source assets are never overwritten.
if bpy.data.is_dirty and bpy.data.filepath:
    bpy.ops.wm.save_as_mainfile(filepath='/tmp/before_peachveil_working_copy.blend',copy=True)
S=bpy.data.scenes.new('Peachveil Sanctuary • 桃隐仙居')
bpy.context.window.scene=S
for other in list(bpy.data.scenes):
    if other != S:
        for ob in list(other.objects):bpy.data.objects.remove(ob,do_unlink=True)
        bpy.data.scenes.remove(other)
bpy.data.orphans_purge(do_recursive=True)
S.name='Peachveil Sanctuary • 桃隐仙居'
for data in list(bpy.data.materials):
    if not data.users:bpy.data.materials.remove(data)

def enum(owner,prop,wanted):
    return next(i.identifier for i in owner.bl_rna.properties[prop].enum_items if i.identifier==wanted)
S.unit_settings.system=enum(S.unit_settings,'system','METRIC');S.unit_settings.scale_length=1
S.world=bpy.data.worlds.new('Preview only • lavender daylight, no exterior meshes')
S.world.use_nodes=True
bg=next(n for n in S.world.node_tree.nodes if n.type=='BACKGROUND')
bg.inputs['Color'].default_value=(.42,.49,.65,1);bg.inputs['Strength'].default_value=.45

def material(name,color,rough=.6,metal=0,alpha=1,emission=0):
    m=bpy.data.materials.new('D3 • '+name);m.use_nodes=True
    p=next(n for n in m.node_tree.nodes if n.type=='BSDF_PRINCIPLED')
    p.inputs['Base Color'].default_value=(*color,alpha)
    p.inputs['Roughness'].default_value=rough;p.inputs['Metallic'].default_value=metal
    p.inputs['Alpha'].default_value=alpha
    if emission:
        p.inputs['Emission Color'].default_value=(*color,1);p.inputs['Emission Strength'].default_value=emission
    if alpha<1:
        m.surface_render_method=enum(m,'surface_render_method','BLENDED')
        m.use_transparent_shadow=True;p.inputs['Specular IOR Level'].default_value=.14
    m.diffuse_color=(*color,alpha)
    return m

def texture(m,kind,base):
    n=512;yy,xx=np.mgrid[0:n,0:n]/n;rng=np.random.default_rng(100+len(bpy.data.materials))
    if kind=='wood':
        q=xx*36+.35*np.sin(yy*8)+.1*np.sin(yy*31)
        field=.86+.10*np.sin(q*math.tau)+.055*np.sin(q*3.3*math.tau)+rng.normal(0,.015,(n,n))
    elif kind=='paper':
        field=.94+.015*np.sin(xx*291)+.02*np.sin(yy*393)+rng.normal(0,.022,(n,n))
    elif kind=='plaster':
        field=.96+.025*np.sin(xx*28+yy*13)+rng.normal(0,.019,(n,n))
    else:
        field=.94+.03*np.sin(xx*n*math.pi)+.025*np.sin(yy*n*math.pi)+rng.normal(0,.012,(n,n))
    rgba=np.ones((n,n,4),np.float32)
    for i,c in enumerate(base):rgba[:,:,i]=np.clip(field*c,0,1)
    im=bpy.data.images.new(m.name+' albedo',width=n,height=n);im.pixels.foreach_set(rgba.ravel())
    im.filepath_raw=str(OUT/'textures'/(m.name.replace('D3 • ','').replace(' ','_')+'.png'))
    im.file_format=enum(im,'file_format','PNG');im.save();im.pack()
    t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=im
    p=next(n for n in m.node_tree.nodes if n.type=='BSDF_PRINCIPLED')
    m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color'])
    return m

wood=texture(material('Polished peachwood',(.32,.13,.065),.34),'wood',(.43,.21,.105))
darkwood=texture(material('Carved rosewood',(.13,.052,.029),.4),'wood',(.23,.094,.046))
floorwood=texture(material('Honey timber floor',(.48,.27,.14),.38),'wood',(.56,.33,.17))
linen=texture(material('Blush silk jacquard',(.76,.35,.42),.78),'cloth',(.84,.47,.54))
ivory=texture(material('Warm ivory silk',(.90,.79,.62),.65),'cloth',(.93,.85,.73))
rose=texture(material('Dusty peach brocade',(.67,.27,.34),.81),'cloth',(.76,.36,.43))
paper=texture(material('Handmade rice paper',(.9,.77,.57),.9),'paper',(.94,.84,.66))
plaster=texture(material('Warm mineral plaster',(.71,.59,.45),.95),'plaster',(.78,.68,.55))
silk=material('Translucent peach gauze',(.96,.53,.62),.88,alpha=.36)
gold=material('Aged champagne gold',(.67,.40,.14),.3,.72)
jade=material('Celadon jade glaze',(.27,.56,.48),.23,.08)
ink=material('Charcoal ink',(.027,.039,.037),.7)
plum=material('Deep plum lacquer',(.25,.058,.095),.34)
glow=material('Warm lantern paper',(.98,.72,.38),.55,emission=1.8)
roofmat=material('Jade charcoal roof tile',(.10,.19,.18),.76)
petal_mats=[material('Peach petal '+str(i),c,.65) for i,c in enumerate([(.92,.45,.54),(.97,.63,.69),(.98,.76,.76)])]
leafmats=[material('Tea leaf '+str(i),c,.78) for i,c in enumerate([(.20,.35,.23),(.32,.46,.29)])]

def empty(name,loc=(0,0,0),parent=None,**props):
    o=bpy.data.objects.new(name,None);S.collection.objects.link(o);o.parent=parent;o.location=loc;o.empty_display_size=.12
    for k,v in props.items():o[k]=v
    return o
root=empty('DREAM_BEDROOM_3',units='meters',title='桃隐仙居 · Peachveil Sanctuary',schemaVersion=1,noExterior=True)
arch=empty('ARCHITECTURE',parent=root)
floor_group=empty('FLOOR',parent=arch,role='floor',canHideForCamera=False)
wall_north=empty('WALL_NORTH',parent=arch,role='wall',canHideForCamera=True,planePointGltf=[0,0,-3.5],outwardNormalGltf=[0,0,-1])
wall_south=empty('WALL_SOUTH',parent=arch,role='wall',canHideForCamera=True,planePointGltf=[0,0,3.5],outwardNormalGltf=[0,0,1])
wall_east=empty('WALL_EAST',parent=arch,role='wall',canHideForCamera=True,planePointGltf=[4,0,0],outwardNormalGltf=[1,0,0])
wall_west=empty('WALL_WEST',parent=arch,role='wall',canHideForCamera=True,planePointGltf=[-4,0,0],outwardNormalGltf=[-1,0,0])
ceiling_group=empty('CEILING',parent=arch,role='ceiling',canHideForCamera=True,planePointGltf=[0,4.65,0],outwardNormalGltf=[0,1,0])
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
        m=o.modifiers.new('Crafted softened edge','BEVEL');m.width=bevel;m.segments=2
        o.modifiers.new('Weighted normals','WEIGHTED_NORMAL')
    return o
def sphere(name,loc,scale,mat,parent=None,segments=16,rings=8):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=segments,ring_count=rings,radius=1,location=loc)
    o=finish(bpy.context.object,name,mat,parent);o.scale=scale
    for p in o.data.polygons:p.use_smooth=True
    return o
def cylinder(name,loc,radius,depth,mat,parent=None,vertices=20,radius2=None):
    if radius2 is None:bpy.ops.mesh.primitive_cylinder_add(vertices=vertices,radius=radius,depth=depth,location=loc)
    else:bpy.ops.mesh.primitive_cone_add(vertices=vertices,radius1=radius,radius2=radius2,depth=depth,location=loc)
    o=finish(bpy.context.object,name,mat,parent)
    for p in o.data.polygons:p.use_smooth=len(p.vertices)==4
    return o
def rod(name,a,b,r,mat,parent=None,vertices=8):
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

# Floor remains at Z=0 in Blender; all architecture is split by camera-cutaway ownership.
cube('Continuous timber foundation',(0,0,-.14),(8.18,7.18,.26),darkwood,.035,floor_group)
for j in range(28):
    y=-3.5+(j+.5)*.25
    for i in range(6):
        a=max(-4,-5.5+i*1.85+(j%3)*.61);b=min(4,-3.65+i*1.85+(j%3)*.61)
        if b>a:
            board=cube('Honey timber plank',((a+b)/2,y,-.014),(b-a-.009,.242,.028),floorwood,.002,floor_group)
            # Grain follows each plank's long axis; offset tiles avoid synchronized repeats.
            u0,v0=random.random(),random.random()
            for loop in board.data.loops:
                co=board.data.vertices[loop.vertex_index].co
                board.data.uv_layers.active.data[loop.index].uv=(co.y*1.4+u0,co.x*.25+v0)
for side in [-1,1]:
    cube('Floor perimeter inlay',(side*3.83,0,.002),(.016,6.68,.008),gold,.001,floor_group)
    cube('Floor perimeter inlay',(0,side*3.34,.002),(7.68,.016,.008),gold,.001,floor_group)

# West plaster wall and southeast entrance screens shelter two open railed facades.
cube('West plaster wall',(-4.065,0,1.625),(.13,7.1,3.25),plaster,.015,wall_west)
for y in [-3.45,0,3.45]:cube('West panel upright',(-3.97,y,1.65),(.09,.12,3.20),wood,.012,wall_west)
for z in [.12,1.08,3.15]:cube('West wall dado rail',(-3.965,0,z),(.11,7.0,.10),darkwood,.008,wall_west)
for y in np.arange(-3.35,3.4,.25):cube('West lower wall slat',(-3.983,y,.56),(.06,.035,.84),wood,.005,wall_west)
# An ivory round moon medallion and jade/gold mountain lines, entirely inside the wall.
disc=cylinder('Moon fresco roundel',(-3.976,.8,2.12),.76,.018,paper,wall_west,64)
disc.rotation_euler.y=math.pi/2
for k in range(3):
    pts=[(-3.958,.8-.62+i*.062,1.82+k*.14+.2*math.sin(i*.45+k)+.1*math.sin(i*.83)) for i in range(21)]
    curve('Mountain fresco gilding',pts,.009,gold if k==2 else jade,wall_west)

# Solid south partitions either side of a central open doorway; latticed transom above it.
entry_x=.15;door_half=.85
for a,b in [(-4,entry_x-door_half),(entry_x+door_half,4)]:
    cube('South framed plaster panel',((a+b)/2,-3.56,1.60),(b-a,.12,3.20),plaster,.012,wall_south)
    for z in [.17,1.0,3.15]:cube('South panel horizontal frame',((a+b)/2,-3.476,z),(b-a,.10,.10),wood,.01,wall_south)
    for x in np.arange(a+.15,b-.05,.28):cube('South dado slat',(x,-3.481,.55),(.045,.06,.82),darkwood,.004,wall_south)
for x in [entry_x-door_half,entry_x+door_half]:
    cube('Entrance carved jamb',(x,-3.45,1.63),(.14,.20,3.26),wood,.02,wall_south)
    cube('Entrance jamb gold line',(x,-3.337,1.42),(.018,.012,2.4),gold,.002,wall_south)
cube('Entrance transom beam',(entry_x,-3.46,2.60),(1.83,.18,.16),wood,.018,wall_south)
for x in np.arange(entry_x-.7,entry_x+.8,.20):
    cube('Entrance transom lattice',(x,-3.46,2.88),(.026,.065,.44),darkwood,.002,wall_south)
for z in [2.77,3.0]:cube('Entrance transom lattice',(entry_x,-3.46,z),(1.6,.065,.024),gold,.002,wall_south)
cube('Entry threshold',(entry_x,-3.45,.018),(1.60,.23,.036),wood,.008,floor_group)
entry=group('PORTAL_ENTRY','入户门槛',(.15,-3.5,0),180,False)

def railing(name,width,origin,yaw,parent):
    g=empty(name,origin,parent);g.rotation_euler.z=yaw
    for z,h in [(.12,.12),(.88,.105)]:cube(name+' handrail',(0,0,z),(width,.115,h),wood,.016,g)
    for x in np.arange(-width/2+.12,width/2,.55):
        cube(name+' baluster',(x,0,.50),(.055,.07,.69),wood,.007,g)
    # Repeating square meanders between rails, with open centers.
    for x in np.arange(-width/2+.29,width/2-.1,.55):
        pts=[(x-.15,-.015,.57),(x-.15,-.015,.72),(x+.15,-.015,.72),(x+.15,-.015,.34),(x-.15,-.015,.34),(x-.15,-.015,.46),(x+.03,-.015,.46)]
        curve(name+' open meander',pts,.016,gold,g)
    return g
railing('North open viewing balustrade',7.8,(0,3.47,0),0,wall_north)
railing('East open viewing balustrade',6.86,(3.97,0,0),math.pi/2,wall_east)

def post(x,y,g):
    cylinder('Octagonal carved timber column',(x,y,1.59),.125,3.12,wood,g,16)
    for z in [.08,.2,2.95,3.09]:cylinder('Column gold collar',(x,y,z),.137,.035,gold,g,16)
    cube('Carved column foot',(x,y,.09),(.32,.32,.18),ivory,.024,g)
    # Bracket arms echo traditional dougong without a heavy exterior cornice.
    for side in [-1,1]:
        pts=[(x,y,2.82),(x+side*.13,y,2.88),(x+side*.34,y,3.03),(x+side*.43,y,3.04)]
        curve('Cloud bracket curved arm',pts,.045,wood,g)
        cube('Cloud bracket cap',(x+side*.32,y,3.13),(.35,.18,.10),darkwood,.012,g)
for x in [-3.94,-1.32,1.32,3.94]:post(x,3.47,wall_north)
for y in [-3.43,-1.15,1.15]:post(3.97,y,wall_east)
for x in [-3.94]:post(x,-3.43,wall_west)
for g,y in [(wall_north,3.47),(wall_south,-3.47)]:
    cube('Facade eave beam',(0,y,3.21),(8.12,.21,.20),wood,.022,g)
    cube('Facade eave gold fillet',(0,y-.115,3.18),(7.9,.016,.025),gold,.002,g)
    for x in np.arange(-3.65,3.7,.35):
        cube('Upper lattice upright',(x,y,2.92),(.025,.07,.40),darkwood,.003,g)
    for z in [2.74,2.94,3.12]:cube('Upper lattice crossrail',(0,y,z),(7.8,.075,.026),wood,.003,g)
for g,x in [(wall_east,3.97),(wall_west,-3.97)]:
    cube('Side eave beam',(x,0,3.21),(.20,7.1,.20),wood,.018,g)
    for y in np.arange(-3.2,3.3,.35):cube('Side upper lattice',(x,y,2.93),(.075,.026,.38),darkwood,.003,g)
    for z in [2.74,2.94,3.12]:cube('Side upper lattice rail',(x,0,z),(.075,6.9,.025),wood,.003,g)

# Curved pitched roof. Solid timber undersides and real jade tile strips; no outside backdrop.
def roofz(x):return 3.25+1.40*(1-abs(x)/4.20)**1.30
for side in [-1,1]:
    v=[];f=[];nx=18
    for j in range(2):
        for i in range(nx+1):
            x=side*4.2*i/nx;v.append((x,-3.7+j*7.4,roofz(x)))
    for i in range(nx):f.append((i,i+1,nx+2+i,nx+1+i))
    ob=mesh('Curved timber roof underside',v,f,darkwood,ceiling_group)
    m=ob.modifiers.new('Roof board thickness','SOLIDIFY');m.thickness=.08
    for y in np.linspace(-3.5,3.5,15):
        curve('Exposed curved roof rafter',[(side*4.13*i/24,y,roofz(side*4.13*i/24)-.075) for i in range(25)],.045,wood,ceiling_group)
    for u in [.27,.58,.87]:
        x=side*4.2*u;rod('Roof purlin',(x,-3.58,roofz(x)-.10),(x,3.58,roofz(x)-.10),.058,wood,ceiling_group,12)
    # Half-round channels follow the curved roof profile, joined as a single mesh later.
    for y in np.linspace(-3.63,3.63,33):
        verts=[];faces=[]
        for i in range(20):
            x=side*(.05+4.14*i/19)
            for k in range(5):
                th=math.pi*k/4;verts.append((x,y+.105*math.cos(th),roofz(x)+.045+.045*math.sin(th)))
        for i in range(19):
            for k in range(4):a=i*5+k;faces.append((a,a+1,a+6,a+5))
        mesh('Jade glazed roof channel',verts,faces,roofmat,ceiling_group)
    for y in [-3.62,3.62]:curve('Roof edge gold beading',[(side*4.2*i/24,y,roofz(side*4.2*i/24)+.05) for i in range(25)],.014,gold,ceiling_group)
rod('Carved ridge timber',(0,-3.65,4.58),(0,3.65,4.58),.105,wood,ceiling_group,16)
rod('Jade ridge cap',(0,-3.70,4.72),(0,3.70,4.72),.10,roofmat,ceiling_group,16)
for y in [-2.7,0,2.7]:
    rod('Cross roof tie beam',(-3.7,y,3.40),(3.7,y,3.40),.085,wood,ceiling_group,12)
    for side in [-1,1]:
        curve('Raised cloud roof brace',[(side*3.6,y,3.38),(side*2.9,y,3.46),(side*2.25,y,3.82),(side*1.55,y,4.10)],.065,wood,ceiling_group)

exec(compile((ROOT/'tools/blender/dream3_furniture.py').read_text(),str(ROOT/'tools/blender/dream3_furniture.py'),'exec'),globals())
exec(compile((ROOT/'tools/blender/dream3_decor.py').read_text(),str(ROOT/'tools/blender/dream3_decor.py'),'exec'),globals())

# Photographic lights and cameras are excluded from GLB, available in .blend.
lights=empty('PREVIEW_LIGHTS')
def area(name,loc,target,power,color,size):
    d=bpy.data.lights.new(name,'AREA');d.energy=power;d.color=color;d.shape=enum(d,'shape','DISK');d.size=size
    o=bpy.data.objects.new(name,d);S.collection.objects.link(o);o.parent=lights;o.location=loc;o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler()
area('Warm peach dawn key',(-1.8,5.5,4.0),(1,-.7,.8),1300,(1,.76,.59),5)
area('Cool open east fill',(5.8,.4,3.2),(0,0,1),750,(.68,.79,1),4)
area('Soft warm front bounce',(-1.5,-3.4,2.8),(1,1,1),420,(1,.78,.65),4)
area('Ceiling soft bounce',(0,0,3.0),(0,0,4.5),240,(1,.66,.46),3)
d=bpy.data.lights.new('Low peach dawn through lattice','SUN');d.energy=.85;d.color=(1,.79,.65);d.angle=.06
o=bpy.data.objects.new(d.name,d);S.collection.objects.link(o);o.parent=lights
o.rotation_euler=Vector((.4,-.8,-.46)).to_track_quat('-Z','Y').to_euler()
for loc,power in [((-1.2,2.5,2.8),35),((2.1,.0,2.8),30),((-2.7,-1.6,2.7),22)]:
    d=bpy.data.lights.new('Lantern preview bounce','POINT');d.energy=power;d.color=(1,.54,.25);d.shadow_soft_size=.35
    o=bpy.data.objects.new(d.name,d);S.collection.objects.link(o);o.location=loc;o.parent=lights
def camera(name,loc,target,lens):
    d=bpy.data.cameras.new(name);d.lens=lens;d.clip_start=.05;d.clip_end=100
    o=bpy.data.objects.new(name,d);S.collection.objects.link(o);o.location=loc;o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler();return o
hero=camera('D3_CAM_HERO',(-3.45,-3.05,2.02),(1.3,1.05,1.75),22)
detailcam=camera('D3_CAM_TEA',(.15,-3.24,1.72),(-1.45,.8,1.55),22)
overview=camera('D3_CAM_OVERVIEW',(10,-13,10.5),(0,0,1.2),48)
overview.data.type=enum(overview.data,'type','ORTHO');overview.data.ortho_scale=12.7
S.camera=hero
try:S.render.engine='CYCLES'
except TypeError as error:raise RuntimeError(str(error))
S.cycles.samples=48;S.cycles.use_denoising=True
S.render.resolution_x=1440;S.render.resolution_y=1080;S.render.resolution_percentage=100
S.render.image_settings.file_format=enum(S.render.image_settings,'file_format','PNG');S.render.film_transparent=False
S.render.filepath=str(OUT/'bedroom_preview.png')
# Guard unmerged source for easier modifications while maintaining lightweight runtime meshes.
bpy.ops.wm.save_as_mainfile(filepath='/tmp/dream3_unmerged.blend')
exec(compile((ROOT/'tools/blender/export_dream_bedroom_3.py').read_text(),str(ROOT/'tools/blender/export_dream_bedroom_3.py'),'exec'),globals())
