import sys
import tempfile
import unittest
from pathlib import Path


TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import check_gametest_inventory as inventory


class GameTestInventoryChecks(unittest.TestCase):
    def test_discovery_is_sorted_and_limited_to_gametest_classes(self):
        temp_root = TOOLS.parent / "build" / "test-tmp"
        temp_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temp_root) as raw:
            root = Path(raw)
            source = root / "src" / "main" / "java" / "example"
            source.mkdir(parents=True)
            (source / "ZuluGameTests.java").write_text("class ZuluGameTests {}", encoding="utf-8")
            (source / "AlphaGameTests.java").write_text("class AlphaGameTests {}", encoding="utf-8")
            (source / "Helper.java").write_text("class Helper {}", encoding="utf-8")

            self.assertEqual(
                inventory.discover(root),
                (
                    "src/main/java/example/AlphaGameTests.java",
                    "src/main/java/example/ZuluGameTests.java",
                ),
            )

    def test_exact_inventory_has_no_differences(self):
        expected = ("a/AlphaGameTests.java", "b/BetaGameTests.java")
        self.assertEqual(inventory.compare(expected, expected), ((), ()))

    def test_missing_test_is_reported(self):
        missing, unexpected = inventory.compare(
            ("a/AlphaGameTests.java", "b/BetaGameTests.java"),
            ("a/AlphaGameTests.java",),
        )
        self.assertEqual(missing, ("b/BetaGameTests.java",))
        self.assertEqual(unexpected, ())

    def test_unreviewed_test_is_reported(self):
        missing, unexpected = inventory.compare(
            ("a/AlphaGameTests.java",),
            ("a/AlphaGameTests.java", "b/BetaGameTests.java"),
        )
        self.assertEqual(missing, ())
        self.assertEqual(unexpected, ("b/BetaGameTests.java",))


if __name__ == "__main__":
    unittest.main()
