#!/usr/bin/env python3
"""Dual-upstream intake for the Advanced Workspace (docs/upstream/UPSTREAM_INTAKE.md).

Tracks two upstreams separately in the Snapmaker Orca repository:
  snapmaker  = remote "origin"   (github.com/Snapmaker/OrcaSlicer, main)   U1/Stock baseline, used only where needed
  orca       = remote "upstream" (github.com/SoftFever/OrcaSlicer, main)    general Orca improvements, ported selectively

The protected Nozzle integration branch is `nozzle/advanced-workspace`. This tool never commits to it, never pushes,
and never merges automatically. It:
  fetch      fetch both remotes (read-only for everything local)
  classify   list commits new since the last reviewed intake point, classify each by the files and words it touches,
             and write docs/upstream/candidates/<date>.md with a suggested action per commit
  prepare    cherry-pick the commits marked "candidate" onto a new branch intake/<date> in a throwaway worktree, stopping
             at the first conflict, and record exactly what applied
  promote    (reviewed step, run by a person) records the reviewed intake point after a candidate branch was merged

Classification never discards Snapmaker Flutter/cloud work: it is quarantined as "stock-only" (may improve the optional
Stock U1 adapter; must never enter the PAXX baseline) for a person to review.
"""
import argparse
import datetime
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATE = ROOT / "docs/upstream/intake-state.json"
OUT = ROOT / "docs/upstream/candidates"
DEFAULT_REPO = "/mnt/faststorage/Snapmaker-Orca/OrcaSlicer"
PROTECTED = "nozzle/advanced-workspace"
REMOTES = {"snapmaker": ("origin", "main"), "orca": ("upstream", "main")}

# Order matters: the first matching rule wins.
RULES = [
    ("stock-only", "Snapmaker Flutter/cloud/account/MQTT: quarantined; may inform the optional Stock U1 adapter only",
     ["resources/web/flutter_web", "SSWCP", "WebSMUserLogin", "src/mqtt", "Utils/MQTT", "HttpServer", "SnapLog", "WebPresetDialog", "PrinterWebView"],
     ["flutter", "cloud", "mqtt", "login", "logout", "account", "sm_user", "snapmaker id"]),
    ("paxx-relevant", "U1 LAN path (Moonraker, native send, filament sync, camera): review for the PAXX baseline",
     ["U1PrintTask", "U1LanPrintHost", "MoonrakerLive", "NativePrinterPanel", "U1SendDialog", "DeviceWorkspace", "PrinterSnapshot", "Utils/MoonRaker"],
     ["u1", "moonraker", "paxx", "toolhead", "filament sync", "rfid", "full spectrum", "mix"]),
    ("slicing", "Slicing engine: candidate; must keep G-code golden tests green on Desktop, Android and Web",
     ["src/libslic3r/"], []),
    ("profiles", "Printer/filament profiles: candidate; Android and Web bundle flattened copies",
     ["resources/profiles/"], []),
    ("workspace-ui", "Orca GUI: candidate for the Advanced Workspace only; never Nozzle's primary interface",
     ["src/slic3r/GUI/"], []),
    ("packaging", "Build/packaging: review against Nozzle identity and the NOZZLE_* options",
     ["CMakeLists.txt", "cmake/", "deps/", "scripts/", ".github/", "src/dev-utils/"], []),
    ("tests", "Tests only: candidate", ["tests/"], []),
    ("other", "Unclassified: review by hand", [], []),
]
ACTION = {"stock-only": "quarantine", "paxx-relevant": "review", "slicing": "candidate", "profiles": "candidate",
          "workspace-ui": "candidate", "packaging": "review", "tests": "candidate", "other": "review"}


def git(repo, *args, check=True):
    return subprocess.run(["git", "-C", repo, *args], check=check, capture_output=True, text=True).stdout


def load_state():
    return json.loads(STATE.read_text()) if STATE.exists() else {"reviewed": {}}


def classify_commit(repo, sha):
    files = [f for f in git(repo, "show", "--name-only", "--format=", sha).splitlines() if f]
    subject = git(repo, "show", "-s", "--format=%s", sha).strip()
    text = subject.lower()
    for name, why, paths, words in RULES:
        if any(p in f for f in files for p in paths) or any(w in text for w in words):
            return name, why, files, subject
    return "other", RULES[-1][1], files, subject


def cmd_fetch(a):
    for name, (remote, _) in REMOTES.items():
        print(f"fetching {name} ({remote})")
        subprocess.run(["git", "-C", a.repo, "fetch", "--no-tags", remote], check=True)


