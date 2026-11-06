#!/usr/bin/env python3
"""Offline verification for Huajian v1.2 multi-part ZIP export. No third-party Python packages."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import zipfile

LIMIT = 400_000_000
PART = re.compile(r"_GPT6_PART_(\d{3})_OF_(\d{3})\.zip$")

def digest_stream(stream):
    h = hashlib.sha256()
    while True:
        block = stream.read(1024 * 1024)
        if not block:
            break
        h.update(block)
    return h.hexdigest()

def sha_file(path: Path):
    with path.open("rb") as fp:
        return digest_stream(fp)

def verify(directory: Path):
    problems = []
    passed = []
    indices = list(directory.glob("*_PACKET_INDEX.json"))
    if len(indices) != 1:
        return [f"expected exactly one *_PACKET_INDEX.json, found {len(indices)}"], passed
    try:
        index = json.loads(indices[0].read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        return [f"invalid packet index: {exc}"], passed
    expected = index.get("parts")
    if not isinstance(expected, list) or not expected:
        return ["index has no parts"], passed
    n_expected = index.get("expected_parts")
    if n_expected != len(expected):
        problems.append(f"index expected_parts={n_expected}, but {len(expected)} entries")
    global_index = index.get("global_index")
    if not isinstance(global_index, dict):
        problems.append("global_index absent")
    elif global_index.get("expected_parts") != len(expected):
        problems.append("global index part count mismatch")
    expected_names = set()
    for number, spec in enumerate(expected, 1):
        name = spec.get("filename", "")
        expected_names.add(name)
        if Path(name).name != name or not PART.search(name):
            problems.append(f"unsafe or malformed part filename: {name}")
            continue
        suffix = PART.search(name)
        if suffix and (int(suffix.group(1)) != number or int(suffix.group(2)) != len(expected)):
            problems.append(f"out-of-order name: {name}")
        path = directory / name
        if not path.is_file():
            problems.append(f"MISSING: {name}")
            continue
        size = path.stat().st_size
        if size >= LIMIT:
            problems.append(f"TOO LARGE: {name} = {size} bytes (must be < {LIMIT})")
        if size != spec.get("bytes"):
            problems.append(f"FILE SIZE MISMATCH: {name}")
        actual = sha_file(path)
        if actual != spec.get("sha256"):
            problems.append(f"ZIP SHA256 MISMATCH: {name}")
        try:
            with zipfile.ZipFile(path) as z:
                names = z.namelist()
                if len(names) != len(set(names)):
                    problems.append(f"duplicate entry paths in {name}")
                if any(p.startswith("/") or ".." in Path(p).parts for p in names):
                    problems.append(f"unsafe entry path inside {name}")
                for required in (
                    "README_FIRST.txt", "MANIFEST/global_index.json",
                    "MANIFEST/part_manifest.json", "MANIFEST/source_session.json",
                    "TIMELINE/part_media.json", "TIMELINE/events_all.json",
                    "GPT_HANDOFF/01_GPT6_PROMPT.md", "VIEWER/index.html"):
                    if required not in names:
                        problems.append(f"{name} missing {required}")
                if "MANIFEST/part_manifest.json" not in names:
                    continue
                if "MANIFEST/global_index.json" in names and global_index:
                    inner_global = json.loads(z.read("MANIFEST/global_index.json"))
                    if inner_global.get("session_id") != index.get("session_id"):
                        problems.append(f"{name} global session ID does not match index")
                    if inner_global.get("expected_parts") != len(expected):
                        problems.append(f"{name} inner global index part count mismatch")
                part = json.loads(z.read("MANIFEST/part_manifest.json"))
                if part.get("part_number") != number or part.get("total_parts") != len(expected):
                    problems.append(f"{name} part number mismatch")
                if part.get("session_id") != index.get("session_id"):
                    problems.append(f"{name} session ID mismatch")
                assets = part.get("assets", [])
                actual_media = {p for p in names if p.startswith("MEDIA/") and not p.endswith("/")}
                listed_media = {a.get("path") for a in assets}
                if actual_media != listed_media:
                    problems.append(f"{name} media set differs from part_manifest")
                for asset in assets:
                    media_path = asset.get("path")
                    if not media_path or media_path not in names:
                        problems.append(f"{name} missing media file: {media_path}")
                        continue
                    with z.open(media_path) as fp:
                        media_hash = digest_stream(fp)
                    if asset.get("sha256") != media_hash:
                        problems.append(f"{name} SHA256 mismatch within ZIP: {media_path}")
                    if z.getinfo(media_path).file_size != asset.get("bytes"):
                        problems.append(f"{name} media size mismatch: {media_path}")
                # ZIP testzip() forces ZIP CRC checking of all files.
                corrupt = z.testzip()
                if corrupt is not None:
                    problems.append(f"{name} CRC error: {corrupt}")
        except (zipfile.BadZipFile, OSError, KeyError, json.JSONDecodeError) as exc:
            problems.append(f"{name} ZIP/manifest damaged: {exc}")
        else:
            passed.append(name)
    extras = {p.name for p in directory.glob("*_GPT6_PART_*.zip")} - expected_names
    if extras:
        problems.append("UNEXPECTED PARTS: " + ", ".join(sorted(extras)))
    return problems, passed

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("folder", nargs="?", default=".", help="folder containing all PART_*.zip and *_PACKET_INDEX.json")
    args = ap.parse_args()
    errors, passed = verify(Path(args.folder))
    for name in passed:
        print("[OK]", name)
    if errors:
        for err in errors:
            print("[FAIL]", err)
        print(f"Verification failed: {len(errors)} problem(s). Do not submit an incomplete evidence set.")
        return 1
    print(f"PASS: {len(passed)} complete ZIP part(s); all hashes, CRC, counts and sizes verified.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
