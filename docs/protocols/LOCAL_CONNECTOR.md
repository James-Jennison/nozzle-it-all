# nozzle-connector protocol, version 1.0

Lets **Nozzle It All Web**, open in a browser, reach printers through **Nozzle It All for Desktop** running on the
same computer. It exists because browsers restrict what a secure web page may contact on a local network:

- Safari blocks plain-HTTP and `ws://` connections from an HTTPS page, even to local addresses.
- Chrome and Edge ask the user for local-network access, and the printer's Moonraker must list the page's origin in
  `cors_domains`.
- Firefox's handling is still changing.

(Evidence and sources: `docs/family/WEB_SLICING_RESEARCH.md`.)

The connector is optional. Web slicing, projects and export never need it. It runs on the user's own computer, only
talks to printers the user added in Desktop, and relays nothing through Nozzle.

Implementation: `desktop/src/main/kotlin/com/nozzleitall/desktop/connector/LocalConnector.kt` (server) and
`web/src/printers/connector.ts` (client). Tests: `LocalConnectorTest`.

## Transport and security

| Rule | Why |
|---|---|
| Listens on `127.0.0.1:47321` only | Never reachable from the network |
| Off until the user turns it on in Desktop → Settings | No surprise listener |
| `Origin` must exactly match an allowed Nozzle web origin, or the request gets 403 | Other websites can't use it |
| `Host` must be `127.0.0.1`, `localhost` or `::1`, or the request gets 421 | DNS-rebinding defence |
| Every route except `hello` and `pair` needs `Authorization: Bearer <token>` | A page can't act without the user pairing it |
| Pairing code: 6 digits shown in Desktop, valid for 2 minutes and one use | Proof of presence at both ends |
| Tokens are 256-bit random values; only their SHA-256 is stored, in an owner-only file | A leaked settings file doesn't leak access |
| Replies are `Cache-Control: no-store` and `X-Content-Type-Options: nosniff` | Nothing is cached or sniffed |
| CORS answers only the requesting allowed origin, and answers Chrome's `Access-Control-Request-Private-Network` preflight | Works with Chrome's local-network permission |

Allowed origins (v1.0): `https://app.nozzleitall.com`, `https://nozzleitall.com`, plus `http://localhost` and
`http://127.0.0.1` on ports 5173 and 4173 for development.

## Endpoints

| Method and path | Auth | Result |
|---|---|---|
| `GET /v1/hello` | – | `{"protocol":"nozzle-connector","version":[1,0],"app":"…","paired":bool}` |
| `POST /v1/pair` `{"code":"123456"}` | – | `{"token":"…"}` or 403 |
| `GET /v1/printers` | ✓ | `{"printers":[{id,name,model,firmware,address}]}`. No secrets. |
| `GET /v1/printers/{id}/status` | ✓ | Printer status in the shared model (same encoding as the adapter protocol) |
| `GET /v1/printers/{id}/snapshot` | ✓ | JPEG camera still |
| `POST /v1/printers/{id}/upload?name=nozzle/x.gcode` (body: file bytes) | ✓ | `{"result":"uploaded"\|"interrupted"\|"failed", …}` |
| `POST /v1/printers/{id}/action` `{"action":{…}}` | ✓ | `{"outcome":"accepted"\|"rejected"\|"unknown","reason":…}` |

Actions: `start` (path, toolheadMap), `pause`, `resume`, `cancel`, `home`, `nozzleTemperature` (toolhead, celsius) and
`bedTemperature` (celsius).

The person confirms in the browser. Desktop then runs the action through its own `ActionGuard`: it re-reads the printer,
refuses if the state changed, executes once, and blocks further commands after an unknown outcome until the state has
been re-checked.

## Versioning

A client refuses a `hello` with a different major version and tells the user to update both apps. Minor versions may
add fields and endpoints only.
