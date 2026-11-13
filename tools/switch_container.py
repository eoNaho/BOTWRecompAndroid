#!/usr/bin/env python3
"""List the PFS0/HFS0 directory structure of a user's NSP or XCI file.

This is a read-only container inspector for validating the first import stage.
It does not decrypt NCAs or extract game content.
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
from dataclasses import dataclass
from pathlib import Path


class ContainerError(Exception):
    pass


@dataclass(frozen=True)
class Entry:
    name: str
    offset: int
    size: int
    hashed_size: int = 0
    hash_offset: int = 0
    digest: bytes = b""


def read_exact(file, offset: int, size: int, file_size: int, what: str) -> bytes:
    if offset < 0 or size < 0 or offset > file_size or size > file_size - offset:
        raise ContainerError(f"{what} extends beyond the file (offset 0x{offset:x}, size 0x{size:x})")
    file.seek(offset)
    data = file.read(size)
    if len(data) != size:
        raise ContainerError(f"could not read {what}: expected {size} bytes, got {len(data)}")
    return data


def parse_fs(file, fs_offset: int, limit: int, expected_magic: bytes) -> tuple[list[Entry], int]:
    """Read a PFS0/HFS0 table, returning entries with absolute file offsets and data start."""
    if fs_offset < 0 or limit < fs_offset or limit > os.fstat(file.fileno()).st_size:
        raise ContainerError("filesystem bounds are outside the input file")

    file_size = os.fstat(file.fileno()).st_size
    header = read_exact(file, fs_offset, 0x10, min(limit, file_size), "filesystem header")
    magic, count, string_size, _reserved = struct.unpack("<4sIII", header)
    if magic != expected_magic:
        raise ContainerError(f"expected {expected_magic.decode('ascii')} at 0x{fs_offset:x}, found {magic!r}")
    if count > 1_000_000:
        raise ContainerError(f"unreasonable entry count: {count}")

    entry_size = 0x18 if expected_magic == b"PFS0" else 0x40
    table_size = count * entry_size
    strings_at = fs_offset + 0x10 + table_size
    data_at = strings_at + string_size
    if strings_at > limit or string_size > limit - strings_at or data_at > limit:
        raise ContainerError("filesystem table or string table extends beyond its bounds")
    raw_entries = read_exact(file, fs_offset + 0x10, table_size, limit, "filesystem entry table")
    strings = read_exact(file, strings_at, string_size, limit, "filesystem string table")

    entries = []
    for i in range(count):
        raw = raw_entries[i * entry_size : (i + 1) * entry_size]
        if expected_magic == b"PFS0":
            rel_offset, size, name_offset, _padding = struct.unpack("<QQII", raw)
            hashed_size = hash_offset = 0
            digest = b""
        else:
            rel_offset, size, name_offset, hashed_size = struct.unpack_from("<QQII", raw)
            hash_offset = 0
            digest = raw[0x20:0x40]

        if name_offset >= len(strings):
            raise ContainerError(f"entry {i} has an invalid name offset")
        end = strings.find(b"\0", name_offset)
        if end < 0:
            raise ContainerError(f"entry {i} has an unterminated name")
        try:
            name = strings[name_offset:end].decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ContainerError(f"entry {i} has a non-UTF-8 name") from exc
        if not name or "/" in name or "\\" in name or name in (".", ".."):
            raise ContainerError(f"entry {i} has an unsafe or empty name")
        if rel_offset > limit - data_at or size > limit - data_at - rel_offset:
            raise ContainerError(f"entry {name!r} extends beyond its filesystem bounds")
        if expected_magic == b"HFS0" and (hash_offset > size or hashed_size > size - hash_offset):
            raise ContainerError(f"entry {name!r} has an invalid hashed region")
        entries.append(Entry(name, data_at + rel_offset, size, hashed_size, hash_offset, digest))

    return entries, data_at


def print_entries(file, entries: list[Entry], base: str, depth: int = 0) -> None:
    indent = "  " * depth
    for entry in entries:
        print(f"{indent}{entry.name}\t{entry.size} bytes\t@ 0x{entry.offset:x}")
        if depth == 0 and base == "XCI" and entry.name in ("secure", "update", "normal") and entry.size >= 0x10:
            try:
                child, _ = parse_fs(file, entry.offset, entry.offset + entry.size, b"HFS0")
            except ContainerError as exc:
                print(f"{indent}  [could not read partition directory: {exc}]", file=sys.stderr)
            else:
                print_entries(file, child, "HFS0", depth + 1)


def inspect(path: Path) -> None:
    with path.open("rb") as file:
        file_size = os.fstat(file.fileno()).st_size
        if file_size < 0x10:
            raise ContainerError("file is too small to contain a PFS0/HFS0 header")

        magic = read_exact(file, 0, 4, file_size, "container magic")
        if magic == b"PFS0":
            entries, _ = parse_fs(file, 0, file_size, b"PFS0")
            print("Format: NSP/PFS0")
            print_entries(file, entries, "NSP")
            return

        if file_size < 0x140 or read_exact(file, 0x100, 4, file_size, "XCI magic") != b"HEAD":
            raise ContainerError("unrecognized container; expected PFS0 (NSP) or XCI magic HEAD at 0x100")
        hfs_offset, hfs_header_size = struct.unpack("<QQ", read_exact(file, 0x130, 0x10, file_size, "XCI HFS0 locator"))
        if hfs_header_size < 0x10 or hfs_offset > file_size or hfs_header_size > file_size - hfs_offset:
            raise ContainerError("XCI root HFS0 header bounds are invalid")
        root, root_data_at = parse_fs(file, hfs_offset, file_size, b"HFS0")
        if root_data_at > hfs_offset + hfs_header_size:
            raise ContainerError("XCI root HFS0 tables exceed the declared header size")
        print("Format: XCI/HFS0")
        print_entries(file, root, "XCI")


def main() -> int:
    parser = argparse.ArgumentParser(description="List PFS0/HFS0 entries in a user-provided NSP or XCI.")
    parser.add_argument("container", type=Path, help="NSP or XCI file")
    args = parser.parse_args()
    try:
        inspect(args.container)
    except (OSError, ContainerError) as exc:
        print(f"switch_container: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
