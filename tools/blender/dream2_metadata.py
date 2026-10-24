#!/usr/bin/env python3
"""Metadata, diagrams and documentation for the original Starlit Tide asset.

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
BASENAME = 'dream_bedroom_2'
TITLE = '星潮玻璃小屋 · Starlit Tide'
ROOM = {'width': 8, 'depth': 7, 'eaveHeight': 3.15, 'ridgeHeight': 4.85,
        'height': 4.85, 'roofSlope': .425,
        'gltfInteriorBounds': {'min': [-4, 0, -3.5], 'max': [4, 4.85, 3.5]}}
SDK_PLACEMENT = {'offsetX': 0, 'offsetY': -1, 'offsetZ': -4, 'yawDeg': 0,
                 'formula': 'sdkWorld = gltfPosition + [0,-1,-4]'}
COORDINATES = {'handedness': 'right', 'up': '+Y', 'defaultAvatarForward': '+Z',
               'yaw': 'degrees about +Y, positive turns +Z toward +X',
               'blenderToGltf': '[x,y,z] -> [x,z,-y]', 'floorGltfY': 0}
# Positions below are GLB (X,Y,Z), metres. Builder may replace pose-contact Y
# with the highest actual furniture surface at that XZ plus 0.002m.
ANCHORS = [
    dict(id='SPAWN', label='地毯中央出生/站立', position=[0,.046,.65], yaw=180, kind='stand', contact='feet', relatedNode='FURN_RUG', navigable=True, look=[0,4.3,-.7]),
    dict(id='RUG_WATCH_TV', label='地毯看电视', position=[.2,.046,1.2], yaw=0, kind='stand', contact='feet', relatedNode='FURN_RUG', navigable=True, look=[1.65,1.7,3.30]),
    dict(id='BED_APPROACH', label='船床侧接近', position=[-.3,0,-.2], yaw=-90, kind='approach', contact='feet', relatedNode='FURN_BED', navigable=True, look=[-2.15,.85,-1.05]),
    dict(id='BED_SIT', label='船床侧沿坐姿接触', position=[-1.1,.74,-.3], yaw=90, kind='sit', contact='buttock_surface', relatedNode='FURN_BED', navigable=False),
    dict(id='BED_LIE', label='船床仰卧髋背接触', position=[-2.77,.74,-1.05], yaw=180, kind='lie', contact='back_hip_surface', relatedNode='FURN_BED', navigable=False, look=[-2.77,4.25,-1.05]),
    dict(id='BED_HEAD', label='枕区头部校准', position=[-2.77,.9,-2.2], yaw=180, kind='lie_target', contact='head_surface', relatedNode='FURN_BED', navigable=False),
    dict(id='DESK_APPROACH', label='书桌椅前接近', position=[1.55,0,-.55], yaw=90, kind='approach', contact='feet', relatedNode='FURN_CHAIR_DESK', navigable=True, look=[3.35,1.1,-.55]),
    dict(id='DESK_SEAT', label='书桌椅座面接触', position=[2.42,.5,-.55], yaw=90, kind='sit', contact='buttock_surface', relatedNode='FURN_CHAIR_DESK', navigable=False, look=[3.5,1.05,-.55]),
    dict(id='READING_APPROACH', label='阅读椅前接近', position=[1.55,0,1.55], yaw=90, kind='approach', contact='feet', relatedNode='FURN_CHAIR_READING', navigable=True),
    dict(id='READING_SEAT', label='阅读椅座面接触', position=[2.7,.53,1.55], yaw=-90, kind='sit', contact='buttock_surface', relatedNode='FURN_CHAIR_READING', navigable=False, look=[.5,1.1,.8]),
    dict(id='NORTH_AISLE', label='北侧观星通道', position=[.5,0,-1.25], yaw=180, kind='waypoint', contact='feet', navigable=True),
    dict(id='TELESCOPE_APPROACH', label='望远镜观星接近', position=[1.55,0,-1.55], yaw=137, kind='approach', contact='feet', relatedNode='FURN_TELESCOPE', navigable=True, look=[2.35,1.5,-2.4]),
    dict(id='WINDOW_VIEW', label='北窗前站立观景', position=[.45,0,-2.45], yaw=180, kind='stand', contact='feet', relatedNode='GLASS_PANORAMA_NORTH', navigable=True, look=[.45,2,-5]),
    dict(id='WINDOW_APPROACH', label='窗边长凳前接近', position=[.9,0,-2.45], yaw=180, kind='approach', contact='feet', relatedNode='FURN_WINDOW_BENCH', navigable=True),
    dict(id='WINDOW_SEAT', label='窗边长凳座面接触', position=[.9,.53,-3.1], yaw=0, kind='sit', contact='buttock_surface', relatedNode='FURN_WINDOW_BENCH', navigable=False, look=[0,4.4,-.3]),
    dict(id='TV_APPROACH', label='电视柜前接近', position=[1.55,0,2.4], yaw=0, kind='approach', contact='feet', relatedNode='FURN_TV', navigable=True, look=[1.65,1.7,3.3]),
    dict(id='GAME_APPROACH', label='游戏机前接近', position=[2.1,0,2.4], yaw=0, kind='approach', contact='feet', relatedNode='FURN_GAME_CONSOLE', navigable=True, look=[2.1,.9,3.1]),
    dict(id='GAME_PICKUP', label='手柄抓握参考', position=[1.5,.69,3.05], yaw=0, kind='grasp', contact='hand_grip', relatedNode='PROP_CONTROLLER', navigable=False),
    dict(id='ENTRY', label='门内入口站立', position=[-.9,0,2.9], yaw=180, kind='stand', contact='feet', relatedNode='PORTAL_ENTRY', navigable=True),
    dict(id='SOUTH_AISLE', label='船床尾通道', position=[-.4,0,1.5], yaw=90, kind='waypoint', contact='feet', navigable=True),
]
EDGES = [
    ['ENTRY','SOUTH_AISLE'], ['SOUTH_AISLE','SPAWN'],
    ['SPAWN','BED_APPROACH'], ['SPAWN','DESK_APPROACH'],
    ['SPAWN','READING_APPROACH'], ['SPAWN','RUG_WATCH_TV'],
    ['SPAWN','NORTH_AISLE'], ['NORTH_AISLE','TELESCOPE_APPROACH'],
    ['NORTH_AISLE','WINDOW_VIEW'], ['WINDOW_VIEW','WINDOW_APPROACH'],
    ['RUG_WATCH_TV','TV_APPROACH'], ['TV_APPROACH','GAME_APPROACH'],
]
# Object roots are discovered by label/yawDeg extras after export. This set only
# decides which physical footprints are relevant to the walking graph.
OBSTACLES = {'FURN_BED','FURN_NIGHTSTAND','FURN_DESK','FURN_CHAIR_DESK',
             'FURN_CHAIR_READING','FURN_SIDE_TABLE','FURN_TELESCOPE',
             'FURN_WINDOW_BENCH','FURN_CHEST','FURN_MEDIA_CONSOLE'}
OBJECTS = [
    'FURN_BED','FURN_NIGHTSTAND','FURN_DESK','FURN_CHAIR_DESK','FURN_RUG',
    'FURN_CHAIR_READING','FURN_SIDE_TABLE','FURN_TELESCOPE','FURN_WINDOW_BENCH',
    'FURN_BOOKCASE','FURN_CHEST','FURN_MEDIA_CONSOLE','FURN_TV',
    'FURN_GAME_CONSOLE','PROP_CONTROLLER','PORTAL_ENTRY',
    'GLASS_PANORAMA_NORTH','GLASS_PANORAMA_EAST','GLASS_ROOF',
]
CUTAWAY = {
    'WALL_NORTH': ('北墙/山形全景玻璃', 'wall', [0,0,-3.5], [0,0,-1]),
    'WALL_SOUTH': ('南墙/入口/电视', 'wall', [0,0,3.5], [0,0,1]),
    'WALL_EAST': ('东墙/全景玻璃', 'wall', [4,0,0], [1,0,0]),
    'WALL_WEST': ('西墙/书架/护墙板', 'wall', [-4,0,0], [-1,0,0]),
    'CEILING': ('双坡玻璃天花板/木梁/灯网', 'ceiling', [0,4.85,0], [0,1,0]),
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
            rec['notes'] = 'Actual furniture top surface plus 0.002m; contact reference, not avatar root. Apply pose-specific bone-to-body offset.'
        if rec['kind'] == 'lie':
            rec['notes'] += ' Yaw is the feet-to-head body axis; a supine animation and contact alignment are required.'
        anchors.append(rec)
    for name, (label, role, point, normal) in CUTAWAY.items():
        idx = glb.names[name]
        meshes = sorted(glb.nodes[i]['name'] for i in glb.descendants(idx) if 'mesh' in glb.nodes[i])
        rec = {'node':name,'label':label,'role':role,'renderableNodes':meshes,
               'planePointGltf':point,'outwardNormalGltf':normal,'gltfBounds':bounds(glb,name)}
        if name == 'CEILING':
            length = math.sqrt(1+.425**2)
            rec['planeMeaning'] = 'Ridge reference only; use boundaryPlanesGltf for actual sloped glass planes.'
            rec['boundaryPlanesGltf'] = [
                {'label':'east_slope','point':[2,4,0],'outwardNormal':[.425/length,1/length,0]},
                {'label':'west_slope','point':[-2,4,0],'outwardNormal':[-.425/length,1/length,0]},
            ]
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
                'rendering':{'materials':'Portable glTF metallic-roughness PBR; alpha-blended actual roof/window glass; emissive fairy lights.',
                  'previewOnly':'Blender cameras/lights/world are excluded from GLB. Host supplies skybox/IBL and runtime lights.',
                  'glassRoofNode':'GLASS_ROOF','exteriorGeometry':False,
                  'optionalNodes':list(CUTAWAY),'cutawayGroups':groups,
                  'alwaysVisibleNodes':['FLOOR'],'visibilityContract':visibility}}
    write_json(folder/'interactions.json', manifest)
    write_json(folder/(BASENAME+'.cutaway.json'), {'schemaVersion':1,'units':'meters',
        'coordinateSystem':COORDINATES,'sdkPlacement':SDK_PLACEMENT,'groups':groups,
        'alwaysVisibleNodes':['FLOOR'],'defaultVisibility':'all_visible',
        'visibilityContract':visibility,
        'planeMeaning':'Unplaced GLB coordinates; transform planes/normals to the same space as camera and target. CEILING uses two sloped boundary planes.'})
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
    navy, gold, cream = '#203f51', '#b18b52', '#f6f1e7'
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
<pattern id="rug" width="15" height="15" patternUnits="userSpaceOnUse"><rect width="15" height="15" fill="#aac2ca"/><path d="M0 0L15 15 M15 0L0 15" stroke="#d5e1df" stroke-width="1" opacity=".8"/></pattern>
</defs>''')
    rect(0,0,width,height,cream);rect(0,0,width,9,navy)
    text(72,69,'STARLIT TIDE',18,gold,600)
    text(72,126,'星潮玻璃小屋 · '+('交互与导航图' if interactions else '空间定位图'),44,navy,700)
    text(74,171,'8.00 × 7.00 m   /   GLB 米制 · +Y 向上   /   双坡透明玻璃天花板',20,'#6a7c81')
    rect(1500,56,350,103,'#e5e8e1',rx=15)
    text(1675,92,'室内 56 m² · 檐高 3.15 m',18,navy,500,'middle')
    text(1675,127,'屋脊 4.85 m · 无屋外场景',18,navy,500,'middle')
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
    palette={'FURN_BED':'#ad7950','FURN_RUG':'url(#rug)','FURN_CHAIR_READING':'#a1bac1','FURN_CHAIR_DESK':'#a1bac1',
             'FURN_TV':'#2d434e','FURN_GAME_CONSOLE':'#eee9d8','PROP_CONTROLLER':'#eee9d8','PORTAL_ENTRY':'#f6f1e7'}
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
            rect(x+15,y+80,w-30,max(h-100,20),'#91aebb','#6e919e',1,16)
            for k in range(2): rect(x+26+k*(w-49)/2,y+27,(w-65)/2,44,'#eee6d3','#cabaa1',1,11)
            for j in range(4): line(x+2,y+h*.35+j*h*.13,x+9,y+h*.35+j*h*.13,'#d8ba8b',2)
        elif name in ('FURN_CHAIR_DESK','FURN_CHAIR_READING'):
            rect(x+6,y+6,max(w-12,5),max(h-12,5),'#d8dfd9','#8b9fa1',1,10)
        elif name=='FURN_RUG':
            rect(x+7,y+7,w-14,h-14,'none','#edf0df',5,6)
        if not interactions:
            p=obj['gltfOrigin'];u,v=xy(p);idx=objs.index(obj)+1
            badge(u,v,idx)
            if name not in ('FURN_RUG','FURN_GAME_CONSOLE','PROP_CONTROLLER','FURN_BOOKCASE'):
                arrow(u,v,obj['yawDeg'],29)
    # Glazing is cyan; entrance opens at x=-0.9 south.
    line(76,250,968,250,'#8bbbc9',8);line(972,254,972,1032,'#8bbbc9',8)
    text(522,278,'北侧山形全景玻璃',16,'#426675',500,'middle')
    for xx in [-2,0,2]: u,_=xy([xx,0,0]);line(u,243,u,257,navy,5)
    u,_=xy([-.9,0,0]);line(u-58,1037.5,u+58,1037.5,cream,12)
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
    text(100,1162,'玻璃天花板 · 横剖面',21,navy,600)
    line(130,1358,455,1358,'#a38660',4)
    line(130,1358,130,1243,navy,5);line(455,1358,455,1243,navy,5)
    line(130,1243,292.5,1181,'#8ab8c6',9);line(292.5,1181,455,1243,'#8ab8c6',9)
    text(292.5,1385,'8.00 m',15,'#657c80',500,'middle')
    text(475,1233,'檐高 3.15 m / 屋脊 4.85 m',17,navy)
    text(475,1270,'Y = 4.85 − 0.425 × |X|',17,navy,mono=True)
    text(475,1310,'双坡真实透明玻璃；天空由宿主天空盒提供。',16,'#667c7e')
    text(475,1344,'CEILING 可单独隐藏；FLOOR 始终保留。',16,'#667c7e')
    # Right integration note.
    rect(1035,1182,817,226,'#203f51',rx=16)
    text(1066,1225,'坐标与接触约定',22,'#f4e4c4',600)
    text(1066,1263,'+X 向右 · −Z 为北 · +Y 向上；yaw 0° 朝 +Z，90° 朝 +X。',16,'#d7e0dc')
    text(1066,1298,'SDK 世界坐标 = GLB 坐标 + [0, −1, −4]；不要重复平移。',16,'#d7e0dc')
    text(1066,1333,'坐姿/躺姿点为身体表面接触目标，不是虚拟人根坐标。',16,'#d7e0dc')
    text(1066,1370,'路径按半径 0.22 m 检查；实际节点、边界与方向以 JSON 为准。',16,'#d7e0dc')
    text(72,1451,'STARLIT TIDE  /  原创 Blender 室内资产 · 无屋外场景 · 2026-10-24',14,'#82908a')
    add('</svg>')
    stem='interaction_map' if interactions else 'floorplan'
    (folder/(stem+'.svg')).write_text('\n'.join(svg),encoding='utf-8')
    cairosvg.svg2png(bytestring='\n'.join(svg).encode(),write_to=str(folder/(stem+'.png')),output_width=width,output_height=height)


def write_readme(folder, glb, manifest):
    """Document the final exported contract, including its practical limits."""
    validation_path=folder/'validation.json'
    validation=json.loads(validation_path.read_text()) if validation_path.exists() else {}
    counts=validation.get('counts',{})
    object_rows=[]
    for o in manifest['objects']:
        p=', '.join(f'{v:g}' for v in o['gltfOrigin'])
        object_rows.append(f"| {o['label']} | `{o['node']}` | `({p})` | {o['yawDeg']:g} |")
    anchor_rows=[]
    for a in manifest['anchors']:
        p=', '.join(f'{v:g}' for v in a['gltfPosition'])
        anchor_rows.append(f"| `{a['id']}` | {a['label']} / `{a['contact']}` | `({p})` | {a['yawDeg']:g} | {'是' if a['navigable'] else '否'} |")
    roof=next(g for g in manifest['rendering']['cutawayGroups'] if g['node']=='CEILING')
    conform_path=folder/'gltf_validation.json'
    conform=json.loads(conform_path.read_text()) if conform_path.exists() else {}
    issues=conform.get('issues',{})
    validator_note=(f"Khronos glTF Validator：{issues.get('numErrors',0)} 个错误、{issues.get('numWarnings',0)} 个警告；详见 `gltf_validation.json`。" if conform else 'Khronos 格式验证结果若已生成，见 `gltf_validation.json`。')
    stat_note=(f"最终 GLB 为 **{len(glb.raw):,} 字节（{len(glb.raw)/1048576:.2f} MiB）**，包含 **{counts.get('triangles','待校验'):,} 三角形、{counts.get('meshes','待校验')} 个 mesh、{counts.get('primitives','待校验')} 个 primitive、{counts.get('materials','待校验')} 个材质、{counts.get('embeddedImages',0)} 张内嵌贴图**。" if counts else f"最终 GLB 为 {len(glb.raw):,} 字节；详细几何规模见 `validation.json`。")
    content=f'''# {TITLE}

参考提供的玻璃屋顶梦幻小屋图片与 `assets/dream_bedroom/README.md` 的资产约定，使用 Blender 原生建模的新室内场景。8 × 7 米，室内 56 平方米；檐高 3.15 米、屋脊 4.85 米。暖木船形床、蓝白织物、长书架、垂藤、黄铜望远镜、玻璃浮球、渔网灯串与小灯笼构成航海观星主题。

**天花板为真实的双坡透明玻璃**，并非星空贴图或背景板。房间包含北侧山形全景窗与东侧玻璃窗；**没有屋外地形、海面、云、树木、天空球、星空或月亮背景**，窗外和玻璃屋顶外应由项目已有天空盒显示。Blender 预览使用的世界颜色和灯光不导出到 GLB。

本次仅新增 `assets/dream_bedroom_2/` 和对应制作工具，未覆盖原卧室，也未修改应用默认场景或 SDK 行为。

## 交付文件

| 文件 | 用途 |
| --- | --- |
| `{BASENAME}.blend` | Blender 源文件，含材质、相机与预览灯光 |
| `{BASENAME}.glb` | 米制 +Y 向上、可独立导入的室内资产 |
| `interactions.json` | {len(manifest['objects'])} 个家具/玻璃语义节点、{len(manifest['anchors'])} 个交互点、方向与接触语义、导航图及实际几何 AABB |
| `{BASENAME}.cutaway.json` | 四面墙与玻璃屋顶的独立隐藏组，实际网格后代名单及平面 |
| `{BASENAME}.meta.json` | 当前 SDK 的场景平移、可走矩形范围 |
| `{BASENAME}.anchors.json` | 当前 `MoveAnchor` 解析器可读的 {sum(a['navigable'] for a in manifest['anchors'])} 个 XZ 行走点、{len(EDGES)} 条双向通道 |
| `floorplan.svg` / `floorplan.png` | 家具位置、原点、方向及玻璃屋顶横剖面 |
| `interaction_map.svg` / `interaction_map.png` | 交互点、接触语义、朝向与导航关系 |
| `bedroom_preview.png` / `glass_roof_preview.png` / `cutaway_preview.png` | Blender 预览，具体以目录内文件为准 |
| `validation.json` / `validation.txt` | 最终 GLB 节点/坐标/AABB/导航与玻璃屋顶检查 |
| `gltf_validation.json` | Khronos glTF 格式验证报告（若已生成） |

## 分墙与玻璃屋顶显隐

| 分组 | 内容 | 外法线（未偏移 GLB） |
| --- | --- | --- |
| `WALL_NORTH` | 北墙、山形全景玻璃与窗框 | `(0,0,-1)` |
| `WALL_SOUTH` | 南墙、入口与壁挂电视 | `(0,0,1)` |
| `WALL_EAST` | 东墙、侧面玻璃与窗框 | `(1,0,0)` |
| `WALL_WEST` | 西墙、护墙板与墙上书架 | `(-1,0,0)` |
| `CEILING` | 双坡玻璃、屋顶木梁、灯网和悬挂装饰 | 两个向上的斜面法线，见 JSON |
| `FLOOR` | 木地板 | 始终保留 |

所有墙与屋顶默认可见。`GLASS_ROOF` 是 `CEILING` 的后代，隐藏天花板组时玻璃和顶上装饰会一起隐藏。玻璃的金属粗糙度 PBR 材质使用 alpha 混合；宿主需正确开启透明混合并提供天空盒/IBL。

各组为空变换节点。**Filament 中仅隐藏父实体不会自动隐藏子网格**；须递归控制可渲染后代，或按 `cutawayGroups[].renderableNodes` 的实际节点名单操作。五组的可渲染后代互不重叠，`FLOOR` 不属于隐藏组。床、阅读椅、地毯及独立家具不随墙隐藏；壁挂电视和墙上书架随所属墙隐藏。

屋脊沿 Z 轴，屋顶内界为 `Y = 4.85 - 0.425 × abs(X)`。`CEILING.planePointGltf` 是屋脊参考点；实际斜面使用 `boundaryPlanesGltf` 两个平面，不能用一个水平面代替。组的 `gltfBounds` 包括木梁、灯网与悬挂装饰。所有平面和法线都在未应用 placement 的 GLB 世界坐标中，检测前应与相机、角色转换到同一坐标空间。GLB 不包含相机遮挡或自动显隐逻辑。

## 米制坐标与 SDK 平移

GLB 为右手坐标系，`+Y` 向上，地面 `Y=0`；室内 X 为 `-4..4`、Z 为 `-3.5..3.5`。北窗在负 Z，电视在正 Z。Blender `(x,y,z)` 导出为 GLB `(x,z,-y)`，导入后无需再旋转 90°。

```text
yaw 0° = +Z，90° = +X，180° = -Z，-90° = -X
forward = [sin(yaw), 0, cos(yaw)]
quaternionXYZW = [0, sin(yaw/2), 0, cos(yaw/2)]
sdkWorld = gltfPosition + [0, -1, -4]
```

三角函数先把角度转为弧度。SDK 中地面 Y=-1、房间中心 `(0,-1,-4)`；不缩放原始米制尺寸。`gltfPosition/gltfOrigin/gltfBounds` 未偏移；`sdkWorldPosition/sdkWorldOrigin` 和 walking sidecar 的 x/z 已加平移，**不能再加一次 meta 偏移**。如果导入器已把 placement 加在场景父节点，应直接读取最终世界矩阵。

## 家具定位

下表为语义父节点原点。实际几何边界由最终 GLB POSITION 顶点和累积世界矩阵计算，见 `objects[].gltfBounds`。父节点原点不等于座面或家具几何中心。无独立网格的入口节点仅表示通道位置，其 boundsKind 为 semantic_point，min/max 都是原点，不表示实体边界。

| 名称 | GLB 节点 | 原点 (X,Y,Z)，米 | yaw° |
| --- | --- | --- | ---: |
{chr(10).join(object_rows)}

玻璃朝向为室内法线，床朝向指向床尾，椅子朝向表示坐下后身体前方。玻璃屋顶不具有单一室内法线，实际屋顶法线在 cutaway 平面中给出。

## 站立、坐姿和躺姿交互

| ID | 用途 / 身体接触语义 | GLB (X,Y,Z)，米 | yaw° | 可走 |
| --- | --- | --- | ---: | :---: |
{chr(10).join(anchor_rows)}

每个 `ANCHOR_<ID>` 均带有前方 0.5 米的 `FACING_<ID>`；有视线目标的点另含 `LOOK_<ID>`。`anchorId/label/kind/contact/yawDeg/navigable/relatedNode` 自定义属性导出为 glTF `extras`。需累乘父变换读取世界位置，不能直接把局部 translation 当世界坐标。

坐姿、躺姿、枕区点的高度由 Blender 对相应家具指定接触网格求垂直 BVH 射线（排除船床边框与摆件），取实际表面再加 2 毫米。它们是**身体表面接触目标，不是通用虚拟人 root 坐标**。推荐先走到对应 approach 点，播放实际坐/躺动作、采样接触骨骼，再应用模型专属的骨骼到身体表面偏移：

```text
currentContactWorld = boneWorldMatrix * modelSpecificContactOffset
rootTranslation += targetContactWorld - currentContactWorld
```

当前 SDK 会按模型最长边归一为 2 米；原点、髋骨、体型及动作仍因 VRM 而异。坐姿需考虑臀部厚度，仰卧需使用实际躺姿动画。`BED_LIE` 的 yaw 只表示从脚指向头的身体长轴；`BED_HEAD` 为额外头部贴枕校准。`GAME_PICKUP` 仅是手部 IK/抓握参考，不包含手柄绑定。宿主还需处理脚底/膝盖/扶手接触、平滑过渡与视线。

## SDK 接入

将 GLB 与同名 meta 复制到 `app/src/main/assets/scene/` 后重打包，可由场景列表读取；本任务没有代替你进行这一步。

```kotlin
controller.loadScene(
    "scene/{BASENAME}.glb",
    ScenePlacement(
        offsetX = 0f, offsetY = -1f, offsetZ = -4f, yawDeg = 0f,
        bounds = WalkBounds(-3.65f, 3.65f, -7.15f, -0.85f),
    ),
)
```

`MoveAnchor` sidecar 仅控制 XZ 走位，yaw/Y 高度/视线/动作/坐躺接触来自完整 `interactions.json`。应按当前场景加载对应 anchors，换场景时更新会话导航集合；仅放入 JSON 不等于 SDK 已自动启用本资产交互。应用当前版本的默认 anchor 加载方式可能固定为另一场景，需在集成时核对，避免使用旧房间点位。

## 验证和规模

{stat_note}

`validate_dream_bedroom_2.py` 直接读取最终 GLB POSITION 顶点、完整父子世界变换和 extras。它检查家具原点/AABB、交互点与 FACING/LOOK、正前方、SDK 平移、sidecar 对应关系、五个独立隐藏组、地板隔离、真实透明屋顶、无外景几何和导航连通性。{len(OBSTACLES)} 个主要家具的世界 AABB 各向扩张 0.22 米，全部 {len(EDGES)} 条静态路径和所有可行走目标均接受障碍检查；地毯是可走面。

当前几何校验状态：**{validation.get('status','待执行')}**。一致性容差为 `0.00002m`，仅表示元数据与导出几何的一致程度。{validator_note}

```bash
python tools/blender/dream2_metadata.py
python tools/blender/validate_dream_bedroom_2.py
# 重导 GLB 后刷新实际 AABB（通常直接重跑 metadata 即可）
python tools/blender/validate_dream_bedroom_2.py --update-bounds
```

这是静态几何/图检查，不是运行时碰撞系统；未覆盖任意起点接入图的首段、角色摆臂、不同体型、额外道具、动态避障或动作接触。半径大于 0.22 米时应重新验证。矩形 bounds 本身不能阻止穿家具。未做 Android/Filament 真机渲染或特定 VRM 的坐姿/躺姿拟合验证。

GLB 使用 metallic-roughness PBR，无骨骼、无动画、无外部文件引用。Blender 相机、灯光与世界环境仅供预览，不导出；发光网格不会自动替代宿主灯光。移动端实际亮度、玻璃排序和性能需要项目已有天空盒/IBL、光源与目标设备评估。未附 LOD。

木纹、蓝白织物与潮汐地毯贴图由本仓库脚本程序生成，源图位于 `textures/`；运行 GLB 不需要另行加载该目录。重建入口为 `tools/blender/build_dream_bedroom_2.py`，依次使用装饰和导出脚本；`render_dream_bedroom_2.py` 生成三张预览，结束后恢复全部墙面与玻璃屋顶可见。`dream2_metadata.py` 从最终 GLB 生成本包 JSON、图示及说明，`validate_dream_bedroom_2.py` 可在无 Blender 环境复验。

## 重建与预览

在仓库根目录执行，`blender` 可替换为本机 Blender 可执行文件路径。制作脚本用于独立进程，会清理该进程中的场景，原卧室资产文件不会被写入。

```bash
blender --background --factory-startup --python tools/blender/build_dream_bedroom_2.py
python tools/blender/dream2_metadata.py
python tools/blender/validate_dream_bedroom_2.py
blender --background assets/dream_bedroom_2/dream_bedroom_2.blend --python tools/blender/render_dream_bedroom_2.py
python tools/blender/dream2_metadata.py --docs-only
```

`dream2_optimize.py` 在家具、玻璃及墙体语义组内合并网格，保留交互空节点。完整屋顶和四墙默认显示。`SHA256SUMS.txt` 用于核对交付文件完整性。

Khronos 验证脚本为 `tools/blender/validate_dream_bedroom_2_gltf.cjs`，需安装官方 `gltf-validator` npm 包，可通过 `GLTF_VALIDATOR_MODULE` 指向仓库外的包路径；不要求改动项目 npm 依赖。
'''
    (folder/'README.md').write_text(content,encoding='utf-8')


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
