"""Run after build_dream_bedroom.py, using the same namespace or driver_namespace."""
import bpy, math, json
from mathutils import Vector
from mathutils.bvhtree import BVHTree

def to_gltf(v):return [round(float(v[0]),6),round(float(v[2]),6),round(float(-v[1]),6)]
def sdk_pos(g):return [g[0],round(g[1]-1,6),round(g[2]-4,6)]
def forward(yaw):return [round(math.sin(math.radians(yaw)),6),0,round(math.cos(math.radians(yaw)),6)]
anchors_root=empty('INTERACTION_ANCHORS',parent=root,schemaVersion=1)
anchors=[]

def surface_z(group,x,y):
    bpy.context.view_layer.update();dep=bpy.context.evaluated_depsgraph_get();heights=[]
    for ob in group.children_recursive:
        if ob.type!='MESH':continue
        inv=ob.matrix_world.inverted()
        tree=BVHTree.FromObject(ob,dep)
        start=inv@Vector((x,y,5));direction=(inv.to_3x3()@Vector((0,0,-1))).normalized()
        pos,normal,index,distance=tree.ray_cast(start,direction,100)
        if pos is not None:heights.append((ob.matrix_world@pos).z)
    if not heights:raise RuntimeError('No contact surface found: '+group.name)
    return max(heights)+.002

def anchor(ident,label,loc,yaw,kind,contact,related=None,navigable=False,notes='',target=None):
    if kind in ['sit','lie','lie_target'] and related:
        loc=(loc[0],loc[1],surface_z(furnitures[related],loc[0],loc[1]))
    o=empty('ANCHOR_'+ident,loc,anchors_root,anchorId=ident,label=label,kind=kind,contact=contact,yawDeg=yaw,navigable=navigable)
    o.rotation_euler.z=math.radians(yaw)
    p=to_gltf(loc)
    record={'id':ident,'node':o.name,'label':label,'kind':kind,'contact':contact,'gltfPosition':p,'sdkWorldPosition':sdk_pos(p),'yawDeg':yaw,'forward':forward(yaw),'navigable':navigable}
    if related:record['relatedNode']=related;o['relatedNode']=related
    if notes:record['notes']=notes;o['notes']=notes
    # A second point makes the facing direction unambiguous even if Euler conventions differ.
    f=forward(yaw);dst=(loc[0]+f[0]*.5,loc[1]-f[2]*.5,loc[2])
    face=empty('FACING_'+ident,dst,anchors_root,anchorId=ident,role='forward_target')
    record['facingNode']=face.name
    if target:
        look=empty('LOOK_'+ident,target,anchors_root,anchorId=ident,role='look_target')
        record['lookTargetNode']=look.name;record['gltfLookTarget']=to_gltf(target)
    anchors.append(record);return record

