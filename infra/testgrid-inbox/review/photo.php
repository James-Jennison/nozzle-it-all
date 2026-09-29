<?php
require __DIR__ . '/lib.php';
require_login();
$path = bundle_path((string)($_GET['b'] ?? ''));
$file = (string)($_GET['f'] ?? '');
if ($path === null || !preg_match('#^attachments/[a-z0-9._-]+/[a-z0-9._-]+\.(jpg|jpeg|png)$#', $file)) { http_response_code(404); exit; }
$zip = new ZipArchive();
if ($zip->open($path, ZipArchive::RDONLY) !== true) { http_response_code(404); exit; }
$data = $zip->getFromName($file); $zip->close();
if ($data === false) { http_response_code(404); exit; }
header('Content-Type: ' . (str_ends_with($file, '.png') ? 'image/png' : 'image/jpeg'));
header('Content-Security-Policy: default-src \'none\'');
echo $data;
