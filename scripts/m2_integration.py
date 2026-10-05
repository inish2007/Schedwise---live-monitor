#!/usr/bin/env python3
"""Explicit live M2 acceptance run through an already running, authenticated backend."""
import argparse
import hashlib
import io
import json
import pathlib
import time
import urllib.error
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--core', type=int)
    args = parser.parse_args()
    token = (ROOT/'data/session-token').read_text().strip()
    def api(path, method='GET', body=None):
        headers = {'Authorization': 'Bearer '+token, 'Origin': 'http://127.0.0.1:5173'}
        if body is not None:
            headers['Content-Type'] = 'application/json'
        request = urllib.request.Request('http://127.0.0.1:8080/api/'+path, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        with urllib.request.urlopen(request, timeout=10) as response:
            content = response.read()
            return content if path.endswith('/export') else json.loads(content)
    caps = api('capabilities')
    latest = api('snapshots/latest')
    assert latest['sampleAgeMillis'] < 3500 and latest['snapshot']['cpus']['cpu']['busyPercent']['value'] is not None
    experiment = api('experiments', 'POST', {} if args.core is None else {'core': args.core})
    identifier = experiment['id']
    observations = []
    failure = None
    archive = None
    try:
        until = time.monotonic()+180
        previous = None
        while time.monotonic() < until:
            status = api('experiments/'+identifier)
            observations.append({'at': time.time_ns(), 'state': status['state'], 'phase': status['phase'],
                                 'requestEvidenceCount': status.get('requestEvidenceCount'), 'finished': status['finished']})
            if (status['state'], status['phase']) != previous:
                print(status['state'], status['phase'], flush=True)
                previous = status['state'], status['phase']
            if status['finished']:
                break
            time.sleep(1)
        else:
            api('experiments/'+identifier+'/stop', 'POST', {})
            raise AssertionError('run exceeded deadline')
        archive = api('sessions/'+identifier+'/export')
        evidence_dir = ROOT/'docs/evidence'
        evidence_dir.mkdir(exist_ok=True)
        archive_path = evidence_dir/f'm2-{identifier}.zip'
        archive_path.write_bytes(archive)
        with zipfile.ZipFile(io.BytesIO(archive)) as capture:
            events = [json.loads(line) for line in capture.read('events.jsonl').splitlines()]
            samples = [json.loads(line) for line in capture.read('samples.jsonl').splitlines()]
            supervisor = json.loads(capture.read('supervisor.json'))
        assert status['state'] == 'COMPLETED', status.get('reason')
        assert status['cleanup']['verified'] and not status['coordinatorAlive']
        assert all(not child['alive'] for child in status['javaCleanup'])
        assert status['collectorAffinityRestored']
        params = status['parameters']
        assert params['baselineSeconds'] >= 30 and params['contentionSeconds'] >= 30
        assert len([e for e in events if e['event'] == 'parameters_frozen']) == 1
        requests = [e['payload'] for e in events if e['event'] == 'child_event' and e['payload']['event'] == 'request']
        assert len(requests) == len({r['requestId'] for r in requests})
        by_schedule = sorted(requests, key=lambda r: r['scheduledNs'])
        assert all(b['scheduledNs']-a['scheduledNs'] == params['intervalNs'] for a,b in zip(by_schedule,by_schedule[1:]))
        value = bytes(32)
        buffer = bytes(range(256))*4
        for _ in range(params['iterations']): value = hashlib.sha256(value+buffer).digest()
        assert value.hex() == params['expectedDigest']
        successful = [r for r in requests if r['outcome'] == 'OK']
        assert successful and all(r['response']['digest'] == value.hex() for r in successful)
        assert all(r['response']['cpuServiceNs'] > 0 for r in successful)
        assert status['contentionCohortVerified']
        assert len(status['workers']) == 2
        assert all(w['progress']['hashes'] > 0 and w['hashesPerSecond']['value'] > 0 for w in status['workers'])
        assert samples and any(row['phaseAtCollection'] == 'CONTENTION' for row in samples)
        assert len(status['javaCleanup']) == 4
        outcome = {'id': identifier, 'testedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),
                   'kind': 'RECORDED', 'originalKind': 'MEASURED', 'status': status, 'observations': observations,
                   'archive': str(archive_path.relative_to(ROOT)), 'archiveSha256': hashlib.sha256(archive).hexdigest(),
                   'sampleCount': len(samples), 'rawRequestCount': len(requests), 'kernel': caps['sources']['kernel']['value']}
        (ROOT/'docs/m2-integration-evidence.json').write_text(json.dumps(outcome, indent=2)+'\n')
        print(json.dumps({'state': status['state'], 'summaries': status['summaries'],
                          'workerHashesPerSecond': [w['hashesPerSecond'] for w in status['workers']],
                          'cleanupVerified': status['cleanup']['verified'], 'capture': str(archive_path)}, indent=2))
    finally:
        current = api('experiments/'+identifier)
        if not current['finished']:
            api('experiments/'+identifier+'/stop','POST',{})
            for _ in range(12):
                if api('experiments/'+identifier)['finished']: break
                time.sleep(1)


if __name__ == '__main__':
    main()
