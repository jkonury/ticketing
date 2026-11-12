import { ApiError } from '@/lib/problem';
import { formatSeoul, seoulDate } from '@/lib/datetime';
import { getOrganizerDashboard } from '@/server/aggregate/organizer-dashboard';
import { requireUser, withSessionRedirect } from '@/server/auth/require-user';
export default async function Page({
  searchParams,
}: {
  searchParams: Promise<{ from?: string; to?: string }>;
}) {
  const user = await requireUser();
  if (user.role !== 'ORGANIZER')
    return (
      <main>
        <h1>접근 권한이 없습니다 (403)</h1>
      </main>
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
      <main>
        <h1>접근 권한이 없습니다 (403)</h1>
      </main>
    );
  return (
    <main>
      <h1>주최자 대시보드</h1>
      <form>
        <label>
          시작 <input type="date" name="from" defaultValue={from} />
        </label>
        <label>
          종료 <input type="date" name="to" defaultValue={to} />
        </label>
        <button>조회</button>
      </form>
      <h2>판매 현황</h2>
      {view.sales.status === 'unavailable' ? (
        <p>판매 현황을 불러오지 못했습니다</p>
      ) : (
        <>
          <table>
            <thead>
              <tr>
                <th>공연</th>
                <th>회차</th>
                <th>판매 좌석 / 전체</th>
                <th>확정 금액</th>
              </tr>
            </thead>
            <tbody>
              {view.schedules.map((item) => (
                <tr key={item.scheduleId}>
                  <td>{item.performanceTitle}</td>
                  <td>{formatSeoul(item.startsAt)}</td>
                  <td>
                    {item.soldSeats} / {item.totalSeats}
                  </td>
                  <td>{item.confirmedAmount.toLocaleString('ko-KR')}원</td>
                </tr>
              ))}
            </tbody>
          </table>
          <h3>공연별 합계</h3>
          <ul>
            {view.sales.data.map((item) => (
              <li key={item.performanceId}>
                {item.title}: {item.soldSeats} / {item.totalSeats},{' '}
                {item.amount.toLocaleString('ko-KR')}원
              </li>
            ))}
          </ul>
        </>
      )}
      <h2>정산</h2>
      {view.settlements.status === 'unavailable' ? (
        <p>정산을 불러오지 못했습니다</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>날짜</th>
              <th>공연 ID</th>
              <th>판매액</th>
              <th>환불액</th>
              <th>정산액</th>
            </tr>
          </thead>
          <tbody>
            {view.settlements.data.map((item, index) => (
              <tr key={`${item.date}-${item.performanceId}-${index}`}>
                <td>{item.date}</td>
                <td>{item.performanceId}</td>
                <td>{item.saleAmount.toLocaleString('ko-KR')}</td>
                <td>{item.refundAmount.toLocaleString('ko-KR')}</td>
                <td>{item.payoutAmount.toLocaleString('ko-KR')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </main>
  );
}
