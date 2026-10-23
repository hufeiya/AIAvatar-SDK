"""Run in Blender: exec(compile(open(__file__).read(), __file__, 'exec')).
Builds a dedicated scene; preserves other scenes. No external assets required.
"""
import bpy
import math
import json
from pathlib import Path
from mathutils import Vector

ROOT = Path('/home/neethu/projects/AIAvatar-SDK')
OUT = ROOT / 'assets/debate_hall'
OUT.mkdir(parents=True, exist_ok=True)
ASSET = ROOT / 'app/src/main/assets/scene/debate_hall.glb'
previous = bpy.data.scenes.get('Debate Hall')
if previous:
    for obj in list(previous.objects):
        bpy.data.objects.remove(obj, do_unlink=True)
    bpy.data.scenes.remove(previous)
scene = bpy.data.scenes.new('Debate Hall')
bpy.context.window.scene = scene
scene.unit_settings.system = 'METRIC'
scene.unit_settings.scale_length = 1

def mat(name, color, metallic=0, rough=.5, emission=0):
    m = bpy.data.materials.new(name)
    m.diffuse_color = (*color, 1)
    m.use_nodes = True
    n = next(n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
    n.inputs['Base Color'].default_value = (*color, 1)
    n.inputs['Metallic'].default_value = metallic
    n.inputs['Roughness'].default_value = rough
    if emission:
        n.inputs['Emission Color'].default_value = (*color, 1)
        n.inputs['Emission Strength'].default_value = emission
    return m

navy = mat('Midnight acoustic fabric', (.025,.048,.085), rough=.85)
wall = mat('Slate blue plaster', (.09,.135,.19), rough=.8)
wood = mat('Warm walnut', (.25,.12,.055), rough=.45)
woodlight = mat('Walnut edge', (.47,.27,.12), rough=.4)
gold = mat('Brushed champagne', (.62,.43,.20), .72,.32)
black = mat('Powder coated graphite', (.025,.032,.04), .45,.36)
teal = mat('PRO petrol upholstery', (.025,.27,.31), rough=.8)
coral = mat('CON terracotta upholstery', (.48,.10,.075), rough=.8)
host = mat('HOST ivory upholstery', (.68,.61,.46), rough=.8)
white = mat('Ivory typography', (.88,.83,.70), rough=.55)
floor = mat('Blue charcoal carpet', (.065,.09,.12), rough=.96)
stone = mat('Dark terrazzo', (.14,.18,.22), rough=.65)
glow = mat('Warm integrated LED', (1,.69,.32), rough=.4, emission=2.5)
cyan = mat('PRO illuminated accent', (.08,.66,.72), emission=1)
red = mat('CON illuminated accent', (1,.25,.14), emission=1)

def cube(name, loc, dims, material, bevel=.025):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc)
    o=bpy.context.object; o.name=name
    o.dimensions=dims
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    o.data.materials.append(material)
    if bevel:
        mod=o.modifiers.new('Soft manufactured edges','BEVEL'); mod.width=bevel; mod.segments=3
        o.modifiers.new('Weighted corner normals','WEIGHTED_NORMAL')
    return o

def cyl(name, loc, radius, depth, material, vertices=32):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=loc)
    o=bpy.context.object; o.name=name; o.data.materials.append(material)
    mod=o.modifiers.new('Edge radius','BEVEL'); mod.width=.008; mod.segments=2
    o.modifiers.new('Normals','WEIGHTED_NORMAL')
    return o

def rod(name, a, b, radius, material):
    a,b=Vector(a),Vector(b)
    o=cyl(name,(a+b)/2,radius,(b-a).length,material,16)
    o.rotation_euler=(b-a).to_track_quat('Z','Y').to_euler()
    return o

