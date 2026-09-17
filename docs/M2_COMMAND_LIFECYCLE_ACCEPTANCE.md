# M2 command interruption prerequisite

Local validation only. Full M2 and physical acceptance remain pending.

Commands now retain their coroutine job, cancel on app background, and reject
execution outside the foreground. Busy state releases when the job terminates,
including cancellation before its body starts. Interrupted sends retain an unknown
outcome message across resume and are not automatically retried. Cancellation
cannot undo a command already delivered to the printer.

Regression coverage: background before coroutine start, during fresh preflight,
and after dispatch; busy release and no replay on foreground resume.

Protected build, lint, debug/instrumentation packaging and source proof passed.
75 JVM tests passed with zero failures, errors or skips.
Evidence: evidence-cycle-510c978ae208.
Claude, Gemini and DeepSeek completed review-cycle-7136f2b1b511 with no findings.
Local-slice closure: closure-8d71302fdab6, READY.

APK SHA-256: 5c589ab82dab9126dd4951d43d42f20aa0569ae1a68a82c41d216305e6d20c22.
Preserved APK and ledger: artifacts/m2-command-lifecycle.
The owner supplied the Razr 2023. Its identity was verified before installation;
the installed APK hash matches the validated artifact. All 15 CompanionScreenTest
and ControlPreviewDeviceTest fixture UI tests passed in 29.833 seconds. These cover
confirmation/offline behavior and simulated cold/unhomed guards, not physical
control acceptance. No printer commands or printer file changes were performed.
The owner confirmed printer clearance and idle state and requires approval for each
exact live action. The Razr 2026 and Google TV were left untouched.

Implementation is supervised in the conversation. Background autonomous workers
remain stopped. Continue full M2, then M3, under these boundaries.
