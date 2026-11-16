#!/usr/bin/env python3
"""호스트 TCP 큐·재전송 지표를 본 부하 전후와 폭주 구간에서 수집하고 tcp-delta.json으로 요약한다.

사용:
  tcp-probe.py snapshot <출력.json>               netstat 카운터, 리슨 큐, 커널 파라미터를 한 번 기록
  tcp-probe.py sample <pid> <출력.csv> [주기초]    pid가 사라질 때까지 주기(기본 0.15초)마다 표본을 CSV로 기록
  tcp-probe.py summarize <회차 디렉터리>           tcp-pre/post.json, tcp-samples.csv, readiness.json, server-delta.json을 합쳐 tcp-delta.json 작성

카운터는 호스트 전역이라 다른 부하·트래픽이 없을 때만 회차 귀속이 의미 있다(단독 실행 전제).
이 파일은 진단 자료이며 verdict.json 판정에는 쓰지 않는다. 앱 코드·커널 설정은 읽기만 한다.
"""
import csv
import importlib.util
import struct
from pathlib import Path
import json
import os
import subprocess
import sys
import time

LISTEN_PORTS = {'gateway': 18000, 'core': 18080, 'realtime': 18081, 'fake-pg': 18090}
# 호스트 전역 카운터(/proc/net/netstat TcpExt, /proc/net/snmp Tcp)에서 남길 항목
TCPEXT_KEYS = ('ListenOverflows', 'ListenDrops', 'TCPTimeouts', 'TCPSynRetrans', 'TCPLostRetransmit',
               'TCPFastRetrans', 'TCPSlowStartRetrans', 'TCPReqQFullDrop', 'TCPReqQFullDoCookies', 'TCPAbortOnData',
               'TCPAbortOnClose', 'TCPAbortFailed', 'TCPBacklogDrop', 'TCPMinTTLDrop', 'SyncookiesSent', 'SyncookiesRecv')
TCP_KEYS = ('ActiveOpens', 'PassiveOpens', 'AttemptFails', 'EstabResets', 'RetransSegs', 'InSegs', 'OutSegs', 'OutRsts')
ESTABLISHED, SYN_RECV, LISTEN = '01', '03', '0A'


def read_pairs(path, prefix):
    """/proc/net/netstat·snmp는 헤더 줄과 값 줄이 번갈아 나온다."""
    with open(path, encoding='utf-8') as source:
        lines = source.read().splitlines()
    result = {}
    for head, values in zip(lines[::2], lines[1::2]):
        if head.startswith(prefix + ':'):
            names, numbers = head.split()[1:], values.split()[1:]
            result.update({name: int(number) for name, number in zip(names, numbers)})
    return result


def counters():
    tcpext = read_pairs('/proc/net/netstat', 'TcpExt')
    tcp = read_pairs('/proc/net/snmp', 'Tcp')
    return {**{f'TcpExt.{k}': tcpext.get(k) for k in TCPEXT_KEYS}, **{f'Tcp.{k}': tcp.get(k) for k in TCP_KEYS}}


def sockets():
    """(상태, 로컬 포트, 원격 포트, tx_queue, rx_queue) 목록. IPv4·IPv6 모두 읽는다."""
    for path in ('/proc/net/tcp', '/proc/net/tcp6'):
        try:
            with open(path, encoding='utf-8') as source:
                next(source)
                for line in source:
                    fields = line.split()
                    tx, rx = fields[4].split(':')
                    yield (fields[3], int(fields[1].rsplit(':', 1)[1], 16), int(fields[2].rsplit(':', 1)[1], 16),
                           int(tx, 16), int(rx, 16))
        except FileNotFoundError:
            continue


def listen_state():
    """리슨 소켓의 Recv-Q(현재 accept 대기)와 Send-Q(backlog 상한). ss -ltn과 같은 값이다."""
    found = {}
    for state, local, _, tx, rx in sockets():
        for name, port in LISTEN_PORTS.items():
            if state == LISTEN and local == port:
                found[name] = {'recvQ': rx, 'sendQ': None}
    # /proc/net/tcp의 리슨 소켓 tx_queue는 backlog 상한이 아니라 0이다. ss -ltn의 Send-Q(상한)는 ss로 읽는다
    try:
        out = subprocess.run(['ss', '-ltnH'], capture_output=True, text=True, timeout=5, check=False).stdout
        for line in out.splitlines():
            fields = line.split()
            for name, port in LISTEN_PORTS.items():
                if len(fields) >= 4 and fields[3].rsplit(':', 1)[-1] == str(port) and name in found:
                    found[name].update({'recvQ': int(fields[1]), 'sendQ': int(fields[2])})
    except (OSError, subprocess.SubprocessError, ValueError):
        pass
    return found


