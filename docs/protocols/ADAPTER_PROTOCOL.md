# nozzle-adapter protocol, version 1.0

How Nozzle It All talks to a printer adapter that runs as its own helper process. Today that is only the optional
Stock U1 adapter (`nozzle-stock-u1-adapter`). The PAXX adapter runs in process and never uses this protocol.

Implementation: `printer-api/src/main/kotlin/com/nozzleitall/printer/external/` (`AdapterProtocol`,
`ExternalAdapterClient` for the host side, `AdapterServer` for the helper side). Tests: `AdapterProtocolTest`.

## Why a separate process

An adapter that may use a vendor cloud, a vendor SDK or Flutter must not be able to crash, block or change the
behaviour of the core application. A helper process gives that isolation mechanically:

- The core has no compile-time dependency on the helper. Removing the helper removes every cloud and Flutter
  requirement; the PAXX product is unchanged.
- The helper is started on first use of a printer that needs it, never at application start
  (`AdapterRegistry.registerOptional`). Scanning for printers never starts it.
- If the helper exits, hangs or speaks a different major version, only the printers it serves go Offline, with a
  plain reason. PAXX printers keep working.
- `AdapterRegistry.registerBuiltIn` refuses any adapter that declares `mayUseVendorCloud = true`.

## Transport

- Newline-delimited UTF-8 JSON on the helper's stdin (requests) and stdout (replies). One JSON object per line;
  lines over 8 MiB are dropped.
- The helper must write nothing else to stdout. Logs go to stderr, which the host forwards to its own log without
  credentials.
- Readers ignore fields they do not recognise. Enum values travel by name; an unrecognised name decodes to a safe
  default (state `UNKNOWN`, action outcome `unknown`), never to success.

## Handshake

1. The helper writes its hello first:
   `{"type":"hello","protocol":"nozzle-adapter","version":[1,0],"adapter":{"id":"stock-u1","displayName":"…","firmware":["STOCK_U1"],"mayUseVendorCloud":true,"version":"0.1.0"}}`
2. The host checks `protocol` and the major version. A different major version is refused with a message telling
   the user to install matching versions; the helper is stopped.
3. The host answers with its own hello: `{"type":"hello","protocol":"nozzle-adapter","version":[1,0],"host":"nozzle-it-all"}`.
   A helper that sees a different major version exits.

Minor versions may only add optional fields and methods. A method the other side does not know is answered with
error code `unsupported`.

## Requests and replies

Request: `{"type":"request","id":<int>,"method":"<name>","params":{…}}`
Reply: `{"type":"response","id":<same>,"result":…}` or `{"type":"response","id":<same>,"error":{"code":"…","message":"…"}}`

Requests may be answered out of order; the host matches by `id`. The host applies a timeout per request (20 s by
default, 75 s for `perform`, 30 min for `upload`).

| Method | Params | Result |
|---|---|---|
| `probe` | `address` | discovered printer or `null` |
| `open` | `config` (identity, adapterId, secret, extras) | `session`, `capabilities` |
| `status` | `session` | printer status |
| `cameras` | `session` | array of cameras |
| `snapshot` | `session`, `camera` | `jpegBase64` |
| `upload` | `session`, `path` (local file), `remoteName` | `result`: `uploaded` / `interrupted` / `failed`, `remotePath` or `reason` |
| `perform` | `session`, `action` | `outcome`: `accepted` / `rejected` / `unknown`, `reason` |
| `close` | `session` | `{}` |
| `account.status` / `account.signIn` / `account.signOut` | – | account state |
| `account.complete` | `response` (what the user brought back from the browser) | account state |
| `shutdown` | – | `{}`, then the helper exits |

Shapes of status, camera, action and account objects are defined by the encoders in `AdapterProtocol.kt`; the round
trip of every action kind is tested.

## Safety rules that cross the boundary

- `perform` executes an action exactly once. The helper never retries.
- A timeout or lost reply is reported to the user as **unknown**, and `ActionGuard` refuses further commands to that
  printer until a fresh status reading has been taken after the failure.
- Confirmation happens in the host (`ActionGuard`), before `perform` is sent. The helper never prompts.

## Account state

`NOT_REQUIRED`, `SIGNED_OUT`, `SIGNING_IN`, `SIGNED_IN`, `EXPIRED`, `UNAVAILABLE`. These are vendor-neutral: the
core only knows that an optional adapter wants a sign-in, never which vendor or how. An expired or unavailable
account affects only the printers that adapter serves.
