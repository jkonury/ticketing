#!/usr/bin/env python3
"""k6와 호스트의 1초 간격 자원 표본을 CSV로 남긴다."""
import csv
import os
import sys
import time

pid, output = int(sys.argv[1]), sys.argv[2]
ticks = os.sysconf('SC_CLK_TCK')
page_size = os.sysconf('SC_PAGE_SIZE')


def sample():
    with open(f'/proc/{pid}/stat', encoding='utf-8') as source:
        fields = source.read().rsplit(')', 1)[1].split()
    process_ticks = int(fields[11]) + int(fields[12])
    rss = int(fields[21]) * page_size
    with open('/proc/stat', encoding='utf-8') as source:
        cpu = [int(value) for value in source.readline().split()[1:]]
    with open('/proc/meminfo', encoding='utf-8') as source:
        memory = {line.split(':')[0]: int(line.split()[1]) * 1024 for line in source if line.startswith(('MemTotal:', 'MemAvailable:'))}
    return time.time(), process_ticks, rss, sum(cpu[:8]), cpu[3] + cpu[4], memory


with open(output, 'w', newline='', encoding='utf-8') as target:
    writer = csv.writer(target)
    writer.writerow(['epoch', 'k6_cpu_cores', 'k6_rss_bytes', 'host_cpu_busy_fraction', 'host_memory_total_bytes', 'host_memory_available_bytes'])
    previous = sample()
    while True:
        time.sleep(1)
        try:
            current = sample()
        except (FileNotFoundError, ProcessLookupError):
            break
        elapsed = current[0] - previous[0]
        total_delta = current[3] - previous[3]
        writer.writerow([current[0], (current[1] - previous[1]) / ticks / elapsed, current[2],
                         1 - (current[4] - previous[4]) / total_delta if total_delta else '',
                         current[5]['MemTotal'], current[5]['MemAvailable']])
        target.flush()
        previous = current
