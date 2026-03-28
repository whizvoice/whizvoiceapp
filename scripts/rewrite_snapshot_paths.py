#!/usr/bin/env python3
"""Rewrite absolute paths embedded in an Android emulator snapshot.pb file.

snapshot.pb is a protobuf binary that stores disk image paths, emulator
command-line args, etc.  When a snapshot is moved to a different machine
the paths must be updated — and because protobuf uses length-prefixed
strings the varint lengths must be recalculated too.

Usage:
    python3 rewrite_snapshot_paths.py <snapshot.pb> <old_prefix> <new_prefix> [<old2> <new2> ...]
"""

import sys


# ---------------------------------------------------------------------------
# Protobuf varint helpers
# ---------------------------------------------------------------------------

def decode_varint(data, pos):
    result = 0
    shift = 0
    while True:
        byte = data[pos]
        result |= (byte & 0x7F) << shift
        pos += 1
        if not (byte & 0x80):
            break
        shift += 7
    return result, pos


def encode_varint(value):
    buf = bytearray()
    while value > 0x7F:
        buf.append((value & 0x7F) | 0x80)
        value >>= 7
    buf.append(value & 0x7F)
    return bytes(buf)


# ---------------------------------------------------------------------------
# Lightweight protobuf wire-format parser
# ---------------------------------------------------------------------------

def _looks_like_protobuf(data):
    """Heuristic: does *data* parse cleanly as a protobuf message?"""
    if len(data) == 0:
        return True  # empty message is valid
    pos = 0
    try:
        while pos < len(data):
            tag, pos = decode_varint(data, pos)
            wire_type = tag & 0x07
            field_number = tag >> 3
            if field_number == 0 or wire_type not in (0, 1, 2, 5):
                return False
            if wire_type == 0:
                _, pos = decode_varint(data, pos)
            elif wire_type == 1:
                pos += 8
            elif wire_type == 2:
                length, pos = decode_varint(data, pos)
                if pos + length > len(data):
                    return False
                pos += length
            elif wire_type == 5:
                pos += 4
        return pos == len(data)
    except (IndexError, ValueError):
        return False


def rewrite_protobuf(data, replacements):
    """Walk the protobuf wire format, replacing strings and fixing lengths."""
    out = bytearray()
    pos = 0

    while pos < len(data):
        # --- tag ---
        tag, new_pos = decode_varint(data, pos)
        tag_bytes = data[pos:new_pos]
        pos = new_pos
        wire_type = tag & 0x07

        if wire_type == 0:  # varint
            _, new_pos = decode_varint(data, pos)
            out.extend(tag_bytes)
            out.extend(data[pos:new_pos])
            pos = new_pos

        elif wire_type == 1:  # 64-bit fixed
            out.extend(tag_bytes)
            out.extend(data[pos:pos + 8])
            pos += 8

        elif wire_type == 5:  # 32-bit fixed
            out.extend(tag_bytes)
            out.extend(data[pos:pos + 4])
            pos += 4

        elif wire_type == 2:  # length-delimited (string / bytes / nested)
            length, new_pos = decode_varint(data, pos)
            payload = data[new_pos:new_pos + length]

            if _looks_like_protobuf(payload) and length > 0:
                # Recurse into nested message
                payload = rewrite_protobuf(payload, replacements)
            else:
                # Treat as string — apply replacements
                try:
                    s = payload.decode("utf-8")
                    for old, new in replacements:
                        s = s.replace(old, new)
                    payload = s.encode("utf-8")
                except UnicodeDecodeError:
                    pass  # binary blob — leave as-is

            out.extend(tag_bytes)
            out.extend(encode_varint(len(payload)))
            out.extend(payload)
            pos = new_pos + length

        else:
            raise ValueError(f"Unsupported wire type {wire_type} at offset {pos}")

    return bytes(out)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    if len(sys.argv) < 4 or (len(sys.argv) - 2) % 2 != 0:
        print(f"Usage: {sys.argv[0]} <snapshot.pb> <old1> <new1> [<old2> <new2> ...]",
              file=sys.stderr)
        sys.exit(1)

    pb_path = sys.argv[1]
    replacements = []
    i = 2
    while i < len(sys.argv):
        replacements.append((sys.argv[i], sys.argv[i + 1]))
        i += 2

    # Sort replacements longest-first so longer prefixes match before shorter ones
    replacements.sort(key=lambda r: -len(r[0]))

    with open(pb_path, "rb") as f:
        data = f.read()

    original_size = len(data)
    new_data = rewrite_protobuf(data, replacements)

    with open(pb_path, "wb") as f:
        f.write(new_data)

    print(f"Rewrote {pb_path}: {original_size} -> {len(new_data)} bytes")
    for old, new in replacements:
        print(f"  {old} -> {new}")


if __name__ == "__main__":
    main()
