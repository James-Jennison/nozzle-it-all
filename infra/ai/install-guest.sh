#!/usr/bin/env bash
set -euo pipefail
test "$(hostname)" = klipper-ai-01
cd /opt/klipper-ai
test ! -e vendor
git init -q vendor
git -C vendor remote add origin https://github.com/TheSpaghettiDetective/obico-server.git
git -C vendor fetch -q --depth 1 origin 49c0bc7001a3fd8d56297fc3032ba287bfe1d50b
git -C vendor checkout -q --detach FETCH_HEAD
test "$(git -C vendor rev-parse HEAD)" = 49c0bc7001a3fd8d56297fc3032ba287bfe1d50b
python3 -m venv venv
venv/bin/pip install --only-binary=:all: --require-hashes -r requirements.lock > dependency-install.log 2>&1
venv/bin/pip check
venv/bin/pip freeze > dependencies.lock.txt
curl --fail --silent --show-error --location --max-time 300 --proto '=https' --proto-redir '=https' \
  https://tsd-pub-static.s3.amazonaws.com/ml-models/model-weights-5a6b1be1fa.onnx -o model.onnx
sha256sum model.onnx > model.sha256
printf '%s  model.onnx\n' '0a6ebd8e30dbf6a450c50f9c0a5406f04ba7eb1c99fd5996e888c78bb383b9aa' | sha256sum --check --status
id klipperai >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin klipperai
chmod 0755 /opt/klipper-ai
chmod 0644 model.onnx service.py
install -m 0644 klipper-ai.service /etc/systemd/system/klipper-ai.service
systemd-analyze verify /etc/systemd/system/klipper-ai.service
systemctl daemon-reload
systemctl enable --now klipper-ai
