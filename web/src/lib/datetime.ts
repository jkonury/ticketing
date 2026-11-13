export function formatSeoul(value: string) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(value));
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value;
  const hour = Number(part('hour'));
  return `${part('year')}. ${part('month')}. ${part('day')}. ${hour < 12 ? '오전' : '오후'} ${hour % 12 || 12}:${part('minute')}`;
}
export function seoulLocalToInstant(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value))
    throw new Error('INVALID_DATETIME');
  const date = new Date(`${value}:00+09:00`);
  if (Number.isNaN(date.getTime())) throw new Error('INVALID_DATETIME');
  const local = new Date(date.getTime() + 9 * 3600000)
    .toISOString()
    .slice(0, 16);
  if (local !== value) throw new Error('INVALID_DATETIME');
  return date.toISOString();
}
export function seoulDate(date: Date) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
}
