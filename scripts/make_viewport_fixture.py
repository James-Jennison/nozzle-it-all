#!/usr/bin/env python3
"""Generate a deterministic 450k-triangle 3MF stress plate; never overwrite a file.

Run on GTHost, then copy the fixture for isolated installed-app acceptance.
No user models, printer access, external services or non-stdlib dependencies.
"""
import argparse
import hashlib
import math
from pathlib import Path
import struct
import zipfile


def f32(value):
    return format(struct.unpack("f", struct.pack("f", value))[0], ".9g")


def model():
    rows, columns = 250, 300
    vertices, triangles = [], []
    for r in range(rows):
        u = r * 2 * math.pi / rows
        for c in range(columns):
            a = c * 2 * math.pi / columns
            vertices.append(f'<vertex x="{f32((32 + 12 * math.cos(a)) * math.cos(u))}" y="{f32((32 + 12 * math.cos(a)) * math.sin(u))}" z="{f32(12 + 12 * math.sin(a))}"/>')
            first, nxt = r * columns, ((r + 1) % rows) * columns
            after = (c + 1) % columns
            triangles.extend((f'<triangle v1="{first+c}" v2="{nxt+c}" v3="{nxt+after}"/>', f'<triangle v1="{first+c}" v2="{nxt+after}" v3="{first+after}"/>'))
    mesh = "<mesh><vertices>" + "".join(vertices) + "</vertices><triangles>" + "".join(triangles) + "</triangles></mesh>"
    objects = "".join(f'<object id="{i}" type="model" name="Viewport stress {i}">{mesh}</object>' for i in range(1, 4))
    build = "".join(f'<item objectid="{i}" transform="1 0 0 0 1 0 0 0 1 {x} 128 0"/>' for i, x in enumerate((48, 128, 208), 1))
    return ('<?xml version="1.0" encoding="UTF-8"?><model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" unit="millimeter"><resources>' + objects + '</resources><build>' + build + '</build></model>').encode()


def write(target):
    entries = {
        "[Content_Types].xml": b'<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>',
        "_rels/.rels": b'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>',
        "3D/3dmodel.model": model(),
    }
    with target.open("xb") as handle, zipfile.ZipFile(handle, "w") as archive:
        for name, content in entries.items():
            info = zipfile.ZipInfo(name, (2000, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, content)
    print(f"450000 triangles; sha256 {hashlib.sha256(target.read_bytes()).hexdigest()}; {target}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    write(parser.parse_args().output)
