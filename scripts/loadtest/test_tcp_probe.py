"""TCP 차분·폭주 경계·Gatling 시각 추출 회귀 검사."""
import csv
import importlib.util
import json
import struct
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('tcp_probe', Path(__file__).with_name('tcp-probe.py'))
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)


class TcpProbeTest(unittest.TestCase):
    def test_리슨_포트에_payment_앱이_포함된다(self):
        self.assertEqual(PROBE.LISTEN_PORTS['payment'], 18085)
        listening = [(PROBE.LISTEN, 18085, 0, 0, 3), (PROBE.LISTEN, 18080, 0, 0, 1)]
        original = PROBE.sockets
        PROBE.sockets = lambda: listening
        try:
            found = PROBE.listen_state()
        finally:
            PROBE.sockets = original
        self.assertEqual(found['payment']['recvQ'], 3)
        self.assertEqual(found['core']['recvQ'], 1)

    def test_실제_예매_시각과_1초_분포를_추출한다(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / 'gatling-results' / 'run' / 'simulation.log'
            log.parent.mkdir(parents=True)
            integer = lambda value: struct.pack('>i', value)
            string = lambda value: integer(len(value)) + value.encode('latin1') + (b'\0' if value else b'')
            data = b'\0' + string('3.16.0') + string('test') + struct.pack('>q', 100000) + string('') + integer(0) + integer(0)
            for index, duration in enumerate([250, 999, 1100, 1300]):
                data += b'\1' + integer(0)
                data += integer(1) + string('booking_create') if index == 0 else integer(-1)
                data += integer(1000 + index) + integer(1000 + index + duration) + b'\1'
                data += integer(2) + string('') if index == 0 else integer(-2)
            log.write_bytes(data)
            result = PROBE.booking_window(directory)
            self.assertEqual(result['between900And1200ms'], 2)
            self.assertEqual(result['atLeast1000ms'], 2)
            window = json.loads((root / 'booking-window.json').read_text())
            self.assertEqual(window['startMs'], 101000)
            self.assertEqual(window['endMs'], 102303)

    def test_폭주_직전_표본과_카운터_차분과_Hikari를_결합한다(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pre = {'epoch': 1, 'counters': {'TcpExt.ListenOverflows': 100}, 'listen': {}, 'sysctl': {}}
            post = {**pre, 'epoch': 12, 'counters': {'TcpExt.ListenOverflows': 103}}
            for name, value in [('tcp-pre.json', pre), ('tcp-post.json', post),
                                ('booking-window.json', {'startMs': 10000}),
                                ('server-delta.json', {'hikariDelta': {'core': {'acquireCount': 25}}})]:
                (root / name).write_text(json.dumps(value))
            with (root / 'tcp-samples.csv').open('w') as target:
                writer = csv.DictWriter(target, fieldnames=PROBE.SAMPLE_COLUMNS)
                writer.writeheader()
                for epoch, overflows, connections in [(9.9, 100, 20), (10.1, 103, 30), (11, 103, 40)]:
                    row = dict.fromkeys(PROBE.SAMPLE_COLUMNS, 0)
                    row.update(epoch=epoch, listen_overflows=overflows, gateway_to_core_estab=connections)
                    writer.writerow(row)
            PROBE.summarize(directory)
            result = json.loads((root / 'tcp-delta.json').read_text())
            self.assertEqual(result['counterDelta']['TcpExt.ListenOverflows'], 3)
            self.assertEqual(result['beforeBurst']['gateway_to_core_estab'], 20)
            self.assertEqual(result['burstWindow3s']['delta']['listen_overflows'], 3)
            self.assertEqual(result['hikariCore']['acquireCount'], 25)
            self.assertTrue(result['counterValid'])
