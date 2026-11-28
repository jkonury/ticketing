import 'server-only';

import { getAdminBooking, getAdminPayment } from '@/server/api/admin';
import { optional } from './section';

export async function getAdminBookingDetail(id: number) {
  const booking = await getAdminBooking(id);
  const payments = await optional(getAdminPayment(booking.bookingId), true);
  return {
    booking,
    payment:
      payments.status === 'ok'
        ? { status: 'ok' as const, data: payments.data[0] ?? null }
        : payments,
  };
}
