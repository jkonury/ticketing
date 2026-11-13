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
    return Response.json(
      await createBooking(schema.parse(await request.json())),
    );
  } catch (error) {
    return errorResponse(error);
  }
}
