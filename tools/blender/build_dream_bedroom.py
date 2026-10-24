"""Forest Reverie bedroom. Execute in live Blender via Blender MCP.
Original procedural geometry/textures; metre scale, Blender Z-up, glTF Y-up.
Creates a dedicated scene and preserves existing scenes.
"""
import bpy, math, random, json, os
from pathlib import Path
from mathutils import Vector
import numpy as np

ROOT = Path('/home/neethu/projects/AIAvatar-SDK')
OUT = ROOT / 'assets/dream_bedroom'
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'textures').mkdir(exist_ok=True)
random.seed(2401)
for old in list(bpy.data.scenes):
    if old.name.startswith('Forest Reverie • Dream Bedroom'):
        for obj in list(old.objects):bpy.data.objects.remove(obj,do_unlink=True)
        bpy.data.scenes.remove(old)
S = bpy.data.scenes.new('Forest Reverie • Dream Bedroom')
bpy.context.window.scene = S
S.unit_settings.system = next(i.identifier for i in bpy.types.UnitSettings.bl_rna.properties['system'].enum_items if i.identifier == 'METRIC')
S.unit_settings.scale_length = 1
S.world = bpy.data.worlds.new('Forest morning atmosphere')
S.world.use_nodes = True
bg = next(n for n in S.world.node_tree.nodes if n.type == 'BACKGROUND')
bg.inputs['Color'].default_value=(.39,.52,.45,1)
bg.inputs['Strength'].default_value=.38

def material(name,color,rough=.6,metal=0,emission=0,alpha=1):
    m=bpy.data.materials.new(name); m.use_nodes=True
    p=next(n for n in m.node_tree.nodes if n.type=='BSDF_PRINCIPLED')
    p.inputs['Base Color'].default_value=(*color,alpha)
    p.inputs['Roughness'].default_value=rough; p.inputs['Metallic'].default_value=metal
    p.inputs['Alpha'].default_value=alpha
    if emission:
        p.inputs['Emission Color'].default_value=(*color,1); p.inputs['Emission Strength'].default_value=emission
    if alpha<1:
        m.surface_render_method=next(i.identifier for i in bpy.types.Material.bl_rna.properties['surface_render_method'].enum_items if i.identifier=='BLENDED')
        m.use_transparent_shadow=True
    m.diffuse_color=(*color,alpha)
    return m

def texture(m,kind,color):
    n=512; yy,xx=np.mgrid[0:n,0:n]/n
    rng=np.random.default_rng(200+len(bpy.data.materials))
    if kind=='wood':
        field=.8+.11*np.sin((xx*47+.55*np.sin(yy*12)+.16*np.sin(yy*41))*2*math.pi)
        field+=.05*np.sin((xx*129+.7*np.sin(yy*8))*2*math.pi)+rng.normal(0,.025,(n,n))
    else:
        field=.93+.035*np.sin(xx*n*math.pi)+.035*np.sin(yy*n*math.pi)+rng.normal(0,.025,(n,n))
    rgba=np.ones((n,n,4),dtype=np.float32)
    for c in range(3): rgba[:,:,c]=np.clip(field*color[c],0,1)
    im=bpy.data.images.new(m.name+'_color',width=n,height=n)
    im.pixels.foreach_set(rgba.ravel()); im.filepath_raw=str(OUT/'textures'/(m.name+'.png'))
    im.file_format=next(i.identifier for i in bpy.types.ImageFormatSettings.bl_rna.properties['file_format'].enum_items if i.identifier=='PNG')
    im.save(); im.pack()
    tex=m.node_tree.nodes.new('ShaderNodeTexImage');tex.image=im
    p=next(n for n in m.node_tree.nodes if n.type=='BSDF_PRINCIPLED')
    m.node_tree.links.new(tex.outputs['Color'],p.inputs['Base Color'])
    return m

