# CPU detector evaluation

This is a private evaluation of Obico's existing failure-detection model, not an installation of the full Obico website/account stack. It is separate from the Android app. No continuous monitoring, notifications, printer commands or automatic pause are implemented here.

## Deployment identity

- Existing administrative SSH alias: `pve-sv1-admin`, strict host-key verification.
- Only new VM 300, `klipper-ai-01`: 4 vCPUs, 8 GiB RAM, 100 GiB disk.
- Service: `klipper-ai.service`, guest loopback `127.0.0.1:3333`.
- Service account: `klipperai`, no login; 2-core CPU quota, 3 GiB memory cap, loopback-only process networking.
- Guest management: QEMU guest agent through the existing Proxmox control connection. No guest SSH credentials or public listener.
- New VM does not start automatically on host boot (`onboot=0`) during evaluation. It is protected against accidental deletion.
- Existing VMs 201–206 and template 9000 retain their captured configuration/firewall hashes and status.

## Reproducible procedure

The live guest already exists. Do not rerun provisioning to update it.

1. `bash infra/ai/provision.sh` creates VM 300 only when it and the provisioning state are absent; it refuses existing state. It captures protected VM baselines first. A failed/partial operation requires inspection, not blind replay.
2. `python3 infra/ai/guest.py cloud-init status --long` checks bootstrap. Source cloud-init schema is validated separately. The initial guest recorded a recoverable warning for an empty ssh_genkeytypes list; that line was removed from source and the Proxmox snippet. The historical warning was not erased or relabeled as success. Guest SSH services are independently checked inactive.
3. `python3 infra/ai/deploy-guest.py` transfers a fixed file set with a verified SHA-256 manifest, and starts a bounded installer for a newly staged directory. An identical staged payload returns without replaying the installer; divergent files are refused. If an earlier install failed, inspect its unit/logs before a specific recovery action.
4. Inspect `klipper-ai-install.service` through guest.py. Provisioning can take several minutes. A launched process is not installation success.
5. Confirm `/health` is ready, then run `python3 infra/ai/seal-network.py`. This removes temporary download rules from this VM only. New inbound/outbound connections are denied; management over the guest agent and guest loopback inference continue to work. Updating packages later requires a separately scoped temporary maintenance rule change.
6. `python3 infra/ai/validate.py` checks installed source/model/vendor identity, resource limits, listener boundaries, API tests, final VM firewall policy and unchanged protected VM hashes/status.

## Detector inputs and outputs

`POST /detect` accepts raw JPEG/PNG bytes, at most 2 MiB, 8 million pixels, and 4096 pixels on either side. Responses contain detector bounding boxes/confidences and inference duration. These are model observations, not a calibrated probability of print failure. Empty detections do not certify a safe print. Invalid input returns an error; inference failure returns unavailable rather than an empty successful result.

`GET /health` confirms the model loaded and the worker responds. It does not attest accuracy or a functioning notification path.

The API accepts no image URLs, has no printer-control client, and has no external listener. A permanent printer/network integration is outside this evaluation.

## Provenance and source availability

- Upstream: https://github.com/TheSpaghettiDetective/obico-server
- Exact commit: `49c0bc7001a3fd8d56297fc3032ba287bfe1d50b`.
- Unmodified vendor source and its AGPL-3.0 license remain in `/opt/klipper-ai/vendor`. Our evaluation source is also supplied in `/opt/klipper-ai` and this directory. No upstream code was copied into the Android application.
- Model: upstream's `model-weights-5a6b1be1fa.onnx`, SHA-256 `0a6ebd8e30dbf6a450c50f9c0a5406f04ba7eb1c99fd5996e888c78bb383b9aa`. This is a recorded download hash, not a publisher signature or independent model audit. The installer now requires that exact hash.
- Python 3.12 CPU runtime; all 15 selected Linux x86_64 wheels are version/hash pinned in `requirements.lock`, installed with `--require-hashes`. The lock is platform-specific.
- No paid API, GPU, provider account or secret configuration is needed for this isolated test.

## Evaluation evidence, 2026-09-06

- Actual 1920 × 1080 printer JPEG, 163,350 bytes; 12 requests of one captured frame.
- First request: 209.41 ms. Warm median: 181.08 ms. Warm maximum: 182.66 ms.
- Repeat after hash-verified reinstall and network sealing: first 212.23 ms, warm median 180.68 ms, warm maximum 182.80 ms.
- Peak service memory: 650,194,944 bytes (about 620 MiB).
- Six API tests passed; hash-verified package reinstall completed successfully; repeat manifest staging did not replay installation.
- After network sealing, TCP connection attempts to public ports 80/443 and a runner's port 22 were blocked/unreachable. Direct validation confirmed final firewall policy and loopback-only listeners.
- No failure detections on this single frame. This proves basic inference operation and a latency sample, not detection accuracy, failure recall, false-positive rate, continuous uptime or performance at peak CI load.
- Evaluation frame is private in the guest with mode 0600. It is not a repository artifact or a reviewer attachment.

## Formal acceptance boundary

Change: `klipper-ai-vm-20260906`.

Direct authorized runtime validation passes. The machine-global broker's runtime gate failed twice *before* connecting to Proxmox with `Bad owner or permissions on /etc/ssh/ssh_config.d/20-systemd-ssh-proxy.conf`. Both immutable failures are retained and classified as environment failures. No SSH permission bypass or system configuration change was used. Unresolved finding `klipper-ai-evidence-transport` blocks formal closure; direct checks do not substitute for the required gate.

Read-only diagnosis: the running broker's user namespace differs from the interactive process, with UID/GID maps containing only `1000 1000 1`. Root UID 0 is unmapped there. The referenced system SSH configuration resolves to a root-owned mode-0644 file in the shared mount namespace. This explains why the broker's SSH sees unacceptable ownership while ordinary SSH succeeds. Correcting machine-global broker execution/isolation needs a separate maintenance scope; weakening SSH checks or altering the valid system file is not a remedy.

Initial review: `review-cycle-a7982cd8a4e6` (Claude/DeepSeek), supplemental independent Gemini `review-cycle-45bdb18a7b12`. Three DeepSeek findings were remediated: post-bootstrap egress, artifact hashes, and explicit manifest deployment. Targeted review: `review-cycle-d8200a77f99f` (all three; read durable status for outcome).

All three targeted reviewers completed. Claude and Gemini reported no new findings. DeepSeek repeated the known evidence transport blocker, requested active egress probes (added and directly validated), and raised unsigned model provenance (recorded as an accepted LOW risk for private evaluation only). No publisher signature or production authenticity guarantee is claimed.

Final recorded closure `closure-54220cbd99f9`: **NOT_READY**, solely because `klipper-ai-evidence-transport` remains unresolved. Convergence `convergence-12bc5810edaa`. Final direct validation passed all six API tests, source/model/vendor checks, resource limits, loopback listeners, active egress probes and protected-VM hashes/status. The updated validator is registered for a future broker rerun after the execution context is repaired.

The detector process may remain running and idle in its isolated guest while the formal gate is unresolved. This is not production acceptance, an app feature release or an active print-monitoring service.
