#!/usr/bin/env python3
"""One HTTPServer thread; each authenticated POST computes a bounded hash chain."""
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import re
import time
import common


def main():
    config = common.read_config()
    iterations = config['iterations']
    if not isinstance(iterations, int) or not 1000 <= iterations <= 100000:
        raise ValueError('hash iterations out of range')
    deadline = config['deadlineNs']

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.0'

        def setup(self):
            super().setup()
            self.connection.settimeout(1)

        def log_message(self, *_):
            pass  # no request paths, headers, or secrets in logs

        def do_POST(self):
            arrival = time.monotonic_ns()
            request_id = self.headers.get('X-Request-ID', '')
            phase = self.headers.get('X-Phase', '')
            if (self.path != '/work' or not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + config['token'])
                    or not re.fullmatch(r'[a-z0-9-]{1,64}', request_id)
                    or phase not in ('CALIBRATION', 'WARMUP', 'BASELINE', 'TRANSITION', 'CONTENTION', 'AFTER_ACTION')
                    or self.headers.get('Content-Length', '0') != '0'):
                self.send_error(400)
                return
            if common.STOP or arrival >= deadline:
                self.send_error(503)
                return
            cpu_start = time.process_time_ns()
            digest = common.hash_work(iterations).hex()
            cpu_ns = time.process_time_ns() - cpu_start
            result = {'requestId': request_id, 'digest': digest, 'iterations': iterations,
                      'arrivalNs': arrival, 'arrivalOffsetNs': arrival - config['originNs'], 'cpuServiceNs': cpu_ns}
            body = json.dumps(result).encode()
            outcome = 'OK'
            try:
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (OSError, TimeoutError):
                outcome = 'CLIENT_DISCONNECTED'
            common.emit('service_request', phase=phase, outcome=outcome, **result)

    with HTTPServer(('127.0.0.1', 0), Handler) as server:
        server.timeout = .1
        common.emit('ready', port=server.server_port, iterations=iterations)
        while not common.STOP and time.monotonic_ns() < deadline:
            server.handle_request()
    common.emit('service_end', reason='STOPPED' if common.STOP else 'DEADLINE')


if __name__ == '__main__':
    main()
