#!/usr/bin/env python3
"""Build the static nozzleitall.com site from site-src/ into site/.

  build_site.py            write the site
  build_site.py --check    build to a temp dir and fail if site/ differs (CI drift check)

Rules enforced here (the build fails, it does not warn):
  * release.json is the only source of platform availability. A platform in state "released" needs a url; a "Launch"/"Download"
    button is only ever rendered for a released platform with a url. Planned/deferred platforms never get an action button.
  * site/mmf-auth/ (the MyMiniFactory OAuth relay) is never written to.
  * The brand violet #8B5CF6 is not used for text (fails AA on the dark background).
  * Every internal link resolves to a built page or asset; no {{placeholder}} is left over.
"""
import hashlib, html, json, re, shutil, sys, tempfile, filecmp
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "site-src"
OUT = ROOT / "site"
STATES = {"active": "Active", "released": "Available", "development": "In development", "planned": "Planned", "deferred": "Deferred"}

def fail(msg): print("BUILD FAILED:", msg, file=sys.stderr); sys.exit(1)

def load_release():
    r = json.loads((SRC / "release.json").read_text())
    for key, p in r["platforms"].items():
        if p["state"] not in STATES: fail(f"{key}: unknown state {p['state']}")
        if p["state"] == "released" and not p.get("url"): fail(f"{key} is 'released' but has no url")
        if p["state"] != "released" and p.get("url"): fail(f"{key} has a url but state is {p['state']}; a link must not exist before release")
    return r

def badge(r, key):
    p = r["platforms"][key]
    return f'<span class="badge {p["state"]}">{STATES[p["state"]]}</span>'

def primary_cta(r):
    a = r["platforms"]["android"]
    if a["state"] == "released" and a.get("url"):
        return f'<a class="btn" href="{a["url"]}">Get the Android app</a>'
    return f'<a class="btn" href="{r["github"]}/releases">Follow releases on GitHub</a>'

def platform_cards(r):
    out = []
    for key, p in sorted(r["platforms"].items(), key=lambda kv: kv[1]["order"]):
        link = "/platforms/web/" if key == "web" else None
        title = f'<a href="{link}">{html.escape(p["name"])}</a>' if link else html.escape(p["name"])
        out.append(f'<div class="card"><h3>{title} {badge(r, key)}</h3><p>{html.escape(p["summary"])}</p></div>')
    return "\n".join(out)

def parse_page(path):
    text = path.read_text()
    m = re.match(r"---\n(.*?)\n---\n(.*)", text, re.S)
    if not m: fail(f"{path.name}: missing front matter")
    meta = dict(line.split(": ", 1) for line in m.group(1).splitlines() if ": " in line)
    for k in ("title", "description", "path"):
        if k not in meta: fail(f"{path.name}: front matter needs {k}")
    return meta, m.group(2)

def model_catalog():
    """The slicing-profile catalog (site-src/printer_models.json, written by scripts/bundle_vendor_profiles.py) as collapsible vendor sections."""
    import html as h
    data = json.loads((SRC / "printer_models.json").read_text())
    total = sum(len(v["models"]) for v in data["vendors"])
    jump = " &middot; ".join(f'<a href="#v-{re.sub(r"[^a-z0-9]+", "-", v["name"].lower()).strip("-")}">{h.escape(v["name"])} ({len(v["models"])})</a>' for v in data["vendors"])
    out = [f'<p><strong>{total} models from {len(data["vendors"])} makers.</strong></p>', f'<p class="fine">{jump}</p>']
    for v in data["vendors"]:
        anchor = re.sub(r"[^a-z0-9]+", "-", v["name"].lower()).strip("-")
        items = "".join(f'<li>{h.escape(m["label"])}{" <span class=\"badge verified\">Verified on hardware</span>" if m["verified"] else ""}</li>' for m in v["models"])
        out.append(f'<details id="v-{anchor}"><summary>{h.escape(v["name"])} ({len(v["models"])})</summary><ul>{items}</ul></details>')
    return "\n".join(out)


