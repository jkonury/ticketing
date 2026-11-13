import { z } from 'zod';
import { assertSameOrigin } from '@/server/auth/csrf';
import { createBooking } from '@/server/api/booking';
import { errorResponse, positiveId } from '@/server/api/route-utils';
const schema = z.object({
  scheduleId: positiveId,
  seatIds: z.array(positiveId).min(1).max(4),
  admissionToken: z.string().min(1),
  idempotencyKey: z.string().min(1).max(100),
});
export async function POST(request: Request) {
  try {
    assertSameOrigin(request.headers);
    const booking = await createBooking(schema.parse(await request.json()));
    return Response.json({ ...booking, serverTime: new Date().toISOString() });
  } catch (error) {
    return errorResponse(error);
  }
}
