"""Exact original tablet restoration and dedicated seat card, optionally in actual JAR.

No JVM. --base-jar can additionally prove all compiled class bytes are unchanged.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
from PIL import Image


def require(value, message):
    if not value:
        raise RuntimeError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pixels(image):
    return image.get_flattened_data() if hasattr(image, 'get_flattened_data') else image.getdata()


def image_at(path):
    return Image.open(path).convert('RGBA')


p = argparse.ArgumentParser()
p.add_argument('--jar', type=Path)
p.add_argument('--base-jar', type=Path)
p.add_argument('--resources-only', action='store_true', help='For separately authorized Java/localization integration changes; still validate exact item names and actual JAR/source bytes')
a = p.parse_args()
require(not a.base_jar or a.jar, '--base-jar requires --jar')
root = Path(__file__).resolve().parents[3]
resources = root / 'src/main/resources'
assets = resources / 'assets/mycinema'
m = json.loads((root / 'art/manager-tablet-restoration/provenance.json').read_text())
for section in ['protected_sha256', 'output_sha256']:
    for name, expected in m[section].items():
        if a.resources_only and (name.startswith('src/main/java/') or '/lang/' in name):
            continue
        require(sha((root / name).read_bytes()) == expected, f'Changed {section}: {name}')

tablet = (assets / 'textures/item/manager_tablet.png').read_bytes()
require(sha(tablet) == m['original_tablet_sha256'], 'Original tablet pixels not restored exactly')
require(tablet == (root / 'art/tablet-icon/manager_tablet-64.png').read_bytes(), 'Tablet differs from archived original')
require(tablet != (root / 'art/manager-gold-card/pixel_v2/assets/manager_gold_card_pixel_32.png').read_bytes(), 'Gold card mapped to tablet')
require(image_at(io.BytesIO(tablet)).size == (64, 64), 'Original tablet must be 64x64')
for name in ['manager_tablet', 'seat_binding_card', 'linking_card', 'display_linking_card']:
    model = json.loads((assets / f'models/item/{name}.json').read_text())
    require(model == {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'mycinema:item/{name}'}}, f'Unexpected {name} mapping/overrides')

seat = image_at(assets / 'textures/item/seat_binding_card.png')
require(seat.size == (32, 32), 'Seat card must be native 32x32')
require(seat.getbbox() == (2, 8, 30, 24), 'Seat card silhouette changed')
require({c[3] for c in pixels(seat)} == {0, 255}, 'Seat card needs hard pixel alpha')
require(seat.getpixel((16, 10)) == (240, 120, 104, 255), 'Coral seat stripe missing')
require(seat.getpixel((7, 15)) == (185, 77, 81, 255), 'Chair back missing')
require(seat.getpixel((6, 20)) == (37, 42, 51, 255), 'Chair foot missing')
require(seat.getpixel((21, 17)) == (185, 77, 81, 255), 'Seat-link connector missing')
for role in ['manager_tablet', 'linking_card', 'access_pairing_card', 'staff_card_entry', 'staff_card_service', 'staff_card_written']:
    require((assets / 'textures/item/seat_binding_card.png').read_bytes() != (assets / f'textures/item/{role}.png').read_bytes(), f'Seat card borrowed {role} art')
display = image_at(assets / 'textures/item/display_linking_card.png')
require(display.size == (32, 32) and display.getbbox() == (2, 8, 30, 24), 'Display card geometry incorrect')
require({c[3] for c in pixels(display)} == {0, 255}, 'Display card needs hard pixel alpha')
require(display.getpixel((16, 10)) == (87, 149, 229, 255), 'Blue display stripe missing')
require(display.getpixel((10, 16)) == (250, 251, 255, 255), 'Display play symbol missing')
for image, path in [(display, 'art/display-linking-card/preview/display_linking_card_pixel_512.png'), (seat, 'art/seat-binding-card/preview/seat_binding_card_pixel_512.png'),
                    (image_at(io.BytesIO(tablet)), 'art/manager-tablet-restoration/manager_tablet_original_512.png')]:
    preview = image_at(root / path)
    expected = image.resize((512, 512), Image.Resampling.NEAREST)
    require(preview.size == expected.size and all(x[3] == y[3] and (x[3] == 0 or x == y) for x, y in zip(pixels(preview), pixels(expected))), 'Preview pixels differ: ' + path)
svg = ET.fromstring((root / 'art/seat-binding-card/source/seat_binding_card_pixel.svg').read_text())
require(all(node.tag.split('}')[-1] in {'svg', 'title', 'desc', 'path', 'rect'} for node in svg.iter()), 'Seat source must remain code-native vector art')
lang = json.loads((assets / 'lang/zh_cn.json').read_text())
require(lang['item.mycinema.manager_tablet'] == lang['block.mycinema.manager_terminal'] == '馆长终端', 'Exact manager name changed')
require(lang['item.mycinema.seat_binding_card'] == '座位绑定卡', 'Seat card name changed')
require(lang['item.mycinema.linking_card'] == '影院管理卡', 'Cinema card name changed')
registry = (root / 'src/main/java/local/cinemamanager/CinemaManager.java').read_text()
seats = (root / 'src/main/java/local/cinemamanager/seats/SeatsModule.java').read_text()
require('ITEMS.register("manager_tablet", () -> new TabletItem(' in registry, 'Tablet ID changed')
require('ITEMS.register("seat_binding_card",()->new SeatBindingItem(' in seats, 'Seat card ID changed')
for card_model in (assets / 'models/item').glob('*card*.json'):
    mapping = json.loads(card_model.read_text())
    for layer, ref in mapping.get('textures', {}).items():
        require(ref.startswith('mycinema:item/'), 'Card still uses vanilla placeholder: ' + str(card_model))
        require((assets / ('textures/' + ref.split(':', 1)[1] + '.png')).is_file(), 'Card custom texture missing: ' + ref)
print('PASS: all card item models now use present custom textures; no paper/map/name_tag card fallback.')
print('PASS: exact original 64px tablet restored; coral chair/link seat card and blue screen/link display card.')
print('PASS: names, IDs, recipes, violet cinema card and staff card resource states unchanged.')
if a.resources_only:
    print('Baseline Java/full-locale-byte preservation NOT CHECKED; exact item names still checked; other authorized integration changes may exist.')
else:
    print('PASS: protected Java source bytes including NBT/binding/permission logic remain unchanged in this resource patch.')

if a.jar:
    names = {path.removeprefix('src/main/resources/') for section in ['protected_sha256', 'output_sha256'] for path in m[section] if path.startswith('src/main/resources/')}
    with zipfile.ZipFile(a.jar) as jar:
        entries = jar.namelist()
        for name in sorted(names):
            require(entries.count(name) == 1, 'Missing/duplicate JAR entry: ' + name)
            require(jar.read(name) == (resources / name).read_bytes(), 'Compiled JAR mismatch: ' + name)
        if a.base_jar:
            with zipfile.ZipFile(a.base_jar) as old:
                old_classes = {n: old.read(n) for n in old.namelist() if n.endswith('.class')}
                classes = {n: jar.read(n) for n in entries if n.endswith('.class')}
                require(classes == old_classes, 'Compiled Java class bytes changed in resource-only candidate')
                print(f'PASS: all {len(classes)} compiled classes are byte-identical to the baseline JAR.')
    print(f'PASS: {len(names)} actual JAR resources match source; SHA256 {sha(a.jar.read_bytes())}.')
else:
    print('Actual compiled JAR coverage NOT RUN; pass --jar after coordinated packaging/build.')
print('Native named inventory/held/dropped/GUI-scale checks for the new candidate remain separate.')
