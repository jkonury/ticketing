#!/usr/bin/env python3
"""Gatling 3.16.0 binary simulation.log를 읽고 요약·독립 실행 판정을 남긴다.

형식 근거: v3.16.0 LogFileDataWriter.scala / RecordHeader.scala.
알 수 없는 버전·잘린 레코드는 실패한다. HTTP 지연에 세션·pause 시간을 넣지 않는다.
"""
import argparse
import json
import math
import struct
from pathlib import Path


class LogReader:
    def __init__(self, path):
        self.data = path.read_bytes()
        self.offset = 0
        self.strings = {}

    def take(self, size):
        end = self.offset + size
        if size < 0 or end > len(self.data):
            raise ValueError("잘린 Gatling 레코드")
        result = self.data[self.offset:end]
        self.offset = end
        return result

    def integer(self):
        return struct.unpack('>i', self.take(4))[0]

    def byte(self):
        return self.take(1)[0]

    def string(self):
        size = self.integer()
        if not size:
            return ''
        value = self.take(size)
        coder = self.byte()
        if coder not in (0, 1):
            raise ValueError("알 수 없는 문자열 인코딩")
        return value.decode('latin1' if coder == 0 else 'utf-16-le')

    def cached(self):
        index = self.integer()
        if index > 0:
            self.strings[index] = self.string()
        return self.strings[abs(index)]

    def groups(self):
        return [self.cached() for _ in range(self.integer())]

    def requests(self):
        if self.byte() != 0 or self.string() != '3.16.0':
            raise ValueError('Gatling 3.16.0 로그만 지원합니다')
        self.string()  # simulation class
        self.take(8)  # run start
        self.string()  # description
        for _ in range(self.integer()):
            self.string()
        for _ in range(self.integer()):
            self.take(self.integer())  # assertion boopickle
        while self.offset < len(self.data):
            kind = self.byte()
            if kind == 1:
                self.groups()
                name = self.cached()
                start, end = self.integer(), self.integer()
                ok = self.byte() == 1
                self.cached()  # message (비밀 응답·URL을 요약에 넣지 않는다)
                if end < start:
                    raise ValueError('음수 HTTP 지연')
                yield name, end - start, ok
            elif kind == 2:
                self.take(9)
            elif kind == 3:
                self.groups()
                self.take(13)
            elif kind == 4:
                self.cached()
                self.take(4)
                raise ValueError('Gatling Error 레코드 발생')
            else:
                raise ValueError(f'알 수 없는 레코드: {kind}')


def distribution(samples):
    ordered = sorted(samples)
    return {'count': len(ordered), **{f'p{p}': ordered[math.ceil(len(ordered) * p / 100) - 1] if ordered else None
                                     for p in (50, 95, 99)}}