FAMILY = {"paxx-extended": "PAXX extended firmware", "snapmaker-stock": "Snapmaker stock firmware", "cosmos": "OpenCentauri COSMOS",
          "klipper": "Klipper", "elegoo-stock": "Elegoo stock firmware", "opencentauri-patched": "OpenCentauri-patched firmware",
          "bambu-lan": "Bambu Lab LAN mode", "prusalink": "PrusaLink", "octoprint": "OctoPrint"}
GRADE = {"PASS": ("verified", "Pass"), "PARTIAL": ("development", "Partial"), "FAIL": ("docs", "Fail"),
         "SKIPPED": ("planned", "Not tested"), "UNVERIFIED": ("planned", "Not tested"), "BLOCKED": ("development", "Blocked")}
CATEGORIES = [("slicing", "Slicing"), ("file_transfer", "File transfer"), ("monitoring", "Monitoring"), ("controls", "Controls"), ("physical_print", "Printing")]

def compatibility():
    """The Test Grid compatibility report (docs/testgrid/compatibility-report.json, built from accepted evidence) as a page."""
    import html as h
    data = json.loads((ROOT / "docs" / "testgrid" / "compatibility-report.json").read_text())
    tested = [row for row in data["rows"] if row["history"]]
    def cell(g, label):
        cls, text = GRADE.get(g.split(" ")[0], ("planned", "Not tested"))
        if "(unreviewed)" in g: text += " (not yet reviewed)"
        return f'<td><span class="mlabel fine">{label}: </span><span class="badge {cls}">{text}</span></td>'
    out = ['<div class="tablewrap"><table class="stack"><thead><tr><th scope="col">Printer, firmware and materials</th>'
           + "".join(f'<th scope="col">{label}</th>' for _, label in CATEGORIES) + '</tr></thead><tbody>']
    for row in sorted(tested, key=lambda r: (r["printer"]["manufacturer"], r["printer"]["model"], r["firmware"]["family"], r["scope"])):
        fw = FAMILY.get(row["firmware"]["family"], row["firmware"]["family"]) + (f' {row["firmware"]["version"]}' if row["firmware"]["version"] not in ("", "—", "unreported") else "")
        scope = "Single material" if row["scope"] == "single_material" else "Multi-material"
        out.append(f'<tr><th scope="row">{h.escape(row["printer"]["manufacturer"] + " " + row["printer"]["model"])}<br><span class="fine">{h.escape(fw)} · {scope} · tested with Nozzle It All {h.escape(row["nozzleVersion"])}</span></th>'
                   + "".join(cell(row["grades"].get(key, "UNVERIFIED"), label) for key, label in CATEGORIES) + '</tr>')
    out.append('</tbody></table></div>')
    # Straight from the suites the app ships (test-grid resources), so this list can't lag behind them.
    suites_dir = ROOT / "test-grid" / "src" / "main" / "resources" / "testgrid" / "suites"
    tested_families = {r["firmware"]["family"] for r in tested}
    titles = []
    for name in json.loads((suites_dir / "index.json").read_text())["suites"]:
        s = json.loads((suites_dir / name).read_text())
        if s["target"]["firmwareFamily"] not in tested_families: titles.append(s["title"])
    out.append('<h2>Waiting for testers</h2><p>The app has tests for these, but no accepted results yet. One suite covers every model of its kind, '
               'and each model gets its own row above once tested:</p><ul>' + "".join(f'<li>{h.escape(t)}</li>' for t in sorted(titles)) + '</ul>')
    ledger = json.loads((ROOT / "docs" / "testgrid" / "evidence" / "acceptance.json").read_text())
    latest = max((e["date"] for e in ledger["accepted"]), default=None)
    if latest: out.append(f'<p class="fine">Most recent result accepted {h.escape(latest)}.</p>')
    return "\n".join(out)