def text(name, body, loc, size, material, rotation=(math.pi/2,0,0), align='CENTER'):
    c=bpy.data.curves.new(name,'FONT'); c.body=body; c.align_x=align; c.size=size
    c.resolution_u=4; c.extrude=.0005; c.bevel_depth=0
    o=bpy.data.objects.new(name,c); scene.collection.objects.link(o)
    o.location=loc; o.rotation_euler=rotation; c.materials.append(material)
    # Text must be mesh for glTF.
    bpy.ops.object.select_all(action='DESELECT'); o.select_set(True)
    bpy.context.view_layer.objects.active=o; bpy.ops.object.convert(target='MESH')
    return bpy.context.object

def empty(name, loc, yaw=0, **props):
    o=bpy.data.objects.new(name,None); scene.collection.objects.link(o)
    o.location=loc; o.rotation_euler.z=math.radians(yaw)
    o.empty_display_size=.18
    for k,v in props.items(): o[k]=v
    return o

# Blender +Y is the back wall; glTF -Z is the back wall. Floor is world Y=-1.
root=empty('DEBATE_HALL', (0,0,0), units='meters', floorY=-1.0,
           forward='glTF +Z', schemaVersion=1)
cube('Foundation', (0,.0,-1.40), (12,13,.4),stone,.08)
cube('Raised stage', (0,2.4,-1.12),(11.2,6.3,.19),stone,.02)
cube('Stage carpet', (0,2.4,-1.025),(11.2,6.3,.05),floor,.02)
cube('Audience carpet', (0,-4,-1.19),(11.5,4.9,.035),floor,.015)
cube('Stage fascia', (0,-.78,-1.1),(11.25,.12,.22),wood,.02)
cube('Stage lip light', (0,-.85,-1.015),(10.9,.025,.022),glow,.004)
for i,(y,z,w) in enumerate([(-1.10,-1.10,4.8),(-1.45,-1.18,5.5)]):
    cube('Central approach step %d'%i,(0,y,z-.04),(w,.38,.08),stone,.02)
    cube('Step nosing %d'%i,(0,y-.19,z),(w-.2,.025,.018),gold,.004)
cube('Rear wall',(0,5.65,1.65),(12,.25,5.5),wall,.03)
cube('Feature backdrop',(0,5.44,1.6),(8.8,.16,4.25),navy,.03)
for x in [-5.85,5.85]:
    cube('Side wall', (x,1,1.65),(.22,9.3,5.5),wall,.03)
    for y in [-2.8,-.9,1,2.9,4.8]:
        cube('Acoustic wall bay',(x*.979,y,1.6),(.06,1.55,3.4),navy,.01)
        cube('Wall bronze upright',(x*.971,y-.8,1.6),(.025,.025,3.8),gold,.003)
        cube('Wall sconce',(x*.966,y,2.75),(.035,.58,.045),glow,.008)
for side in [-1,1]:
    for i in range(15):
        x=side*(4.65+i*.065)
        cube('Walnut acoustic slat',(x,5.37,1.7),(.032,.10,4.85),woodlight,.005)
for x in [-4.45,4.45]:
    cube('Backdrop border',(x,5.31,1.6),(.035,.035,4.3),gold,.005)
for z in [-.55,3.75]:
    cube('Backdrop border',(0,5.31,z),(8.93,.035,.035),gold,.005)
text('Title','DEBATE  /  FORUM',(0,5.29,2.65),.49,white)
text('Subtitle','IDEAS   /   REASON   /   DIALOGUE',(0,5.28,2.20),.15,gold)
text('Season','THE ASSEMBLY     -     2026',(0,5.28,-.22),.12,white)
# Abstract central emblem: three radiating open arcs, modelled as slim rods.
for i in range(3):
    for j in range(16):
        a=math.radians(28+j*8); b=math.radians(28+(j+1)*8); r=.42+i*.15
        rod('Dialogue emblem',(r*math.cos(a),5.25,1.23+r*math.sin(a)),
            (r*math.cos(b),5.25,1.23+r*math.sin(b)),.014,gold)
