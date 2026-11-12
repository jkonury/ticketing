import { assertSameOrigin } from '@/server/auth/csrf';
import { getQueue, leaveQueue } from '@/server/api/queue';
import { errorResponse, positiveId } from '@/server/api/route-utils';
type Context = { params: Promise<{ scheduleId: string }> };
export async function GET(_request: Request, context: Context) {
  try {
    const { scheduleId } = await context.params;
    return Response.json(await getQueue(positiveId.parse(scheduleId)));
  } catch (error) {
    return errorResponse(error);
  }
}
export async function DELETE(request: Request, context: Context) {
  try {
    assertSameOrigin(request.headers);
    const { scheduleId } = await context.params;
    await leaveQueue(positiveId.parse(scheduleId));
    return new Response(null, { status: 204 });
  } catch (error) {
    return errorResponse(error);
  }
}
