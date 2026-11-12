"""Geometry/source acceptance only. Does not claim a Java build or native pose."""
import hashlib, itertools, json, math, re, struct
from pathlib import Path

root=Path(__file__).resolve().parents[3]
assets=root/'src/main/resources/assets/mycinema'
contract=json.loads((root/'validation/double-support-seat-contract.json').read_text())
original=json.loads((root/'reference/double-support/kumo_wine_seat.original.json').read_text())
model=json.loads((assets/'models/block/cinema_seat_double_support.json').read_text())
assert len(model['elements'])==len(original['elements'])==56
s=contract['uniform_scale'];t=contract['translation_pixels']
assert 0<s<1 and len({s})==1
checks=0
for i,(a,b) in enumerate(zip(original['elements'],model['elements'])):
    assert a['name']==b['name'] and a['faces']==b['faces']
    for key in ('from','to'):
        assert all(abs(b[key][axis]-(a[key][axis]*s+t[axis]))<1e-12 for axis in range(3))
    boxes=[entry['box'] for entry in contract['collision_boxes_pixels_north'] if entry['element']==i]
    assert boxes
    if 'rotation' in a:
        assert b['rotation']['axis']==a['rotation']['axis']=='x'
        assert b['rotation']['angle']==a['rotation']['angle']==22.5
        assert all(abs(b['rotation']['origin'][axis]-(a['rotation']['origin'][axis]*s+t[axis]))<1e-12 for axis in range(3))
        assert max(box[4]-box[1] for box in boxes)<=.125+1e-10
    else: assert boxes==[b['from']+b['to']]
    # Interior and boundary samples of every actual rendered cuboid must be
    # covered by that element's own collision slices, not a neighbor's box.
    for frac in itertools.product((0,.125,.5,.875,1),repeat=3):
        p=[b['from'][j]+frac[j]*(b['to'][j]-b['from'][j]) for j in range(3)]
        if 'rotation' in b:
            o=b['rotation']['origin'];angle=math.radians(b['rotation']['angle']);y,z=p[1]-o[1],p[2]-o[2]
            p[1]=o[1]+y*math.cos(angle)-z*math.sin(angle)
            p[2]=o[2]+y*math.sin(angle)+z*math.cos(angle)
        assert any(all(box[j]-1e-9<=p[j]<=box[j+3]+1e-9 for j in range(3)) for box in boxes),(i,p)
        checks+=1
for entry in contract['collision_boxes_pixels_north']:
    box=entry['box']
    assert all(-1e-9<=box[j]<box[j+3]<=16+1e-9 for j in range(3))
    # All four cardinal rotations must remain inside the same occupied cell.
    x0,y0,z0,x1,y1,z1=box
    for turns in range(4):
        assert min(x0,z0)>=-1e-9 and max(x1,z1)<=16+1e-9
        x0,x1,z0,z1=16-z1,16-z0,x0,x1

java=(root/'src/main/java/local/cinemamanager/seats/DoubleSupportSeatGeometry.java').read_text()
section=java.split('private static final double[][] BOXES = {',1)[1].split('};',1)[0]
java_boxes=[[float(v) for v in line.split(',')] for line in re.findall(r'\{([^{}]+)\}',section)]
assert len(java_boxes)==len(contract['collision_boxes_pixels_north'])
for a,b in zip(java_boxes,contract['collision_boxes_pixels_north']):
    assert all(abs(x-y)<1e-12 for x,y in zip(a,b['box']))
for name in original['textures']:
    before=(root/f'reference/double-support/textures/{name}.png').read_bytes()
    after=(assets/f'textures/block/double_support/{name}.png').read_bytes()
    assert before==after and after.startswith(b'\x89PNG\r\n\x1a\n')
assert contract['native_eye_height'] is None and contract['native_head_envelope'] is None
assert not contract['native_exit_test']
variants=json.loads((assets/'blockstates/cinema_seat_double_support.json').read_text())['variants']
assert len(variants)==4
for i,f in enumerate(('north','east','south','west')): assert variants['facing='+f]['y']==i*90
loot=json.loads((root/'src/main/resources/data/mycinema/loot_table/blocks/cinema_seat_double_support.json').read_text())
assert loot['pools'][0]['entries']==[{'type':'minecraft:item','name':contract['block_id']}]
assert 'copy' not in json.dumps(loot)
assert hashlib.sha256((root/'reference/double-support/kumo_wine_seat.original.json').read_bytes()).hexdigest()==contract['source_sha256']
print(f'PASS source geometry: {checks} rendered-solid coverage samples; {len(java_boxes)} bounded collision slices; 56 unchanged proportional parts, 9 exact textures, 4 facings, identity-free loot. Java/native remain UNRUN.')