def enrich(directory):
    directory = Path(directory)
    logs = list((directory / 'gatling-results').glob('*/simulation.log'))
    if len(logs) != 1:
        raise ValueError('원시 로그가 정확히 1개여야 합니다')
    requests = {}
    failures = {}
    for name, duration, ok in LogReader(logs[0]).requests():
        requests.setdefault(name, []).append(duration)
        failures[name] = failures.get(name, 0) + int(not ok)
    summary = json.loads((directory / 'gatling-summary.json').read_text())
    summary['requests'] = {name: {**distribution(samples), 'failed': failures[name]} for name, samples in requests.items()}
    summary['totalRequests'] = sum(map(len, requests.values()))
    summary['failedRequests'] = sum(failures.values())
    summary['rawLog'] = str(logs[0])
    # 전체 분포는 네트워크 실패까지 포함한 Gatling 원본을 쓴다. 응답 분류 누락은 판정에서 거부한다.
    summary['bookingOutcomes']['all'] = distribution(requests.get('booking_create', []))
    (directory / 'gatling-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    return summary


def judge(directory, scenario, limit, exit_code, warmup=False, check_server=False, isolation=False, warmup_delta=True):
    directory = Path(directory)
    checks = {}
    def check(name, value):
        checks[name] = bool(value)
    def read(name):
        return json.loads((directory / name).read_text())
    try:
        summary = read('gatling-summary.json')
        outcomes, requests = summary['bookingOutcomes'], summary['requests']
        total = summary['totalRequests']
        check('toolExit', exit_code in (0, 2) if warmup else exit_code == 0)
        check('globalFailureRate', total > 0 and summary['failedRequests'] / total < .01)
        if scenario == 'coupon-drop':
            claim = requests.get('coupon_claim', {})
            counters = summary['metrics']
            check('claimOther', counters.get('claim_other', {}).get('count', 0) == 0)
            check('claimFailedRequests', claim.get('count', 0) > 0 and claim.get('failed', 1) == 0)
            if not warmup:
                check('claimP95', claim.get('p95') is not None and claim['p95'] < 1000)
            check('integrity', read('integrity.json')['ok'])
            if check_server:
                delta = read('server-delta.json')
                check('serverDelta', delta['valid'])
                controlled = delta['controlledInputChecks']
                check('controlledInputs', len(controlled) == 2 and all(c.get('ok') is True for c in controlled))
            result = {'passed': bool(checks) and all(v is True for v in checks.values()), 'warmup': warmup, 'bookingP95LimitMs': limit, 'toolExitCode': exit_code, 'checks': checks}
            (directory / 'verdict.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
            return result
        for stream in ['sse_queue'] + (['sse_seats'] if scenario == 'queue-surge' else []):
            stream_result = requests.get(stream, {})
            count = stream_result.get('count', 0)
            check(stream, count > 0 and 1 - stream_result.get('failed', count) / count > .999)
        all_booking = outcomes['all']
        check('bookingSamplesComplete', all_booking['count'] > 0 and all_booking['count'] == sum(outcomes[k]['count'] for k in ('success', 'conflict', 'rateLimited', 'other')))
        check('bookingUnexpectedRate', all_booking['count'] > 0 and outcomes['other']['count'] / all_booking['count'] < .01)
        check('successSamples', outcomes['success']['count'] > 0)
        if not warmup:
            check('bookingP95', all_booking['p95'] is not None and all_booking['p95'] < limit)
            check('successP95', outcomes['success']['p95'] is not None and outcomes['success']['p95'] < limit)
        integrity = read('integrity.json')
        check('integrity', integrity['ok'])
        if scenario == 'hot-seats':
            ready = read('readiness.json')
            expected = ready['vus']
            for key in ('registered', 'admitted', 'readyOnTime', 'bookingAttempt'):
                check(key, ready.get(key) == expected)
            check('noLate', ready.get('readyLate') == 0 and ready.get('fullContention') is True)
            check('bookingRequestCount', all_booking['count'] == expected)
            check('no429', outcomes['rateLimited']['count'] == 0)
        if scenario == 'seat-conflict-refund':
            check('compensation', read('compensation.json').get('ok') is True)
        if scenario != 'seat-conflict-refund':
            counters = summary['metrics']
            success = outcomes['success']['count']
            for name in ('flow_booking_created', 'flow_payment_requested', 'booking_confirmed'):
                check(name, counters.get(name, {}).get('count', 0) == success)
            check('dbConfirmed', integrity['info']['bookingsByStatus'].get('CONFIRMED', 0) == success)
            check('noPending', integrity['info']['bookingsByStatus'].get('PENDING', 0) == 0)
        if check_server:
            delta = read('server-delta.json')
            check('serverDelta', delta['valid'])
            controlled = delta['controlledInputChecks']
            check('controlledInputs', len(controlled) == 2 and all(c.get('ok') is True for c in controlled))
        if isolation:
            check('isolation', read('isolation.json')['isolated'])
            if warmup_delta:  # 재기동 진단 계열은 누적 카운터가 초기화돼 워밍업 구간 차분을 낼 수 없다
                warm_delta = read('server-delta-warmup.json')
                check('warmupDelta', warm_delta['valid'] and len(warm_delta['controlledInputChecks']) == 2 and all(c.get('ok') is True for c in warm_delta['controlledInputChecks']))
    except (OSError, ValueError, KeyError, TypeError, ZeroDivisionError) as error:
        checks['requiredArtifacts'] = False
        checks['artifactError'] = str(error)
    result = {'passed': bool(checks) and all(v is True for v in checks.values()), 'warmup': warmup, 'bookingP95LimitMs': limit, 'toolExitCode': exit_code, 'checks': checks}
    (directory / 'verdict.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['enrich', 'judge'])
    parser.add_argument('directory')
    parser.add_argument('--scenario', default='hot-seats')
    parser.add_argument('--limit', type=int, default=300)
    parser.add_argument('--exit-code', type=int, default=0)
    parser.add_argument('--warmup', action='store_true')
    parser.add_argument('--server', action='store_true')
    parser.add_argument('--isolation', action='store_true')
    parser.add_argument('--no-warmup-delta', action='store_true')
    args = parser.parse_args()
    if args.action == 'enrich':
        enrich(args.directory)
        return 0
    result = judge(args.directory, args.scenario, args.limit, args.exit_code, args.warmup, args.server, args.isolation, not args.no_warmup_delta)
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
