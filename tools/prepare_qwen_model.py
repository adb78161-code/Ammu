#!/usr/bin/env python3
import hashlib
import json
import shutil
import sys
from pathlib import Path
from urllib.request import urlretrieve

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "model-manifest.json"
ASSET_DIR = ROOT / "app/src/main/assets/models"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    target = ASSET_DIR / manifest["filename"]
    cache = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/qwen-model"
    cache.mkdir(parents=True, exist_ok=True)
    downloaded = cache / manifest["filename"]
    url = manifest["url"]
    if not downloaded.exists() or downloaded.stat().st_size != manifest["size_bytes"]:
        downloaded.unlink(missing_ok=True)
        urlretrieve(url, downloaded)
    if downloaded.stat().st_size != manifest["size_bytes"]:
        raise RuntimeError(f"size mismatch: {downloaded.stat().st_size} != {manifest['size_bytes']}")
    actual = sha256(downloaded)
    if actual != manifest["sha256"]:
        raise RuntimeError(f"SHA-256 mismatch: {actual} != {manifest['sha256']}")
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    if not target.exists() or sha256(target) != manifest["sha256"]:
        shutil.copyfile(downloaded, target)
    print(f"verified {manifest['filename']} {manifest['size_bytes']} bytes {actual}")
    return 0


if __name__ == "__main__":
    main()