anchor('SPAWN','中央出生/站立点',(-.1,-.65,.03),180,'stand','feet',navigable=True,related='FURN_RUG')
anchor('RUG_WATCH_TV','地毯观看电视',(0,-1.1,.03),0,'stand','feet',related='FURN_RUG',navigable=True,target=(.3,-3.30,1.55))
anchor('BED_APPROACH','床边站立点',(-.65,.65,.03),-90,'approach','feet',related='FURN_BED',navigable=True,target=(-2.35,1.1,.75))
anchor('BED_SIT','床沿坐姿接触点',(-1.40,.80,.73),90,'sit','buttock_surface',related='FURN_BED',notes='Seat contact, not avatar root. Sample sitting pose before applying hip contact offset.')
anchor('BED_LIE','床上躺卧髋部接触点',(-2.86,1.08,.74),180,'lie','back_hip_surface',related='FURN_BED',notes='Yaw describes feet-to-head longitudinal axis. Use supine animation; do not treat yaw alone as a lying pose.')
anchor('BED_HEAD','枕头头部接触点',(-2.86,1.98,.91),180,'lie_target','head_surface',related='FURN_BED')
anchor('DESK_APPROACH','书桌椅前站立点',(1,.73,0),180,'approach','feet',related='FURN_CHAIR_DESK',navigable=True,target=(1,2.80,1.25))
anchor('DESK_SEAT','书桌椅座面中心',(1,1.565,.5495),180,'sit','buttock_surface',related='FURN_CHAIR_DESK',target=(1.13,2.78,1.25))
anchor('READING_APPROACH','阅读椅前站立点',(1.65,-.65,0),115,'approach','feet',related='FURN_CHAIR_READING',navigable=True)
seat_world=reading.matrix_world @ Vector((0,-.03,.56))
anchor('READING_SEAT','阅读椅座面中心',tuple(seat_world),-65,'sit','buttock_surface',related='FURN_CHAIR_READING')
anchor('WINDOW_VIEW','窗前看森林',(3.35,1.25,0),90,'stand','feet',related='GLASS_PANORAMA_EAST',navigable=True,target=(5.5,1.25,2.2))
anchor('TV_APPROACH','电视柜前站立点',(.25,-2.28,0),0,'approach','feet',related='FURN_TV',navigable=True,target=(.3,-3.30,1.55))
anchor('GAME_APPROACH','游戏机前站立点',(1.12,-2.28,0),0,'approach','feet',related='FURN_GAME_CONSOLE',navigable=True,target=(1.10,-2.82,.80))
anchor('GAME_PICKUP','游戏手柄抓取参考点',(.55,-2.94,.635),0,'grasp','hand_grip',related='PROP_CONTROLLER',notes='Hand IK / grip reference; not a place for the avatar feet.')
anchor('ENTRY','门内入口点',(-2.75,-2.85,0),180,'stand','feet',related='PORTAL_ENTRY',navigable=True)
anchor('AISLE_SOUTH','床尾通道',(-1.8,-1.65,0),90,'waypoint','feet',navigable=True)
anchor('AISLE_EAST_LOWER','东侧通道转折',(1.8,.70,0),180,'waypoint','feet',navigable=True)
anchor('AISLE_EAST_UPPER','东侧窗边通道',(1.8,1.20,0),90,'waypoint','feet',navigable=True)

bpy.context.view_layer.update()
deps=bpy.context.evaluated_depsgraph_get()
def bbox(obj):
    pts=[]
    for child in [obj]+list(obj.children_recursive):
        if child.type not in {'MESH','CURVE','FONT'}:continue
        ev=child.evaluated_get(deps)
        if ev.type=='MESH':
            pts.extend([ev.matrix_world@v.co for v in ev.data.vertices])
        else:
            pts.extend([ev.matrix_world@Vector(c) for c in ev.bound_box])
    if not pts:return None
    xyz=[to_gltf(p) for p in pts]
    return {'min':[round(min(p[i] for p in xyz),6) for i in range(3)],'max':[round(max(p[i] for p in xyz),6) for i in range(3)]}

objects=[]
for name,o in furnitures.items():
    yaw=float(o.get('yawDeg',0));p=to_gltf(o.matrix_world.translation)
    rec={'node':name,'label':o['label'],'gltfOrigin':p,'sdkWorldOrigin':sdk_pos(p),'yawDeg':yaw,'forward':forward(yaw),'gltfBounds':bbox(o)}
    o['forwardGltf']=forward(yaw)
    objects.append(rec)

edges=[('ENTRY','AISLE_SOUTH'),('AISLE_SOUTH','SPAWN'),('SPAWN','BED_APPROACH'),('SPAWN','DESK_APPROACH'),('SPAWN','READING_APPROACH'),('SPAWN','RUG_WATCH_TV'),('RUG_WATCH_TV','TV_APPROACH'),('RUG_WATCH_TV','GAME_APPROACH'),('DESK_APPROACH','AISLE_EAST_LOWER'),('AISLE_EAST_LOWER','AISLE_EAST_UPPER'),('AISLE_EAST_UPPER','WINDOW_VIEW')]
linkmap={a['id']:[] for a in anchors if a['navigable']}
for a,b in edges:linkmap[a].append(b.lower());linkmap[b].append(a.lower())
walking={'anchors':[{'name':a['id'].lower(),'x':a['sdkWorldPosition'][0],'z':a['sdkWorldPosition'][2],'label':a['label'],'labelEn':a['id'].lower().replace('_',' '),'links':linkmap[a['id']]} for a in anchors if a['navigable']]}

