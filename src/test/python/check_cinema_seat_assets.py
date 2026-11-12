"""Source-only guard against invisible/proxy geometry and drift between model, collision and map contract."""
import json, re, struct
from pathlib import Path
root=Path(__file__).resolve().parents[3]
assets=root/'src/main/resources/assets/mycinema'
contract=json.loads((root/'validation/cinema-seat-physical-contract.json').read_text())
model=json.loads((assets/'models/block/cinema_seat.json').read_text())
source=(root/'src/main/java/local/cinemamanager/seats/CinemaSeatGeometry.java').read_text()
parts=[]
for name, material, numbers in re.findall(r'new Part\("([^"\n]+)", "([^"\n]+)", ([^)]*)\)',source):
    parts.append({'name':name,'material':material,'box':[float(n) for n in numbers.split(',')]})
assert parts==contract['boxes_pixels_north'], 'Exported contract differs from the actual Java collision boxes'
assert len(parts)==len(model['elements'])==10
for part,element in zip(parts,model['elements']):
    assert element['name']==part['name']
    assert element['from']+element['to']==part['box'], 'Rendered geometry differs from selection/collision'
    assert 'rotation' not in element, 'Element rotation needs a matching collision export'
    for lo,hi in zip(element['from'],element['to']):
        assert 0<=lo<hi<=16, 'Undeclared cross-cell rendering/occupancy'
    assert set(element['faces'])=={'north','east','south','west','up','down'}
    for face in element['faces'].values():
        assert face['texture']=='#'+part['material']
        assert 'cullface' not in face, 'Small chair parts cannot be culled as complete block faces'
lo=[min(p['box'][i] for p in parts) for i in range(3)]
hi=[max(p['box'][i+3] for p in parts) for i in range(3)]
assert lo+hi==contract['model_bounds_pixels_north']
variants=json.loads((assets/'blockstates/cinema_seat.json').read_text())['variants']
assert set(variants)=={'facing='+f for f in ['north','east','south','west']}
for face,y in contract['blockstate_y_rotation_degrees'].items():
    assert variants['facing='+face]['model']=='mycinema:block/cinema_seat'
    assert variants['facing='+face]['y']==y
for texture in ['upholstery','frame']:
    name=model['textures'][texture].split(':',1)[1]
    data=(assets/('textures/'+name+'.png')).read_bytes()
    assert data[:8]==b'\x89PNG\r\n\x1a\n'
    assert struct.unpack('>II',data[16:24])==(16,16)
assert json.loads((assets/'models/item/cinema_seat.json').read_text())['parent']=='mycinema:block/cinema_seat'
loot=json.loads((root/'src/main/resources/data/mycinema/loot_table/blocks/cinema_seat.json').read_text())
assert loot['pools'][0]['entries']==[{'type':'minecraft:item','name':'mycinema:cinema_seat'}]
assert 'copy' not in json.dumps(loot), 'Items must not copy installed physical seat/controller identities'
assert contract['parts']==[{'name':'primary','offset_blocks':[0,0,0],'canonical':True}]
assert all(v is None for v in contract['native_measurements'].values()), 'Do not silently replace native measurements with source estimates'
print('PASS: exact 10-part source/model/collision contract, 4 facings, 1-cell footprint, textures, item and identity-free loot. Native measurements remain pending.')
