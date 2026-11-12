"""Resource-only check: all wall/floor states have the correct complete, thin model."""
from pathlib import Path
import json

root = Path(__file__).resolve().parents[3]
assets = root / "src/main/resources/assets/mycinema"
variants = json.loads((assets / "blockstates/seat_guide.json").read_text())["variants"]
expected = {f"facing={facing},floor={floor}" for facing in ("north", "east", "south", "west") for floor in ("false", "true")}
assert set(variants) == expected, "Every registered state must resolve exactly once."
for index, facing in enumerate(("north", "east", "south", "west")):
    for floor in (False, True):
        variant = variants[f"facing={facing},floor={str(floor).lower()}"]
        model_name = "seat_guide_floor" if floor else "seat_guide"
        assert variant["model"] == f"mycinema:block/{model_name}"
        assert variant.get("x", 0) == 0 and variant.get("y", 0) == index * 90
        model = json.loads((assets / f"models/block/{model_name}.json").read_text())
        assert len(model["elements"]) == 1
        element = model["elements"][0]
        assert element["from"] == ([4, 0, 6] if floor else [4, 6, 15.5])
        assert element["to"] == ([12, 0.5, 10] if floor else [12, 10, 16])
        assert set(element["faces"]) == {"north", "east", "south", "west", "up", "down"}
        for face in element["faces"].values():
            assert face["texture"] == "#body"
            assert "cullface" not in face, "The thin floor face must remain visible above its support."
assert json.loads((assets / "models/item/seat_guide.json").read_text())["parent"] == "mycinema:block/seat_guide"
print("PASS: eight seat-guide wall/floor variants, matching half-pixel model bounds, visible faces and unchanged item model.")
