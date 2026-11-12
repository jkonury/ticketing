export function formatSeoul(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value));
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
