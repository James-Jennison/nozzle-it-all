#!/usr/bin/env python3
"""Files the owner's review decisions (nozzleitall.com/testgrid/review/) into the Test Grid evidence.

For every bundle decided on the review page and not yet recorded in docs/testgrid/evidence/acceptance.json:
  accepted -> copied to GTHost, verified, filed read-only in ~/testgrid-evidence/bundles, and added to the ledger;
  rejected -> added to the ledger's rejected list with the note as the reason.
Then rebuilds the evidence index, the compatibility report and the website's compatibility page. It changes files in
this checkout only; commit, PR and site deploy stay separate steps. Read-only on the web server.

Run from the repository root on a branch: python3 scripts/testgrid_process_inbox.py [--dry-run]
"""
import json, subprocess, sys, tempfile, datetime
from pathlib import Path

WEB, INBOX = "website-vm-admin", "/home/jamesjen/testgrid-inbox"
GT, SRC = "jjennison@100.87.130.114", "testgrid-build/src"
LEDGER = Path("docs/testgrid/evidence/acceptance.json")
DRY = "--dry-run" in sys.argv

def run(cmd, **kw): return subprocess.run(cmd, check=True, text=True, capture_output=True, **kw).stdout

def main():
    decisions = json.loads(run(["ssh", WEB, f"cat {INBOX}/decisions.json 2>/dev/null || echo '{{}}'"]) or "{}")
    ledger = json.loads(LEDGER.read_text())
    known = {e["bundleDigest"] for e in ledger["accepted"]} | {e["bundleDigest"] for e in ledger.get("rejected", [])}
    todo = []
    with tempfile.TemporaryDirectory() as tmp:
        for sha, d in sorted(decisions.items(), key=lambda kv: kv[1].get("at", "")):
            if d.get("decision") not in ("accept", "reject"): continue
            meta = json.loads(run(["ssh", WEB, f"cat {INBOX}/bundles/{sha}.json"]))
            if meta["bundleDigest"] in known: continue
            todo.append((sha, d, meta))
        if not todo: print("Nothing new to file."); return
        for sha, d, meta in todo:
            print(f'{d["decision"]:7} {meta["receivedAt"]}  {meta["printer"]}  {meta["suite"]}  bundle {meta["bundleDigest"][:12]}  tester {meta["tester"]}')
        if DRY: print("(dry run)"); return
        for sha, d, meta in todo:
            date = d.get("at", datetime.datetime.utcnow().isoformat())[:10]
            if d["decision"] == "accept":
                local = Path(tmp) / f"{sha}.zip"
                run(["scp", "-q", f"{WEB}:{INBOX}/bundles/{sha}.zip", str(local)])
                run(["ssh", GT, "mkdir -p testgrid-evidence/incoming"])
                run(["scp", "-q", str(local), f"{GT}:testgrid-evidence/incoming/{sha}.zip"])
                out = run(["ssh", GT, f"cd {SRC} && export ANDROID_HOME=/opt/android-sdk && ./gradlew --no-daemon -q :test-grid:cli "
                           f"--args='store-add --store /home/jjennison/testgrid-evidence/bundles /home/jjennison/testgrid-evidence/incoming/{sha}.zip' 2>&1 | tail -1 "
                           f"&& chmod 444 /home/jjennison/testgrid-evidence/bundles/{meta['bundleDigest']}.nozzle-evidence.zip"])
                if not (out.startswith("ADDED") or out.startswith("PRESENT") or "already" in out.lower()):
                    sys.exit(f"Filing {sha[:12]} failed: {out.strip()}")
                ledger["accepted"].append({"acceptedBy": "owner (review page)", "bundleDigest": meta["bundleDigest"], "date": date,
                    "note": (d.get("note") or "Accepted on the review page.") + f' Sent by tester {meta["tester"]} ({meta["printer"]}, {meta["suite"].strip()}).'})
            else:
                ledger.setdefault("rejected", []).append({"bundleDigest": meta["bundleDigest"], "date": date,
                    "reason": (d.get("note") or "Rejected on the review page.") + f' Sent by tester {meta["tester"]}.'})
    LEDGER.write_text(json.dumps(ledger, indent=2, sort_keys=True) + "\n")
    run(["rsync", "-a", "--delete", "--exclude", ".git", "--exclude", "build", "--exclude", ".gradle", "--exclude", ".cxx", "--exclude", "node_modules", "--exclude", ".kotlin", "./", f"{GT}:{SRC}/"])
    g = f"cd {SRC} && export ANDROID_HOME=/opt/android-sdk && ./gradlew --no-daemon -q :test-grid:cli --args="
    run(["ssh", GT, g + "'index --bundles /home/jjennison/testgrid-evidence/bundles --location gthost-build01:~/testgrid-evidence/bundles --out docs/testgrid/evidence/index.json'"])
    run(["ssh", GT, g + "'report --bundles /home/jjennison/testgrid-evidence/bundles --ledger docs/testgrid/evidence/acceptance.json --markdown docs/testgrid/COMPATIBILITY_REPORT.md --json docs/testgrid/compatibility-report.json'"])
    for f in ["docs/testgrid/evidence/index.json", "docs/testgrid/COMPATIBILITY_REPORT.md", "docs/testgrid/compatibility-report.json"]:
        run(["scp", "-q", f"{GT}:{SRC}/{f}", f])
    run(["python3", "scripts/build_site.py"])
    print(f"Filed {len(todo)} decision(s); ledger, index, report and site/compatibility rebuilt. Review, commit, PR, then scripts/deploy_site.sh --go.")

if __name__ == "__main__": main()
