// handleSummary를 쓰면 k6 기본 요약 출력이 사라지므로 지표별 한 줄 요약을 대신 만든다.
// (--summary-export JSON은 handleSummary와 무관하게 그대로 만들어진다)
interface MetricSummary {
  values?: Record<string, number>;
  thresholds?: Record<string, { ok: boolean }>;
}

export type SummaryData = { metrics: Record<string, MetricSummary> };

function format(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(3);
}

export function compactSummary(data: SummaryData): string {
  const lines = ['=== 요약 ==='];
  for (const name of Object.keys(data.metrics).sort()) {
    const metric = data.metrics[name];
    const values = Object.entries(metric.values ?? {})
      .map(([key, value]) => `${key}=${format(value)}`)
      .join(' ');
    const thresholds = Object.entries(metric.thresholds ?? {})
      .map(([expression, result]) => `${result.ok ? '통과' : '미달'}(${expression})`)
      .join(' ');
    lines.push(`${name}: ${values}${thresholds ? `  [threshold ${thresholds}]` : ''}`);
  }
  return `${lines.join('\n')}\n`;
}

// 시나리오 B의 준비·시작 현황. 기록되지 않은 지표(k6가 표본 없는 카운터를 내보내지 않는다)는 null이며 0으로 채우지 않는다.
export function readinessReport(data: SummaryData, vusCount: number, startAt: number | undefined) {
  const count = (name: string): number | null => data.metrics[name]?.values?.count ?? null;
  const readyOnTime = count('flow_ready_on_time');
  const readyLate = count('flow_ready_late');
  return {
    vus: vusCount,
    startAtEpochMs: startAt ?? null,
    startAtIso: startAt === undefined ? null : new Date(startAt).toISOString(),
    registered: count('flow_registered'),
    admitted: count('flow_admitted'),
    readyOnTime,
    readyLate,
    bookingAttempt: count('flow_booking_attempt'),
    // 정시 준비 인원이 VU 수에 못 미치는 사용자(등록·입장 실패 또는 지각). 조용히 제외하지 않고 그대로 남긴다.
    notReadyOnTime: readyOnTime === null ? null : vusCount - readyOnTime,
    fullContention: readyOnTime === vusCount && readyLate === 0,
  };
}
