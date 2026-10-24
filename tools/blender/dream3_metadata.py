#!/usr/bin/env python3
"""Metadata, diagrams and documentation for the original Peachveil Sanctuary asset.

The Blender builder imports ANCHORS/EDGES as its shared layout contract. After
export this script reads actual GLB node transforms and geometry to produce the
sidecars. It never edits the original dream_bedroom asset or app integration.
"""
from __future__ import annotations

import argparse
from html import escape
import json
import math
from pathlib import Path

from validate_dream_bedroom import Glb

ROOT = Path(__file__).resolve().parents[2]
BASENAME = 'dream_bedroom_3'
TITLE = '桃隐仙居 · Peachveil Sanctuary'
ROOM = {'width': 8, 'depth': 7, 'eaveHeight': 3.25, 'ridgeHeight': 4.65,
        'height': 4.82, 'ridgeCapMaxHeight': 4.82,
        'roofProfile': '3.25 + 1.40*(1-abs(X)/4.20)**1.30, 18 planar facets per side',
        'gltfInteriorBounds': {'min': [-4, 0, -3.5], 'max': [4, 4.82, 3.5]}}
SDK_PLACEMENT = {'offsetX': 0, 'offsetY': -1, 'offsetZ': -4, 'yawDeg': 0,
                 'formula': 'sdkWorld = gltfPosition + [0,-1,-4]'}
COORDINATES = {'handedness': 'right', 'up': '+Y', 'defaultAvatarForward': '+Z',
               'yaw': 'degrees about +Y, positive turns +Z toward +X',
               'blenderToGltf': '[x,y,z] -> [x,z,-y]', 'floorGltfY': 0}
# GLB (X,Y,Z), metres. Builder samples actual support meshes for contact Y.
ANCHORS = [
    dict(id='SPAWN', label='中央出生/站立', position=[0,0,1.1], yaw=180, kind='stand', contact='feet', navigable=True, look=[0,2,-3.5]),
    dict(id='ENTRY', label='门内入口', position=[.15,0,2.95], yaw=180, kind='stand', contact='feet', relatedNode='PORTAL_ENTRY', navigable=True),
    dict(id='AISLE_CENTER', label='中央行走通道', position=[0,0,0], yaw=180, kind='waypoint', contact='feet', navigable=True),
    dict(id='BED_APPROACH', label='纱帐床侧接近', position=[.15,0,-.4], yaw=90, kind='approach', contact='feet', relatedNode='FURN_BED', navigable=True, look=[2.05,.9,-1.05]),
    dict(id='BED_SIT', label='床沿坐姿接触', position=[1.05,.77,-.45], yaw=-90, kind='sit', contact='buttock_surface', relatedNode='FURN_BED', navigable=False),
    dict(id='BED_LIE', label='床上仰卧髋背接触', position=[1.5,.8,-1.05], yaw=180, kind='lie', contact='back_hip_surface', relatedNode='FURN_BED', navigable=False, look=[1.5,3.1,-1.05]),
    dict(id='BED_HEAD', label='枕区头部校准', position=[1.5,.95,-2.1], yaw=180, kind='lie_target', contact='head_surface', relatedNode='FURN_BED', navigable=False),
    dict(id='DESK_APPROACH', label='书案椅前接近', position=[-2.65,0,-.85], yaw=180, kind='approach', contact='feet', relatedNode='FURN_CHAIR_DESK', navigable=True, look=[-2.65,1,-2.75]),
    dict(id='DESK_SEAT', label='书案椅座面接触', position=[-2.65,.5,-1.65], yaw=180, kind='sit', contact='buttock_surface', relatedNode='FURN_CHAIR_DESK', navigable=False, look=[-2.65,.95,-2.75]),
    dict(id='NORTH_AISLE', label='北侧行走通道', position=[-.25,0,-1.3], yaw=180, kind='waypoint', contact='feet', navigable=True),
    dict(id='WINDOW_VIEW', label='北侧栏前观景', position=[-.2,0,-2.85], yaw=180, kind='stand', contact='feet', relatedNode='WALL_NORTH', navigable=True, look=[-.2,1.6,-5]),
    dict(id='TEA_APPROACH', label='茶席接近', position=[-.85,0,1.65], yaw=-90, kind='approach', contact='feet', relatedNode='FURN_TEA_TABLE', navigable=True, look=[-2,.6,.75]),
    dict(id='TEA_SEAT', label='南侧蒲团坐姿接触', position=[-2,.22,1.65], yaw=180, kind='sit', contact='buttock_surface', relatedNode='FURN_TEA_CUSHION_SOUTH', navigable=False, look=[-2,.6,.75]),
    dict(id='MEDITATION_APPROACH', label='修炼台侧接近', position=[1.05,0,2.15], yaw=90, kind='approach', contact='feet', relatedNode='FURN_MEDITATION', navigable=True, look=[2.35,.65,2.15]),
    dict(id='MEDITATION_SEAT', label='莲台打坐接触', position=[2.35,.28,2.15], yaw=180, kind='sit', contact='buttock_surface', relatedNode='FURN_MEDITATION', navigable=False, look=[2.35,1.3,-1]),
    dict(id='ALTAR_APPROACH', label='香案侧接近', position=[-1.15,0,2.4], yaw=-90, kind='approach', contact='feet', relatedNode='FURN_ALTAR', navigable=True, look=[-2.65,1.2,2.9]),
    dict(id='EAST_CROSSING', label='床尾修炼台间通道', position=[1.05,0,.98], yaw=90, kind='waypoint', contact='feet', navigable=True),
    dict(id='SWORD_APPROACH', label='剑架前赏剑', position=[2.6,0,.98], yaw=90, kind='approach', contact='feet', relatedNode='FURN_SWORD_STAND', navigable=True, look=[3.45,1,.95]),
]
EDGES = [
    ['ENTRY','SPAWN'], ['SPAWN','AISLE_CENTER'],
    ['AISLE_CENTER','BED_APPROACH'], ['AISLE_CENTER','NORTH_AISLE'],
    ['NORTH_AISLE','DESK_APPROACH'], ['NORTH_AISLE','WINDOW_VIEW'],
    ['SPAWN','TEA_APPROACH'], ['ENTRY','ALTAR_APPROACH'],
    ['SPAWN','MEDITATION_APPROACH'],
    ['MEDITATION_APPROACH','EAST_CROSSING'], ['EAST_CROSSING','SWORD_APPROACH'],
]
OBSTACLES = {'FURN_BED','FURN_NIGHTSTAND','FURN_DESK','FURN_CHAIR_DESK',
             'FURN_TEA_TABLE','FURN_TEA_CUSHION_NORTH','FURN_TEA_CUSHION_SOUTH',
             'FURN_MEDITATION','FURN_BOOKCASE','FURN_ALTAR','FURN_SWORD_STAND','FURN_PEACH_VASE'}