def cmd_classify(a):
    state = load_state()
    OUT.mkdir(parents=True, exist_ok=True)
    date = datetime.date.today().isoformat()
    lines = [f"# Upstream intake candidates, {date}", "", f"Repository: `{a.repo}`. Protected branch: `{PROTECTED}` (never changed by this tool).", ""]
    summary = {}
    for name, (remote, branch) in REMOTES.items():
        head = git(a.repo, "rev-parse", f"{remote}/{branch}").strip()
        base = state["reviewed"].get(name) or git(a.repo, "merge-base", PROTECTED, f"{remote}/{branch}").strip()
        shas = git(a.repo, "rev-list", "--reverse", "--no-merges", f"{base}..{head}").split()
        if a.limit:
            shas = shas[-a.limit:]
        lines += [f"## {name} ({remote}/{branch})", "", f"From reviewed point `{base[:10]}` to `{head[:10]}`: {len(shas)} commits.", "",
                  "| Commit | Class | Suggested action | Subject |", "|---|---|---|---|"]
        counts = {}
        records = []
        for sha in shas:
            cls, why, files, subject = classify_commit(a.repo, sha)
            counts[cls] = counts.get(cls, 0) + 1
            records.append({"sha": sha, "class": cls, "action": ACTION[cls], "subject": subject, "files": files[:50]})
            lines.append(f"| `{sha[:10]}` | {cls} | {ACTION[cls]} | {subject.replace('|', '/')[:110]} |")
        lines += ["", "Counts: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())), ""]
        summary[name] = {"base": base, "head": head, "commits": records}
    lines += ["## Classes", ""] + [f"- **{n}** ({ACTION[n]}): {why}" for n, why, _, _ in RULES]
    lines += ["", "Promotion needs a person: review the candidate branch, run `scripts/validate.sh`, the engine golden tests and the",
              "Advanced Workspace build, record each imported change in PROVENANCE.md, then merge into the protected branch."]
    report = OUT / f"{date}.md"
    report.write_text("\n".join(lines) + "\n")
    (OUT / f"{date}.json").write_text(json.dumps(summary, indent=1))
    print(f"wrote {report.relative_to(ROOT)}")


def cmd_prepare(a):
    date = datetime.date.today().isoformat()
    data = json.loads((OUT / f"{a.date or date}.json").read_text())
    branch = f"intake/{a.date or date}"
    wt = Path(a.worktrees) / branch.replace("/", "-")
    git(a.repo, "worktree", "add", "-b", branch, str(wt), PROTECTED)
    applied, stopped = [], None
    for name, rec in data.items():
        for c in rec["commits"]:
            if c["action"] != "candidate":
                continue
            r = subprocess.run(["git", "-C", str(wt), "cherry-pick", "-x", c["sha"]], capture_output=True, text=True)
            if r.returncode != 0:
                subprocess.run(["git", "-C", str(wt), "cherry-pick", "--abort"], capture_output=True)
                stopped = {"upstream": name, **c, "error": r.stderr.strip()[-400:]}
                break
            applied.append({"upstream": name, "sha": c["sha"], "subject": c["subject"]})
        if stopped:
            break
    record = {"branch": branch, "worktree": str(wt), "applied": applied, "stoppedAt": stopped}
    (OUT / f"{a.date or date}-prepare.json").write_text(json.dumps(record, indent=1))
    print(json.dumps({"branch": branch, "applied": len(applied), "stoppedAt": stopped and stopped["sha"]}, indent=1))


def cmd_promote(a):
    state = load_state()
    state["reviewed"][a.upstream] = a.sha
    state.setdefault("history", []).append({"upstream": a.upstream, "sha": a.sha, "reviewer": a.reviewer, "date": datetime.date.today().isoformat()})
    STATE.write_text(json.dumps(state, indent=1) + "\n")
    print(f"recorded {a.upstream} reviewed through {a.sha[:10]} by {a.reviewer}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--repo", default=DEFAULT_REPO)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("fetch")
    c = sub.add_parser("classify"); c.add_argument("--limit", type=int, default=0)
    pr = sub.add_parser("prepare"); pr.add_argument("--date"); pr.add_argument("--worktrees", default="/mnt/faststorage/Snapmaker-Orca")
    pm = sub.add_parser("promote"); pm.add_argument("upstream", choices=list(REMOTES)); pm.add_argument("sha"); pm.add_argument("--reviewer", required=True)
    a = p.parse_args()
    {"fetch": cmd_fetch, "classify": cmd_classify, "prepare": cmd_prepare, "promote": cmd_promote}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
