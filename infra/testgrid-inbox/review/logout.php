<?php
require __DIR__ . '/lib.php';
setcookie(COOKIE, '', ['expires' => 1, 'path' => COOKIE_PATH, 'secure' => true, 'httponly' => true, 'samesite' => 'Strict']);
header('Location: ' . COOKIE_PATH . 'login.php');
