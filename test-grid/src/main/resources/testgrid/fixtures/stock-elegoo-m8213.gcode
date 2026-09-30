; Nozzle Test Grid fixture: G-code from an older Elegoo/OpenCentauri stock-firmware profile.
; It must never reach a Moonraker printer: COSMOS 26.07+ emergency-stops on M8213.
; Test Grid never sends this file. upload_guard steps hand it to the upload path's preflight and expect a refusal.
G28
M8213 ; older stock-firmware command
G1 Z5 F600
