#!/usr/bin/env python3
"""Exercise the real HTTP collector and prove sensitive controller fields are excluded."""
import importlib.util
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import time

spec = importlib.util.spec_from_file_location('monitor', Path(__file__).with_name('soak-monitor.py'))
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)
secret = 'do-not-export-this-sentinel'


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        assert self.path == '/api/control'
        assert self.headers['Authorization'] == 'Bearer ' + secret
        body = {'networkKey': secret, 'controller': {'joinCommand': secret},
                'bots': [{'id': 'a', 'name': 'Tester', 'online': True, 't': int(time.time() * 1000),
                          'health': 20, 'food': 20, 'settings': {'secret': secret}}],
                'operations': {'a': {'details': {'held': 'false', 'runtime': '{ticks=3, recoveries=1}', 'secret': secret}}},
                'actions': [{'id': 'order', 'description': secret, 'results': [{'bot': 'a', 'stage': 'completed', 'message': secret}]}]}
        self.send_response(200)
        self.end_headers()
        self.wfile.write(json.dumps(body).encode())


server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with tempfile.TemporaryDirectory() as folder:
        output = Path(folder)
        summary = monitor.run(f'http://127.0.0.1:{server.server_port}/api/control', secret, output, 0, 1)
        assert summary['samples'] == 1 and summary['terminalOrders']['completed'] == 1
        assert all(secret not in path.read_text() for path in output.iterdir())
finally:
    server.shutdown()
    server.server_close()
print('Live soak monitor passed: authenticated polling, outcome metrics and explicit secret exclusion')
