import { Page, PageHeader } from '@/components/layout/page';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { InlineError } from '@/components/ui/feedback';
import { tableClass, thClass, tdClass } from '@/components/ui/table-styles';
import { ApiError } from '@/lib/problem';
import { formatSeoul, seoulDate } from '@/lib/datetime';
import { getOrganizerDashboard } from '@/server/aggregate/organizer-dashboard';
import { requireUser, withSessionRedirect } from '@/server/auth/require-user';
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
  return (
    <Page>
      <PageHeader title="주최자 대시보드" />
      <form className="flex flex-wrap items-end gap-3">
        <Field label="시작" className="w-full max-w-60">
          {(c) => <Input {...c} type="date" name="from" defaultValue={from} />}
        </Field>
        <Field label="종료" className="w-full max-w-60">
          {(c) => <Input {...c} type="date" name="to" defaultValue={to} />}
        </Field>
        <Button type="submit">조회</Button>
      </form>
      <h2 className="mb-2 mt-8 text-heading font-bold">판매 현황</h2>
      {view.sales.status === 'unavailable' ? (
        <InlineError>판매 현황을 불러오지 못했습니다</InlineError>
      ) : (
        <>
          <table className={tableClass}>
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
                  <td className={tdClass}>
                    {item.confirmedAmount.toLocaleString('ko-KR')}원
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <h3 className="mb-2 mt-6 text-body font-bold">공연별 합계</h3>
          <ul className="grid gap-1 pl-5 text-body">
            {view.sales.data.map((item) => (
              <li key={item.performanceId}>
                {item.title}: {item.soldSeats} / {item.totalSeats},{' '}
                {item.amount.toLocaleString('ko-KR')}원
              </li>
            ))}
          </ul>
        </>
      )}
      <h2 className="mb-2 mt-8 text-heading font-bold">정산</h2>
      {view.settlements.status === 'unavailable' ? (
        <InlineError>정산을 불러오지 못했습니다</InlineError>
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