OBJECTS = [
    'FURN_BED','FURN_NIGHTSTAND','FURN_DESK','FURN_CHAIR_DESK',
    'FURN_TEA_TABLE','FURN_TEA_CUSHION_NORTH','FURN_TEA_CUSHION_SOUTH',
    'FURN_RUG','FURN_MEDITATION','FURN_BOOKCASE','FURN_ALTAR','FURN_SWORD_STAND',
    'PORTAL_ENTRY','FURN_PEACH_VASE',
]
CUTAWAY = {
    'WALL_NORTH': ('北侧木柱/栏杆/花格', 'wall', [0,0,-3.5], [0,0,-1]),
    'WALL_SOUTH': ('南侧灰泥隔墙/木框/入口', 'wall', [0,0,3.5], [0,0,1]),
    'WALL_EAST': ('东侧木柱/栏杆/花格', 'wall', [4,0,0], [1,0,0]),
    'WALL_WEST': ('西侧灰泥墙/护墙板/月纹装饰', 'wall', [-4,0,0], [-1,0,0]),
    'CEILING': ('中式曲面木屋顶/梁架/悬灯', 'ceiling', [0,4.65,0], [0,1,0]),
}


def rounded(values):
    return [round(float(v), 6) for v in values]


def forward(yaw):
    t = math.radians(yaw)
    return rounded([math.sin(t), 0, math.cos(t)])


def sdk(p):
    return rounded([p[0],p[1]-1,p[2]-4])


def bounds(glb, node):
    meshes = [i for i in glb.descendants(glb.names[node]) if 'mesh' in glb.nodes[i]]
    if not meshes and node == 'PORTAL_ENTRY':
        p = rounded(glb.position(node))
        return {'min':p, 'max':p}
    return {k: rounded(v) for k,v in glb.bounds(node).items()}


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')


