import Link from 'next/link';
import { formatSeoul, seoulLocalToInstant } from '@/lib/datetime';
import { listAuditLogs } from '@/server/api/admin';
import { withSessionRedirect } from '@/server/auth/require-user';

type Query = {
  action?: string;
  actorId?: string;
  from?: string;
  to?: string;
  cursor?: string;
};

function toNumber(value?: string) {
  const number = value ? Number(value) : NaN;
  return Number.isFinite(number) ? number : undefined;
}
function toInstant(value?: string) {
  if (!value) return undefined;
  try {
    return seoulLocalToInstant(value);
  } catch {
    return undefined;
  }
}

export default async function Page({
  searchParams,
}: {
  searchParams: Promise<Query>;
}) {
  const query = await searchParams;
  const page = await withSessionRedirect(() =>
    listAuditLogs({
      action: query.action || undefined,
      actorId: toNumber(query.actorId),
      from: toInstant(query.from),
      to: toInstant(query.to),
      cursor: toNumber(query.cursor),
    }),
  );
  const next = new URLSearchParams();
  for (const key of ['action', 'actorId', 'from', 'to'] as const)
    if (query[key]) next.set(key, query[key]);
  if (page.nextCursor !== null) next.set('cursor', String(page.nextCursor));
  return (
    <main>
      <h1>감사 로그</h1>
      <form>
        <label>
          액션 <input name="action" defaultValue={query.action ?? ''} />
        </label>
        <label>
          행위자 ID <input name="actorId" defaultValue={query.actorId ?? ''} />
        </label>
        <label>
          시작{' '}
          <input
            type="datetime-local"
            name="from"
            defaultValue={query.from ?? ''}
          />
        </label>
        <label>
          종료{' '}
          <input
            type="datetime-local"
            name="to"
            defaultValue={query.to ?? ''}
          />
        </label>
        <button>조회</button>
      </form>
      <table>
        <thead>
          <tr>
            <th>일시</th>
            <th>행위자</th>
            <th>액션</th>
            <th>대상</th>
            <th>상세</th>
          </tr>
        </thead>
        <tbody>
          {page.items.map((log) => (
            <tr key={log.id}>
              <td>{formatSeoul(log.occurredAt)}</td>
              <td>{log.actorId ?? '-'}</td>
              <td>{log.action}</td>
              <td>
                {log.targetType} {log.targetId}
              </td>
              <td>
                <code>{JSON.stringify(log.detail ?? {})}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {page.nextCursor !== null && (
        <Link href={`/admin/audit-logs?${next}`}>다음 페이지</Link>
      )}
    </main>
  );
}
