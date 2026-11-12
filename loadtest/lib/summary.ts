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
