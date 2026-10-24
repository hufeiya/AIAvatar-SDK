#!/usr/bin/env python3
"""Check Peachveil Sanctuary's final GLB geometry, contact metadata and navigation.

No Blender or third-party package is needed. Reads embedded POSITION vertices
and accumulated world transforms; this is not a runtime collision simulation.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import struct
import sys

# Share the tested binary/transform decoder, never mutate the original package.
from validate_dream_bedroom import (
    EPS, Glb, delta, normalized, segment_hits_expanded_aabb,
    validate_cutaway_groups,
)

ROOT = Path(__file__).resolve().parents[2]
BASENAME = 'dream_bedroom_3'


def rounded_bounds(glb, name):
    if name == 'PORTAL_ENTRY' and not any('mesh' in glb.nodes[i] for i in glb.descendants(glb.names[name])):
        p = [round(v,6) for v in glb.position(name)]
        return {'min':p, 'max':p}
    return {key: [round(v, 6) for v in values] for key, values in glb.bounds(name).items()}


def update_bounds(folder):
    glb = Glb(folder / (BASENAME + '.glb'))
    path = folder / 'interactions.json'
    manifest = json.loads(path.read_text())
    for record in manifest['objects'] + manifest['navigation']['obstacles'] + manifest['rendering']['cutawayGroups']:
        record['gltfBounds'] = rounded_bounds(glb, record['node'])
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    sidecar_path = folder / (BASENAME + '.cutaway.json')
    sidecar = json.loads(sidecar_path.read_text())
    sidecar['groups'] = manifest['rendering']['cutawayGroups']
    sidecar_path.write_text(json.dumps(sidecar, ensure_ascii=False, indent=2) + '\n')


def surface_heights_at(glb, name, point):
    """Independent vertical ray through final exported triangles, in world XZ.

    Finds all surfaces. The nearest one immediately below a contact is used,
    rather than the highest object decoration above a valid seat surface.
    """
    from validate_dream_bedroom import transform
    px, pz = point[0], point[2]
    heights = []
    for index in glb.descendants(glb.names[name]):
        node = glb.nodes[index]
        if 'mesh' not in node:
            continue
        for primitive in glb.doc['meshes'][node['mesh']]['primitives']:
            if primitive.get('mode', 4) != 4:
                continue
            verts = [transform(glb.world[index], p) for p in glb.positions(primitive['attributes']['POSITION'])]
            if 'indices' in primitive:
                a = glb.doc['accessors'][primitive['indices']]
                view = glb.doc['bufferViews'][a['bufferView']]
                fmt, size = {5121:('B',1), 5123:('H',2), 5125:('I',4)}[a['componentType']]
                offset = view.get('byteOffset',0)+a.get('byteOffset',0)
                stride = view.get('byteStride',size)
                indices = [struct.unpack_from('<'+fmt,glb.binary,offset+i*stride)[0] for i in range(a['count'])]
            else:
                indices = list(range(len(verts)))
            for i in range(0,len(indices)-2,3):
                a,b,c = [verts[j] for j in indices[i:i+3]]
                den = (b[2]-c[2])*(a[0]-c[0])+(c[0]-b[0])*(a[2]-c[2])
                if abs(den) < 1e-12:
                    continue
                u = ((b[2]-c[2])*(px-c[0])+(c[0]-b[0])*(pz-c[2]))/den
                v = ((c[2]-a[2])*(px-c[0])+(a[0]-c[0])*(pz-c[2]))/den
                w = 1-u-v
                if min(u,v,w) >= -1e-7:
                    heights.append(u*a[1]+v*b[1]+w*c[1])
    return heights


def validate(folder):
    glb = Glb(folder / (BASENAME + '.glb'))
    manifest = json.loads((folder / 'interactions.json').read_text())
    meta = json.loads((folder / (BASENAME + '.meta.json')).read_text())
    walk = json.loads((folder / (BASENAME + '.anchors.json')).read_text())['anchors']
    errors, checks = [], Counter()

    def expect(ok, message, category):
        checks[category] += 1
        if not ok:
            errors.append(message)

    def close(a, b, message, category):
        expect(delta(a, b) <= EPS, f'{message}: actual={a}, expected={b}', category)

    theta = math.radians(meta['yawDeg'])
    c, s = math.cos(theta), math.sin(theta)
    offset = [meta['offsetX'], meta['offsetY'], meta['offsetZ']]

    def sdk(p):
        return [c*p[0]+s*p[2]+offset[0], p[1]+offset[1], -s*p[0]+c*p[2]+offset[2]]

    expect(not glb.duplicates, f'Duplicate GLB node names: {glb.duplicates}', 'uniqueNames')
    expect(manifest['units'] == 'meters', 'Manifest units must be meters', 'coordinates')
    expect(manifest['coordinateSystem']['up'] == '+Y', 'GLB up must be +Y', 'coordinates')
    for key in ('offsetX', 'offsetY', 'offsetZ', 'yawDeg'):
        expect(meta[key] == manifest['sdkPlacement'][key], f'SDK placement mismatch: {key}', 'sdkPlacement')

    cutaway = validate_cutaway_groups(glb, manifest, expect)
    sidecar = json.loads((folder / (BASENAME + '.cutaway.json')).read_text())
    for key in ('units', 'coordinateSystem', 'sdkPlacement'):
        expect(sidecar.get(key) == manifest.get(key), f'Cutaway sidecar differs: {key}', 'cutawaySidecar')
    for sk, mk in [('groups', 'cutawayGroups'), ('alwaysVisibleNodes', 'alwaysVisibleNodes'), ('visibilityContract', 'visibilityContract')]:
        expect(sidecar.get(sk) == manifest['rendering'].get(mk), f'Cutaway sidecar differs: {sk}', 'cutawaySidecar')

    from dream3_metadata import ANCHORS, OBJECTS
    expected_ids = {a['id'] for a in ANCHORS}
    expect({a['id'] for a in manifest['anchors']} == expected_ids, 'Exported anchor set differs from layout contract', 'layoutContract')
    expect(set(OBJECTS).issubset({o['node'] for o in manifest['objects']}), 'Missing required furniture contract', 'layoutContract')
    for definition in ANCHORS:
        name = 'ANCHOR_'+definition['id']
        if name in glb.names:
            actual = glb.position(name)
            close([actual[0], actual[2]], [definition['position'][0], definition['position'][2]], 'Anchor XZ differs from shared layout: '+name, 'layoutContract')
            declared = next((a for a in manifest['anchors'] if a['id'] == definition['id']), None)
            expect(declared is not None and declared['yawDeg'] == definition['yaw'], 'Anchor yaw differs from shared layout: '+name, 'layoutContract')
    bounds_report, contact_report, feet_report = [], [], []
    for record in manifest['objects'] + manifest['anchors']:
        node = record['node']
        expect(node in glb.names, f'Missing node: {node}', 'nodeExists')
        if node not in glb.names:
            continue
        anchor = 'id' in record
        key, skey = ('gltfPosition', 'sdkWorldPosition') if anchor else ('gltfOrigin', 'sdkWorldOrigin')
        p = glb.position(node)
        close(p, record[key], f'World translation mismatch: {node}', 'worldPosition')
        close(sdk(p), record[skey], f'SDK translation mismatch: {node}', 'sdkWorldPosition')
        yaw = math.radians(record['yawDeg'])
        forward = [math.sin(yaw), 0, math.cos(yaw)]
        close(forward, record['forward'], f'Declared forward mismatch: {node}', 'forward')
        close(glb.forward(node), record['forward'], f'GLB local +Z mismatch: {node}', 'nodeForward')
        if anchor:
            facing = record['facingNode']
            expect(facing in glb.names, f'Missing direction target: {facing}', 'nodeExists')
            if facing in glb.names:
                fp = glb.position(facing)
                close(normalized([fp[i]-p[i] for i in range(3)]), forward, f'FACING mismatch: {node}', 'facingDirection')
                distance = math.sqrt(sum((fp[i]-p[i])**2 for i in range(3)))
                expect(abs(distance-.5) <= EPS, f'FACING target is not 0.5m ahead: {node}', 'facingDistance')
            if 'lookTargetNode' in record:
                look = record['lookTargetNode']
                expect(look in glb.names, f'Missing gaze target: {look}', 'nodeExists')
                if look in glb.names:
                    close(glb.position(look), record['gltfLookTarget'], f'LOOK mismatch: {node}', 'lookTarget')
            extras = glb.nodes[glb.names[node]].get('extras', {})
            for field in ('anchorId', 'kind', 'contact', 'navigable'):
                expected = record['id'] if field == 'anchorId' else record[field]
                expect(extras.get(field) == expected, f'Anchor extras mismatch: {node}.{field}', 'anchorExtras')
            if record['kind'] in ('sit', 'lie', 'lie_target'):
                expect(not record['navigable'], f'Pose contact cannot be a walk target: {node}', 'poseContract')
                expect(record.get('relatedNode') in glb.names, f'Pose contact requires furniture: {node}', 'poseContract')
                related = record.get('relatedNode')
                if related in glb.names:
                    heights = [h for h in surface_heights_at(glb, related, p) if h <= p[1]+EPS]
                    clearance = p[1]-max(heights) if heights else None
                    contact_report.append({'anchor':record['id'],'relatedNode':related,'surfaceClearanceMeters':clearance})
                    expect(clearance is not None and abs(clearance-.002) <= EPS, f'Pose contact is not 2mm above actual mesh: {node}, clearance={clearance}', 'poseSurfaceContact')
            if record['contact'] == 'feet':
                support = []
                for support_node in ('FLOOR','FURN_RUG'):
                    if support_node in glb.names:
                        support.extend(surface_heights_at(glb, support_node, p))
                top = max((h for h in support if h <= .25), default=None)
                clearance = p[1]-top if top is not None else None
                feet_report.append({'anchor':record['id'], 'supportHeightMeters':top, 'surfaceClearanceMeters':clearance})
                expect(clearance is not None and abs(clearance-.002) <= EPS, f'Feet contact is not 2mm above actual floor/rug: {node}, clearance={clearance}', 'feetSurfaceContact')
        else:
            semantic_point = record.get('boundsKind') == 'semantic_point'
            if semantic_point:
                expect(node == 'PORTAL_ENTRY' and not any('mesh' in glb.nodes[i] for i in glb.descendants(glb.names[node])), f'Invalid semantic point bounds: {node}', 'semanticBounds')
                actual = {'min':p, 'max':p}
            else:
                actual = glb.bounds(node)
            err = max(delta(actual[k], record['gltfBounds'][k]) for k in ('min', 'max'))
            bounds_report.append({'node': node, 'maxErrorMeters': err, 'actual': actual})
            expect(err <= EPS, f'Furniture bounds differ: {node}, error={err}', 'furnitureBounds')

    anchors = {a['id']: a for a in manifest['anchors']}
    expect(len(anchors) == len(manifest['anchors']), 'Duplicate anchor IDs', 'anchorIDs')
    walking = {a['name']: a for a in walk}
    navigable = {a['id'].lower() for a in manifest['anchors'] if a['navigable']}
    expect(set(walking) == navigable, 'Walking names differ from navigable IDs', 'walkingSidecar')
    expect(len(walking) == len(walk), 'Duplicate walking point names', 'walkingSidecar')
    for name, w in walking.items():
        a = anchors.get(name.upper())
        if not a:
            continue
        close([w['x'], w['z']], [a['sdkWorldPosition'][0], a['sdkWorldPosition'][2]], f'Walking coordinate mismatch: {name}', 'walkingSidecar')
        b = meta['bounds']
        expect(b['minX'] <= w['x'] <= b['maxX'] and b['minZ'] <= w['z'] <= b['maxZ'], f'Walking point outside bounds: {name}', 'walkingBounds')
    edges = {tuple(sorted(e)) for e in manifest['navigation']['edges']}
    walk_edges = set()
    for name, w in walking.items():
        for link in w.get('links', []):
            expect(link in walking, f'Unknown walk link: {name}->{link}', 'walkingLinks')
            expect(name in walking.get(link, {}).get('links', []), f'Non-reciprocal walk link: {name}->{link}', 'walkingLinks')
            walk_edges.add(tuple(sorted([name.upper(), link.upper()])))
    expect(edges == walk_edges, 'Walking graph differs from interaction graph', 'walkingLinks')
    reached, pending = set(), ['SPAWN']
    while pending:
        current = pending.pop()
        if current in reached:
            continue
        reached.add(current)
        for edge in edges:
            if current in edge:
                pending.extend(n for n in edge if n not in reached)
    expect(reached == {n.upper() for n in navigable}, 'Walking graph is disconnected', 'connectivity')

    radius = manifest['navigation']['agentRadius']
    collisions, endpoint_collisions = [], []
    for obstacle in manifest['navigation']['obstacles']:
        node = obstacle['node']
        expect(node in glb.names, f'Missing navigation obstacle: {node}', 'nodeExists')
        if node not in glb.names:
            continue
        box = glb.bounds(node)
        for key in ('min', 'max'):
            close(box[key], obstacle['gltfBounds'][key], f'Obstacle bounds differ: {node}', 'navigationBounds')
        for aid in navigable:
            p = anchors[aid.upper()]['gltfPosition']
            if segment_hits_expanded_aabb(p, p, box, radius):
                endpoint_collisions.append({'anchor': aid, 'obstacle': node})
        for edge in sorted(edges):
            if any(a not in anchors for a in edge):
                expect(False, f'Edge references unknown ID: {edge}', 'walkingLinks')
                continue
            p, q = [glb.position(anchors[a]['node']) for a in edge]
            if segment_hits_expanded_aabb(p, q, box, radius):
                collisions.append({'edge': list(edge), 'obstacle': node})
    expect(not endpoint_collisions, f'Walk targets overlap expanded obstacles: {endpoint_collisions}', 'walkTargetClearance')
    expect(not collisions, f'Walking edges intersect expanded obstacles: {collisions}', 'edgeClearance')

    # Validate the Chinese pavilion contract: timber roof and open boundaries.
    expect('DREAM_BEDROOM_3' in glb.names, 'Missing asset root DREAM_BEDROOM_3', 'assetRoot')
    glass_nodes = [n for n in glb.names if n.startswith('GLASS_')]
    expect(not glass_nodes, f'Open railing pavilion unexpectedly contains glass nodes: {glass_nodes}', 'openRailings')
    roof_indices = glb.descendants(glb.names['CEILING'])
    roof_materials = set()
    for index in roof_indices:
        if 'mesh' in glb.nodes[index]:
            roof_materials.update(p.get('material') for p in glb.doc['meshes'][glb.nodes[index]['mesh']]['primitives'])
    expect(bool(roof_materials), 'CEILING must have actual roof/beam geometry', 'timberRoof')
    roof_planes = next(g for g in manifest['rendering']['cutawayGroups'] if g['node']=='CEILING').get('boundaryPlanesGltf', [])
    expect(len(roof_planes) == 36, 'Curved roof requires all 36 profile facet planes', 'roofPlanes')
    for plane in roof_planes:
        expect(abs(sum(v*v for v in plane['outwardNormal'])-1) < EPS, 'Roof plane normal must be normalized', 'roofPlanes')
    for index in (0, 5, 11, 17, 23, 29, 35):
        if index < len(roof_planes):
            point = roof_planes[index]['point']
            hits = surface_heights_at(glb, 'CEILING', point)
            expect(any(abs(y-point[1]) <= EPS for y in hits), f'Roof reference facet differs from actual exported mesh: {roof_planes[index]["label"]}', 'roofProfileGeometry')
    expect(manifest['rendering'].get('exteriorGeometry') is False, 'Manifest must state no exterior geometry', 'noExterior')
    expect(manifest['rendering'].get('openRailings') is True, 'Manifest must state open railings', 'openRailings')
    outside_tokens = ('BACKDROP', 'SKY_DOME', 'SKYBOX', 'FOREST_BACKDROP', 'EXTERIOR_SCENE')
    outside_nodes = [n for n in glb.names if any(t in n.upper() for t in outside_tokens)]
    expect(not outside_nodes, f'Exterior/background geometry forbidden: {outside_nodes}', 'noExterior')
    for image in glb.doc.get('images', []):
        expect('bufferView' in image and 'uri' not in image, f'Image not embedded: {image.get("name")}', 'embeddedImages')
    for buf in glb.doc.get('buffers', []):
        expect('uri' not in buf, 'GLB contains external buffer', 'embeddedBuffers')
    expect(not glb.doc.get('animations'), 'This static asset unexpectedly contains animations', 'staticAsset')
    expect(not glb.doc.get('skins'), 'This static asset unexpectedly contains skins', 'staticAsset')
    primitives = [p for m in glb.doc.get('meshes', []) for p in m['primitives']]
    triangles = sum(glb.doc['accessors'][p['indices']]['count']//3 for p in primitives if p.get('mode', 4) == 4 and 'indices' in p)
    counts = {'sceneNodes': len(glb.world), 'meshes': len(glb.doc.get('meshes', [])), 'primitives': len(primitives), 'triangles': triangles, 'vertices': sum(glb.doc['accessors'][p['attributes']['POSITION']]['count'] for p in primitives), 'materials': len(glb.doc.get('materials', [])), 'embeddedImages': len(glb.doc.get('images', [])), 'furnitureRoots': len(manifest['objects']), 'interactionAnchors': len(anchors), 'navigableAnchors': len(walking), 'undirectedEdges': len(edges)}
    return {'status': 'PASS' if not errors else 'FAIL', 'asset': BASENAME + '.glb', 'sha256': hashlib.sha256(glb.raw).hexdigest(), 'byteLength': len(glb.raw), 'actualSceneBounds': glb.bounds('DREAM_BEDROOM_3'), 'counts': counts, 'toleranceMeters': EPS, 'agentRadiusMeters': radius, 'checks': dict(checks), 'navigationCollisions': collisions, 'navigationEndpointCollisions': endpoint_collisions, 'roofMaterialIndices': sorted(i for i in roof_materials if i is not None), 'cutawayGroups': cutaway, 'furnitureBounds': bounds_report, 'poseSurfaceContacts': contact_report, 'feetSurfaceContacts': feet_report, 'errors': list(dict.fromkeys(errors)), 'limitations': ['Static graph paths checked against listed furniture AABBs expanded by agent radius; arbitrary entry paths and dynamic avoidance need host logic.', 'GLB coordinates and pose contact targets were verified; Android/Filament rendering and a specific VRM sitting/lying fit were not tested.', 'SDK walking sidecar contains XZ only. Arrival yaw, ground height, gaze, animations and contact IK need host logic.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--asset-dir', type=Path, default=ROOT/'assets'/BASENAME)
    parser.add_argument('--update-bounds', action='store_true')
    args = parser.parse_args()
    if args.update_bounds:
        update_bounds(args.asset_dir)
    result = validate(args.asset_dir)
    (args.asset_dir/'validation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    lines = [f"Peachveil Sanctuary validation: {result['status']}", f"GLB SHA-256: {result['sha256']}", f"Counts: {json.dumps(result['counts'])}", f"Coordinate tolerance: {EPS}m; agent radius: {result['agentRadiusMeters']}m"]
    lines += ['ERROR: '+e for e in result['errors']]
    lines += ['LIMITATION: '+w for w in result['limitations']]
    (args.asset_dir/'validation.txt').write_text('\n'.join(lines)+'\n')
    print('\n'.join(lines))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