def build_manifest(folder):
    glb = Glb(folder/(BASENAME+'.glb'))
    objects, anchors, groups = [], [], []
    for name in OBJECTS:
        if name not in glb.names:
            raise ValueError('Missing semantic object: '+name)
        extras = glb.nodes[glb.names[name]].get('extras', {})
        p, yaw = rounded(glb.position(name)), float(extras.get('yawDeg', 0))
        objects.append({'node': name, 'label': extras.get('label', name),
                        'gltfOrigin': p, 'sdkWorldOrigin': sdk(p),
                        'yawDeg': yaw, 'forward': forward(yaw),
                        'gltfBounds': bounds(glb,name)})
        if not any('mesh' in glb.nodes[i] for i in glb.descendants(glb.names[name])):
            objects[-1]['boundsKind'] = 'semantic_point'
    for definition in ANCHORS:
        aid = definition['id']
        name = 'ANCHOR_'+aid
        if name not in glb.names:
            raise ValueError('Missing interaction node: '+name)
        extras = glb.nodes[glb.names[name]].get('extras', {})
        p, yaw = rounded(glb.position(name)), float(extras.get('yawDeg', definition['yaw']))
        rec = {'id': aid, 'node': name, 'label': extras.get('label', definition['label']),
               'kind': extras.get('kind', definition['kind']),
               'contact': extras.get('contact', definition['contact']),
               'gltfPosition': p, 'sdkWorldPosition': sdk(p), 'yawDeg': yaw,
               'forward': forward(yaw), 'navigable': bool(extras.get('navigable', definition['navigable'])),
               'facingNode': 'FACING_'+aid}
        related = extras.get('relatedNode', definition.get('relatedNode'))
        if related:
            rec['relatedNode'] = related
        look = 'LOOK_'+aid
        if look in glb.names:
            rec.update(lookTargetNode=look, gltfLookTarget=rounded(glb.position(look)))
        if rec['kind'] in ('sit','lie','lie_target'):
            rec['notes'] = 'Actual support mesh surface plus 0.002m; contact reference, not avatar root. Apply pose-specific bone-to-body offset.'
        if rec['kind'] == 'lie':
            rec['notes'] += ' Yaw is the feet-to-head body axis; a supine animation and contact alignment are required.'
        anchors.append(rec)
    for name, (label, role, point, normal) in CUTAWAY.items():
        idx = glb.names[name]
        meshes = sorted(glb.nodes[i]['name'] for i in glb.descendants(idx) if 'mesh' in glb.nodes[i])
        rec = {'node':name,'label':label,'role':role,'renderableNodes':meshes,
               'planePointGltf':point,'outwardNormalGltf':normal,'gltfBounds':bounds(glb,name)}
        if name == 'CEILING':
            rec['planeMeaning'] = 'Ridge reference only. Boundary planes are the 36 original roof-profile mesh facets, before board thickness, tiles and ridge-cap decoration.'
            rec['boundaryPlanesGltf'] = []
            def roof_y(x):
                return 3.25+1.40*(1-abs(x)/4.20)**1.30
            for side, direction in [('east',1),('west',-1)]:
                for k in range(18):
                    x0,x1=direction*4.2*k/18,direction*4.2*(k+1)/18
                    y0,y1=roof_y(x0),roof_y(x1)
                    slope=(y1-y0)/(x1-x0)
                    norm=math.sqrt(1+slope*slope)
                    rec['boundaryPlanesGltf'].append({
                        'label':f'{side}_facet_{k+1:02d}',
                        'point':rounded([(x0+x1)/2,(y0+y1)/2,0]),
                        'outwardNormal':rounded([-slope/norm,1/norm,0]),
                        'profileSegmentGltf': [rounded([x0,y0,0]),rounded([x1,y1,0])],
                        'longitudinalRangeGltfZ':[-3.7,3.7],
                    })
        groups.append(rec)
    visibility = 'All groups export visible. Recursively hide/show every descendant renderable, or use renderableNodes; empty group visibility alone does not hide Filament mesh entities.'
    obstacles = [{'node':o['node'],'gltfBounds':o['gltfBounds'],'shape':'world_aabb',
                  'note':'Conservative static graph obstacle, not a runtime collider.'}
                 for o in objects if o['node'] in OBSTACLES]
    manifest = {'schemaVersion':1,'title':TITLE,'units':'meters','coordinateSystem':COORDINATES,
                'room':ROOM,'sdkPlacement':SDK_PLACEMENT,
                'poseContract':{'feet':'Sole contact with floor/rug, not avatar root.',
                  'buttock_surface':'Seat surface contact; evaluate sitting pose and apply hips-to-contact offset.',
                  'back_hip_surface':'Supine pelvis/back surface; needs lying animation and bone contact alignment.',
                  'head_surface':'Optional pillow/head surface calibration, not a walking destination.',
                  'hand_grip':'Hand IK/grip reference.',
                  'rootPlacement':'rootTranslation += targetContactWorld - measuredContactWorld'},
                'objects':objects,'anchors':anchors,
                'navigation':{'agentRadius':.22,'validatedMode':'Final GLB furniture world AABBs expanded in XZ by agentRadius; rug is walkable.',
                  'edges':EDGES,'obstacles':obstacles,
                  'limitations':'Static graph edges only. No arbitrary starting-path, runtime collision, dynamic avoidance, animation or IK.'},
                'rendering':{'materials':'Portable glTF metallic-roughness PBR; blush fabric, carved wood, brass details and emissive lanterns.',
                  'previewOnly':'Blender cameras/lights/world are excluded from GLB. Host supplies skybox/IBL and runtime lights.',
                  'exteriorGeometry':False,'openRailings':True,'openRailingSides':['north','east'],'solidWallSides':['south','west'],'glassGeometry':False,
                  'optionalNodes':list(CUTAWAY),'cutawayGroups':groups,
                  'alwaysVisibleNodes':['FLOOR'],'visibilityContract':visibility}}
    write_json(folder/'interactions.json', manifest)
    write_json(folder/(BASENAME+'.cutaway.json'), {'schemaVersion':1,'units':'meters',
        'coordinateSystem':COORDINATES,'sdkPlacement':SDK_PLACEMENT,'groups':groups,
        'alwaysVisibleNodes':['FLOOR'],'defaultVisibility':'all_visible',
        'visibilityContract':visibility,
        'planeMeaning':'Unplaced GLB coordinates; transform planes/normals to the same space as camera and target. CEILING boundaryPlanesGltf contains the 36 curved-profile mesh facets before thickness and roof decoration.'})
    linkmap = {a['id']:[] for a in anchors if a['navigable']}
    for a,b in EDGES:
        linkmap[a].append(b.lower()); linkmap[b].append(a.lower())
    write_json(folder/(BASENAME+'.anchors.json'), {'anchors':[
        {'name':a['id'].lower(),'x':a['sdkWorldPosition'][0],'z':a['sdkWorldPosition'][2],
         'label':a['label'],'labelEn':a['id'].lower().replace('_',' '),'links':linkmap[a['id']]}
        for a in anchors if a['navigable']]})
    write_json(folder/(BASENAME+'.meta.json'), {'offsetX':0,'offsetY':-1,'offsetZ':-4,'yawDeg':0,
        'bounds':{'minX':-3.65,'maxX':3.65,'minZ':-7.15,'maxZ':-.85}})
    return glb, manifest