oak=texture(material('Honey oak',(.5,.27,.12),.42),'wood',(.64,.40,.21))
walnut=texture(material('Walnut',(.22,.12,.05),.45),'wood',(.36,.20,.095))
sage=material('Sage limewash',(.24,.36,.20),.92)
panel=material('Deep olive panelling',(.115,.20,.12),.76)
linen=texture(material('Moss washed linen',(.31,.44,.22),.95),'cloth',(.46,.61,.34))
velvet=texture(material('Forest velvet',(.055,.16,.085),.94),'cloth',(.12,.29,.15))
cream=texture(material('Warm ivory boucle',(.79,.73,.56),.93),'cloth',(.86,.81,.66))
rugmat=texture(material('Oatmeal woven rug',(.65,.60,.42),1),'cloth',(.77,.72,.53))
terra=material('Clay ceramic',(.44,.19,.095),.74)
gold=material('Aged brass',(.52,.34,.12),.33,.72)
black=material('Charcoal enamel',(.018,.025,.022),.35,.25)
white=material('Porcelain',(.89,.87,.76),.3)
glass=material('Glass • alpha portable',(.61,.84,.76),.12,.05,alpha=.055)
next(n for n in glass.node_tree.nodes if n.type=='BSDF_PRINCIPLED').inputs['Specular IOR Level'].default_value=.15
glow=material('Warm fairy lights',(1,.62,.22),.3,emission=4)
screen=material('Midnight teal screen',(.025,.105,.10),.25,emission=.35)
blue=material('Blue console LED',(.13,.62,.9),.3,emission=2)
flower=material('Lavender',(.39,.24,.51),.9)
leaves=[material('Foliage %02d'%i,c,.9) for i,c in enumerate([(.10,.24,.105),(.20,.34,.12),(.065,.17,.075),(.31,.42,.17),(.24,.40,.22)])]
books=[material('Book cloth %d'%i,c,.86) for i,c in enumerate([(.38,.13,.07),(.17,.25,.18),(.64,.51,.27),(.14,.22,.28),(.58,.40,.25)])]

def empty(name,loc=(0,0,0),parent=None,**props):
    o=bpy.data.objects.new(name,None); S.collection.objects.link(o);o.location=loc;o.parent=parent
    o.empty_display_size=.15
    for k,v in props.items():o[k]=v
    return o

root=empty('DREAM_BEDROOM',units='meters',floorY=0.0,forward='glTF +Z',title='Forest Reverie',schemaVersion=1)
arch=empty('ARCHITECTURE',parent=root)
floor_group=empty('FLOOR',parent=arch,role='floor',canHideForCamera=False)
wall_north=empty('WALL_NORTH',parent=arch,role='wall',canHideForCamera=True,
                 outwardNormalGltf=[0,0,-1],planePointGltf=[0,0,-3.5])
wall_south=empty('WALL_SOUTH',parent=arch,role='wall',canHideForCamera=True,
                 outwardNormalGltf=[0,0,1],planePointGltf=[0,0,3.5])
wall_east=empty('WALL_EAST',parent=arch,role='wall',canHideForCamera=True,
                outwardNormalGltf=[1,0,0],planePointGltf=[4,0,0])
wall_west=empty('WALL_WEST',parent=arch,role='wall',canHideForCamera=True,
                outwardNormalGltf=[-1,0,0],planePointGltf=[-4,0,0])
ceiling_group=empty('CEILING',parent=arch,role='ceiling',canHideForCamera=True,
                   outwardNormalGltf=[0,1,0],planePointGltf=[0,3.4,0])
cutaway_groups=[wall_north,wall_south,wall_east,wall_west,ceiling_group]
forest=empty('FOREST_BACKDROP',parent=root,optional=True,collision=False)
furnitures={}

def group(name,label,loc,yaw=0):
    o=empty(name,loc,root,label=label,yawDeg=yaw)
    o.rotation_euler.z=math.radians(yaw);furnitures[name]=o;return o

def finish(o,name,mat,parent=None):
    o.name=name
    if mat:o.data.materials.append(mat)
    if parent:o.parent=parent
    return o

def cube(name,loc,dims,mat,bevel=.015,parent=None):
    bpy.ops.mesh.primitive_cube_add(size=1,location=loc)
    o=finish(bpy.context.object,name,mat,parent);o.dimensions=dims
    bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    if bevel:
        m=o.modifiers.new('Soft edge','BEVEL');m.width=bevel;m.segments=3
        o.modifiers.new('Corner normals','WEIGHTED_NORMAL')
    return o

def sphere(name,loc,scale,mat,parent=None,segments=24,rings=12):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=segments,ring_count=rings,radius=1,location=loc)
    o=finish(bpy.context.object,name,mat,parent);o.scale=scale
    for p in o.data.polygons:p.use_smooth=True
    return o

