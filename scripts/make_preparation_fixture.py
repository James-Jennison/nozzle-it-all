#!/usr/bin/env python3
"""Generate distinct creased high-valence fans for real background preparation acceptance.
Run on GTHost. Deliberately pathological display geometry, not a printable model.
No artificial sleeps or production delay hook. Never overwrite the output.
"""
import argparse
import hashlib
import math
from pathlib import Path
import zipfile


def write(target, count):
    n = 150000
    objects = []
    for obj in range(1, count + 1):
        vertices = ['<vertex x="0" y="0" z="25"/>']
        triangles = []
        for i in range(n):
            a = i * 2 * math.pi / n
            vertices.append(f'<vertex x="{70*math.cos(a):.8g}" y="{70*math.sin(a):.8g}" z="{25+22*math.sin(7*a+obj*.001):.8g}"/>')
            triangles.append(f'<triangle v1="0" v2="{i+1}" v3="{(i+1)%n+1}"/>')
        objects.append(f'<object id="{obj}" type="model" name="Preparation fan {obj}"><mesh><vertices>'+''.join(vertices)+'</vertices><triangles>'+''.join(triangles)+'</triangles></mesh></object>')
    model = '<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" unit="millimeter"><resources>'+''.join(objects)+'</resources><build>'+''.join(f'<item objectid="{i}" transform="1 0 0 0 1 0 0 0 1 128 128 0"/>' for i in range(1,count+1))+'</build></model>'
    entries = {
        '[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>',
        '_rels/.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>',
        '3D/3dmodel.model': model,
    }
    with target.open('xb') as handle, zipfile.ZipFile(handle,'w') as archive:
        for name, content in entries.items():
            info=zipfile.ZipInfo(name,(2000,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            archive.writestr(info,content.encode())
    print(f'{count*n} triangles; sha256 {hashlib.sha256(target.read_bytes()).hexdigest()}')


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    parser.add_argument('--objects',type=int,default=6)
    args=parser.parse_args()
    assert 1 <= args.objects <= 12
    write(args.output,args.objects)