def create_diagram(folder, manifest, interactions=False):
    """Produce a self-contained, metre-scaled SVG and matching PNG."""
    import cairosvg
    width, height, scale = 1920, 1480, 112.5
    ox, oy = 522, 644
    navy, gold, cream = '#573e48', '#b48b59', '#faf1eb'
    svg = []
    def add(item): svg.append(item)
    def xy(p): return ox+p[0]*scale, oy+p[2]*scale
    def rect(x,y,w,h,fill,stroke='none',sw=1,rx=0,extra=''):
        add(f'<rect x="{x:.2f}" y="{y:.2f}" width="{w:.2f}" height="{h:.2f}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}" {extra}/>')
    def text(x,y,value,size=18,color=navy,weight=400,anchor='start',mono=False):
        family='DejaVu Sans Mono' if mono else 'Noto Sans CJK SC'
        add(f'<text x="{x:.2f}" y="{y:.2f}" font-family="{family},sans-serif" font-size="{size}" font-weight="{weight}" fill="{color}" text-anchor="{anchor}">{escape(str(value))}</text>')
    def line(x,y,u,v,color=gold,sw=2,extra=''):
        add(f'<line x1="{x:.2f}" y1="{y:.2f}" x2="{u:.2f}" y2="{v:.2f}" stroke="{color}" stroke-width="{sw}" {extra}/>')
    def circle(x,y,r,fill,stroke='none',sw=1):
        add(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="{r:.2f}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>')
    def arrow(x,y,yaw,length=38,color=gold):
        t=math.radians(yaw)
        line(x,y,x+math.sin(t)*length,y+math.cos(t)*length,color,2.4,'marker-end="url(#direction)"')
    def badge(x,y,num,color=navy,r=14):
        circle(x,y,r,color,cream,2);text(x,y+4,str(num).zfill(2),11,'#ffffff',700,'middle')
    add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">')
    add('''<defs>
<marker id="direction" markerWidth="7" markerHeight="7" refX="5" refY="3" orient="auto" markerUnits="strokeWidth"><path d="M0 0L5 3L0 6" fill="none" stroke="#b18b52" stroke-width="1.3" stroke-linecap="round"/></marker>
<pattern id="plank" width="70" height="220" patternUnits="userSpaceOnUse"><rect width="70" height="220" fill="#ead7b9"/><path d="M0 0V220 M70 0V220 M0 110H70 M10 5Q22 55 13 100 M51 120Q35 165 54 214" fill="none" stroke="#c7ac87" stroke-width="1" opacity=".5"/></pattern>
<pattern id="rug" width="15" height="15" patternUnits="userSpaceOnUse"><rect width="15" height="15" fill="#dba6ac"/><path d="M0 0L15 15 M15 0L0 15" stroke="#f3d8cc" stroke-width="1" opacity=".8"/></pattern>
</defs>''')
    rect(0,0,width,height,cream);rect(0,0,width,9,navy)
    text(72,69,'PEACHVEIL SANCTUARY',18,gold,600)
    text(72,126,'桃隐仙居 · '+('交互与导航图' if interactions else '空间定位图'),44,navy,700)
    text(74,171,'8.00 × 7.00 m   /   GLB 米制 · +Y 向上   /   中式梁架 · 粉纱帐床 · 桃花灯影',20,'#6a7c81')
    rect(1500,56,350,103,'#e5e8e1',rx=15)
    text(1675,92,'室内 56 m² · 檐高 3.25 m',18,navy,500,'middle')
    text(1675,127,'屋脊 4.65 m · 无屋外场景',18,navy,500,'middle')
    # Actual metre-scale room rectangle and grid.
    rect(72,250,900,787.5,'url(#plank)',navy,8,6)
    for i in range(-3,4):
        x=ox+i*scale;line(x,254,x,1034,'#ffffff',.7,'opacity=".23"')
    for i in range(-3,4):
        y=oy+i*scale;line(76,y,968,y,'#ffffff',.7,'opacity=".23"')
    line(72,218,972,218,'#7c8c8b',1.2);line(72,210,72,226,'#7c8c8b',1.2);line(972,210,972,226,'#7c8c8b',1.2)
    rect(472,203,100,30,cream);text(522,225,'8.00 m',18,'#71817e',500,'middle')
    add('<g transform="translate(38 644) rotate(-90)">');text(0,0,'7.00 m',18,'#71817e',500,'middle');add('</g>')
    objs=[o for o in manifest['objects'] if not o['node'].startswith('GLASS_')]
    palette={'FURN_BED':'#ad7950','FURN_RUG':'url(#rug)','FURN_MEDITATION':'#b7c7bd','FURN_CHAIR_DESK':'#c99f9e','FURN_TEA_CUSHION_SOUTH':'#d9a0a7','FURN_TEA_CUSHION_NORTH':'#d9a0a7',
             'PORTAL_ENTRY':'#f6f1e7'}
    # Rug first, so furniture footprints remain legible over it.
    order=sorted(objs,key=lambda o: o['node']!='FURN_RUG')
    for obj in order:
        name, box=obj['node'],obj['gltfBounds']
        lo,hi=box['min'],box['max']; x,y=xy(lo);w=(hi[0]-lo[0])*scale;h=(hi[2]-lo[2])*scale
        fill=palette.get(name,'#c8a77c')
        if name=='FURN_BOOKCASE': fill='#b9a386'
        if interactions: fill='#c4c1b5' if name!='FURN_RUG' else '#d2dadd'
        rect(x,y,max(w,5),max(h,5),fill,'#8b795e',1.5,8)
        add(f'<title>{escape(name)} — actual exported XZ AABB</title>')
        if name=='FURN_BED':
            rect(x+12,y+15,w-24,h-30,'#eae0c7','#806c51',1,25)
            rect(x+15,y+80,w-30,max(h-100,20),'#d9a0a7','#bd7f8a',1,16)
            for k in range(2): rect(x+26+k*(w-49)/2,y+27,(w-65)/2,44,'#eee6d3','#cabaa1',1,11)
            for j in range(4): line(x+2,y+h*.35+j*h*.13,x+9,y+h*.35+j*h*.13,'#d8ba8b',2)
        elif name=='FURN_CHAIR_DESK':
            rect(x+6,y+6,max(w-12,5),max(h-12,5),'#d8dfd9','#8b9fa1',1,10)
        elif name=='FURN_RUG':
            rect(x+7,y+7,w-14,h-14,'none','#edf0df',5,6)
    # Draw origins after every footprint, including the walkable rug beneath furniture.
    if not interactions:
        for idx,obj in enumerate(objs,1):
            p=obj['gltfOrigin'];u,v=xy(p);badge(u,v,idx)
            if obj['node'] not in ('FURN_RUG','FURN_BOOKCASE'):
                arrow(u,v,obj['yawDeg'],29)
    # Open railings; entrance opens at x=0.15 south.
    line(76,250,968,250,'#aa7654',8);line(972,254,972,1032,'#aa7654',8)
    text(522,278,'北侧敞开花格栏杆 · 天空由宿主提供',16,'#426675',500,'middle')
    for xx in [-2,0,2]: u,_=xy([xx,0,0]);line(u,243,u,257,navy,5)
    u,_=xy([.15,0,0]);line(u-58,1037.5,u+58,1037.5,cream,12)
    text(u,1070,'入口 · ENTRY',17,'#6b7d80',500,'middle')
    line(ox-10,oy,ox+10,oy,'#7c8f8d',1.2);line(ox,oy-10,ox,oy+10,'#7c8f8d',1.2)
    # Sidebar.
    rect(1035,218,817,940,'#fcfaf4','#ddd8c9',1,16)
    text(1066,263,'交互点 / 接触语义' if interactions else '家具语义节点 / 原点',27,navy,700)
    text(1066,295,'坐标属于未应用 SDK 平移的 GLB 世界空间，单位：米',16,'#7c8987')
    if interactions:
        by_id={a['id']:a for a in manifest['anchors']}
        for a,b in manifest['navigation']['edges']:
            u,v=xy(by_id[a]['gltfPosition']);x,y=xy(by_id[b]['gltfPosition'])
            line(u,v,x,y,'#698e9b',3,'stroke-dasharray="8 5" opacity=".9"')
        for i,a in enumerate(manifest['anchors']):
            u,v=xy(a['gltfPosition']);color='#ad764d' if not a['navigable'] else navy
            if a['kind'] in ('sit','lie','lie_target'):
                rect(u-9,v-9,18,18,color,cream,2,3)
            else:circle(u,v,8,color,cream,2)
            arrow(u,v,a['yawDeg'],27)
            text(u+11,v-10,str(i+1).zfill(2),13,color,700)
            yy=329+i*38.5
            badge(1082,yy-4,i+1,color,11)
            text(1104,yy,a['id'],13,navy,600,mono=True)
            text(1320,yy,a['label'],14,'#667b7e')
            p=a['gltfPosition'];text(1818,yy,f"({p[0]:.2f}, {p[1]:.2f}, {p[2]:.2f})  {a['yawDeg']:g}°",12,'#6b7776',anchor='end',mono=True)
        text(1066,1135,'● 可行走站立点   ■ 坐/躺接触点   ┄ 双向静态通道',17,'#667b7e')
    else:
        for i,o in enumerate(objs):
            yy=340+i*48.5
            if i%2==0:rect(1052,yy-25,783,46,'#f0eee3',rx=6)
            badge(1082,yy-5,i+1,navy,12)
            text(1105,yy-2,o['label'],16,navy,600)
            text(1105,yy+14,o['node'],10,'#82908d',mono=True)
            p=o['gltfOrigin'];text(1696,yy,f"({p[0]:.2f}, {p[1]:.2f}, {p[2]:.2f})",13,'#627579',anchor='end',mono=True)
            text(1815,yy,f"{o['yawDeg']:g}°",15,gold,600,'end')
    # Roof section and coordinate contract below map.
    rect(72,1120,900,288,'#e4e9e4',rx=16)
    text(100,1162,'中式曲面木屋顶 · 横剖面',21,navy,600)
    line(130,1358,455,1358,'#a38660',4)
    line(130,1358,130,1243,navy,5);line(455,1358,455,1243,navy,5)
    profile=' '.join(f'{130+(x+4.2)/8.4*325:.2f},{1358-(3.25+1.4*(1-abs(x)/4.2)**1.3)*38:.2f}' for x in [-4.2+i*8.4/36 for i in range(37)])
    add(f'<polyline points="{profile}" fill="none" stroke="#946b4b" stroke-width="9"/>')
    text(292.5,1385,'室内 8.00 m',15,'#657c80',500,'middle')
    text(475,1233,'檐高 3.25 m / 屋脊 4.65 m',17,navy)
    text(475,1270,'曲面木顶 · 两侧各 18 段 · 顶饰最高 4.82 m',17,navy)
    text(475,1310,'北/东敞开栏杆；南/西灰泥墙，借景已有天空盒。',16,'#667c7e')
    text(475,1344,'CEILING 可单独隐藏；FLOOR 始终保留。',16,'#667c7e')
    # Right integration note.
    rect(1035,1182,817,226,'#203f51',rx=16)
    text(1066,1225,'坐标与接触约定',22,'#f4e4c4',600)
    text(1066,1263,'+X 向右 · −Z 为北 · +Y 向上；yaw 0° 朝 +Z，90° 朝 +X。',16,'#d7e0dc')
    text(1066,1298,'SDK 世界坐标 = GLB 坐标 + [0, −1, −4]；不要重复平移。',16,'#d7e0dc')
    text(1066,1333,'坐姿/躺姿点为身体表面接触目标，不是虚拟人根坐标。',16,'#d7e0dc')
    text(1066,1370,'路径按半径 0.22 m 检查；实际节点、边界与方向以 JSON 为准。',16,'#d7e0dc')
    text(72,1451,'PEACHVEIL SANCTUARY  /  原创 Blender 室内资产 · 无屋外场景 · 2026-10-24',14,'#82908a')
    add('</svg>')
    stem='interaction_map' if interactions else 'floorplan'
    (folder/(stem+'.svg')).write_text('\n'.join(svg),encoding='utf-8')
    cairosvg.svg2png(bytestring='\n'.join(svg).encode(),write_to=str(folder/(stem+'.png')),output_width=width,output_height=height)


def write_readme(folder, glb, manifest):
    """Document the exported contract and validation, never claim app integration."""
    report_path = folder/'validation.json'
    validation = json.loads(report_path.read_text()) if report_path.exists() else {}
    counts = validation.get('counts', {})
    conform_path = folder/'gltf_validation.json'
    conform = json.loads(conform_path.read_text()) if conform_path.exists() else {}
    issues = conform.get('issues', {})
    validator_note = (f"Khronos glTF Validator：**{issues.get('numErrors',0)} 错误、{issues.get('numWarnings',0)} 警告**，完整报告见 `gltf_validation.json`。" if conform else 'Khronos 格式验证报告尚未生成。')
    stats = (f"**{len(glb.raw):,} 字节（{len(glb.raw)/1048576:.2f} MiB）**；**{counts.get('triangles',0):,} 三角形、{counts.get('meshes',0)} 个 mesh、{counts.get('primitives',0)} 个 primitive、{counts.get('materials',0)} 个材质、{counts.get('embeddedImages',0)} 张内嵌贴图**。" if counts else f"{len(glb.raw):,} 字节；最终几何规模见 `validation.json`。")
    objrows=[]
    for obj in manifest['objects']:
        pos=', '.join(f'{v:g}' for v in obj['gltfOrigin'])
        objrows.append(f"| {obj['label']} | `{obj['node']}` | `({pos})` | {obj['yawDeg']:g} |")
    anchorrows=[]
    for a in manifest['anchors']:
        pos=', '.join(f'{v:g}' for v in a['gltfPosition'])
        anchorrows.append(f"| `{a['id']}` | {a['label']} / `{a['contact']}` | `({pos})` | {a['yawDeg']:g} | {'是' if a['navigable'] else '否'} |")
    previews=[p.name for p in folder.glob('*preview.png')]
    preview_text='、'.join('`'+n+'`' for n in sorted(previews)) if previews else '制作脚本会生成 Blender 预览图'
    content=f'''# {TITLE}

参考附图中暖木梁架、粉色纱帐、花灯与桃花的室内氛围，并沿用 `assets/dream_bedroom/README.md` 的坐标、家具语义、交互点及分墙规范，使用 Blender 原生建模的新中式修仙小屋。室内 **8 × 7 米，约 56 平方米**；檐高 3.25 米、结构屋脊 4.65 米，脊瓦装饰最高约 4.82 米。粉纱架子床、矮茶桌与蒲团、书案、书架、香案、剑架及莲台打坐区形成完整室内布置。

北侧与东侧为木柱、花格和敞开栏杆，南侧入口两旁及西侧为暖色灰泥木框墙，开口借景项目已有天空盒。**没有屋外地形、山水、云海、建筑、外景树木、天空球、天空贴图背景板或其他屋外场景**。室内桃花为装饰；Blender 预览的世界颜色、相机和灯光不导出到 GLB。

本次只新增 `assets/dream_bedroom_3/` 和对应制作工具，没有覆盖原卧室，没有复制到 `app/src/main/assets/scene/`，没有修改 SDK 或应用代码。

## 交付文件

| 文件 | 用途 |
| --- | --- |
| `{BASENAME}.blend` | 可继续编辑的 Blender 源文件，含预览相机和灯光 |
| `{BASENAME}.glb` | 米制、+Y 向上的独立室内资产，贴图内嵌 |
| `interactions.json` | {len(manifest['objects'])} 个家具/入口语义节点、{len(manifest['anchors'])} 个交互点、朝向、接触语义、实际网格 AABB 与导航图 |
| `{BASENAME}.cutaway.json` | 四面墙和屋顶独立隐藏组、网格后代名单、边界平面与法线 |
| `{BASENAME}.meta.json` | 当前 SDK 的场景平移及可走矩形 |
| `{BASENAME}.anchors.json` | `MoveAnchor` 可读的 {sum(a['navigable'] for a in manifest['anchors'])} 个 XZ 行走点、{len(EDGES)} 条双向通道 |
| `floorplan.svg` / `floorplan.png` | 米制平面图，标明家具原点、方向与屋顶剖面 |
| `interaction_map.svg` / `interaction_map.png` | 交互点、接触语义、朝向及静态导航路径 |
| 预览图 | {preview_text} |
| `validation.json` / `validation.txt` | 最终 GLB 节点、坐标、几何边界、接触面与导航校验 |
| `gltf_validation.json` | Khronos glTF 格式验证报告 |
| `textures/` | 原始程序化材质贴图；运行 GLB 不需要外部加载 |
| `SHA256SUMS.txt` | 交付文件完整性校验值（若已生成） |

## 分墙和屋顶显隐

| 分组节点 | 内容 | 外法线（未偏移 GLB） |
| --- | --- | --- |
| `WALL_NORTH` | 北侧木柱、栏杆、花格与附属装饰 | `(0,0,-1)` |
| `WALL_SOUTH` | 南侧入口、灰泥隔墙、木框与附属装饰 | `(0,0,1)` |
| `WALL_EAST` | 东侧木柱、栏杆、花格与附属装饰 | `(1,0,0)` |
| `WALL_WEST` | 西侧灰泥墙、木框护墙板及月纹装饰 | `(-1,0,0)` |
| `CEILING` | 中式曲面屋顶、梁架及悬灯装饰 | 36 段屋面参考法线，见 JSON |
| `FLOOR` | 地板与底板 | 始终保留 |

**所有墙组和屋顶在交付文件中默认显示**。这些组都是空变换节点，Filament 中只隐藏父实体不会自动隐藏子网格；宿主须递归控制全部可渲染后代，或按 `cutawayGroups[].renderableNodes` 名单逐一操作。五个可隐藏组的网格后代互不重叠，地板不属于隐藏组。床、茶席、书案与独立家具保留各自语义节点。`cutaway_preview.png` 展示隐藏 `WALL_SOUTH`、`WALL_EAST` 和 `CEILING` 后的房间，地板与家具保留；渲染结束后源文件恢复全部组可见。

屋顶沿 Z 方向起脊，采用两侧各 18 段的弯曲屋面。轮廓公式为 `Y = 3.25 + 1.40 × (1 − abs(X)/4.20)^1.30`，檐口 X=±4.2 米。`CEILING.planePointGltf` 是结构屋脊参考点，不能当成整个屋顶的水平面；`boundaryPlanesGltf` 给出原始屋面网格 36 个分段平面、端点及法线，不包含板厚、筒瓦和脊瓦装饰。组的实际 AABB 包含梁架、瓦片和悬挂装饰，最高脊瓦约 4.82 米。四个墙平面表示室内边界，开口不会被本元数据填成实墙。

所有平面、法线和家具边界均使用未应用 placement 的 GLB 世界坐标。做相机遮挡判断前须转换到与相机、角色相同的空间。GLB 本身不包含自动剖切或显隐逻辑。

## 坐标和方向

所有数值单位为米。GLB 使用右手系，+Y 向上，地板上表面为 Y=0，房间中心为原点。室内 X 为 `-4..4`、Z 为 `-3.5..3.5`，北侧为负 Z。Blender `(x,y,z)` 导出为 GLB `(x,z,-y)`，导入后不用再旋转 90°。

```text
yaw 0° = +Z，90° = +X，180° = -Z，-90° = -X
forward = [sin(yaw), 0, cos(yaw)]
quaternionXYZW = [0, sin(yaw/2), 0, cos(yaw/2)]
sdkWorld = gltfPosition + [0, -1, -4]
```

三角函数先将角度换为弧度。SDK 中地板 Y=-1、房间中心 `(0,-1,-4)`，保留原始米制尺寸。`gltfPosition/gltfOrigin/gltfBounds` 未偏移；`sdkWorldPosition/sdkWorldOrigin` 以及 walking sidecar 的 x/z 已经应用平移，不能重复叠加 meta 偏移。若场景父节点已应用 placement，应读取各节点最终世界矩阵。

## 家具定位

下表为家具父节点原点和前向。实际 AABB 从最终 GLB 的 POSITION 顶点与累积世界矩阵计算，见 `objects[].gltfBounds`；家具原点不一定是网格中心或座面中心。`PORTAL_ENTRY` 是无网格的入口语义点，boundsKind 为 semantic_point，min/max 都等于它的原点，不表示实体门洞包围盒。

| 名称 | GLB 节点 | 原点 (X,Y,Z) | yaw° |
| --- | --- | --- | ---: |
{chr(10).join(objrows)}

床前向指向床尾；椅子和蒲团前向表示坐下后身体面向。床的纱帐、床架与软装同属于床语义组；步行障碍检查采用包含它们的保守整体 AABB。

## 虚拟人交互

| ID | 用途 / 身体接触语义 | GLB (X,Y,Z) | yaw° | 可走 |
| --- | --- | --- | ---: | :---: |
{chr(10).join(anchorrows)}

每个 `ANCHOR_<ID>` 都有前方 0.5 米的 `FACING_<ID>`，部分另有 `LOOK_<ID>` 视线目标。`anchorId/label/kind/contact/yawDeg/navigable/relatedNode` 随 glTF extras 导出。读取节点位置时必须累乘父变换，不能直接把局部 translation 当世界坐标。

坐姿、躺姿、枕区点由 Blender 对指定支撑网格垂直 BVH 射线求交，取接触面再加 2 毫米；独立验证脚本从导出的真实三角形复查。脚底站立点按地板/地毯的实际表面采样。地毯可走，完整 Y 高度见本表；walking sidecar 只提供 XZ。

这些点是**身体表面接触目标，不是通用虚拟人 root 坐标**。应先走到对应 approach 点，播放实际坐下、躺下或打坐动作，采样骨骼世界变换，并以模型专属的骨骼到身体表面偏移修正根平移：

```text
currentContactWorld = boneWorldMatrix * modelSpecificContactOffset
rootTranslation += targetContactWorld - currentContactWorld
```

当前 SDK 会把模型最长边归一为 2 米；模型原点、髋骨位置、体型与动作仍不同。坐姿需考虑臀部厚度；仰卧需实际躺姿动画。`BED_LIE` 的 yaw 表示从脚指向头的身体长轴，不会让站姿自动躺下；`BED_HEAD` 只是辅助头部校准。莲台为打坐接触参考，不包含角色盘腿动画。宿主还需检查膝盖、脚底、床栏与纱帐关系并平滑过渡，必要时应用 IK。

## SDK 接入

本任务没有把文件复制进应用。需要接入时，将 GLB、同名 meta、anchors 和 cutaway sidecar 放入 `app/src/main/assets/scene/` 并重新打包，或使用仓库的场景接入工作流。完整 `interactions.json` 还需转换成应用解析的 `<场景名>.interactions.json` 简化字段结构，不能直接把完整表改名复制。当前 `SceneMetaParser.loadWalkAnchors(context, sceneFile)` 会优先读取场景同名 anchors，缺失时退回 home；`MainActivity` 已按 selectedScene 更新锚点集合。

```kotlin
controller.loadScene(
    "scene/{BASENAME}.glb",
    ScenePlacement(
        offsetX = 0f, offsetY = -1f, offsetZ = -4f, yawDeg = 0f,
        bounds = WalkBounds(-3.65f, 3.65f, -7.15f, -0.85f),
    ),
)
```

`MoveAnchor` 只读取 name/x/z/label/labelEn/links；它不会自动执行 yaw、Y、视线、坐躺动画或接触对齐。`interactions.json` 保存完整约定。应用已提供同名简化交互表加载和坐躺接触流程，但本包未生成或注入应用侧交互投影；新增茶席/打坐点还需选择并校准适合的动画。矩形 bounds 只钳制房间范围，不能防止穿床或穿桌。

## 导航、验证和规模

最终 GLB：{stats}

本包共 {len(EDGES)} 条双向静态路径，连接 {sum(a['navigable'] for a in manifest['anchors'])} 个行走点；坐姿、床面和头部点不进入步行图。{len(OBSTACLES)} 个主要家具按最终网格 AABB 在 XZ 各向扩张 0.22 米，校验全部目标及连线。通往书案经北侧通道绕过茶席，赏剑经 `EAST_CROSSING` 绕过床尾与修炼台；东侧通道较窄，应沿给定路径行走。地毯视为可走面。

几何校验状态：**{validation.get('status','待执行')}**。`validate_dream_bedroom_3.py` 检查实际节点、世界坐标、方向、FACING/LOOK、SDK 平移、家具 AABB、座面接触、walking sidecar、双向连通、分墙网格后代、地板隔离和无外部资源引用。JSON/GLB 一致性容差为 `0.00002m`，不代表运行时动作对齐达到该精度。{validator_note}

这是静态图和保守几何检查，未覆盖任意起点接入导航图的首段、不同体型、摆臂、额外道具、动态避障或动作接触。半径大于 0.22 米的角色需要重新验证。未做 Android/Filament 真机渲染、移动端性能或特定 VRM 坐躺/打坐姿态适配。

GLB 使用 glTF metallic-roughness PBR，无骨骼、无动画。灯笼发光网格不会自动照亮宿主场景；运行时由项目提供天空盒、IBL、光源、阴影和透明排序，亮度与 Blender 预览可能不同。纱帐有半透明混合材质。未附 LOD。

## 重建与预览

工具在独立 Blender 进程中制作该资产，不会覆盖旧卧室。可用本机 Blender 可执行文件代替命令中的 blender。

```bash
blender --background --factory-startup --python tools/blender/build_dream_bedroom_3.py
python tools/blender/dream3_metadata.py
python tools/blender/validate_dream_bedroom_3.py
blender --background assets/dream_bedroom_3/dream_bedroom_3.blend --python tools/blender/render_dream_bedroom_3.py
python tools/blender/dream3_metadata.py --docs-only
```

重建入口依次执行 `dream3_furniture.py`、`dream3_decor.py` 和 `export_dream_bedroom_3.py`；导出复用 `dream2_optimize.py` 的按语义组网格合并函数，保留家具及交互空节点。`dream3_metadata.py` 从最终 GLB 生成 JSON、SVG/PNG 图及本说明。`validate_dream_bedroom_3.py --update-bounds` 可刷新 JSON 中的实际边界；通常重跑 metadata 即可。Khronos 检查入口 `validate_dream_bedroom_3_gltf.cjs` 使用官方 gltf-validator npm 包，可通过 `GLTF_VALIDATOR_MODULE` 指向仓库外的包目录，无须改动项目 npm 依赖。渲染脚本结束后恢复四墙与屋顶全部可见。
'''
    (folder/'README.md').write_text(content, encoding='utf-8')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--asset-dir',type=Path,default=ROOT/'assets'/BASENAME)
    parser.add_argument('--docs-only',action='store_true',help='Refresh README from existing manifest and validation; do not rewrite GLB-derived sidecars')
    args=parser.parse_args()
    folder=args.asset_dir
    if args.docs_only:
        glb=Glb(folder/(BASENAME+'.glb'))
        manifest=json.loads((folder/'interactions.json').read_text())
    else:
        glb,manifest=build_manifest(folder)
        create_diagram(folder,manifest)
        create_diagram(folder,manifest,True)
    write_readme(folder,glb,manifest)
    print(f'Generated {BASENAME}: {len(manifest["objects"])} semantic objects, {len(manifest["anchors"])} anchors, {len(EDGES)} walking edges')


if __name__=='__main__':
    main()
