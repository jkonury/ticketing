import { z } from 'zod';
import { assertSameOrigin } from '@/server/auth/csrf';
import { requestPayment } from '@/server/api/booking';
import { errorResponse, positiveId } from '@/server/api/route-utils';
export async function POST(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  try {
    assertSameOrigin(request.headers);
    const { id } = await context.params;
    const body = z
      .object({ idempotencyKey: z.string().min(1) })
      .parse(await request.json());
    return Response.json(
      await requestPayment(positiveId.parse(id), body.idempotencyKey),
      { status: 202 },
    );
  } catch (error) {
    return errorResponse(error);
  }
}