blockers=[]
for obj in objects:
    if obj['node'] in ['FURN_RUG','PORTAL_ENTRY','FURN_TV','FURN_GAME_CONSOLE','PROP_CONTROLLER','GLASS_PANORAMA_NORTH','GLASS_PANORAMA_EAST']:continue
    blockers.append({'node':obj['node'],'gltfBounds':obj['gltfBounds'],'shape':'world_aabb','note':'Conservative navigation obstacle; not a runtime physics collider.'})

manifest={
 'schemaVersion':1,'title':'Forest Reverie / 森林梦境卧室','units':'meters',
 'coordinateSystem':{'handedness':'right','up':'+Y','defaultAvatarForward':'+Z','yaw':'degrees about +Y, positive turns +Z toward +X','blenderToGltf':'[x,y,z] -> [x,z,-y]','floorGltfY':0},
 'room':{'width':8,'depth':7,'height':3.4,'gltfInteriorBounds':{'min':[-4,0,-3.5],'max':[4,3.4,3.5]}},
 'sdkPlacement':{'offsetX':0,'offsetY':-1,'offsetZ':-4,'yawDeg':0,'formula':'sdkWorld = gltfPosition + [0,-1,-4]'},
 'poseContract':{'feet':'Foot sole contact with finished floor or rug. Not avatar root.','buttock_surface':'Seat surface target. Apply hips-to-contact offset after evaluating sitting animation.','back_hip_surface':'Supine back/pelvis surface target. Needs lying animation plus bone contact alignment.','hand_grip':'Hand IK reference.','rootPlacement':'Do not set avatar root equal to these contact points. Root translation += targetContactWorld - measuredContactWorld.'},
 'objects':objects,'anchors':anchors,
 'navigation':{'agentRadius':.22,'validatedMode':'Straight graph edges tested against expanded conservative furniture AABBs; rug treated as traversable.','edges':edges,'obstacles':blockers,'limitations':'Bounds are a clamp, not collision detection. Vertical steps, animation, IK and dynamic avoidance are not implemented by this asset.'},
 'rendering':{'materials':'Portable glTF metallic-roughness PBR, packed original textures; alpha-blended glass; emissive light meshes.','previewOnly':'Blender area lights and cameras are not exported. Runtime lighting must be configured by the host.','optionalNodes':['FOREST_BACKDROP','CEILING_OPTIONAL','CUTAWAY_FRONT_WALL']}
}
(OUT/'interactions.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
(OUT/'dream_bedroom.anchors.json').write_text(json.dumps(walking,ensure_ascii=False,indent=2)+'\n')
meta={'offsetX':0,'offsetY':-1,'offsetZ':-4,'yawDeg':0,'bounds':{'minX':-3.65,'maxX':3.65,'minZ':-7.15,'maxZ':-.85}}
(OUT/'dream_bedroom.meta.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')

# Apply modifiers, convert curves, merge geometry by material within semantic groups.
# This preserves all furniture roots / contact nodes while reducing draw calls.
bpy.ops.object.select_all(action='DESELECT')
for o in list(root.children_recursive):
    if o.type in {'MESH','CURVE','FONT'}:
        o.hide_set(False);o.select_set(True)
if bpy.context.selected_objects:
    bpy.context.view_layer.objects.active=bpy.context.selected_objects[0]
    bpy.ops.object.convert(target=next(i.identifier for i in bpy.ops.object.convert.get_rna_type().properties['target'].enum_items if i.identifier=='MESH'))
bpy.ops.object.select_all(action='DESELECT')
groups={}
for o in list(root.children_recursive):
    if o.type=='MESH':groups.setdefault(o.parent,[]).append(o)
for parent,objs in groups.items():
    if len(objs)==1:
        objs[0].name=(parent.name if parent else 'ROOM')+'_GEOMETRY'
        continue
    bpy.ops.object.select_all(action='DESELECT')
    for o in objs:o.select_set(True)
    bpy.context.view_layer.objects.active=objs[0];bpy.ops.object.join()
    bpy.context.object.name=(parent.name if parent else 'ROOM')+'_GEOMETRY'

# Curves expose unreliable pre-conversion bound_box values in some Blender builds.
# Recalculate after conversion so the manifest describes the actual exported meshes.
bpy.context.view_layer.update()
deps=bpy.context.evaluated_depsgraph_get()
for rec in objects:rec['gltfBounds']=bbox(furnitures[rec['node']])
by_name={rec['node']:rec for rec in objects}
for rec in blockers:rec['gltfBounds']=by_name[rec['node']]['gltfBounds']
cutaway_records=[]
labels={'WALL_NORTH':'北墙/全景窗','WALL_SOUTH':'南墙/入口/壁挂电视',
        'WALL_EAST':'东墙/侧窗','WALL_WEST':'西墙/挂画','CEILING':'天花板/吊灯'}
for group_obj in cutaway_groups:
    cutaway_records.append({
        'node':group_obj.name,'label':labels[group_obj.name],
        'role':group_obj['role'],
        'renderableNodes':sorted(o.name for o in group_obj.children_recursive if o.type=='MESH'),
        'planePointGltf':list(group_obj['planePointGltf']),
        'outwardNormalGltf':list(group_obj['outwardNormalGltf']),
        'gltfBounds':bbox(group_obj),
    })
manifest['rendering'].update({
    'cutawayGroups':cutaway_records,
    'alwaysVisibleNodes':['FLOOR'],
    'optionalNodes':['FOREST_BACKDROP']+[o.name for o in cutaway_groups],
    'visibilityContract':'Group nodes are empty transforms. Hide/show every descendant renderable; GLB does not run camera-occlusion logic. All groups export visible.',
    'legacyAliases':{'CUTAWAY_FRONT_WALL':'WALL_SOUTH','CEILING_OPTIONAL':'CEILING'},
})
cutaway_manifest={
    'schemaVersion':1,'units':'meters','coordinateSystem':manifest['coordinateSystem'],
    'sdkPlacement':manifest['sdkPlacement'],'groups':cutaway_records,
    'alwaysVisibleNodes':['FLOOR'],
    'defaultVisibility':'all_visible',
    'visibilityContract':manifest['rendering']['visibilityContract'],
    'planeMeaning':'Interior boundary planes, normal points outside. Apply scene world transform before testing camera and target; geometry bounds include trims/decorations.',
}
(OUT/'dream_bedroom.cutaway.json').write_text(json.dumps(cutaway_manifest,ensure_ascii=False,indent=2)+'\n')
(OUT/'interactions.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')

bpy.ops.object.select_all(action='DESELECT')
root.select_set(True)
for o in root.children_recursive:o.hide_set(False);o.select_set(True)
bpy.context.view_layer.objects.active=root
kwargs=dict(filepath=str(OUT/'dream_bedroom.glb'),use_selection=True,export_extras=True,export_yup=True,export_apply=True,export_cameras=False,export_lights=False)
# GLB is the export operator's default format; query a dynamic enum via operator default.
kwargs['export_format']=bpy.ops.export_scene.gltf.get_rna_type().properties['export_format'].default
try:bpy.ops.export_scene.gltf(**kwargs)
except TypeError as error:
    # Dynamic enums in Blender 5 expose no items/default through RNA. Read the
    # operator's actual allowed values from its diagnostic rather than assume.
    import ast, re
    allowed=ast.literal_eval(re.search(r"not found in (\([^\n]+\))",str(error)).group(1))
    kwargs['export_format']=next(v for v in allowed if v.endswith('LB'))
    bpy.ops.export_scene.gltf(**kwargs)
bpy.ops.object.select_all(action='DESELECT')
S.camera=hero
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'dream_bedroom.blend'))
print('EXPORTED',str(OUT/'dream_bedroom.glb'),'bytes',(OUT/'dream_bedroom.glb').stat().st_size)
print('ANCHORS',len(anchors),'OBJECTS',len(objects))