text('Left stance','PRO',(-3.2,5.27,1.15),.43,cyan)
text('Left motto','AFFIRMATIVE',(-3.2,5.27,.84),.115,white)
text('Right stance','CON',(3.2,5.27,1.15),.43,red)
text('Right motto','NEGATIVE',(3.2,5.27,.84),.115,white)

layout=json.loads((ROOT/'app/src/main/assets/debate/layout_home_debate_v1.json').read_text())
manifest={'schemaVersion':1,'units':'meters','coordinateSystem':'glTF RH +Y up, +Z forward',
          'floorY':-1.0,'seatSurfaceHeight':.52,'seats':{}}
for ident,s in layout['seats'].items():
    x,y,yaw=s['x'],-s['z'],s['yawDeg']
    m=host if ident=='HOST' else teal if ident.startswith('PRO') else coral
    rig=empty('CHAIR_'+ident,(x,y,-1),yaw,seatId=ident)
    def part(name,loc,dims,material,bevel=.025):
        o=cube(ident+'_'+name,loc,dims,material,bevel); o.parent=rig; return o
    part('seat cushion',(0,0,.47),(.55,.53,.10),m,.055)
    part('seat piping',(0,0,.421),(.57,.55,.025),gold,.012)
    part('back shell',(0,.245,.83),(.56,.075,.68),wood,.045)
    part('back cushion',(0,.191,.83),(.49,.065,.58),m,.04)
    for xx in [-.215,.215]:
        for yy in [-.19,.20]:
            part('leg',(xx,yy,.205),(.033,.033,.41),black,.008)
            part('brass foot',(xx,yy,.025),(.037,.037,.05),gold,.006)
    # Armless chairs provide clear space for VRMA elbows and standing transitions.
    part('back medallion',(0,.289,.91),(.23,.008,.16),gold,.012)
    # A slim pedestal desk ahead of each participant; knee and standing clearance.
    part('desk top',(0,-.74,.84),(.66,.45,.055),woodlight,.025)
    part('desk edge',(0,-.74,.805),(.67,.46,.02),gold,.008)
    part('desk column',(0,-.84,.4),(.07,.10,.8),black,.012)
    part('desk foot',(0,-.80,.025),(.44,.27,.05),black,.015)
    part('desk modesty',(0,-.955,.62),(.62,.035,.34),navy,.012)
    o=text(ident+'_nameplate',ident,(0,-.978,.57),.095,white); o.parent=rig
    # Desktop note pad, angled microphone stem and capsule.
    part('notes',(-.09,-.74,.872),(.22,.20,.006),white,.004)
    mic=cyl(ident+'_mic base',(.21,-.8,.885),.045,.016,black); mic.parent=rig
    for o in [rod(ident+'_mic stem',(.21,-.8,.89),(.18,-.68,1.08),.009,black),
              rod(ident+'_mic capsule',(.18,-.68,1.08),(.18,-.65,1.10),.018,black)]: o.parent=rig
    # Directional locator nodes exported as glTF nodes, never visible geometry.
    for prefix,z in [('AVATAR_ROOT',1.0),('SEAT',.52),('FLOOR',0)]:
        a=empty(prefix+'_'+ident,(0,0,z),0,seatId=ident,kind=prefix,
                forwardLocal='+Z in glTF',seatSurfaceHeight=.52)
        a.parent=rig
    a=empty('FACING_'+ident,(0,-.6,.52),0,seatId=ident,kind='direction_target'); a.parent=rig
    # Permanent unobtrusive seat ID on the floor to aid manual placement.
    o=text(ident+'_floor label',ident,(0,-.40,.005),.085,gold,rotation=(0,0,0)); o.parent=rig
    theta=math.radians(yaw)
    manifest['seats'][ident]={
        'avatarRootNode':'AVATAR_ROOT_'+ident,'seatNode':'SEAT_'+ident,'floorNode':'FLOOR_'+ident,
        'facingNode':'FACING_'+ident,'avatarRoot':[s['x'],0,s['z']],
        'seatSurface':[s['x'],-.48,s['z']], 'floor':[s['x'],-1,s['z']],
        'forward':[round(math.sin(theta),7),0,round(math.cos(theta),7)],'yawDeg':yaw,
        'gltfRotationQuaternionXYZW':[0,math.sin(theta/2),0,math.cos(theta/2)]}

