"""Render the full room and an example cutaway, then restore the full room.

Execute in Blender after build_dream_bedroom.py. This is an asset preview only;
camera-dependent visibility in the SDK must be implemented by the host.
"""
import bpy
from pathlib import Path

OUT = Path('/home/neethu/projects/AIAvatar-SDK/assets/dream_bedroom')
scene = bpy.context.scene
root = bpy.data.objects['DREAM_BEDROOM']
for obj in root.children_recursive:
    obj.hide_set(False)
    obj.hide_render = False
try:
    for camera_name, filename, hidden_groups in [
        ('CAM_HERO', 'bedroom_preview.png', []),
        ('CAM_MEDIA', 'media_preview.png', []),
        ('CAM_OVERVIEW', 'cutaway_preview.png', ['WALL_SOUTH', 'WALL_EAST', 'CEILING']),
    ]:
        scene.camera = bpy.data.objects[camera_name]
        scene.render.filepath = str(OUT / filename)
        for group in hidden_groups:
            for obj in bpy.data.objects[group].children_recursive:
                obj.hide_render = True
        bpy.ops.render.render(write_still=True)
        for group in hidden_groups:
            for obj in bpy.data.objects[group].children_recursive:
                obj.hide_render = False
finally:
    for obj in root.children_recursive:
        obj.hide_set(False)
        obj.hide_render = False
    scene.camera = bpy.data.objects['CAM_HERO']
    scene.render.filepath = str(OUT / 'bedroom_preview.png')
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'dream_bedroom.blend'))
