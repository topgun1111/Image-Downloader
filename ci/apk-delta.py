"""Generate bounded byte-exact APK deltas from identical compressed ZIP entries."""
import argparse
import hashlib
import os
import struct
import tempfile
import zipfile
from pathlib import Path

MAX = 128 * 1024 * 1024


def entry_ranges(path, data):
    with zipfile.ZipFile(path) as z:
        result = {}
        for entry in z.infolist():
            off = entry.header_offset
            if off < 0 or off + 30 > len(data) or data[off:off+4] != b"PK\x03\x04":
                raise ValueError("Invalid local ZIP header")
            name, extra = struct.unpack_from("<HH", data, off + 26)
            start = off + 30 + name + extra
            end = start + entry.compress_size
            if start < off or end > len(data):
                raise ValueError("Invalid ZIP entry range")
            if entry.filename in result:
                raise ValueError("Duplicate ZIP entry")
            result[entry.filename] = (start, end)
        return result


def generate(base, target, output):
    if not 0 < base.stat().st_size <= MAX or not 0 < target.stat().st_size <= MAX:
        raise ValueError("APK size out of range")
    old, new = base.read_bytes(), target.read_bytes()
    old_entries, new_entries = entry_ranges(base, old), entry_ranges(target, new)
    old_view, new_view = memoryview(old), memoryview(new)
    copies = []
    for name, (start, end) in new_entries.items():
        if name not in old_entries or end <= start:
            continue
        left, right = old_entries[name]
        if right-left == end-start and old_view[left:right] == new_view[start:end]:
            copies.append((start, end, left))
    copies.sort()
    with output.open("wb") as out:
        out.write(b"IDP1" + struct.pack(">I", len(new)))
        pos = 0
        for start, end, left in copies:
            if start < pos:
                raise ValueError("Overlapping ZIP entry")
            if start > pos:
                out.write(b"\x02" + struct.pack(">I", start-pos))
                out.write(new_view[pos:start])
            out.write(b"\x01" + struct.pack(">II", end-start, left))
            pos = end
        if pos < len(new):
            out.write(b"\x02" + struct.pack(">I", len(new)-pos))
            out.write(new_view[pos:])
        out.write(b"\x00")
    if output.stat().st_size >= len(new)*0.8:
        output.unlink()
        return False
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base", type=Path)
    parser.add_argument("target", type=Path)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    if not 0 < args.base.stat().st_size <= MAX:
        raise ValueError("Base size out of range")
    sha = hashlib.sha256(args.base.read_bytes()).hexdigest()
    output = args.directory / ("delta-" + sha + ".idpatch")
    # Never leave a partially written patch for the release workflow to upload.
    temporary = output.with_suffix(".tmp")
    try:
        if generate(args.base, args.target, temporary):
            os.replace(temporary, output)
            print(output)
        else:
            print("")
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
