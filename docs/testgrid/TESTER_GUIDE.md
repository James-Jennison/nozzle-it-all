# Running a Test Grid suite (for invited testers)

You need your own printer, Nozzle It All on an Android phone on the same network (or your private route to it), and
time to stay with the printer for anything at level 3 or 4. You never give anyone your printer's address or
credentials; they stay on your phone.

## Before you start

- Save and connect the printer in Nozzle as usual, with the right printer type and slicing profile (for a Centauri
  Carbon on COSMOS: type Klipper, profile "Elegoo Centauri Carbon (OpenCentauri COSMOS)", and run "Detect firmware
  now" in Edit printer).
- Clear the bed. Have calipers, and enough of one filament (two colours for the multi-material tests).
- Know where your printer's own stop control is. Test Mode's **Interrupt** stops further steps but does not stop a
  running print.

## Running it

1. **Turn Test Mode on (once per phone):** Settings → About & credits → tap the version line 7 times quickly. A note
   confirms "Test Mode is on"; the same taps turn it off. It is hidden from everyone who doesn't do this.
   Then **Settings → Test Mode.** It opens in its own window with a "TEST MODE" banner. Closing it keeps the run; you
   can resume later.
2. **Choose the printer.** Test Mode reads it (read-only) and shows model, firmware family and version, adapter,
   status and capabilities. If anything is wrong (for example a U1 saved as PAXX that reads as stock) it says so and
   won't continue. Tick "This is the printer in front of me".
   For an Elegoo Centauri Carbon on Elegoo's LAN protocol, say whether it runs stock or OpenCentauri-patched firmware;
   the printer can't report it.
3. **Choose a suite.** Suites for your printer type are listed first. You can also import a suite file a maintainer
   sent you. A suite for another firmware family won't start. That is deliberate: results are never transferred
   between firmwares.
4. **Review the plan.** Every step is listed with what it does. Choose the highest safety level you allow:
   0 slicing only, 1 read-only, 2 upload/delete one test file, 3 heaters to 60 °C / 40 °C, homing and a 5 mm move,
   4 printing. Tests above your choice are recorded as skipped.
5. **Run.** Test Mode does the automatic steps itself and stops whenever it needs you:
   - **Preconditions:** tick only what is true now. "Not met" skips the test (recorded as blocked).
   - **Approve this step:** shows the exact action (for example "Heat the active nozzle to 60 °C") and the printer
     (name, model, firmware, adapter, address). Nothing happens until you tap **Approve and send**. **Decline** skips
     it and the rest of that test.
   - **Your observation:** answer what you see or measure. "Can't observe this" is fine; it is recorded.
   - **Attach evidence:** pick a photo from your gallery. Location and camera data are removed.
   - **Outcome unknown:** the printer may or may not have received the last command. Go and look. Describe what you
     see; Test Mode reads the printer again. The command is never resent. Then you are offered the test's cleanup
     (for example heaters off), each step approved separately.
6. **Review the evidence.** Every file that will be exported is shown in full. Check that nothing identifies you.
7. **Export** to a file and send it to the maintainers the way you agreed. Then **Done** removes the run from the phone.

## If something goes wrong

- The app closes or the phone restarts: open Test Mode, **Resume run**. A command that was in flight becomes "outcome
  unknown" for you to check. Nothing is repeated, and approvals are asked for again.
- The printer does something unexpected: stop it with its own controls or Nozzle's emergency stop, then **Interrupt
  this test** and describe it in the next observation or note.

Step-by-step printed instructions for the reference suites are in [manual/](manual/). Any suite's instructions can be
generated: `./gradlew -q :test-grid:cli --args="instructions <suite-id>"`.
