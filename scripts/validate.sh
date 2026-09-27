#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Shared design tokens and terminology: generated files current, contrast targets met.
python3 scripts/generate_design_tokens.py --check
python3 scripts/artifact-proof.py prepare
# Android app, plus the shared and Desktop modules (dependency-boundary guards run as part of each module's check).
heavy-gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest \
  :domain:check :transport:check :printer-api:check :adapter-paxx:check :stock-u1-adapter:check :project-format:check :desktop:check
python3 scripts/artifact-proof.py verify
