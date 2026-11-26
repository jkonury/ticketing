import type { MyPageView } from '@/server/aggregate/my-page';
import { PageHeader } from '@/components/layout/page';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Card } from '@/components/ui/card';
import { Poster } from '@/components/ui/poster';
import { EmptyState, InlineError } from '@/components/ui/feedback';
import { bookingLabel, paymentLabel } from '@/lib/labels';
import { formatSeoul } from '@/lib/datetime';
import { MyCoupons } from './my-coupons';
import { CancelButton } from './cancel-button';

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
      {view.bookings.length === 0 ? (
        <EmptyState title="예매 내역이 없어요." />
      ) : (
        <ul className="my-5 grid list-none gap-3 p-0">
          {view.bookings.map((booking) => (
            <li key={booking.id}>
              <Card className="grid gap-3 sm:grid-cols-[6rem_1fr_auto] sm:items-start">
                <Poster
                  id={booking.performanceId}
                  title={booking.title}
                  className="hidden sm:block"
                />
                <div className="grid min-w-0 gap-2">
                  <h2 className="m-0 break-words text-heading font-bold text-fg">
                    {booking.title}
                  </h2>
                  <p className="m-0 text-body text-fg-muted">
                    {formatSeoul(booking.startsAt)} · 좌석 {booking.seatCount}석
                    · {booking.amount.toLocaleString('ko-KR')}원
                  </p>
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={bookingTones[booking.status] ?? 'neutral'}>
                      {bookingLabel(booking.status)}
                    </Badge>
                    {view.paymentsStatus === 'ok' &&
                      (booking.payment ? (
                        <Badge
                          tone={
                            paymentTones[booking.payment.status] ?? 'neutral'
                          }
                        >
                          {paymentLabel(booking.payment.status)}
                        </Badge>
                      ) : (
                        <Badge>결제 내역 없음</Badge>
                      ))}
                  </div>
                </div>
                {['PENDING', 'CONFIRMED'].includes(booking.status) && (
                  <div>
                    <CancelButton id={booking.id} />
                  </div>
                )}
              </Card>
            </li>
          ))}
        </ul>
      )}
      <MyCoupons view={view} />
    </>
  );
}
