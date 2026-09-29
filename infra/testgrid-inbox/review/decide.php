<?php
require __DIR__ . '/lib.php';
require_login();
if ($_SERVER['REQUEST_METHOD'] !== 'POST' || !same_origin() || !hash_equals(csrf(), (string)($_POST['csrf'] ?? ''))) { http_response_code(403); page('Refused', '<p>Refused.</p>'); }
$sha = (string)($_POST['b'] ?? '');
$decision = (string)($_POST['decision'] ?? '');
if (bundle_path($sha) === null || !in_array($decision, ['accept', 'reject', 'undecided'], true)) { http_response_code(400); page('Refused', '<p>Unknown bundle or decision.</p>'); }
save_decision($sha, ['decision' => $decision, 'note' => mb_substr(trim((string)($_POST['note'] ?? '')), 0, 2000), 'at' => gmdate('c')]);
header('Location: ' . COOKIE_PATH . '#b-' . substr($sha, 0, 12));