def cylinder(name,loc,radius,depth,mat,parent=None,vertices=32,radius2=None):
    if radius2 is None:
        bpy.ops.mesh.primitive_cylinder_add(vertices=vertices,radius=radius,depth=depth,location=loc)
    else:
        bpy.ops.mesh.primitive_cone_add(vertices=vertices,radius1=radius,radius2=radius2,depth=depth,location=loc)
    o=finish(bpy.context.object,name,mat,parent)
    m=o.modifiers.new('Rim softness','BEVEL');m.width=.008;m.segments=2
    o.modifiers.new('Normals','WEIGHTED_NORMAL')
    return o

def rod(name,a,b,r,mat,parent=None,vertices=12):
    a,b=Vector(a),Vector(b)
    o=cylinder(name,(a+b)/2,r,(b-a).length,mat,parent,vertices)
    o.rotation_euler=(b-a).to_track_quat('Z','Y').to_euler();return o

def curve(name,points,r,mat,parent=None,cyclic=False):
    c=bpy.data.curves.new(name,'CURVE');c.dimensions='3D';c.resolution_u=12;c.bevel_depth=r;c.bevel_resolution=2
    p=c.splines.new('POLY');p.points.add(len(points)-1)
    for a,b in zip(p.points,points):a.co=(*b,1)
    p.use_cyclic_u=cyclic
    o=bpy.data.objects.new(name,c);S.collection.objects.link(o);finish(o,name,mat,parent);return o

def mesh(name,verts,faces,mat,parent=None):
    m=bpy.data.meshes.new(name);m.from_pydata(verts,[],faces);m.update()
    o=bpy.data.objects.new(name,m);S.collection.objects.link(o);finish(o,name,mat,parent)
    # World/projected UV coordinates are explicit and portable to glTF.
    uv=m.uv_layers.new(name='UVMap')
    for p in m.polygons:
        for li in p.loop_indices:
            co=m.vertices[m.loops[li].vertex_index].co;uv.data[li].uv=(co.x,co.y)
    return o