def sysctl(name):
    try:
        with open('/proc/sys/' + name.replace('.', '/'), encoding='utf-8') as source:
            return source.read().strip()
    except OSError:
        return None


def snapshot(output):
    data = {'epoch': time.time(), 'counters': counters(), 'listen': listen_state(),
            'sysctl': {key: sysctl(key) for key in ('net.core.somaxconn', 'net.ipv4.tcp_max_syn_backlog',
                                                    'net.ipv4.tcp_syncookies', 'net.ipv4.tcp_synack_retries',
                                                    'net.ipv4.tcp_syn_retries', 'net.ipv4.tcp_abort_on_overflow')}}
    with open(output, 'w', encoding='utf-8') as target:
        json.dump(data, target, ensure_ascii=False, indent=2)
        target.write('\n')


SAMPLE_COLUMNS = ['epoch', 'client_to_gateway_estab', 'gateway_to_core_estab', 'core_to_postgres_estab',
                  'gateway_recvq', 'core_recvq', 'gateway_syn_recv', 'core_syn_recv',
                  'listen_overflows', 'listen_drops', 'tcp_timeouts', 'syn_retrans', 'retrans_segs']


def sample(pid, output, interval):
    with open(output, 'w', newline='', encoding='utf-8') as target:
        writer = csv.writer(target)
        writer.writerow(SAMPLE_COLUMNS)
        while os.path.exists(f'/proc/{pid}'):
            started = time.time()
            counts = {'g': 0, 'c': 0, 'pg': 0, 'gsr': 0, 'csr': 0}
            queue = {'gateway': 0, 'core': 0}
            for state, local, remote, _, rx in sockets():
                # 클라이언트 쪽 소켓(원격 포트가 서버 포트)만 센다. 서버 쪽 소켓과 이중 집계하지 않는다
                if state == ESTABLISHED:
                    if remote == 18000:
                        counts['g'] += 1
                    elif remote == 18080:
                        counts['c'] += 1
                    elif remote == 15432:
                        counts['pg'] += 1
                elif state == SYN_RECV:
                    if local == 18000:
                        counts['gsr'] += 1
                    elif local == 18080:
                        counts['csr'] += 1
                elif state == LISTEN:
                    if local == 18000:
                        queue['gateway'] = rx
                    elif local == 18080:
                        queue['core'] = rx
            current = counters()
            writer.writerow([f'{started:.3f}', counts['g'], counts['c'], counts['pg'], queue['gateway'], queue['core'],
                             counts['gsr'], counts['csr'], current['TcpExt.ListenOverflows'], current['TcpExt.ListenDrops'],
                             current['TcpExt.TCPTimeouts'], current['TcpExt.TCPSynRetrans'], current['Tcp.RetransSegs']])
            target.flush()
            time.sleep(max(0, interval - (time.time() - started)))


def load_json(path):
    with open(path, encoding='utf-8') as source:
        return json.load(source)


