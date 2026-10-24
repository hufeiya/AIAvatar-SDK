"""Merge decorative meshes within independent semantic/cutaway groups."""
import bpy

def optimize(root):
    groups={'FLOOR','WALL_NORTH','WALL_SOUTH','WALL_EAST','WALL_WEST','CEILING'}
    owners={o for o in [root]+list(root.children_recursive)
            if o.name in groups or o.name.startswith(('FURN_','GLASS_','PROP_','PORTAL_'))}
    grouped={}
    bpy.context.view_layer.update()
    for ob in list(root.children_recursive):
        if ob.type!='MESH':continue
        owner=ob.parent
        while owner not in owners and owner!=root:owner=owner.parent
        world=ob.matrix_world.copy();ob.parent=owner;ob.matrix_world=world
        grouped.setdefault(owner,[]).append(ob)
    for owner,meshes in grouped.items():
        bpy.ops.object.select_all(action='DESELECT')
        for ob in meshes:ob.select_set(True)
        bpy.context.view_layer.objects.active=meshes[0]
        if len(meshes)>1:bpy.ops.object.join()
        bpy.context.object.name=owner.name+'_GEOMETRY'
    for ob in list(root.children_recursive):
        if ob.type=='EMPTY' and ob not in owners and not ob.children and not ob.name.startswith(('ANCHOR_','FACING_','LOOK_','INTERACTION_','ARCHITECTURE')):
            bpy.data.objects.remove(ob,do_unlink=True)
    bpy.ops.object.select_all(action='DESELECT')
    return len(grouped)

if __name__=='__main__':
    import ast,re
    from pathlib import Path
    folder=Path('/home/neethu/projects/AIAvatar-SDK/assets/dream_bedroom_2')
    root=bpy.data.objects['DREAM_BEDROOM_2']
    count=optimize(root)
    root.select_set(True)
    for ob in root.children_recursive:ob.select_set(True)
    bpy.context.view_layer.objects.active=root
    kwargs=dict(filepath=str(folder/'dream_bedroom_2.glb'),use_selection=True,export_extras=True,export_yup=True,export_apply=True,export_cameras=False,export_lights=False)
    kwargs['export_format']=bpy.ops.export_scene.gltf.get_rna_type().properties['export_format'].default
    try:bpy.ops.export_scene.gltf(**kwargs)
    except TypeError as error:
        allowed=ast.literal_eval(re.search(r"not found in (\([^\n]+\))",str(error)).group(1))
        kwargs['export_format']=next(v for v in allowed if v.endswith('LB'))
        bpy.ops.export_scene.gltf(**kwargs)
    bpy.ops.object.select_all(action='DESELECT')
    bpy.ops.wm.save_as_mainfile(filepath=str(folder/'dream_bedroom_2.blend'))
    print('Optimized semantic meshes:',count)
