import type { BadgeTone } from '@/components/ui/badge';

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
