"""Historical gold-card asset integrity plus the CURRENT restored tablet mapping.

The gold art is preserved for history. It is no longer the manager tablet texture.
"""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[3]
manifest = json.loads((root / 'art/manager-gold-card/provenance.json').read_text())
production_path = manifest['production_texture']
for name, expected in manifest['sha256'].items():
    if name == production_path:
        continue  # Explicit user correction restores the pre-gold tablet pixels.
    if hashlib.sha256((root / name).read_bytes()).hexdigest() != expected:
        raise RuntimeError('Changed historical gold/tablet/logic asset: ' + name)
production = (root / production_path).read_bytes()
if production != (root / manifest['previous_production_fallback']).read_bytes():
    raise RuntimeError('manager_tablet must use the original tablet-computer artwork')
if production == (root / manifest['production_source']).read_bytes():
    raise RuntimeError('Historical gold card was incorrectly assigned to the manager tablet')
print('PASS: historical gold art preserved, original tablet art restored; old gold mapping is retired.')
print('Run check_tablet_and_seat_card_resources.py for current source/JAR coverage.')
