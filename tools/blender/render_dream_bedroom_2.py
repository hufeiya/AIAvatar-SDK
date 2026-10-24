"""Render two interior cameras and a cutaway; save with all walls restored."""
import bpy,sys
from pathlib import Path
S=bpy.context.scene
OUT=Path('/home/neethu/projects/AIAvatar-SDK/assets/dream_bedroom_2')
root=bpy.data.objects['DREAM_BEDROOM_2']
original_device=S.cycles.device
try:
    prefs=bpy.context.preferences.addons['cycles'].preferences
    choices=[i[0] for i in prefs.get_device_types(bpy.context)]
    backend=next(v for v in choices if 'OPTIX' in v)
    prefs.compute_device_type=backend;prefs.get_devices()
    gpu_devices=[d for d in prefs.devices if d.type==backend]
    if gpu_devices:
        for d in prefs.devices:d.use=d in gpu_devices
        S.cycles.device=next(i.identifier for i in S.cycles.bl_rna.properties['device'].enum_items if i.identifier=='GPU')
except Exception as error:
    print('Using configured render device:',error)
preview=[('D2_CAM_HERO','bedroom_preview.png',[]),('D2_CAM_GLASS_ROOF','glass_roof_preview.png',[]),('D2_CAM_OVERVIEW','cutaway_preview.png',['WALL_SOUTH','WALL_EAST','CEILING'])]
try:
    for cam,filename,groups in preview:
        S.camera=bpy.data.objects[cam]
        for g in groups:
            for ob in bpy.data.objects[g].children_recursive:ob.hide_render=True
        S.render.filepath=str(OUT/filename)
        bpy.ops.render.render(write_still=True)
        for g in groups:
            for ob in bpy.data.objects[g].children_recursive:ob.hide_render=False
finally:
    for ob in root.children_recursive:ob.hide_render=False;ob.hide_set(False)
    S.camera=bpy.data.objects['D2_CAM_HERO']
    S.cycles.device=original_device
    S.render.filepath=str(OUT/'bedroom_preview.png')
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'dream_bedroom_2.blend'))
