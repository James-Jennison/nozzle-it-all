<?php
require __DIR__ . '/lib.php';
$error = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $hash = trim((string)@file_get_contents(inbox() . '/review-password'));
    usleep(300000); // slow guessing down
    if (!same_origin()) $error = 'Refused.';
    elseif ($hash !== '' && password_verify((string)($_POST['password'] ?? ''), $hash)) {
        $until = time() + SESSION_SECONDS;
        setcookie(COOKIE, $until . '.' . sign('session.' . $until), ['expires' => $until, 'path' => COOKIE_PATH, 'secure' => true, 'httponly' => true, 'samesite' => 'Strict']);
        header('Location: ' . COOKIE_PATH); exit;
    } else $error = $hash === '' ? 'No review password is set yet.' : 'Wrong password.';
}
page('Test Grid review · sign in', '<h1>Nozzle Test Grid review</h1><p class="muted">Maintainers only.</p>'
    . ($error ? '<p class="FAIL">' . e($error) . '</p>' : '')
    . '<form method="post"><p><input type="password" name="password" autocomplete="current-password" autofocus required></p><p><button>Sign in</button></p></form>');
