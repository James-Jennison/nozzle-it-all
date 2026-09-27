# nozzle-workspace bridge, version 1.0

How Nozzle It All for Desktop hands a project to the **Advanced Workspace** (the Orca-derived specialist editor) and
takes it back safely.

Implementation:
- Desktop: `desktop/.../workspace/AdvancedWorkspace.kt`.
- Advanced Workspace: `src/slic3r/Utils/NozzleSession.*` on branch `nozzle/advanced-workspace` of the Snapmaker Orca
  fork.
- Tests: `DesktopAcceptanceTest` (workspace round trips).

## Principles

- The two programs are separate processes. Nozzle never links against, embeds or scripts Orca GUI classes.
- The only shared data is a **canonical 3MF** plus the **Nozzle manifest**, written to `Auxiliaries/Nozzle/project.json`
  with `nozzle:*` model metadata (see `project-format`). Orca-derived writers preserve both on save.
- The user's saved project is never touched until the workspace's result has been checked.

## Session

1. Nozzle saves the project, then creates `workspace-sessions/<uuid>/` in its own data folder containing:
   - a working copy of the project 3MF;
   - `session.json`:
     `{"protocol":"nozzle-workspace","version":[1,0],"sessionId","projectId","baseRevision","workingCopy","baseSha256","state":"open"}`.
2. Nozzle starts the workspace as its own process:
   `nozzle-advanced-workspace --datadir <nozzle data>/advanced-workspace <working copy>`,
   with `NOZZLE_WORKSPACE_SESSION=<session.json>` in its environment. The workspace's data folder is Nozzle's own, never
   `~/.config/OrcaSlicer` or `~/.config/Snapmaker_Orca`.
3. On each successful save the workspace updates `session.json`, keeping every existing field and setting
   `"state":"saved"`, `"savedAtMillis"` and `"savedSha256"`. It refuses (logs, no update) if the major version isn't 1.
4. When the workspace process exits, Nozzle collects the result.

## Collecting the result

| Condition | What happens |
|---|---|
| `session.json` major version ≠ 1 | Refused, with "install matching versions" |
| Working copy missing | Refused; the project is unchanged |
| Working copy byte-identical to the one handed over | Nothing to do |
| Archive incomplete or damaged (interrupted save, truncated copy) | Refused; the project is unchanged; the workspace copy is kept in the session folder |
| Newer project major version | Refused with a plain message |
| No objects | Refused |
| Manifest or `nozzle:ProjectId` names a different project | Refused |
| Manifest dropped by the workspace | Nozzle restores its own copy and tells the user to re-check materials |
| Project also changed in Nozzle since the session began | Conflict: keep both (the workspace version is saved as a new project), use the workspace version, or keep Nozzle's |
| Otherwise | The workspace version becomes the project, with revision + 1. Material slots for surviving objects are kept, new objects get slot 1, and unknown archive entries (for example Orca's own `Metadata/*.config`) are preserved. |

The new version is written atomically: a temporary file is written, read back, then renamed. So a crash never leaves a
half-written project.
