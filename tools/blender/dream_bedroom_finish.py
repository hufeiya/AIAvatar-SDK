# Executed by build_dream_bedroom.py in its construction namespace.
def plant(name,loc,height=.9,parent=root):
    g=empty(name,loc,parent)
    pr=height*.17
    cylinder('Ceramic planter',(0,0,height*.15),pr*.78,height*.3,terra,g,40,radius2=pr)
    cylinder('Planter lip',(0,0,height*.295),pr*1.03,.035,terra,g,40)
    cylinder('Dark soil',(0,0,height*.306),pr*.92,.01,walnut,g,32)
    for i in range(11):
        a=i*2.399;z0=height*.30;reach=height*random.uniform(.23,.46)
        tip=Vector((math.cos(a)*reach,math.sin(a)*reach,height*random.uniform(.65,1.0)))
        base=Vector((0,0,z0));mid=base.lerp(tip,.58)
        curve('Plant petiole',[base,mid,tip],height*.007,leaves[1],g)
        start=base.lerp(tip,.43);side=Vector((-math.sin(a),math.cos(a),.1))*height*.11
        v=[tuple(start),tuple(start.lerp(tip,.48)+side),tuple(tip),tuple(start.lerp(tip,.48)-side),tuple(start.lerp(tip,.5)+Vector((0,0,.04*height)))]
        leaf=mesh('Sculpted leaf',v,[(0,1,4),(1,2,4),(2,3,4),(3,0,4)],leaves[i%len(leaves)],g)
    return g
plant('PLANT_WINDOW',(3.37,2.62,0),1.35)
plant('PLANT_BEDSIDE',(-3.58,2.75,.72),.59)
plant('PLANT_BOOKCASE',(-3.58,-1.9,1.09),.58)
plant('PLANT_MEDIA',(-.73,-2.97,.59),.48)

# Flowers on the desk: fine stems, ivory blossoms and lavender buds.
cylinder('Desk flower vase',(-.97,.13,.96),.10,.28,glass,desk,40,radius2=.065)
for i in range(17):
    a=i*2.399;xx=-.97+math.cos(a)*random.uniform(.04,.18);yy=.13+math.sin(a)*random.uniform(.04,.18);zz=random.uniform(1.23,1.48)
    rod('Flower stem',(-.97,.13,.85),(xx,yy,zz),.003,leaves[0],desk,8)
    if i%3:
        sphere('Flower centre',(xx,yy,zz),(.02,.02,.021),gold,desk,12,8)
        for j in range(5):
            a=j*math.tau/5;sphere('Ivory petal',(xx+math.cos(a)*.032,yy+math.sin(a)*.032,zz),(.032,.024,.013),cream,desk,12,6)
    else:
        for j in range(5):sphere('Lavender blossom',(xx,yy,zz+j*.018),(.022,.022,.028),flower,desk,12,8)

# Original framed botanical illustrations on the sage wall.
for y,w,h in [(.55,.65,.88),(-.28,.54,.69),(-1.01,.54,.69)]:
    cube('Botanical oak frame',(-3.92,y,2.13),(.07,w,h),oak,.02,wall_west)
    cube('Botanical ivory mat',(-3.876,y,2.13),(.009,w-.07,h-.07),cream,.004,wall_west)
    for j in range(4):
        zz=1.90+j*.105
        curve('Pressed botanical stem',[(-3.867,y-.04,1.84),(-3.867,y+.02,2.15),(-3.867,y-.025,2.40)],.006,leaves[0],wall_west)
        for side in [-1,1]:
            sphere('Pressed botanical leaf',(-3.862,y+side*.075,zz),(.007,.072,.027),leaves[(j+1)%4],wall_west,16,8)

# Warm fairy lights run across the panorama, with a small hanging lunar mobile.
pts=[(-3.8+i/80*7.6,3.12,3.02-.15*math.sin(i/80*math.pi*3)**2) for i in range(81)]
curve('Fairy light cable',pts,.006,walnut,wall_north)
for i in range(35):
    x=-3.75+i/34*7.5;z=3.02-.15*math.sin((x+3.8)/7.6*math.pi*3)**2
    sphere('Fairy amber bulb',(x,3.12,z-.025),(.022,.022,.031),glow,wall_north,12,8)
for x,z in [(-.48,2.56),(.05,2.77),(.63,2.60)]:
    rod('Mobile thread',(x,3.03,3.12),(x,3.03,z),.002,gold,wall_north,8)
    points=[]
    for j in range(10):
        a=j*math.pi/5+math.pi/2;r=.085 if j%2==0 else .038
        points.append((x+math.cos(a)*r,3.03,z+math.sin(a)*r))
    mesh('Hanging brass star',points,[tuple(range(10))],gold,wall_north)
moonpts=[(-.04+.18*math.cos(a),3.02,2.54+.18*math.sin(a)) for a in np.linspace(.6,math.tau-.6,50)]
curve('Lunar crescent',moonpts,.018,glow,wall_north)

# Pendant lamps. All luminous meshes survive GLB export; preview lights are separate.
for loc,r in [((-.12,-.15,2.84),.32),((2.82,-.10,2.47),.20)]:
    x,y,z=loc;rod('Pendant cable',(x,y,3.38),(x,y,z+.15),.009,black,ceiling)
    sphere('Opal pendant globe',loc,(r,r,r*.66),cream,ceiling,32,16)
    cylinder('Pendant brass cap',(x,y,z+.19),r*.23,.08,gold,ceiling,32)
    sphere('Pendant glow',(x,y,z-.13),(r*.62,r*.62,.04),glow,ceiling,24,12)

