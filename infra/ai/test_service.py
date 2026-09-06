import io
import unittest
from PIL import Image
from service import create_app


class FakeDetector:
    meta = None
    calls = 0
    def detect(self, *_args, **_kwargs):
        self.calls += 1
        return [('failure', 0.7, (10., 10., 5., 5.))]


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.detector = FakeDetector()
        self.client = create_app(self.detector).test_client()

    def png(self, size=(30, 20)):
        out=io.BytesIO();Image.new('RGB',size).save(out,format='PNG');return out.getvalue()

    def test_valid_image_returns_observations_without_actions(self):
        result=self.client.post('/detect',data=self.png(),content_type='image/png')
        self.assertEqual(result.status_code,200)
        self.assertEqual(result.json['width'],30)
        self.assertFalse(result.json['automatic_printer_actions'])
        self.assertEqual(self.detector.calls,1)

    def test_rejects_remote_url_instead_of_fetching(self):
        result=self.client.post('/detect',json={'img':'http://169.254.169.254/'})
        self.assertEqual(result.status_code,415)
        self.assertEqual(self.detector.calls,0)

    def test_rejects_oversized_payload(self):
        self.assertEqual(self.client.post('/detect',data=b'x'*(2*1024*1024+1),content_type='image/jpeg').status_code,413)
        self.assertEqual(self.detector.calls,0)

    def test_rejects_bad_image(self):
        self.assertEqual(self.client.post('/detect',data=b'invalid',content_type='image/png').status_code,400)

    def test_rejects_excessive_dimensions(self):
        self.assertEqual(self.client.post('/detect',data=self.png((4097,1)),content_type='image/png').status_code,400)
        self.assertEqual(self.detector.calls,0)

    def test_model_failure_is_unavailable_not_healthy(self):
        def fail(*_args,**_kwargs):raise RuntimeError('test failure')
        self.detector.detect=fail
        result=self.client.post('/detect',data=self.png(),content_type='image/png')
        self.assertEqual(result.status_code,503)
        self.assertNotIn('detections',result.json)


if __name__=='__main__':unittest.main()
