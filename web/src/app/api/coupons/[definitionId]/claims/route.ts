import { assertSameOrigin } from '@/server/auth/csrf';
import { claimCoupon } from '@/server/api/coupon';
import { errorResponse, positiveId } from '@/server/api/route-utils';
export async function POST(
  request: Request,
  context: { params: Promise<{ definitionId: string }> },
) {
  try {
    assertSameOrigin(request.headers);
    const { definitionId } = await context.params;
    return Response.json(await claimCoupon(positiveId.parse(definitionId)), {
      status: 201,
    });
  } catch (error) {
    return errorResponse(error);
  }
}