def prism(name,poly,z0,z1,mat,parent=None):
    n=len(poly);v=[(x,y,z) for z in [z0,z1] for x,y in poly]
    f=[tuple(reversed(range(n))),tuple(range(n,2*n))]+[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
    o=mesh(name,v,f,mat,parent);m=o.modifiers.new('Rounded edge','BEVEL');m.width=.025;m.segments=3;o.modifiers.new('Normals','WEIGHTED_NORMAL');return o

def textmesh(name,body,loc,size,mat,parent=None,rot=(math.pi/2,0,0)):
    c=bpy.data.curves.new(name,'FONT');c.body=body;c.size=size;c.align_x='CENTER';c.extrude=.0005;c.resolution_u=4
    o=bpy.data.objects.new(name,c);S.collection.objects.link(o);finish(o,name,mat,parent);o.location=loc;o.rotation_euler=rot
    bpy.ops.object.select_all(action='DESELECT');o.select_set(True);bpy.context.view_layer.objects.active=o;bpy.ops.object.convert(target='MESH');return bpy.context.object

# 8 x 7 m usable floor. The origin is the centre of the finished floor.
cube('Floor structural slab',(0,0,-.14),(8.26,7.26,.26),walnut,.025,floor_group)
for row in range(28):
    y=-3.5+(row+.5)*.25
    for col in range(5):
        a=max(-4,-4.8+col*2+(row%3)*.55);b=min(4,-2.8+col*2+(row%3)*.55)
        if b>a:
            cube('Oak floorboard',(a+(b-a)/2,y,-.015),(b-a-.012,.238,.03),oak,.004,floor_group)
cube('West sage wall',(-4.1,0,1.7),(.2,7.2,3.4),sage,.025,wall_west)
cube('East lower wall',(4.1,1.25,.32),(.2,4.7,.64),sage,.02,wall_east)
cube('East solid front wall',(4.1,-2.3,1.7),(.2,2.4,3.4),sage,.02,wall_east)
cube('North window sill wall',(0,3.6,.32),(8.2,.2,.64),sage,.02,wall_north)
cube('North lintel',(0,3.6,3.24),(8.2,.2,.32),panel,.02,wall_north)
cube('East lintel',(4.1,1.25,3.24),(.2,4.7,.32),panel,.02,wall_east)
front=empty('CUTAWAY_FRONT_WALL',parent=wall_south,canHideForCamera=True,legacyAlias=True)
cube('Front wall broad',(1,-3.6,1.7),(6.2,.2,3.4),sage,.02,front)
cube('Front wall left',(-3.75,-3.6,1.7),(.7,.2,3.4),sage,.02,front)
cube('Door lintel',(-2.75,-3.6,2.92),(1.3,.2,.96),sage,.01,front)
ceiling=empty('CEILING_OPTIONAL',parent=ceiling_group,canHideForCamera=True,legacyAlias=True)
cube('Ceiling',(0,0,3.5),(8.25,7.25,.18),cream,.02,ceiling)
for y,side in [(-3.36,wall_south),(3.36,wall_north)]:cube('Oak cornice',(0,y,3.21),(8,.12,.12),oak,.025,side)
for x,side in [(-3.96,wall_west),(3.96,wall_east)]:cube('Oak cornice',(x,0,3.21),(.12,7,.12),oak,.02,side)
cube('West wainscot',(-3.975,0,.58),(.045,7,1.16),panel,.008,wall_west)
cube('West chair rail',(-3.93,0,1.16),(.09,7,.055),oak,.01,wall_west)
for i in range(14):cube('Panel upright',(-3.93,-3.25+i*.5,.57),(.05,.025,1.1),oak,.003,wall_west)
for x,side in [(-3.97,wall_west),(3.97,wall_east)]:cube('Skirting',(x,0,.07),(.07,7,.14),walnut,.01,side)
cube('North window sill',(0,3.4,.67),(8,.32,.09),oak,.025,wall_north)
cube('East window sill',(3.91,1.18,.67),(.32,4.62,.09),oak,.025,wall_east)

northglass=group('GLASS_PANORAMA_NORTH','北侧全景玻璃',(0,3.48,.7),0)
eastglass=group('GLASS_PANORAMA_EAST','东侧全景玻璃',(3.98,1.18,.7),-90)
northglass.parent=wall_north
eastglass.parent=wall_east
cube('North glass sheet',(0,0,1.19),(7.86,.018,2.38),glass,0,northglass)
cube('East glass sheet',(0,0,1.19),(4.58,.018,2.38),glass,0,eastglass)
for x in [-3.96,-1.4,1.35,3.96]:cube('Window bronze mullion',(x,3.46,1.88),(.055,.065,2.52),black,.008,wall_north)
for y in [-1.15,1.05,3.45]:cube('Window bronze mullion',(3.98,y,1.88),(.065,.055,2.52),black,.008,wall_east)
for z in [.72,3.1]:
    cube('North window rail',(0,3.46,z),(8,.065,.055),black,.007,wall_north)
    cube('East window rail',(3.98,1.18,z),(.065,4.64,.055),black,.007,wall_east)

# Sculpted curtain folds; open panels leave the forest view unobstructed.
def curtain(name,x0,width,y,depth=0):
    nx,nz=44,26;v=[];f=[]
    for j in range(nz+1):
        t=j/nz;z=.72+t*2.36
        pinch=1-.35*math.exp(-((t-.33)/.18)**2)
        for i in range(nx+1):
            u=i/nx;x=x0+width*(.5+(u-.5)*pinch)
            v.append((x,y+.075*math.sin(u*math.pi*16)*(.8+.2*t),z+.015*math.cos(u*math.pi*16)*(1-t)))
    for j in range(nz):
        for i in range(nx):a=j*(nx+1)+i;f.append((a,a+1,a+nx+2,a+nx+1))
    o=mesh(name,v,f,velvet,wall_north)
    for p in o.data.polygons:p.use_smooth=True
    sol=o.modifiers.new('Cloth thickness','SOLIDIFY');sol.thickness=.006
    curve('Curtain tie',[(x0+.12,y-.08,1.51),(x0+width/2,y-.13,1.45),(x0+width-.12,y-.08,1.51)],.018,gold,wall_north)
curtain('Left forest curtain',-3.93,1.05,3.25)
curtain('Right forest curtain',2.98,.94,3.25)
rod('Curtain rail',(-3.98,3.22,3.12),(3.98,3.22,3.12),.023,gold,wall_north)

print('STAGE 1 • materials and architecture:',len(S.objects),'objects')
bpy.app.driver_namespace['dream_ns']=globals()
for part in ['dream_bedroom_furniture.py','dream_bedroom_finish.py','export_dream_bedroom.py']:
    path=ROOT/'tools/blender'/part
    if path.exists():exec(compile(path.read_text(),str(path),'exec'),globals())
