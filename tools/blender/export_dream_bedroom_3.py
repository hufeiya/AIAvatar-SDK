"""Sample actual contact surfaces, retain semantic nodes, export embedded GLB."""
from mathutils.bvhtree import BVHTree
sys.path.insert(0,str(ROOT/'tools/blender'))
from dream3_metadata import ANCHORS

def top_surface(groups,x,y,contact_only=False):
    bpy.context.view_layer.update();dep=bpy.context.evaluated_depsgraph_get();hits=[]
    for g in groups:
        for ob in [g]+list(g.children_recursive):
            if ob.type!='MESH':continue
            if contact_only and not (ob.get('contactSurface',False) or any(t in ob.name.lower() for t in ['mattress','duvet','pillow','cushion','seat','rug'])):continue
            inv=ob.matrix_world.inverted();tree=BVHTree.FromObject(ob,dep)
            pos,normal,index,distance=tree.ray_cast(inv@Vector((x,y,8)),(inv.to_3x3()@Vector((0,0,-1))).normalized(),100)
            if pos is not None:hits.append((ob.matrix_world@pos).z)
    if not hits:raise RuntimeError('No actual contact surface at '+str((x,y)))
    return max(hits)+.002

anchors_root=empty('INTERACTION_ANCHORS',parent=root,schemaVersion=1)
for a in ANCHORS:
    x,z,neg_y=a['position'];y=-neg_y
    if a['kind'] in ['sit','lie','lie_target']:
        z=top_surface([furnitures[a['relatedNode']]],x,y,True)
    elif a['navigable']:
        z=top_surface([floor_group,rug],x,y)
    yaw=a['yaw'];t=math.radians(yaw)
    props={k:a[k] for k in ['label','kind','contact','navigable']}
    props.update(anchorId=a['id'],yawDeg=yaw)
    if a.get('relatedNode'):props['relatedNode']=a['relatedNode']
    o=empty('ANCHOR_'+a['id'],(x,y,z),anchors_root,**props);o.rotation_euler.z=t
    empty('FACING_'+a['id'],(x+.5*math.sin(t),y-.5*math.cos(t),z),anchors_root,anchorId=a['id'],role='forward_target')
    if 'look' in a:
        p=a['look'];empty('LOOK_'+a['id'],(p[0],-p[2],p[1]),anchors_root,anchorId=a['id'],role='look_target')

# Convert evaluated modifiers and curves once, then join only within semantic ownership.
bpy.ops.object.select_all(action='DESELECT')
for o in root.children_recursive:
    if o.type in {'MESH','CURVE','FONT'}:o.select_set(True)
bpy.context.view_layer.objects.active=bpy.context.selected_objects[0]
bpy.ops.object.convert(target=next(i.identifier for i in bpy.ops.object.convert.get_rna_type().properties['target'].enum_items if i.identifier=='MESH'))
from dream2_optimize import optimize
count=optimize(root)
bpy.ops.object.select_all(action='DESELECT');root.select_set(True)
for o in root.children_recursive:o.select_set(True);o.hide_render=False;o.hide_set(False)
bpy.context.view_layer.objects.active=root
kwargs=dict(filepath=str(OUT/'dream_bedroom_3.glb'),use_selection=True,export_extras=True,export_yup=True,export_apply=True,export_cameras=False,export_lights=False)
kwargs['export_format']=bpy.ops.export_scene.gltf.get_rna_type().properties['export_format'].default
try:bpy.ops.export_scene.gltf(**kwargs)
except TypeError as error:
    allowed=ast.literal_eval(re.search(r"not found in (\([^\n]+\))",str(error)).group(1))
    kwargs['export_format']=next(v for v in allowed if v.endswith('LB'));bpy.ops.export_scene.gltf(**kwargs)
bpy.ops.object.select_all(action='DESELECT');S.camera=hero
for screen in bpy.data.screens:
    for a in screen.areas:
        if a.type=='VIEW_3D':
            a.spaces.active.region_3d.view_perspective=enum(a.spaces.active.region_3d,'view_perspective','CAMERA')
            a.spaces.active.shading.type=enum(a.spaces.active.shading,'type','MATERIAL')
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'dream_bedroom_3.blend'))
print('PEACHVEIL_EXPORTED',count,'semantic meshes',len(S.objects),'objects',(OUT/'dream_bedroom_3.glb').stat().st_size,'bytes')