def render(r, meta, body, base):
    def sub(text):
        text = text.replace("{{github}}", r["github"]).replace("{{email}}", r["support_email"]).replace("{{updated}}", r["updated"])
        text = text.replace("{{primary_cta}}", primary_cta(r)).replace("{{platform_cards}}", platform_cards(r)).replace("{{model_catalog}}", model_catalog() if "{{model_catalog}}" in text else "").replace("{{compatibility}}", compatibility() if "{{compatibility}}" in text else "")
        text = re.sub(r"\{\{status:(\w+)\}\}", lambda m: badge(r, m.group(1)), text)
        return text
    page = base.replace("{{content}}", body)
    css_v = hashlib.sha256((SRC / "assets" / "site.css").read_bytes()).hexdigest()[:8]  # cache-bust: Cloudflare/browsers keep the old CSS otherwise
    page = page.replace('/assets/site.css"', f'/assets/site.css?v={css_v}"')
    page = re.sub(r"\{\{cur:([\w-]+)\}\}", lambda m: ' aria-current="page"' if meta.get("nav") == m.group(1) else "", page)
    page = page.replace("{{title}}", html.escape(meta["title"])).replace("{{description}}", html.escape(meta["description"], quote=True))
    return sub(page)

def build(out):
    r = load_release()
    base = (SRC / "templates" / "base.html").read_text()
    pages = {}
    for f in sorted((SRC / "pages").glob("*.html")):
        meta, body = parse_page(f)
        pages[meta["path"]] = render(r, meta, body, base)
    for p, doc in pages.items():
        if "{{" in doc: fail(f"{p}: unresolved placeholder {re.findall(r'{{[^}]*}}', doc)[:3]}")
        target = out / p.strip("/") / "index.html" if p != "/404.html" else out / "404.html"
        if p == "/": target = out / "index.html"
        target.parent.mkdir(parents=True, exist_ok=True); target.write_text(doc)
    assets = out / "assets"
    if assets.exists(): shutil.rmtree(assets)
    shutil.copytree(SRC / "assets", assets)
    # links must resolve
    built = {p.rstrip("/") or "/" for p in pages}
    for p, doc in pages.items():
        for href in re.findall(r'href="(/[^"#?]*)', doc):
            h = href.rstrip("/") or "/"
            if h in built: continue
            if (out / href.lstrip("/")).exists() or (ROOT / "site" / href.lstrip("/")).exists(): continue
            fail(f"{p}: broken internal link {href}")
    # no premature launch/download buttons
    for p, doc in pages.items():
        if re.search(r"(?i)launch (the )?web app|app\.nozzleitall\.com/?\"", doc) and r["platforms"]["web"]["state"] != "released":
            fail(f"{p}: web app is not released but the page links or offers to launch it")
    css = (SRC / "assets" / "site.css").read_text()
    for line in css.splitlines():
        if re.search(r"(?<![-\w])color\s*:[^;]*(#8B5CF6|var\(--fill\))", line, re.I): fail("brand violet used as text colour: " + line.strip())
    return pages

def main():
    if "--check" in sys.argv:
        with tempfile.TemporaryDirectory() as d:
            build(Path(d))
            bad = []
            for f in Path(d).rglob("*"):
                if f.is_file() and (not (OUT / f.relative_to(d)).exists() or not filecmp.cmp(f, OUT / f.relative_to(d), shallow=False)): bad.append(str(f.relative_to(d)))
            if bad: fail("site/ is out of date; run scripts/build_site.py. Differs: " + ", ".join(bad))
        print("site/ is up to date"); return
    OUT.mkdir(exist_ok=True)
    pages = build(OUT)
    print(f"built {len(pages)} pages into {OUT.relative_to(ROOT)}/ (site/mmf-auth untouched)")

if __name__ == "__main__": main()
