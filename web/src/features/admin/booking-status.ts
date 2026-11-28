import type { BadgeTone } from '@/components/ui/badge';
import type { getAdminBooking } from '@/server/api/admin';

type CancelReason = NonNullable<
  Awaited<ReturnType<typeof getAdminBooking>>['cancelReason']
>;

export const bookingCancelReasons = {
  USER_REQUESTED: '사용자 요청',
  PAYMENT_FAILED: '결제 실패',
  SEAT_ALREADY_SOLD: '좌석 판매 완료',
  AMOUNT_MISMATCH: '결제 금액 불일치',
  ADMIN_CANCELLED: '관리자 강제 취소',
  PERFORMANCE_CANCELLED: '공연 취소',
} satisfies Record<CancelReason, string>;

export const bookingStatuses = {
  PENDING: { label: '결제 대기', tone: 'warning' },
  CONFIRMED: { label: '예매 확정', tone: 'success' },
  EXPIRED: { label: '만료', tone: 'neutral' },
  CANCELLED: { label: '취소', tone: 'neutral' },
} satisfies Record<string, { label: string; tone: BadgeTone }>;

export function parseBookingStatus(value: string | undefined) {
  return value === 'PENDING' ||
    value === 'CONFIRMED' ||
    value === 'EXPIRED' ||
    value === 'CANCELLED'
    ? value
    : undefined;
}
