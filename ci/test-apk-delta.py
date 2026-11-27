"""Regression test: shifted bytes, legacy compatibility and compressed reconstruction."""
import hashlib
import importlib.util
import random
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("delta", Path(__file__).with_name("apk-delta.py"))
delta = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delta)


def make(path, data):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as z:
        z.writestr("lib/arm64-v8a/libtest.so", data)
        z.writestr("assets/data.txt", b"hello" * 1000)


def verify(base, target, patch, classes):
    out = patch.with_suffix(".apk")
    subprocess.run(["java", "-cp", classes, "app.imagedownloader.ApkDelta",
                    str(base), str(patch), str(out), str(target.stat().st_size)], check=True)
    assert hashlib.sha256(out.read_bytes()).digest() == hashlib.sha256(target.read_bytes()).digest()


def main():
    classes = sys.argv[1]
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        random_bytes = random.Random(42).randbytes(256 * 1024)
        base, target = root / "base.apk", root / "target.apk"
        make(base, random_bytes)
        make(target, random_bytes[:10001] + b"INSERT" * 31 + random_bytes[10001:150000] + b"X" * 18000 + random_bytes[150000:])
        legacy, modern = root / "legacy.idpatch", root / "modern.idpatch"
        assert delta.generate(base, target, legacy, False)
        assert delta.generate(base, target, modern, True)
        assert legacy.read_bytes()[:4] == b"IDP1"
        assert modern.read_bytes()[:4] == b"IDP2"
        assert legacy.stat().st_size < target.stat().st_size // 3
        assert modern.stat().st_size < legacy.stat().st_size
        verify(base, target, legacy, classes)
        verify(base, target, modern, classes)
        print("Shifted-block and compressed delta round-trip passed.")

if __name__ == "__main__":
    main()
