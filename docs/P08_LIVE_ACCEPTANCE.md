# Unique-destination live file uploads and renames

## Current result: installed and accepted

Final r3 installed on Motorola Razr 2023, with installed APK SHA256 dfb9db027d3f2bc3503d948432420059dcc13b8c65457ee6b8957fa753edc2c9 matching the reviewed build. Source manifest a4360b36638e945f105b6590e98f4ff1be4bb50e29c627e9b7fc02742b2762a7. Controlled validation passes 129 JVM tests, lint, both APK builds and source proof. Phone acceptance passes 21 fixture/regression tests plus the real U1 upload-and-rename test. Normal MainActivity entry and persisted Verified rename notice were visually checked. All disposable test files were hash-checked before cleanup and their absence verified. No print was started or queued.

Evidence: artifacts/p08-live/acceptance-r3.json, installed-identity-r3.json, device-fixtures-r3.log, live-hardware-r3.log, live-file-final-proof.json, normal-app-verified.png and cleanup-*.json. Controlled evidence evidence-2a4653ce680b. Grok/Gemini final no findings in review-cycle-f07a78f5b422. Claude identified an outstanding rename verification gap; the successful live rename satisfied it and Claude confirmed resolved/no findings in review-cycle-9f9ae4c8d44e. Closure closure-7c56ab7a7b74: READY for this upload/rename scope.

The earlier r2 live test exposed a real protocol mismatch missed by mocks and reviewers: upload returns a raw HTTP201 JSON acknowledgement, while ordinary API/move responses use a result wrapper. R3 handles the raw response only for uploads, updates the fixture to the actual contract, and passes an interrupted-request-body/no-retry regression. Failed r2 runs remain preserved. Their files were independently reconciled by content hash, then individually removed; the requests were never automatically replayed.

Earlier pending statements below are historical and superseded by this current result. Full P08 deletion and full M2 remain unfinished.

Owner approved automatically unique destination names. Files > Printer file changes exposes Upload and Rename, shows the exact printer and full generated destination, and requires a separate confirmation. Uploads consume the imported/downloaded temporary workspace and freeze its contents during review. The server is never asked to start a print. Live delete remains unavailable; this is not full P08 or M2 completion.

The transport checks idle/ready and active/loaded-file state, rejects observed destination collisions, checks source content before rename, sends no automatic retries or redirects, and verifies destination bytes using SHA256 after acknowledgement. A single-use draft expires after two minutes. Lifecycle, disconnect/state change and edits cancel or invalidate pending work. A pending outcome receipt is persisted before dispatch; uncertain outcomes identify the destination for inspection rather than reporting success.

Protocol limits: UUID names reduce ordinary collision risk, but Moonraker provides no atomic no-replace or conditional rename. Another client can change a path between the final client check and the server operation. The exact destination and rename source-change warning are shown before confirmation. No server changes or general overwrite path were added.

Initial controlled validation failed on a trailing lambda binding after the test factory parameter was introduced; the close argument was made explicit. The corrected full local validation passed 128 JVM tests, lint, both APKs and source proof (artifacts/p08-live/validation-v2.log). This earlier pass does not validate later source refinements.

Initial review cycle review-cycle-6d1f1a98e5f4 completed: Grok and Claude no findings; Gemini raised multipart subdirectory handling and receipt replacement. Both concerns were addressed by explicit path/leaf multipart fields and atomic receipt replacement. The subdirectory fixture now verifies the destination hash and final receipt. Initial package submission review-cycle-a52406c32e69 failed before review because its phase name was unsupported; that record is preserved.

Final r2 evidence and review are in progress. Installation and device/live acceptance are not yet claimed. Current evidence: evidence-cycle-ccb1c061e616; review: review-cycle-a68a41a9afc3. Opt-in LiveFileHardwareTest requires approved_live_files=true and uses comments-only disposable filenames. It never starts a print.
