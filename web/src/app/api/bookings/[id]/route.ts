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
    });
  } catch (error) {
    return errorResponse(error);
  }
}
