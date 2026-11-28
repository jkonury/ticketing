import { Suspense, type ReactNode } from 'react';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { RouteLoading } from '@/components/route-loading';
import { Page, PageHeader } from '@/components/layout/page';
import { Badge } from '@/components/ui/badge';
import { buttonClassName } from '@/components/ui/button';
import { tableClass, thClass, tdClass } from '@/components/ui/table-styles';
import { parsePositiveId } from '@/features/admin/admin-filters';
import { bookingStatuses } from '@/features/admin/booking-status';
import { formatSeoul } from '@/lib/datetime';
import { ApiError } from '@/lib/problem';
import { getAdminBookingDetail } from '@/server/aggregate/admin-booking-detail';
import { withSessionRedirect } from '@/server/auth/require-user';

type Params = Promise<{ id: string }>;

const couponLabels = {
  RESERVING: '예약 중',
  RESERVED: '예약됨',
  REJECTED: '거절됨',
};

const paymentLabels = new Map([
  ['REQUESTED', '결제 요청'],
  ['SUCCEEDED', '결제 승인'],
  ['FAILED', '결제 실패'],
  ['REFUND_REQUESTED', '환불 요청'],
  ['REFUNDED', '환불 완료'],
]);

function won(amount: number) {
  return `${amount.toLocaleString('ko-KR')}원`;
}

function time(value: string | null | undefined) {
  return value ? formatSeoul(value) : '—';
}

function Facts({ entries }: { entries: [string, ReactNode][] }) {
  return (
    <dl className="grid grid-cols-2 gap-4 text-body">
      {entries.map(([label, value]) => (
        <div key={label} className="min-w-0">
          <dt className="text-caption text-fg-muted">{label}</dt>
          <dd className="mt-1 wrap-break-word">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

export default function Route({ params }: { params: Params }) {
  return (
    <Suspense fallback={<RouteLoading />}>
      <BookingDetail params={params} />
    </Suspense>
  );
}

async function BookingDetail({ params }: { params: Params }) {
  const id = parsePositiveId((await params).id);
  if (id === undefined) notFound();
  const { booking, payment } = await withSessionRedirect(() =>
    getAdminBookingDetail(id).catch((error: unknown) => {
      if (error instanceof ApiError && error.status === 404) notFound();
      throw error;
    }),
  );
  const status = bookingStatuses[booking.status];
  const coupon = booking.coupon;
  const details = payment.status === 'ok' ? payment.data : null;
  return (
    <Page>
      <PageHeader
        title={`예매 #${booking.bookingId}`}
        description={booking.performanceTitle}
        actions={
          <Link
            href="/admin/bookings"
            className={buttonClassName({ variant: 'secondary' })}
          >
            예매 목록
          </Link>
        }
      />
      <div className="grid items-start gap-6 md:grid-cols-2">
        <section
          className="grid gap-4 rounded-card border border-line bg-surface p-4"
          aria-labelledby="booking-summary"
        >
          <h2 id="booking-summary" className="text-heading font-semibold">
            예매 정보
          </h2>
          <Facts
            entries={[
              [
                '예매 상태',
                <Badge key="booking-status" tone={status.tone}>
                  {status.label}
                </Badge>,
              ],
              ['회원 ID', booking.memberId],
              ['회차 ID', booking.scheduleId],
              ['공연 시작', time(booking.startsAt)],
              ['예매 생성', time(booking.createdAt)],
              ['결제 기한', time(booking.expiresAt)],
              ['좌석 정가', won(booking.listAmount)],
              ['할인 금액', won(booking.discountAmount)],
              ['결제 대상 금액', won(booking.payableAmount)],
            ]}
          />
        </section>
        <section
          className="grid gap-4 rounded-card border border-line bg-surface p-4"
          aria-labelledby="payment-summary"
        >
          <h2 id="payment-summary" className="text-heading font-semibold">
            결제 정보
          </h2>
          {details ? (
            <Facts
              entries={[
                [
                  '결제 상태',
                  paymentLabels.get(details.status) ?? details.status,
                ],
                ['결제 금액', won(details.amount)],
                ['요청 시각', time(details.requestedAt)],
                ['승인 시각', time(details.approvedAt)],
                ['환불 요청 시각', time(details.refundRequestedAt)],
                ['환불 완료 시각', time(details.refundedAt)],
                ['실패 사유', details.failureReason ?? '—'],
              ]}
            />
          ) : (
            <p className="text-body text-fg-muted">
              {payment.status === 'unavailable'
                ? '결제 정보를 불러오지 못했어요. 잠시 뒤 새로고침해 주세요.'
                : '등록된 결제 내역이 없어요.'}
            </p>
          )}
        </section>
        <section
          className="grid gap-4 rounded-card border border-line bg-surface p-4"
          aria-labelledby="coupon-summary"
        >
          <h2 id="coupon-summary" className="text-heading font-semibold">
            쿠폰
          </h2>
          {coupon ? (
            <Facts
              entries={[
                ['발급 쿠폰 ID', coupon.issuedCouponId],
                ['쿠폰 상태', couponLabels[coupon.state]],
                [
                  '쿠폰 할인 금액',
                  coupon.discountAmount == null
                    ? '—'
                    : won(coupon.discountAmount),
                ],
                ['거절 사유', coupon.rejectReason ?? '—'],
              ]}
            />
          ) : (
            <p className="text-body text-fg-muted">사용한 쿠폰이 없어요.</p>
          )}
        </section>
        <section aria-labelledby="booking-seats">
          <h2 id="booking-seats" className="text-heading font-semibold">
            좌석
          </h2>
          <table className={tableClass} aria-labelledby="booking-seats">
            <thead>
              <tr>
                <th scope="col" className={thClass}>
                  좌석 ID
                </th>
                <th scope="col" className={thClass}>
                  좌석
                </th>
                <th scope="col" className={thClass}>
                  금액
                </th>
              </tr>
            </thead>
            <tbody>
              {booking.seats.map((seat) => (
                <tr key={seat.seatId}>
                  <td className={tdClass}>{seat.seatId}</td>
                  <td className={tdClass}>{seat.label}</td>
                  <td className={tdClass}>{won(seat.price)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
    </Page>
  );
}
