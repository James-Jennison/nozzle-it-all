# Google Play listing (DRAFT for owner review)

Everything here is derived from the code and the website. Anything marked **CHECK** must be confirmed in Play Console at the time, because Play's forms and policies change.

## Basics
- **App name** (30 max): `Nozzle It All` (13)
- **Short description** (80 max): `Slice 3D models on your phone and control your printers. Free and open source.` (78)
- **Category:** Tools. **Contact email:** support@nozzleitall.com. **Website:** https://nozzleitall.com. **Privacy policy URL:** https://nozzleitall.com/privacy/
- **Price:** free. **Ads:** none. **In-app purchases:** none.
- **Target audience:** adults and teens who run 3D printers; not directed at children.

## Full description (4000 max)
Nozzle It All is a free, open-source app for people who 3D print. It slices models on your phone and controls the printers you already own, over your own network.

SLICE ON YOUR PHONE
- An on-device slicing engine built from OrcaSlicer turns STL, 3MF and OBJ files into G-code. Nothing is uploaded to a cloud slicer.
- Move, rotate and scale models on a 3D plate that shows your printer's real bed, paint supports where you want them, and step through the sliced layers before you print.

CONTROL YOUR PRINTERS
- Watch temperatures and progress, start, pause and cancel prints, browse files, run macros and view the camera.
- Works with Klipper (Moonraker), Snapmaker U1, OctoPrint, PrusaLink and Bambu Lab (LAN mode). Some are confirmed on real printers and some are built from the vendors' published protocols and not yet tested on a real printer. The app and our website (nozzleitall.com/printers) say which.
- Find printers on your Wi-Fi, or reach them away from home through a service you choose, such as Tailscale.

FIND SOMETHING TO PRINT
- Browse free models from MyMiniFactory and start a project from one. The designer's credit is saved with your project.

ALERTS
- Optional notifications when a print finishes, fails or a printer goes offline. Off until you turn them on.

PRIVATE BY DESIGN
- No account, no ads, no tracking. The app sends your data to no Nozzle It All server. It talks to your printers and to MyMiniFactory when you browse or download models.

FREE AND OPEN SOURCE
- GNU AGPL-3.0-or-later, including the slicing engine. Source and licenses: nozzleitall.com/open-source

Nozzle It All is an independent project. Klipper, Moonraker, OctoPrint, PrusaLink, Bambu Lab, Snapmaker, MyMiniFactory and OrcaSlicer are names or trademarks of their owners and are used only to say what the app works with. Their mention does not imply endorsement.

## Graphics
- **Icon (512):** `brand/play-icon-512.png` (full-bleed square).
- **Screenshots:** REAL captures from the app on a real phone only (Play removes listings with misleading screenshots). Suggested set of 5: Discover results, the 3D plate with a model, the sliced preview with layer slider and stats, a printer's Home tile, the Add printer wizard. Capture with `adb exec-out screencap` on the Razr; do not use the Lovart mockups (they show a UI the app does not have).
- **Feature graphic (1024x500):** plain logo + wordmark + tagline on the dark background, no phone mockup. Not made yet.

## Data safety form (draft answers, CHECK with the current form and get a review)
- Data collected by the developer: **none**. We run no server that receives app data.
- Data shared with third parties: user-initiated requests go to **MyMiniFactory** (search terms, model downloads, sign-in). Declare per Play's rules for data sent to a third-party service; the rules on "user-initiated" transfers are subtle, so check them.
- Encryption in transit: HTTPS to MyMiniFactory; printer connections use each printer's own protocol and some local protocols are plain HTTP (the app allows cleartext for local printers).
- Data deletion: nothing is held on our side; on-device data is removed by forgetting a printer or uninstalling.
- Independent of us: if installed from Play, Google may collect anonymised crash data.

## Permission declarations Play will ask about
- `FOREGROUND_SERVICE_SPECIAL_USE` (the optional background print-alert service). Play requires a declared subtype and usually a short video showing the feature. **CHECK** the current requirement and prepare a 30-second screen recording of turning alerts on and the notification appearing.
- `POST_NOTIFICATIONS`, multicast state: routine; justify in the form.

## Other
- **Content rating:** answer the IARC questionnaire honestly (no violence, no user-generated content shared between users, internet access to a third-party model library). **CHECK.**
- **Model library content:** MyMiniFactory hosts user-submitted models; check Play's user-generated-content policy applies to how Discover presents them.
