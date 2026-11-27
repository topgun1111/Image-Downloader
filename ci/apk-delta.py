"""Generate legacy IDP1 and compressed IDP2 byte-exact signed APK patches."""
import argparse
import gzip
import hashlib
import os
import struct
import zipfile
from pathlib import Path

MAX = 512 * 1024 * 1024
BLOCK = 4096
ANCHOR = 32


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
            if start < off or end > len(data) or entry.filename in result:
                raise ValueError("Invalid ZIP entry range")
            result[entry.filename] = (start, end)
        return result


def matching(old, new, left, right, start, end):
    """Reuse identical entries, then search changed entries for matching byte blocks.
    Index old anchors at regular intervals; look for them in the corresponding new
    entry using a bounded C-level search, then extend matches in both directions.
    This recovers shifted data without holding a per-byte index in RAM.
    """
    if right-left == end-start and memoryview(old)[left:right] == memoryview(new)[start:end]:
        return [(start, end, left)] if end > start else []
    if min(right-left, end-start) < BLOCK:
        return []
    # Probe new blocks at aligned positions, using same-offset checks first.
    # For shifted blocks, restrict the search to a 64 KiB neighborhood.
    matches = []
    pos = start
    while pos + BLOCK <= end:
        rel = pos-start
        expected = left+rel
        chunk = new[pos:pos+BLOCK]
        if expected+BLOCK <= right and expected >= left and old[expected:expected+BLOCK] == chunk:
            found = expected
        else:
            lo = max(left, expected-65536)
            hi = min(right, expected+65536+BLOCK)
            found = old.find(chunk[:ANCHOR], lo, hi)
            if found < 0 or found+BLOCK > right or old[found:found+BLOCK] != chunk:
                pos += BLOCK
                continue
        length = BLOCK
        # Extend in larger blocks first, then preserve the remaining equal bytes.
        while pos+length+BLOCK <= end and found+length+BLOCK <= right and old[found+length:found+length+BLOCK] == new[pos+length:pos+length+BLOCK]:
            length += BLOCK
        while pos+length < end and found+length < right and old[found+length] == new[pos+length]:
            length += 1
        matches.append((pos, pos+length, found))
        pos += length
    return matches


def write_stream(old, new, old_entries, new_entries, out, advanced):
    copies = []
    for name, (start, end) in new_entries.items():
        if name not in old_entries or end <= start:
            continue
        left, right = old_entries[name]
        if advanced:
            copies.extend(matching(old, new, left, right, start, end))
        elif right-left == end-start and memoryview(old)[left:right] == memoryview(new)[start:end]:
            copies.append((start, end, left))
    copies.sort()
    # Merge consecutive copy ranges to minimize decoder work and patch overhead.
    merged = []
    for start, end, left in copies:
        if merged and merged[-1][1] == start and merged[-1][2]+merged[-1][1]-merged[-1][0] == left:
            merged[-1] = (merged[-1][0], end, merged[-1][2])
        else:
            merged.append((start, end, left))
    out.write(b"IDP1" + struct.pack(">I", len(new)))
    pos = 0
    for start, end, left in merged:
        if start < pos:
            raise ValueError("Overlapping ZIP entry")
        if start > pos:
            out.write(b"\x02" + struct.pack(">I", start-pos))
            out.write(memoryview(new)[pos:start])
        out.write(b"\x01" + struct.pack(">II", end-start, left))
        pos = end
    if pos < len(new):
        out.write(b"\x02" + struct.pack(">I", len(new)-pos))
        out.write(memoryview(new)[pos:])
    out.write(b"\x00")


def generate(base, target, output, advanced=False):
    if not 0 < base.stat().st_size <= MAX or not 0 < target.stat().st_size <= MAX:
        raise ValueError("APK size out of range")
    old, new = base.read_bytes(), target.read_bytes()
    old_entries, new_entries = entry_ranges(base, old), entry_ranges(target, new)
    with output.open("wb") as out:
        if advanced:
            out.write(b"IDP2")
            with gzip.GzipFile(fileobj=out, mode="wb", compresslevel=6, mtime=0) as compressed:
                write_stream(old, new, old_entries, new_entries, compressed, True)
        else:
            # Legacy decoders already accept arbitrary COPY/LITERAL ranges.
            write_stream(old, new, old_entries, new_entries, out, True)
    if output.stat().st_size >= len(new)*0.8:
        output.unlink()
        return False
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base", type=Path)
    parser.add_argument("target", type=Path)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--format", choices=["legacy", "compressed", "arm64", "lite"], default="legacy")
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    if not 0 < args.base.stat().st_size <= MAX:
        raise ValueError("Base size out of range")
    sha = hashlib.sha256(args.base.read_bytes()).hexdigest()
    prefix = {"legacy":"delta-", "compressed":"delta2-", "arm64":"delta2-arm64-", "lite":"delta2-lite-"}[args.format]
    output = args.directory / (prefix + sha + ".idpatch")
    temporary = output.with_suffix(".tmp")
    try:
        if generate(args.base, args.target, temporary, args.format != "legacy"):
            os.replace(temporary, output)
            print(output)
        else:
            print("")
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
