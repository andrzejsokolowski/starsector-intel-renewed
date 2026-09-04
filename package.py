"""Stage the release folder (no spaces) and zip it folder-at-root for the mod manager.

Run from the mod root after `./gradlew jar`. Writes IntelRenewed/ and Intel-Renewed.zip.
"""
import os
import shutil
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
STAGE = os.path.join(ROOT, "IntelRenewed")
ZIP = os.path.join(ROOT, "Intel-Renewed.zip")
SHIP = ["mod_info.json", "intel_renewed.version", "README.md", "data", "jars"]

if os.path.isdir(STAGE):
    shutil.rmtree(STAGE)
os.makedirs(STAGE)
for item in SHIP:
    src = os.path.join(ROOT, item)
    dst = os.path.join(STAGE, item)
    if os.path.isdir(src):
        shutil.copytree(src, dst)
    else:
        shutil.copy2(src, dst)

# A UTF-8 BOM breaks every Starsector JSON/CSV; refuse to ship one.
for dp, _, fn in os.walk(STAGE):
    for f in fn:
        if f.endswith((".json", ".csv", ".version")):
            with open(os.path.join(dp, f), "rb") as fh:
                if fh.read(3) == b"\xef\xbb\xbf":
                    raise SystemExit(f"BOM in {f}")

if os.path.exists(ZIP):
    os.remove(ZIP)
with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED) as z:
    for dp, _, fn in os.walk(STAGE):
        for f in fn:
            p = os.path.join(dp, f)
            z.write(p, os.path.relpath(p, ROOT).replace(os.sep, "/"))
with zipfile.ZipFile(ZIP) as z:
    for n in z.namelist():
        print(n)
print("zip:", ZIP, os.path.getsize(ZIP), "bytes")
