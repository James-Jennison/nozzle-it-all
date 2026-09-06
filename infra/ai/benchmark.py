"""Bounded evaluation on a single captured frame; not an accuracy benchmark."""
import hashlib
import json
from pathlib import Path
import statistics
import time
import urllib.request

raw=Path('/opt/klipper-ai/evaluation-frame.jpg').read_bytes()
results=[]
for index in range(12):
    start=time.monotonic()
    request=urllib.request.Request('http://127.0.0.1:3333/detect',data=raw,
                                   headers={'Content-Type':'image/jpeg'},method='POST')
    with urllib.request.urlopen(request,timeout=15) as response:
        result=json.load(response)
    assert result['automatic_printer_actions'] is False
    assert isinstance(result['detections'],list)
    results.append({'elapsed_ms':round((time.monotonic()-start)*1000,2),
                    'inference_ms':result['inference_ms'],
                    'detections':result['detections']})
warm=results[1:]
report={'frame_sha256':hashlib.sha256(raw).hexdigest(),'image_bytes':len(raw),
        'width':result['width'],'height':result['height'],'requests':len(results),
        'cold_elapsed_ms':results[0]['elapsed_ms'],
        'warm_median_ms':statistics.median(r['elapsed_ms'] for r in warm),
        'warm_max_ms':max(r['elapsed_ms'] for r in warm),'samples':results,
        'limitations':'One frame repeated. Throughput only; not accuracy or CI-peak validation.'}
Path('/opt/klipper-ai/benchmark.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
