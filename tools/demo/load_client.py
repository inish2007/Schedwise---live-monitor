#!/usr/bin/env python3
"""Open-loop release schedule; missed/capacity-skipped slots remain in the evidence."""
import asyncio
import json
import time
import common


async def exchange(port, token, request_id, phase):
    reader, writer = await asyncio.open_connection('127.0.0.1', port, limit=8192)
    try:
        writer.write((f'POST /work HTTP/1.0\r\nHost: 127.0.0.1\r\nAuthorization: Bearer {token}\r\n'
                      f'X-Request-ID: {request_id}\r\nX-Phase: {phase}\r\nContent-Length: 0\r\n\r\n').encode())
        await writer.drain()
        header = (await reader.readuntil(b'\r\n\r\n')).decode('ascii')
        if not header.startswith('HTTP/1.0 200 '):
            raise ValueError('non-200 response')
        fields = dict(line.split(':', 1) for line in header.split('\r\n')[1:] if ':' in line)
        size = int(fields['Content-Length'])
        if not 0 < size <= 4096:
            raise ValueError('response exceeds bound')
        return json.loads(await reader.readexactly(size))
    finally:
        writer.close()
        # No unbounded wait_closed after request cancellation.


async def request(config, request_id, phase, scheduled):
    dispatched = time.monotonic_ns()
    result = None
    outcome = 'OK'
    try:
        result = await asyncio.wait_for(exchange(config['port'], config['token'], request_id, phase), config['timeoutMs']/1000)
        if result['requestId'] != request_id or result['iterations'] != config['iterations'] or result['digest'] != config['expectedDigest']:
            raise ValueError('response identity/work/digest mismatch')
    except asyncio.TimeoutError:
        outcome = 'TIMEOUT'
    except asyncio.CancelledError:
        outcome = 'CANCELLED'
    except (OSError, ValueError, KeyError, asyncio.IncompleteReadError, asyncio.LimitOverrunError):
        outcome = 'ERROR'
    completed = time.monotonic_ns()
    common.emit('request', requestId=request_id, phase=phase, scheduledNs=scheduled, dispatchNs=dispatched,
                completionNs=completed, dispatchDelayNs=dispatched-scheduled, latencyNs=completed-dispatched,
                outcome=outcome, deadlineMiss=outcome != 'OK' or completed-scheduled > config['deadlineMs']*1_000_000,
                response=result if outcome == 'OK' else None)


def phase_at(config, scheduled):
    for phase in config['phases']:
        if phase['startNs'] <= scheduled < phase['endNs']:
            return phase['name']
    raise ValueError('release outside phase bounds')


async def run(config):
    if not 1 <= config['rateHz'] <= 60 or not 1 <= config['maxInFlight'] <= 8 or not 100 <= config['timeoutMs'] <= 2000:
        raise ValueError('load bounds exceeded')
    start = config['phases'][0]['startNs']
    end = config['phases'][-1]['endNs']
    interval = config['intervalNs']
    if end-start > 150_000_000_000 or interval < 16_666_666:
        raise ValueError('schedule exceeds bound')
    in_flight = set()
    index = 0
    common.emit('ready', rateHz=config['rateHz'], intervalNs=interval, maxInFlight=config['maxInFlight'])
    while start + index*interval < end and not common.STOP and time.monotonic_ns() < config['deadlineNs']:
        scheduled = start + index*interval
        await asyncio.sleep(max(0, (scheduled-time.monotonic_ns())/1e9))
        if common.STOP:
            break
        phase = phase_at(config, scheduled)
        now = time.monotonic_ns()
        outcome = 'MISSED_DISPATCH' if now-scheduled > interval else 'CAPACITY_SKIP' if len(in_flight) >= config['maxInFlight'] else None
        if outcome:
            common.emit('request', requestId=f'load-{index}', phase=phase, scheduledNs=scheduled, dispatchNs=None,
                        completionNs=None, dispatchDelayNs=None, latencyNs=None, observedNs=now,
                        outcome=outcome, deadlineMiss=True, response=None)
        else:
            task = asyncio.create_task(request(config, f'load-{index}', phase, scheduled))
            in_flight.add(task)
            task.add_done_callback(in_flight.discard)
        index += 1
        if index > 6000:
            raise RuntimeError('request count bound exceeded')
    pending = list(in_flight)
    if common.STOP:
        for task in pending:
            task.cancel()
    if pending:
        await asyncio.gather(*pending)
    common.emit('client_end', scheduledCount=index, outcome='CANCELLED' if common.STOP else 'COMPLETED')


if __name__ == '__main__':
    asyncio.run(run(common.read_config()))
