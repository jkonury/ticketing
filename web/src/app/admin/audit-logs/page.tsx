import Link from 'next/link';
import { Page, PageHeader } from '@/components/layout/page';
import { Button, buttonClassName } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { tableClass, thClass, tdClass } from '@/components/ui/table-styles';
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

export default async function Route({
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
    <Page>
      <PageHeader title="감사 로그" />
      <form className="grid gap-4 md:grid-cols-2">
        <Field label="액션">
          {(c) => (
            <Input {...c} name="action" defaultValue={query.action ?? ''} />
          )}
        </Field>
        <Field label="행위자 ID">
          {(c) => (
            <Input {...c} name="actorId" defaultValue={query.actorId ?? ''} />
          )}
        </Field>
        <Field label="시작">
          {(c) => (
            <Input
              {...c}
              type="datetime-local"
              name="from"
              defaultValue={query.from ?? ''}
            />
          )}
        </Field>
        <Field label="종료">
          {(c) => (
            <Input
              {...c}
              type="datetime-local"
              name="to"
              defaultValue={query.to ?? ''}
            />
          )}
        </Field>
        <div>
          <Button type="submit">조회</Button>
        </div>
      </form>
      <table className={tableClass}>
        <thead>
          <tr>
            <th className={thClass}>일시</th>
            <th className={thClass}>행위자</th>
            <th className={thClass}>액션</th>
            <th className={thClass}>대상</th>
            <th className={thClass}>상세</th>
          </tr>
        </thead>
        <tbody>
          {page.items.map((log) => (
            <tr key={log.id}>
              <td className={tdClass}>{formatSeoul(log.occurredAt)}</td>
              <td className={tdClass}>{log.actorId ?? '-'}</td>
              <td className={tdClass}>{log.action}</td>
              <td className={tdClass}>
                {log.targetType} {log.targetId}
              </td>
              <td className={tdClass}>
                <code>{JSON.stringify(log.detail ?? {})}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {page.nextCursor !== null && (
        <Link
          href={`/admin/audit-logs?${next}`}
          className={buttonClassName({ variant: 'secondary' })}
        >
          다음 페이지
        </Link>
      )}
    </Page>
  );
}
