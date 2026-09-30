; Nozzle Test Grid fixture: G-code as sliced for Elegoo's stock Centauri Carbon firmware.
; It must never reach a Moonraker printer: Klipper has no M729, and OpenCentauri COSMOS 26.07+ emergency-stops on it.
; Test Grid never sends this file. upload_guard steps hand it to the upload path's preflight and expect a refusal.
G28
M729 ; stock-firmware nozzle clean
G1 Z5 F600
