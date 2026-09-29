<?php
// The maintainers' view of bundles testers sent from Test Mode: newest first, with results, answers, measurements and
// photos, and an Accept / Reject decision. Accepted bundles are filed into the evidence store and the compatibility
// report by scripts/testgrid_process_inbox.sh; nothing counts until then.
require __DIR__ . '/lib.php';
require_login();

$filter = (string)($_GET['show'] ?? 'open');
$decisions = decisions();
$metas = [];
foreach (glob(inbox() . '/bundles/*.json') ?: [] as $f) {
    $m = json_decode((string)file_get_contents($f), true);
    if (is_array($m) && isset($m['sha256'])) $metas[] = $m;
}
usort($metas, fn($a, $b) => strcmp((string)$b['receivedAt'], (string)$a['receivedAt']));

$counts = ['open' => 0, 'accept' => 0, 'reject' => 0];
foreach ($metas as $m) { $d = $decisions[$m['sha256']]['decision'] ?? 'undecided'; $counts[$d === 'undecided' ? 'open' : $d]++; }

$html = '<h1>Nozzle Test Grid review</h1><p class="muted">Bundles testers sent from Test Mode. Accepting records your decision here; '
    . 'accepted bundles count once they are filed into the evidence store and the report. '
    . '<a href="logout.php">Sign out</a></p><p>'
    . '<a href="?show=open">To review (' . $counts['open'] . ')</a> · <a href="?show=accept">Accepted (' . $counts['accept'] . ')</a> · '
    . '<a href="?show=reject">Rejected (' . $counts['reject'] . ')</a> · <a href="?show=all">All (' . count($metas) . ')</a></p>';

$csrf = csrf();
$shown = 0;
foreach ($metas as $m) {
    $sha = $m['sha256'];
    $dec = $decisions[$sha] ?? ['decision' => 'undecided'];
    $state = $dec['decision'] === 'undecided' ? 'open' : $dec['decision'];
    if ($filter !== 'all' && $filter !== $state) continue;
    $shown++;
    $path = bundle_path($sha);
    $b = $path ? read_bundle($path) : ['error' => 'The bundle file is missing.'];
    $ev = $b['evidence'] ?? [];
    $t = $ev['target'] ?? [];
    $fw = $t['firmware'] ?? [];
    $html .= '<div class="card" id="b-' . e(substr($sha, 0, 12)) . '">';
    $html .= '<h2 style="font-size:1.1rem;margin:0">' . e(trim(($t['manufacturer'] ?? '') . ' ' . ($t['model'] ?? ''))) . ' <span class="muted">· '
        . e(($fw['family'] ?? '') . ' ' . ($fw['version'] ?? '')) . '</span></h2>';
    $html .= '<p class="muted wrap">Received ' . e($m['receivedAt']) . ' · tester <code>' . e($m['tester']) . '</code> · suite ' . e($m['suite'])
        . ' · level ' . e($ev['run']['maxSafetyLevel'] ?? '?') . ' · Nozzle ' . e($ev['producer']['version'] ?? '?') . ' (' . e(substr((string)($ev['producer']['sourceRevision'] ?? ''), 0, 8)) . ')'
        . ' · ' . e(round(($m['bytes'] ?? 0) / 1048576, 1)) . ' MB · bundle <code>' . e(substr((string)$m['bundleDigest'], 0, 12)) . '</code>'
        . ' · kind ' . e($t['kind'] ?? '?') . '</p>';
    if (isset($b['error'])) { $html .= '<p class="FAIL">' . e($b['error']) . '</p>'; }
    elseif ($b['problems']) { $html .= '<p class="FAIL">Integrity: ' . e(implode('; ', $b['problems'])) . '. Do not accept.</p>'; }
    else { $html .= '<p class="PASS">Integrity: every file matches its recorded SHA-256 (full verification when filed).</p>'; }
    if (($t['kind'] ?? '') === 'simulated') $html .= '<p class="PARTIAL">Simulated run: it grades nothing.</p>';

    $html .= '<table><tr><th>Test</th><th>Result</th><th>Details</th></tr>';
    foreach (($ev['tests'] ?? []) as $test) {
        $r = (string)($test['result'] ?? '');
        $details = [];
        if (isset($test['carriedFrom'])) $details[] = 'passed earlier (run ' . e(substr((string)$test['carriedFrom']['runId'], 0, 8)) . ')';
        elseif ($r !== 'PASS' && ($test['reason'] ?? '') !== '') $details[] = e($test['reason']);
        foreach (($test['steps'] ?? []) as $s) {
            $resp = $s['data']['response'] ?? null;
            if (($s['kind'] ?? '') === 'observe' && $resp !== null) {
                $details[] = '<span class="' . e($s['status'] === 'PASSED' ? 'PASS' : ($s['status'] === 'FAILED' ? 'FAIL' : 'muted')) . '">' . e($s['id']) . '</span>: '
                    . e($resp) . e(isset($s['data']['unit']) ? ' ' . $s['data']['unit'] : '') . (isset($s['data']['note']) ? ' <span class="muted">(' . e($s['data']['note']) . ')</span>' : '');
            }
        }
        $photos = '';
        foreach (($test['evidence'] ?? []) as $a) {
            $url = 'photo.php?b=' . urlencode($sha) . '&f=' . urlencode((string)$a['file']);
            $photos .= '<a href="' . e($url) . '" target="_blank" rel="noopener"><img loading="lazy" src="' . e($url) . '" alt="' . e($a['id']) . '"></a>';
        }
        $html .= '<tr><td>' . e($test['title'] ?? $test['id']) . '<br><span class="muted">' . e(($test['category'] ?? '') . ' · ' . ($test['scope'] ?? '')) . '</span></td>'
            . '<td class="' . e($r) . '">' . e($r) . '</td><td class="wrap">' . implode('<br>', $details) . ($photos ? '<br>' . $photos : '') . '</td></tr>';
    }
    $html .= '</table>';

    $status = ['open' => 'Not reviewed', 'accept' => 'Accepted', 'reject' => 'Rejected'][$state];
    $html .= '<form method="post" action="decide.php" style="margin-top:10px"><input type="hidden" name="csrf" value="' . e($csrf) . '"><input type="hidden" name="b" value="' . e($sha) . '">'
        . '<p><strong>' . e($status) . '</strong>' . (isset($dec['at']) ? ' <span class="muted">' . e($dec['at']) . '</span>' : '') . '</p>'
        . '<p><textarea name="note" rows="2" style="width:100%" placeholder="Note (why, corrections, what to recheck)">' . e($dec['note'] ?? '') . '</textarea></p>'
        . '<p><button name="decision" value="accept">Accept</button> <button name="decision" value="reject">Reject</button>'
        . ($state !== 'open' ? ' <button name="decision" value="undecided">Undo</button>' : '') . '</p></form>';
    $html .= '</div>';
}
if ($shown === 0) $html .= '<p class="muted">Nothing here.</p>';
page('Test Grid review', $html);
