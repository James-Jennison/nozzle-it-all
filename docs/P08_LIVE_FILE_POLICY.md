# Live file destination policy — unique names approved

Current production FileTransfer is read-only. Existing mutation prototypes deliberately remain test-only. Read-only verification on 2026-09-08 confirms the U1 gcodes root is writable.

Moonraker documents that move/rename replaces an existing destination. Its documented upload and move parameters contain no atomic create-only or conditional-write option. A client listing check cannot prevent another client creating the destination between checking and sending. The simulator revision guard does not exist in the documented live API.

Proposed app-only first implementation: upload and rename to generated unique destination filenames, show the complete destination in the confirmation, reject observed collisions, never automatically overwrite or retry an uncertain write, and reconcile with downloaded content hashes. This reduces ordinary concurrent name collisions without claiming a server-enforced no-overwrite guarantee. Preserve existing-file download/import/export. Delete remains separately confirmed and blocks loaded/active files; exact source-change races likewise need explicit treatment before live delivery.

Alternative: preserve exact requested destination names with an explicit replacement warning and owner acceptance of the concurrent-write risk. Strict atomic no-replace would require a verified server capability or a separately authorized server change; neither has been established on this firmware.

Owner selected the recommended unique-destination filenames on 2026-09-08. Initial live upload/rename is being implemented under that policy. Live deletion remains separate unfinished scope. No strict server-side no-overwrite guarantee is claimed.

Reference: https://moonraker.readthedocs.io/en/latest/external_api/file_manager/#move-a-file-or-directory
