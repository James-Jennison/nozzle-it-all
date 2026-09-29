<?php
// Nozzle Test Grid inbox: Test Mode posts a finished evidence bundle here ("Send to Nozzle It All").
//
// POST https://nozzleitall.com/testgrid/submit.php
//   Content-Type: application/zip, body = the bundle exactly as exported
//   X-Nozzle-Tester: the tester ID the app made for this phone (random, "t-" + 20 URL-safe characters; names no one)
// Replies JSON: 201 {received, id, bundleDigest}; 200 when the same bundle was already received; 4xx {error}.
//
// Bundles are stored outside the web root, named by their SHA-256, never overwritten, with a small metadata file
// (time, tester ID, sizes, suite). No IP address or other request detail is kept. A bundle is only checked for
// shape here; maintainers verify it with the Test Grid CLI (BundleReader) before accepting anything.
declare(strict_types=1);

const MAX_BYTES = 40 * 1024 * 1024;
const MAX_PER_TESTER_PER_DAY = 30;
const MAX_TOTAL_PER_DAY = 300;

header('Content-Type: application/json');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function reply(int $code, array $body): void { http_response_code($code); echo json_encode($body, JSON_UNESCAPED_SLASHES); exit; }

$inbox = dirname($_SERVER['DOCUMENT_ROOT']) . '/testgrid-inbox';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') reply(405, ['error' => 'Send a bundle with POST.']);
if (!is_dir("$inbox/bundles")) reply(503, ['error' => 'The inbox is not set up.']);

// There is no login: the ID only groups a tester's bundles. An abused ID goes in blocked.txt (one per line).
$given = trim((string)($_SERVER['HTTP_X_NOZZLE_TESTER'] ?? ''));
if (!preg_match('/^t-[A-Za-z0-9_-]{16,40}$/', $given)) reply(400, ['error' => 'This version of Nozzle It All did not send a tester ID.']);
foreach (@file("$inbox/blocked.txt", FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) ?: [] as $line) {
    if (hash_equals(trim($line), $given)) reply(403, ['error' => 'Bundles from this phone are no longer accepted. Email support@nozzleitall.com.']);
}
$label = $given;

$length = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
if ($length <= 0) reply(411, ['error' => 'The request had no bundle.']);
if ($length > MAX_BYTES) reply(413, ['error' => 'The bundle is larger than 40 MB.']);

$today = gmdate('Y-m-d');
$count = 0; $total = 0;
foreach (glob("$inbox/bundles/*.json") ?: [] as $meta) {
    $m = json_decode((string)file_get_contents($meta), true);
    if (!is_array($m) || substr((string)($m['receivedAt'] ?? ''), 0, 10) !== $today) continue;
    $total++;
    if (($m['tester'] ?? '') === $label) $count++;
}
if ($count >= MAX_PER_TESTER_PER_DAY) reply(429, ['error' => 'Too many bundles today from this phone. Try again tomorrow.']);
if ($total >= MAX_TOTAL_PER_DAY) reply(429, ['error' => 'The inbox is full for today. Try again tomorrow, or email it instead.']);

@mkdir("$inbox/tmp", 0750, true);
$tmp = tempnam("$inbox/tmp", 'bundle');
$in = fopen('php://input', 'rb'); $out = fopen($tmp, 'wb');
$bytes = stream_copy_to_stream($in, $out, MAX_BYTES + 1);
fclose($in); fclose($out);
$fail = function (int $code, string $msg) use ($tmp) { @unlink($tmp); reply($code, ['error' => $msg]); };
if ($bytes === false || $bytes <= 0) $fail(400, 'The bundle could not be read.');
if ($bytes > MAX_BYTES) $fail(413, 'The bundle is larger than 40 MB.');

$zip = new ZipArchive();
if ($zip->open($tmp, ZipArchive::RDONLY) !== true) $fail(415, 'That is not a Nozzle evidence bundle (not a zip).');
$integrity = json_decode((string)$zip->getFromName('integrity.json'), true);
$evidence = json_decode((string)$zip->getFromName('evidence.json'), true);
$zip->close();
if (!is_array($integrity) || !is_array($evidence) || !preg_match('/^[0-9a-f]{64}$/', (string)($integrity['bundleDigest'] ?? '')))
    $fail(415, 'That is not a Nozzle evidence bundle (no integrity.json or evidence.json).');

$sha = hash_file('sha256', $tmp);
$dest = "$inbox/bundles/$sha.zip";
if (file_exists($dest)) { @unlink($tmp); reply(200, ['received' => true, 'duplicate' => true, 'id' => substr($sha, 0, 12), 'bundleDigest' => $integrity['bundleDigest']]); }
rename($tmp, $dest);
chmod($dest, 0440);
file_put_contents("$inbox/bundles/$sha.json", json_encode([
    'receivedAt' => gmdate('c'), 'tester' => $label, 'bytes' => $bytes, 'sha256' => $sha,
    'bundleDigest' => $integrity['bundleDigest'], 'suite' => ($evidence['suite']['id'] ?? null) . ' ' . ($evidence['suite']['version'] ?? ''),
    'printer' => trim(($evidence['target']['manufacturer'] ?? '') . ' ' . ($evidence['target']['model'] ?? '')),
], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
reply(201, ['received' => true, 'id' => substr($sha, 0, 12), 'bundleDigest' => $integrity['bundleDigest']]);
