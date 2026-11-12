import { assertSameOrigin } from '@/server/auth/csrf';
import { cancelBooking } from '@/server/api/booking';
import { errorResponse, positiveId } from '@/server/api/route-utils';
export async function POST(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  try {
    assertSameOrigin(request.headers);
    const { id } = await context.params;
    return Response.json(await cancelBooking(positiveId.parse(id)));
  } catch (error) {
    return errorResponse(error);
  }
}
