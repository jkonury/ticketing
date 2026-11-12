import { assertSameOrigin } from '@/server/auth/csrf';
import { enterQueue } from '@/server/api/queue';
import { errorResponse, positiveId } from '@/server/api/route-utils';
export async function POST(
  request: Request,
  context: { params: Promise<{ scheduleId: string }> },
) {
  try {
    assertSameOrigin(request.headers);
    const { scheduleId } = await context.params;
    return Response.json(await enterQueue(positiveId.parse(scheduleId)));
  } catch (error) {
    return errorResponse(error);
  }
}
