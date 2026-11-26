import type { MyPageView } from '@/server/aggregate/my-page';
import { PageHeader } from '@/components/layout/page';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { InlineError } from '@/components/ui/feedback';
import { bookingLabel, paymentLabel } from '@/lib/labels';
import { formatSeoul } from '@/lib/datetime';
import { MyCoupons } from './my-coupons';
import { CancelButton } from './cancel-button';

const th =
  'min-w-30 border-b border-line p-3.5 text-left text-caption font-normal text-fg-muted';
const td = 'min-w-30 border-b border-line p-3.5 text-left';

const bookingTones: Record<string, BadgeTone> = {
  PENDING: 'warning',
  CONFIRMED: 'success',
  EXPIRED: 'neutral',
  CANCELLED: 'neutral',
};
const paymentTones: Record<string, BadgeTone> = {
  REQUESTED: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  REFUND_REQUESTED: 'warning',
  REFUNDED: 'neutral',
};

export function BookingList({ view }: { view: MyPageView }) {
  return (
    <>
      <PageHeader title="마이페이지" />
      {view.paymentsStatus === 'unavailable' && (
        <InlineError>결제 상태를 불러오지 못했습니다</InlineError>
      )}
      <table className="my-5 block w-full overflow-x-auto border-collapse bg-surface text-body">
        <thead>
          <tr>
            <th className={th}>공연</th>
            <th className={th}>회차</th>
            <th className={th}>예매 상태</th>
            {view.paymentsStatus === 'ok' && <th className={th}>결제 상태</th>}
            <th className={th}>취소</th>
          </tr>
        </thead>
        <tbody>
          {view.bookings.map((booking) => (
            <tr key={booking.id}>
              <td className={td}>{booking.title}</td>
              <td className={td}>{formatSeoul(booking.startsAt)}</td>
              <td className={td}>
                <Badge tone={bookingTones[booking.status] ?? 'neutral'}>
                  {bookingLabel(booking.status)}
                </Badge>
              </td>
              {view.paymentsStatus === 'ok' && (
                <td className={td}>
                  {booking.payment ? (
                    <Badge
                      tone={paymentTones[booking.payment.status] ?? 'neutral'}
                    >
                      {paymentLabel(booking.payment.status)}
                    </Badge>
                  ) : (
                    '결제 내역 없음'
                  )}
                </td>
              )}
              <td className={td}>
                {['PENDING', 'CONFIRMED'].includes(booking.status) && (
                  <CancelButton id={booking.id} />
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <MyCoupons view={view} />
    </>
  );
}
