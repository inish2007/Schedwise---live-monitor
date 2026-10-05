#!/usr/bin/env python3
"""Finite, single-threaded hashing. Progress counts only executed SHA-256 calls."""
import time
import common


def main():
    config = common.read_config()
    budget = config['hashBudget']
    if not isinstance(budget, int) or not 1000 <= budget <= 500_000_000:
        raise ValueError('hash budget out of range')
    begin = time.monotonic_ns()
    cpu_begin = time.process_time_ns()
    count = 0
    digest = bytes(32)
    last_report = begin
    common.emit('ready', hashBudget=budget)
    common.emit('worker_progress', hashes=0, hashBudget=budget, cpuNs=0, elapsedNs=0, digest=digest.hex())
    while count < budget and not common.STOP and time.monotonic_ns() < config['deadlineNs']:
        batch = min(1024, budget - count)
        digest = common.hash_work(batch, digest)
        count += batch
        now = time.monotonic_ns()
        if now - last_report >= 500_000_000:
            common.emit('worker_progress', hashes=count, hashBudget=budget, cpuNs=time.process_time_ns()-cpu_begin,
                        elapsedNs=now-begin, digest=digest.hex())
            last_report = now
    common.emit('worker_progress', hashes=count, hashBudget=budget, cpuNs=time.process_time_ns()-cpu_begin,
                elapsedNs=time.monotonic_ns()-begin, digest=digest.hex())
    common.emit('worker_end', hashes=count, outcome='COMPLETED' if count == budget else 'CANCELLED' if common.STOP else 'DEADLINE')


if __name__ == '__main__':
    main()
