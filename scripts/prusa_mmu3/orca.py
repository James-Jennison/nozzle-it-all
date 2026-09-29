import json, glob, os
import os
OD = os.path.join(os.environ.get("TEST_SLICER", "/mnt/faststorage/Test Slicer"), "OrcaSlicer/resources/profiles/Prusa")
def load(sub):
    m = {}
    for f in glob.glob(os.path.join(OD, sub, "*.json")):
        j = json.load(open(f)); j["__file"] = os.path.relpath(f, OD); m[j["name"]] = j
    return m
machines = load("machine"); filaments = load("filament"); processes = load("process")
def chain(coll, name):
    out = []
    while name:
        j = coll[name]; out.append(j); name = j.get("inherits")
    return out
def resolve(coll, name):
    r = {}
    for j in reversed(chain(coll, name)):
        r.update({k: v for k, v in j.items() if k not in ("__file",)})
    return r
