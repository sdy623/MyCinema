import tempfile
import unittest
import zipfile
from pathlib import Path

from tools import check_release_compliance


class ReleaseComplianceTests(unittest.TestCase):
    def test_rejects_bundled_ponder_classes(self):
        test_root = check_release_compliance.ROOT / "build/test-tmp"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as directory:
            artifact = Path(directory) / "mycinema.jar"
            with zipfile.ZipFile(artifact, "w") as archive:
                archive.writestr("net/createmod/ponder/api/scene/SceneBuilder.class", b"not-a-real-class")
                archive.writestr("META-INF/LICENSE", "")
                archive.writestr("META-INF/NOTICE", "")
                archive.writestr("META-INF/THIRD_PARTY.md", "")
            errors = check_release_compliance.scan_jar(artifact)
        self.assertTrue(any("third-party class" in error and "ponder" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
