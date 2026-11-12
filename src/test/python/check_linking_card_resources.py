"""Check the exact Cinema Linking Card mapping and optional compiled JAR resources.

No JVM required. Source/asset coverage is not native Minecraft visual acceptance.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile
import xml.etree.ElementTree as ET

from PIL import Image


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def pixels(image):
    return image.get_flattened_data() if hasattr(image, 'get_flattened_data') else image.getdata()


def sha(data):
    return hashlib.sha256(data).hexdigest()


parser = argparse.ArgumentParser()
parser.add_argument('--jar', type=Path, help='Verify resources in an actual compiled JAR')
parser.add_argument('--resources-only', action='store_true', help='Allow separately authorized Java/localization integration; still compare actual JAR resources to current source')
args = parser.parse_args()
root = Path(__file__).resolve().parents[3]
manifest = json.loads((root / 'art/linking-card/provenance.json').read_text())
for section in ['protected_sha256', 'output_sha256']:
    for path, expected in manifest[section].items():
        if args.resources_only and (path.startswith('src/main/java/') or '/lang/' in path):
            continue
        require(sha((root / path).read_bytes()) == expected, f'Changed {section}: {path}')

resources = root / 'src/main/resources'
model_bytes = (resources / manifest['model']).read_bytes()
texture_bytes = (resources / manifest['texture']).read_bytes()
model = json.loads(model_bytes)
require(model == {'parent': 'minecraft:item/generated',
                  'textures': {'layer0': 'mycinema:item/linking_card'}},
        'Linking Card must resolve only to its dedicated texture, without overrides')
image = Image.open(io.BytesIO(texture_bytes)).convert('RGBA')
require(image.size == (32, 32), 'Production texture must be native 32x32')
colors = set(pixels(image))
require({c[3] for c in colors} == {0, 255}, 'Texture must have hard transparent/opaque pixels')
expected_palette = {(37, 42, 51, 255), (231, 232, 237, 255), (217, 201, 255, 255),
                    (167, 124, 240, 255), (118, 84, 177, 255), (198, 201, 212, 255),
                    (250, 251, 255, 255)}
require({c for c in colors if c[3]} == expected_palette, 'Expected exact seven-color opaque palette')
require(image.getbbox() == (2, 8, 30, 24), 'Card must preserve the established pixel silhouette')
require(image.getpixel((16, 10)) == (167, 124, 240, 255), 'Distinct violet stripe missing')
require(image.getpixel((13, 17)) == (118, 84, 177, 255), 'Link connecting bar missing')
for role in ['manager_tablet', 'access_pairing_card', 'staff_card_blank', 'staff_card_written']:
    require(texture_bytes != (resources / f'assets/mycinema/textures/item/{role}.png').read_bytes(),
            f'Linking Card cannot silently borrow the {role} asset')

preview = Image.open(root / 'art/linking-card/preview/linking_card_pixel_512.png').convert('RGBA')
require(preview.size == (512, 512), 'Preview must be 16x integer enlargement')
expected_preview = image.resize((512, 512), Image.Resampling.NEAREST)
# RGB values under zero alpha are irrelevant; all visible preview pixels must match.
require(all(a[3] == b[3] and (a[3] == 0 or a == b)
            for a, b in zip(pixels(preview), pixels(expected_preview))),
        'Preview must contain the production pixels without smoothing')

svg = (root / 'art/linking-card/source/linking_card_pixel.svg').read_text()
svg_root = ET.fromstring(svg)
allowed_tags = {'svg', 'title', 'desc', 'path', 'rect'}
require(all(node.tag.split('}')[-1] in allowed_tags for node in svg_root.iter()),
        'SVG must remain repository-native vectors without embedded images, scripts, fonts or filters')
for node in svg_root.iter():
    for key, value in node.attrib.items():
        if key in {'x', 'y', 'width', 'height'}:
            require(value.isdigit(), f'Non-integer coordinate: {key}={value}')
        if key == 'd':
            require(not re.search(r'\d\.\d', value), 'Non-integer path coordinate')

refs = []
for path in (resources / 'assets/mycinema/models').rglob('*.json'):
    if 'mycinema:item/linking_card' in path.read_text():
        refs.append(path.relative_to(resources).as_posix())
require(refs == [manifest['model']], 'Only Cinema Linking Card may use its dedicated texture')
registry = (root / 'src/main/java/local/cinemamanager/CinemaManager.java').read_text()
require('ITEMS.register("linking_card", () -> new LinkingCard(' in registry, 'Existing registry ID changed')
print('PASS: dedicated Cinema Linking Card model/32px texture, violet family art, hard alpha, exact preview.')
print('PASS: item registration, recipe, restored manager tablet, staff cyan/role resources preserved.')
print('Protected baseline Java/full-locale bytes: ' + ('NOT CHECKED (integration mode).' if args.resources_only else 'PASS.'))

if args.jar:
    checked = []
    with zipfile.ZipFile(args.jar) as jar:
        paths = set([manifest['model'], manifest['texture']])
        paths.update(path.removeprefix('src/main/resources/')
                     for path in manifest['protected_sha256'] if path.startswith('src/main/resources/'))
        for name in sorted(paths):
            require(jar.read(name) == (resources / name).read_bytes(), f'Compiled JAR mismatch: {name}')
            checked.append(name)
    print(f'PASS: {len(checked)} compiled JAR resources byte-match source; JAR SHA256 {sha(args.jar.read_bytes())}.')
else:
    print('Compiled JAR coverage NOT RUN. Pass --jar PATH after the coordinated build.')
print('Native inventory/held/dropped/GUI-scale acceptance remains a separate client check.')
