import { getBooking } from '@/server/api/booking';
import { errorResponse, positiveId } from '@/server/api/route-utils';
export async function GET(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  try {
    const { id } = await context.params;
    const booking = await getBooking(positiveId.parse(id));
    return Response.json({
      bookingId: booking.bookingId,
      status: booking.status,
      expiresAt: booking.expiresAt,
      listAmount: booking.listAmount ?? booking.totalAmount,
      discountAmount: booking.discountAmount ?? 0,
      payableAmount: booking.payableAmount ?? booking.totalAmount,
      coupon: booking.coupon ?? null,
    });
  } catch (error) {
    return errorResponse(error);
  }
}
