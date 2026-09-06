"""Private evaluation API. Image bytes in, detector observations out; no printer controls.

Uses the unchanged Obico ONNX pre/post-processing from the pinned vendor tree.
Service source is supplied with the deployment; upstream AGPL license is retained.
"""
import io
import os
import sys
import time
import warnings

import cv2
import numpy as np
import onnxruntime as ort
from flask import Flask, jsonify, request
from PIL import Image, UnidentifiedImageError
from werkzeug.exceptions import RequestEntityTooLarge

sys.path.insert(0, '/opt/klipper-ai/vendor/ml_api')
from lib.onnx import OnnxNet
from lib.meta import Meta

MAX_BYTES = 2 * 1024 * 1024
MAX_PIXELS = 8_000_000
Image.MAX_IMAGE_PIXELS = MAX_PIXELS


def decode_image(raw):
    if not raw or len(raw) > MAX_BYTES:
        raise ValueError('Image must contain 1 to 2097152 bytes.')
    with warnings.catch_warnings():
        warnings.simplefilter('error', Image.DecompressionBombWarning)
        with Image.open(io.BytesIO(raw)) as source:
            if source.format not in ('JPEG', 'PNG'):
                raise ValueError('Only JPEG and PNG images are accepted.')
            if source.width * source.height > MAX_PIXELS or max(source.size) > 4096:
                raise ValueError('Image dimensions exceed evaluation limits.')
            if getattr(source, 'n_frames', 1) != 1:
                raise ValueError('Animated images are not accepted.')
            return cv2.cvtColor(np.asarray(source.convert('RGB')), cv2.COLOR_RGB2BGR)


def load_detector():
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    net = OnnxNet.__new__(OnnxNet)
    net.session = ort.InferenceSession('/opt/klipper-ai/model.onnx', sess_options=options,
                                      providers=['CPUExecutionProvider'])
    net.meta = Meta('/opt/klipper-ai/vendor/ml_api/model/model.meta')
    return net


def create_app(detector=None):
    detector = detector if detector is not None else load_detector()
    app = Flask(__name__)
    app.config.update(MAX_CONTENT_LENGTH=MAX_BYTES)

    @app.errorhandler(RequestEntityTooLarge)
    def too_large(_error):
        return jsonify(error='Image exceeds 2 MiB.'), 413

    @app.get('/health')
    def health():
        return jsonify(status='ready', backend='onnx-cpu', mode='evaluation',
                       automatic_printer_actions=False)

    @app.post('/detect')
    def detect():
        if request.mimetype not in ('image/jpeg', 'image/png'):
            return jsonify(error='Send raw JPEG or PNG bytes.'), 415
        try:
            image = decode_image(request.get_data(cache=False))
        except (ValueError, UnidentifiedImageError, OSError, Image.DecompressionBombWarning,
                Image.DecompressionBombError):
            return jsonify(error='Invalid image or unsupported dimensions.'), 400
        started = time.monotonic()
        try:
            boxes = detector.detect(detector.meta, image, None, thresh=0.08)
        except Exception:
            app.logger.error('Detector failed; no result is available.')
            return jsonify(error='Inference unavailable.'), 503
        return jsonify(detections=boxes, inference_ms=round((time.monotonic()-started)*1000, 2),
                       width=image.shape[1], height=image.shape[0], mode='evaluation',
                       automatic_printer_actions=False)

    return app
