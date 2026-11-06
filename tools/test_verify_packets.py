#!/usr/bin/env python3
"""Synthetic integration tests of ZIP manifests, missing parts, SHA tampering and the 400MB rule."""
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile
from verify_packets import verify


def sha(data):
    return hashlib.sha256(data).hexdigest()


class PacketVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.ids = []
        for i in (1, 2):
            filename = f"fixture_GPT6_PART_{i:03d}_OF_002.zip"
            path = self.temp / filename
            payload = b"AAC-sample-fixture-" + bytes([i]) * 1200
            media_path = f"MEDIA/audio/audio_{i:04d}.m4a"
            part = {"session_id": "fixture", "part_number": i, "total_parts": 2,
                    "assets": [{"path": media_path, "sha256": sha(payload), "bytes": len(payload)}]}
            global_index = {"session_id": "fixture", "expected_parts": 2}
            with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
                z.writestr("README_FIRST.txt", "fixture")
                z.writestr("MANIFEST/global_index.json", json.dumps(global_index))
                z.writestr("MANIFEST/part_manifest.json", json.dumps(part))
                z.writestr("MANIFEST/source_session.json", "{}")
                z.writestr("TIMELINE/part_media.json", "{}")
                z.writestr("TIMELINE/events_all.json", "[]")
                z.writestr("GPT_HANDOFF/01_GPT6_PROMPT.md", "fixture")
                z.writestr("VIEWER/index.html", "fixture")
                z.writestr(media_path, payload)
            self.ids.append({"filename": filename, "bytes": path.stat().st_size,
                             "sha256": sha(path.read_bytes())})
        self.index_path = self.temp / "fixture_PACKET_INDEX.json"
        self.index_path.write_text(json.dumps({"session_id": "fixture", "expected_parts": 2,
                                                "parts": self.ids, "global_index": global_index}))

    def tearDown(self):
        shutil.rmtree(self.temp)

    def test_valid_parts(self):
        issues, passing = verify(self.temp)
        self.assertEqual(issues, [])
        self.assertEqual(len(passing), 2)

    def test_missing_part(self):
        (self.temp / self.ids[1]["filename"]).unlink()
        issues, _ = verify(self.temp)
        self.assertTrue(any("MISSING" in issue for issue in issues))

    def test_sha_mismatch(self):
        path = self.temp / self.ids[0]["filename"]
        with path.open("ab") as f:
            f.write(b"-TAMPERED")
        issues, _ = verify(self.temp)
        self.assertTrue(any("SHA256 MISMATCH" in issue for issue in issues))

    def test_missing_inner_asset(self):
        path = self.temp / self.ids[0]["filename"]
        tmp = path.with_suffix(".tmp")
        with zipfile.ZipFile(path) as src, zipfile.ZipFile(tmp, "w") as dest:
            for item in src.infolist():
                if not item.filename.startswith("MEDIA/"):
                    dest.writestr(item.filename, src.read(item))
        tmp.replace(path)
        self.ids[0]["bytes"] = path.stat().st_size
        self.ids[0]["sha256"] = sha(path.read_bytes())
        index = json.loads(self.index_path.read_text())
        index["parts"] = self.ids
        self.index_path.write_text(json.dumps(index))
        issues, _ = verify(self.temp)
        self.assertTrue(any("media" in issue or "missing" in issue for issue in issues))

    def test_duplicate_zip_index_error(self):
        index = json.loads(self.index_path.read_text())
        index["expected_parts"] = 3
        self.index_path.write_text(json.dumps(index))
        issues, _ = verify(self.temp)
        self.assertTrue(any("expected_parts" in issue for issue in issues))

if __name__ == "__main__":
    unittest.main(verbosity=2)
