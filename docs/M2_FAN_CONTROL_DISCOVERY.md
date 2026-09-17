# P07 fan-control discovery

Read-only observation; no fan command or implementation acceptance.
Source: artifacts/m2-control-discovery/fan-and-override-status.json.

The current printer reports active extruder1, standard fan, fan_generic e1_fan,
e2_fan, e3_fan and cavity_fan, plus power/nozzle heater_fan objects. All sampled
fan speeds were0, bed target0, print standby and webhooks ready. Speed and flow
factors were1.0. These are timestamped observations, not persisted assumptions.

Next live fan implementation must display the exact configured object identity.
Do not infer a tool-to-fan mapping from these names alone or from the existence of
standard fan. Exclude automatic heater/controller fan objects from manual control.
Distinguish generic fans, standard fan and any vendor-specific command overrides
through capability inspection before selecting a dispatch route. A user-visible
fan selection and exact command review are required before any physical test.

Current advanced-control preview remains simulation only. No fan-control defect
in an existing live path is alleged, because that path has not been implemented.

## Owner hardware clarification

Owner identifies this printer as a Snapmaker U1 with custom firmware over stock.
GET printer/info reports software_version1.5.2.13_20260722102206; server/info
reports Moonraker1.5.2 and API1.4.0. These values do not identify the custom project
or prove compatibility with upstream fan command handlers. Exact overlay/version
is pending owner input. Evidence:u1-firmware-identity.json.

FanControls/FanPanel changes currently in the checkout are uninstalled drafts.
Do not install or physically accept them on the U1 until custom fan dispatch and
tool mapping are verified. Absence of a gcode_macro override only rules out that
kind of override; it does not prove unmodified vendor-native command behavior.
The installed APK remains the previously accepted macro-bounds build.

The draft compiles and5 focused FanControls tests passed (0 failures/errors/skips).
This is not full validation or review. Published Snapmaker source shows extended
M106/M107 fan-ID handling and separate generic-fan command/web endpoints:
https://github.com/Snapmaker/u1-klipper/blob/main/klippy/extras/fan.py
https://github.com/Snapmaker/u1-klipper/blob/main/klippy/extras/fan_generic.py
These moving source references were inspected for design context only. They have
not been established as the exact files running on the owner printer, and do not
identify the additional custom overlay. No inference of installed compatibility
is permitted from the local tests or upstream source alone.

## Exact extended release supplied by owner

Owner identified v1.5.2-paxx12-21:
https://github.com/paxx12-snapmaker-u1/SnapmakerU1-Extended-Firmware/releases/tag/v1.5.2-paxx12-21
Its vars.mk selects U1_1.5.2.13_20260722102206_upgrade.bin, matching the base version
reported by this printer. The custom tag identity is owner-supplied; API base
version alone does not independently identify an overlay build.

Release tree8d97e83f0329b72c512563a8e98305cfbdef7a18 and inspected Klipper patch
file targets are stored under artifacts/m2-fans/firmware-source. The selected
release's Klipper patch set does not patch fan.py or fan_generic.py. The newer
41-feature-generic-purifier development patch is absent from this release tree.
User-configured hooks/includes and native vendor routing still require care;
absence of a macro override is not proof of arbitrary unmodified firmware.

The previous missing-project/version blocker is resolved by the owner's links.
Continue local fan development with exact names and fresh readiness/capability
checks. Installation, device validation and each physical fan test remain
separate supervised gates. Do not infer which physical fan is e1_fan from its name.
