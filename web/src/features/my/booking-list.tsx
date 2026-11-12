import type { MyPageView } from '@/server/aggregate/my-page';
import { bookingLabel, paymentLabel } from '@/lib/labels';
import { formatSeoul } from '@/lib/datetime';
import { CancelButton } from './cancel-button';
export function BookingList({ view }: { view: MyPageView }) {
  return (
    <>
      <h1>마이페이지</h1>
      {view.paymentsStatus === 'unavailable' && (
        <p>결제 상태를 불러오지 못했습니다</p>
      )}
      <table>
        <thead>
          <tr>
            <th>공연</th>
            <th>회차</th>
            <th>예매 상태</th>
            {view.paymentsStatus === 'ok' && <th>결제 상태</th>}
            <th>취소</th>
          </tr>
        </thead>
        <tbody>
          {view.bookings.map((booking) => (
            <tr key={booking.id}>
              <td>{booking.title}</td>
              <td>{formatSeoul(booking.startsAt)}</td>
              <td>{bookingLabel(booking.status)}</td>
              {view.paymentsStatus === 'ok' && (
                <td>
                  {booking.payment
                    ? paymentLabel(booking.payment.status)
                    : '결제 내역 없음'}
                </td>
              )}
              <td>
                {['PENDING', 'CONFIRMED'].includes(booking.status) && (
                  <CancelButton id={booking.id} />
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
