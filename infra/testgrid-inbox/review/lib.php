<?php
// Shared by the Test Grid review pages. Maintainers only: a password (bcrypt hash in testgrid-inbox/review-password,
// set with scripts/testgrid_review_password.sh) gives a signed cookie limited to /testgrid/review/, valid 12 hours.
// .htaccess is disabled on this server, so the login lives here. Nothing here changes or deletes a received bundle.
declare(strict_types=1);
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Referrer-Policy: no-referrer');

const COOKIE = 'ng_review';
const COOKIE_PATH = '/testgrid/review/';
const SESSION_SECONDS = 12 * 3600;

function inbox(): string { return dirname($_SERVER['DOCUMENT_ROOT']) . '/testgrid-inbox'; }

function secret(): string {
    $f = inbox() . '/review-secret';
    if (!is_file($f)) { $old = umask(077); file_put_contents($f, bin2hex(random_bytes(32))); umask($old); }
    return trim((string)file_get_contents($f));
}

function sign(string $data): string { return hash_hmac('sha256', $data, secret()); }

function signed_in(): bool {
    $c = (string)($_COOKIE[COOKIE] ?? '');
    if (!preg_match('/^(\d+)\.([0-9a-f]{64})$/', $c, $m)) return false;
    return (int)$m[1] > time() && hash_equals(sign('session.' . $m[1]), $m[2]);
}

function csrf(): string { return sign('csrf.' . ($_COOKIE[COOKIE] ?? '')); }

function same_origin(): bool {
    $origin = (string)($_SERVER['HTTP_ORIGIN'] ?? '');
    return $origin === '' || $origin === 'https://nozzleitall.com';
}

function require_login(): void {
    if (signed_in()) return;
    header('Location: ' . COOKIE_PATH . 'login.php');
    exit;
}

function decisions(): array {
    $d = json_decode((string)@file_get_contents(inbox() . '/decisions.json'), true);
    return is_array($d) ? $d : [];
}

function save_decision(string $sha, array $entry): void {
    $f = inbox() . '/decisions.json';
    $h = fopen($f, 'c+');
    flock($h, LOCK_EX);
    $d = json_decode((string)stream_get_contents($h), true);
    if (!is_array($d)) $d = [];
    $d[$sha] = $entry;
    ftruncate($h, 0); rewind($h);
    fwrite($h, json_encode($d, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
    flock($h, LOCK_UN); fclose($h);
}

function bundle_path(string $sha): ?string {
    if (!preg_match('/^[0-9a-f]{64}$/', $sha)) return null;
    $p = inbox() . "/bundles/$sha.zip";
    return is_file($p) ? $p : null;
}

function e($s): string { return htmlspecialchars((string)$s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8'); }

function page(string $title, string $body): void {
    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
        . '<meta name="robots" content="noindex,nofollow"><title>' . e($title) . '</title><style>'
        . ':root{color-scheme:light dark;--bg:#fff;--fg:#1a1a1a;--muted:#666;--line:#ddd;--pass:#1b7f3b;--fail:#b3261e;--part:#9a6700;--card:#f6f6f6}'
        . '@media (prefers-color-scheme:dark){:root{--bg:#111;--fg:#eee;--muted:#aaa;--line:#333;--pass:#6fd08c;--fail:#ff8a80;--part:#e3b341;--card:#1c1c1c}}'
        . 'body{font:15px/1.5 system-ui,sans-serif;background:var(--bg);color:var(--fg);margin:0 auto;max-width:1000px;padding:16px}'
        . 'h1{font-size:1.4rem}table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}'
        . '.muted{color:var(--muted)}.PASS{color:var(--pass);font-weight:600}.FAIL{color:var(--fail);font-weight:600}.PARTIAL,.BLOCKED,.UNVERIFIED{color:var(--part);font-weight:600}'
        . '.card{background:var(--card);border-radius:10px;padding:12px 16px;margin:12px 0}img{max-width:260px;border-radius:8px;margin:4px}'
        . 'button,input,textarea{font:inherit}button{padding:6px 14px;border-radius:8px;border:1px solid var(--line);cursor:pointer}'
        . 'code{font-size:.9em}.wrap{overflow-wrap:anywhere}</style></head><body>' . $body . '</body></html>';
    exit;
}

/** Evidence and integrity of one bundle; checks every listed file's SHA-256 against integrity.json. */
function read_bundle(string $path): array {
    $zip = new ZipArchive();
    if ($zip->open($path, ZipArchive::RDONLY) !== true) return ['error' => 'Not a readable zip.'];
    $integrity = json_decode((string)$zip->getFromName('integrity.json'), true) ?: [];
    $evidence = json_decode((string)$zip->getFromName('evidence.json'), true) ?: [];
    $problems = [];
    foreach (($integrity['files'] ?? []) as $name => $sha) {
        $data = $zip->getFromName($name);
        if ($data === false) $problems[] = "missing $name";
        elseif (!hash_equals((string)$sha, hash('sha256', $data))) $problems[] = "$name changed";
    }
    for ($i = 0; $i < $zip->numFiles; $i++) {
        $n = (string)$zip->getNameIndex($i);
        if ($n !== 'integrity.json' && !isset($integrity['files'][$n])) $problems[] = "unlisted $n";
    }
    $zip->close();
    return ['evidence' => $evidence, 'integrity' => $integrity, 'problems' => $problems];
}