# Forest outside both window walls: optional, original geometry with no image dependency.
ground=material('Forest ground',(.075,.14,.065),1)
cube('Forest ground',(0,9,-.34),(33,28,.35),ground,.04,forest)
tree_specs=[(-7,7,6),(-4.9,6.7,5.1),(-2.7,7.7,6.2),(-.7,6.7,5.3),(1.9,7.4,6.6),(4.2,6.5,5.7),(6.7,5.6,6),(7.7,2.2,5.5),(8.0,-1,6.1),(-8,11,7),(-4,12,7),(1,13,7.5),(5,12,7),(10,8,7)]
for ti,(tx,ty,h) in enumerate(tree_specs):
    rod('Forest trunk',(tx,ty,-.25),(tx+.15,ty,h*.85),.13,walnut,forest,10)
    for j in range(6):
        a=j*2.4+ti;z=h*(.45+j*.073);r=random.uniform(.65,1.3)
        ex=tx+math.cos(a)*r;ey=ty+math.sin(a)*r
        rod('Tree branch',(tx,ty,z-.45),(ex,ey,z+.15),.045,walnut,forest,8)
        sphere('Forest canopy',(ex,ey,z+.3),(r*.9,r*.85,.75),leaves[(ti+j)%5],forest,16,10)
    sphere('Tree crown',(tx,ty,h),(1.05,1.0,.9),leaves[ti%5],forest,16,10)
for i in range(24):
    x=random.uniform(-8,9);y=random.uniform(5,11)
    sphere('Fern understory',(x,y,.3),(random.uniform(.6,1.3),.85,.65),leaves[i%5],forest,12,8)
for o in S.objects:
    if o.name.startswith(('Forest canopy','Tree crown')):
        for v in o.data.vertices:
            p=v.co;p*=1+.10*math.sin(p.x*12+3)*math.sin(p.y*14+1)*math.sin(p.z*11+2)

# Real entrance remains open so avatars can enter; the portal has no collision mesh.
entry=group('PORTAL_ENTRY','卧室入口',(-2.75,-3.5,0),180)
entry.parent=wall_south
for x in [-.65,.65]:cube('Door oak jamb',(x,0,1.2),(.075,.24,2.4),oak,.012,entry)
cube('Door oak lintel',(0,0,2.4),(1.38,.24,.075),oak,.012,entry)
cube('Entry threshold',(0,0,.016),(1.25,.28,.032),oak,.006,entry)

def light(name,loc,color,power,size,target=None):
    data=bpy.data.lights.new(name,next(i.identifier for i in bpy.types.Light.bl_rna.properties['type'].enum_items if i.identifier=='AREA'))
    data.energy=power;data.color=color;data.shape=next(i.identifier for i in bpy.types.AreaLight.bl_rna.properties['shape'].enum_items if i.identifier=='DISK');data.size=size
    o=bpy.data.objects.new(name,data);S.collection.objects.link(o);o.location=loc
    if target:o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler()
    return o
lights=empty('PREVIEW_LIGHTS')
for o in [light('North soft daylight',(0,3.1,2.8),(.76,.88,1),500,5,(0,-1,0)),
          light('East soft daylight',(3.8,1.3,2.6),(.86,1,.85),450,3,(-2,0,.6)),
          light('Large ceiling bounce',(-.4,-.2,3.22),(1,.83,.59),400,5,(0,0,0)),
          light('Front fill',(0,-3,2.7),(1,.86,.69),250,4,(0,1,1)),
          light('Bedside warm pool',(-.8,2.05,1.02),(1,.58,.25),25,.35,(-2,1,.3)),
          light('Reading warm pool',(2.82,-.1,2.28),(1,.64,.32),40,.45,(2.8,-.1,0))]:o.parent=lights
light('Forest sky softbox',(1,7,10),(.9,1,.85),2400,9,(0,7,1)).parent=lights

def camera(name,loc,target,lens=22):
    c=bpy.data.cameras.new(name);c.lens=lens;c.clip_start=.06;c.clip_end=150
    o=bpy.data.objects.new(name,c);S.collection.objects.link(o);o.location=loc
    o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler();return o
hero=camera('CAM_HERO',(2.4,-3.18,2.30),(-.65,1.30,1.20),19)
reverse=camera('CAM_MEDIA',(-2.0,2.6,2.05),(.8,-1.8,1.15),20)
overview=camera('CAM_OVERVIEW',(10,-13,11),(0,0,0),40)
S.camera=hero
S.render.resolution_x=1440;S.render.resolution_y=1080;S.render.resolution_percentage=100
S.render.image_settings.file_format=next(i.identifier for i in bpy.types.ImageFormatSettings.bl_rna.properties['file_format'].enum_items if i.identifier=='PNG')
S.render.filepath=str(OUT/'bedroom_preview.png')
try:S.render.engine='CYCLES'
except TypeError as e:print('Render engine options:',e)
if hasattr(S,'cycles'):
    S.cycles.samples=32;S.cycles.use_denoising=True;S.cycles.max_bounces=6;S.cycles.transparent_max_bounces=8
try:S.view_settings.view_transform=next(i.identifier for i in S.view_settings.bl_rna.properties['view_transform'].enum_items if 'AgX' in i.identifier)
except StopIteration:pass
S.view_settings.exposure=.25
for ob in ceiling.children_recursive:ob.hide_render=False;ob.hide_set(False)
# Camera-based preview of the enclosed room; front wall remains present for media shot.
for screen_area in bpy.context.screen.areas:
    if screen_area.type=='VIEW_3D':
        screen_area.spaces.active.region_3d.view_perspective='CAMERA'
        screen_area.spaces.active.overlay.show_overlays=False

bpy.context.view_layer.update()
print('STAGE 3 • decor, forest, cameras:',len(S.objects),'objects')
