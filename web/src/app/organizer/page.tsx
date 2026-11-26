import { Page, PageHeader } from '@/components/layout/page';
import Link from 'next/link';
import { Button, buttonClassName } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { cx } from '@/lib/cx';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { EmptyState, InlineError } from '@/components/ui/feedback';
import { tableClass, thClass, tdClass } from '@/components/ui/table-styles';
import { ApiError } from '@/lib/problem';
import { formatSeoul, seoulDate } from '@/lib/datetime';
import { getOrganizerDashboard } from '@/server/aggregate/organizer-dashboard';
import { requireUser, withSessionRedirect } from '@/server/auth/require-user';
const won = (value: number) => `${value.toLocaleString('ko-KR')}원`;

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <Card className="grid gap-1">
      <dt className="text-caption text-fg-muted">{label}</dt>
      <dd className="text-heading font-extrabold">{value}</dd>
    </Card>
  );
}

export default async function Route({
  searchParams,
}: {
  searchParams: Promise<{ from?: string; to?: string }>;
}) {
  const user = await requireUser();
  if (user.role !== 'ORGANIZER')
    return (
      <Page>
        <PageHeader title="접근 권한이 없습니다 (403)" />
      </Page>
    );
  const query = await searchParams;
  const now = new Date();
  const to = query.to ?? seoulDate(now);
  const from = query.from ?? seoulDate(new Date(now.getTime() - 6 * 86400000));
  const view = await withSessionRedirect(() =>
    getOrganizerDashboard({ from, to }),
  ).catch((error: unknown) => {
    if (error instanceof ApiError && error.status === 403) return null;
    throw error;
  });
  if (!view)
    return (
      <Page>
        <PageHeader title="접근 권한이 없습니다 (403)" />
      </Page>
    );
  const schedules = view.schedules;
  const totalSeats = schedules.reduce((sum, i) => sum + i.totalSeats, 0);
  const soldSeats = schedules.reduce((sum, i) => sum + i.soldSeats, 0);
  const amount = schedules.reduce((sum, i) => sum + i.confirmedAmount, 0);
  const rate = totalSeats ? Math.round((soldSeats / totalSeats) * 100) : 0;
  return (
    <Page>
      <PageHeader
        title="주최자 대시보드"
        actions={
          <Link href="/organizer/new" className={buttonClassName()}>
            공연 등록
          </Link>
        }
      />
      <form className="flex flex-wrap items-end gap-3">
        <Field label="시작" className="w-full max-w-60">
          {(c) => <Input {...c} type="date" name="from" defaultValue={from} />}
        </Field>
        <Field label="종료" className="w-full max-w-60">
          {(c) => <Input {...c} type="date" name="to" defaultValue={to} />}
        </Field>
        <Button type="submit">조회</Button>
      </form>
      <h2 className="mb-3 mt-8 text-heading font-bold">판매 현황</h2>
      {view.sales.status === 'unavailable' ? (
        <InlineError>판매 현황을 불러오지 못했어요</InlineError>
      ) : view.schedules.length === 0 ? (
        <EmptyState
          title="등록된 공연이 없어요"
          description="공연을 등록하면 판매 현황을 볼 수 있어요."
          action={
            <Link href="/organizer/new" className={buttonClassName()}>
              공연 등록
            </Link>
          }
        />
      ) : (
        <>
          <dl className="grid gap-3 sm:grid-cols-3">
            <Metric label="판매율" value={`${rate}%`} />
            <Metric label="확정 매출" value={won(amount)} />
            <Metric
              label="잔여석"
              value={`${(totalSeats - soldSeats).toLocaleString('ko-KR')}석`}
            />
          </dl>
          <table className={cx(tableClass, 'hidden md:table')}>
            <thead>
              <tr>
                <th className={thClass}>공연</th>
                <th className={thClass}>회차</th>
                <th className={thClass}>판매 좌석 / 전체</th>
                <th className={thClass}>확정 금액</th>
              </tr>
            </thead>
            <tbody>
              {view.schedules.map((item) => (
                <tr key={item.scheduleId}>
                  <td className={tdClass}>{item.performanceTitle}</td>
                  <td className={tdClass}>{formatSeoul(item.startsAt)}</td>
                  <td className={tdClass}>
                    {item.soldSeats} / {item.totalSeats}
                  </td>
                  <td className={tdClass}>{won(item.confirmedAmount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <ul className="mt-4 grid gap-3 md:hidden">
            {view.schedules.map((item) => (
              <li key={item.scheduleId}>
                <Card className="grid gap-1">
                  <p className="text-body font-semibold">
                    {item.performanceTitle}
                  </p>
                  <p className="text-caption text-fg-muted">
                    {formatSeoul(item.startsAt)}
                  </p>
                  <p className="text-body">
                    {item.soldSeats} / {item.totalSeats}석 ·{' '}
                    {won(item.confirmedAmount)}
                  </p>
                </Card>
              </li>
            ))}
          </ul>
        </>
      )}
      <h2 className="mb-2 mt-8 text-heading font-bold">정산</h2>
      {view.settlements.status === 'unavailable' ? (
        <InlineError>정산을 불러오지 못했어요</InlineError>
      ) : (
        <table className={tableClass}>
          <thead>
            <tr>
              <th className={thClass}>날짜</th>
              <th className={thClass}>공연 ID</th>
              <th className={thClass}>판매액</th>
              <th className={thClass}>환불액</th>
              <th className={thClass}>정산액</th>
            </tr>
          </thead>
          <tbody>
            {view.settlements.data.map((item, index) => (
              <tr key={`${item.date}-${item.performanceId}-${index}`}>
                <td className={tdClass}>{item.date}</td>
                <td className={tdClass}>{item.performanceId}</td>
                <td className={tdClass}>
                  {item.saleAmount.toLocaleString('ko-KR')}
                </td>
                <td className={tdClass}>
                  {item.refundAmount.toLocaleString('ko-KR')}
                </td>
                <td className={tdClass}>
                  {item.payoutAmount.toLocaleString('ko-KR')}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Page>
  );
}