# A central carpet medallion and aisle strips frame the camera without blocking actors.
for x,material in [(-3.4,cyan),(3.4,red)]:
    cube('Aisle line',(x,-3.6,-1.167),(.018,4.4,.008),material,.002)
text('Approach wordmark','D E B A T E',(0,-3.7,-1.165),.29,gold,rotation=(0,0,0))
# Overhead lighting structure remains open above the primary shot.
for y in [-.5,3.4]:
    cube('Ceiling lighting raft',(0,y,4.05),(10.8,.50,.10),navy,.03)
    cube('Ceiling diffuser',(0,y,3.986),(10.3,.20,.025),glow,.009)
for x in [-5.4,5.4]:
    cube('Ceiling side rail',(x,1.5,4.05),(.10,7.8,.12),gold,.02)

def area(name,loc,target,power,color,size):
    d=bpy.data.lights.new(name,'AREA'); d.energy=power; d.color=color; d.shape='DISK'; d.size=size
    o=bpy.data.objects.new(name,d); scene.collection.objects.link(o); o.location=loc
    o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler()
area('Large warm front key',(0,-4,5.5),(0,2,0),2200,(1,.83,.63),8)
area('Cool ceiling fill',(0,2,4),(0,2,-1),1500,(.64,.8,1),7)
area('Left softbox',(-5,-.5,2.8),(0,2,0),650,(.4,.8,1),4)
area('Right softbox',(5,.5,2.8),(0,2,0),650,(1,.62,.4),4)
world=bpy.data.worlds.new('Hall ambient'); world.use_nodes=True
next(n for n in world.node_tree.nodes if n.type=='BACKGROUND').inputs[0].default_value=(.16,.20,.28,1)
next(n for n in world.node_tree.nodes if n.type=='BACKGROUND').inputs[1].default_value=.35
scene.world=world
camera_data=bpy.data.cameras.new('Hall preview camera'); cam=bpy.data.objects.new('Hall preview camera',camera_data)
scene.collection.objects.link(cam); cam.location=(0,-10.5,3.2)
cam.rotation_euler=(Vector((0,2,1.0))-cam.location).to_track_quat('-Z','Y').to_euler()
camera_data.lens=34; scene.camera=cam
try: scene.render.engine='CYCLES'
except TypeError: pass
scene.cycles.samples=32
scene.cycles.use_denoising=True
scene.render.resolution_x=1600; scene.render.resolution_y=1050; scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG'
scene.render.filepath=str(OUT/'debate_hall_preview.png')

# Export only this scene's content, no studio camera/lights. Keep marker custom properties.
bpy.ops.object.select_all(action='DESELECT')
for o in scene.objects:
    if o.type in {'MESH','EMPTY'}:
        if o != root and o.parent is None: o.parent=root
        o.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(ASSET),use_selection=True,export_format='GLB',
    export_extras=True,export_yup=True,export_apply=True,export_cameras=False,export_lights=False)
(OUT/'seat_anchors.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
newlayout=dict(layout); newlayout['layoutId']='debate_hall_v1'; newlayout['scene']={'asset':'scene/debate_hall.glb'}
(ROOT/'app/src/main/assets/debate/layout_debate_hall_v1.json').write_text(json.dumps(newlayout,ensure_ascii=False,indent=2)+'\n')
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'debate_hall.blend'))
print('BUILT',len(scene.objects),'objects',ASSET.stat().st_size,'GLB bytes')
