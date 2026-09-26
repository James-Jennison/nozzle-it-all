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
STATES = {"active": "Active", "released": "Available", "planned": "Planned", "deferred": "Deferred"}

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

def render(r, meta, body, base):
    def sub(text):
        text = text.replace("{{github}}", r["github"]).replace("{{email}}", r["support_email"]).replace("{{updated}}", r["updated"])
        text = text.replace("{{primary_cta}}", primary_cta(r)).replace("{{platform_cards}}", platform_cards(r))
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