def booking_window(directory):
    """기존 Gatling reader로 예매 요청 실제 시작·종료와 100ms 분포를 남긴다."""
    logs = list((Path(directory) / 'gatling-results').glob('*/simulation.log'))
    if not logs:
        return None
    if len(logs) != 1:
        raise ValueError('원시 로그가 정확히 1개여야 합니다')
    spec = importlib.util.spec_from_file_location('gatling_results', Path(__file__).with_name('gatling-results.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    reader = module.LogReader(logs[0])
    # 기존 reader로 형식을 검증한 뒤 시각을 추가 추출한다.
    durations = [duration for name, duration, _ in reader.requests() if name == 'booking_create']
    reader = module.LogReader(logs[0])
    reader.byte()
    reader.string()
    reader.string()
    epoch = struct.unpack('>q', reader.take(8))[0]
    reader.string()
    for _ in range(reader.integer()):
        reader.string()
    for _ in range(reader.integer()):
        reader.take(reader.integer())
    times = []
    while reader.offset < len(reader.data):
        kind = reader.byte()
        if kind == 1:
            reader.groups()
            name = reader.cached()
            start, end = reader.integer(), reader.integer()
            reader.byte()
            reader.cached()
            if name == 'booking_create':
                times.append((epoch + start, epoch + end))
        elif kind == 2:
            reader.take(9)
        elif kind == 3:
            reader.groups()
            reader.take(13)
        else:
            raise ValueError(f'알 수 없는 레코드: {kind}')
    if times:
        window = {'count': len(times), 'startMs': min(s for s, _ in times),
                  'lastStartMs': max(s for s, _ in times), 'endMs': max(e for _, e in times),
                  'source': 'Gatling booking_create 원시 요청 시각'}
        (Path(directory) / 'booking-window.json').write_text(json.dumps(window, ensure_ascii=False, indent=2) + '\n')
    histogram = {}
    for duration in durations:
        bucket = duration // 100 * 100
        key = f'{bucket}-{bucket + 99}ms'
        histogram[key] = histogram.get(key, 0) + 1
    return {'all': module.distribution(durations), 'histogram100ms': histogram,
            'between900And1200ms': sum(900 <= value < 1200 for value in durations),
            'atLeast1000ms': sum(value >= 1000 for value in durations)}


def summarize(directory):
    result = {'note': '호스트 전역 카운터 차분. 다른 부하가 없는 단독 실행에서만 이 회차의 값으로 해석한다. 진단용이며 verdict.json에 쓰지 않는다.'}
    pre, post = load_json(f'{directory}/tcp-pre.json'), load_json(f'{directory}/tcp-post.json')
    result['counterDelta'] = {key: (post['counters'][key] - pre['counters'][key])
                              if pre['counters'].get(key) is not None and post['counters'].get(key) is not None else None
                              for key in post['counters']}
    result['counterValid'] = all(value is None or value >= 0 for value in result['counterDelta'].values())
    result['snapshotEpochs'] = {'pre': pre['epoch'], 'post': post['epoch']}
    result['sysctl'] = pre['sysctl']
    result['listenBefore'], result['listenAfter'] = pre['listen'], post['listen']
    rows = []
    with open(f'{directory}/tcp-samples.csv', encoding='utf-8') as source:
        for row in csv.DictReader(source):
            rows.append({key: float(value) for key, value in row.items()})
    result['sampleCount'] = len(rows)
    result['latencyDistribution'] = booking_window(directory)
    burst = None
    try:
        burst = load_json(f'{directory}/booking-window.json')['startMs'] / 1000
        result['burstStartSource'] = 'booking-window.json/startMs'
    except (OSError, KeyError, ValueError):
        readiness = Path(directory) / 'readiness.json'
        burst = load_json(readiness).get('startAtEpochMs') if readiness.exists() else None
        burst = burst / 1000 if burst is not None else None
        result['burstStartSource'] = 'readiness.json/startAtEpochMs'
    result['burstStartEpoch'] = burst
    if rows:
        result['maxSampleGapSeconds'] = max((b['epoch'] - a['epoch'] for a, b in zip(rows, rows[1:])), default=0)
        result['peak'] = {key: max(row[key] for row in rows) for key in SAMPLE_COLUMNS[1:8]}
        result['samplesWithListenQueue'] = {'gateway': sum(row['gateway_recvq'] > 0 for row in rows),
                                            'core': sum(row['core_recvq'] > 0 for row in rows)}
    if rows and burst:
        before = [row for row in rows if row['epoch'] < burst]
        window = [row for row in rows if burst <= row['epoch'] <= burst + 3]
        if before:
            last = before[-1]
            result['beforeBurst'] = {key: last[key] for key in SAMPLE_COLUMNS[1:8]} | {'secondsBefore': round(burst - last['epoch'], 3)}
        if window:
            first, final = window[0], window[-1]
            result['burstWindow3s'] = {
                'peak': {key: max(row[key] for row in window) for key in SAMPLE_COLUMNS[1:8]},
                'delta': {key: final[key] - (before[-1][key] if before else first[key])
                          for key in ('listen_overflows', 'listen_drops', 'tcp_timeouts', 'syn_retrans', 'retrans_segs')}}
            # 카운터가 처음 오른 시각이 폭주 시작 후 몇 초인지(SYN 재전송 1초 가설 확인용)
            base = before[-1] if before else first
            for key in ('listen_overflows', 'syn_retrans', 'tcp_timeouts'):
                moved = [round(row['epoch'] - burst, 2) for row in window if row[key] > base[key]]
                result['burstWindow3s'][f'{key}FirstRiseSeconds'] = moved[0] if moved else None
    try:
        hikari = load_json(f'{directory}/server-delta.json')['hikariDelta']['core']
        result['hikariCore'] = hikari
    except (OSError, KeyError, ValueError):
        result['hikariCore'] = None
    with open(f'{directory}/tcp-delta.json', 'w', encoding='utf-8') as target:
        json.dump(result, target, ensure_ascii=False, indent=2)
        target.write('\n')


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else ''
    if action == 'snapshot' and len(sys.argv) == 3:
        snapshot(sys.argv[2])
    elif action == 'sample' and len(sys.argv) in (4, 5):
        sample(int(sys.argv[2]), sys.argv[3], float(sys.argv[4]) if len(sys.argv) == 5 else 0.15)
    elif action == 'summarize' and len(sys.argv) == 3:
        summarize(sys.argv[2])
    else:
        print(__doc__, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
