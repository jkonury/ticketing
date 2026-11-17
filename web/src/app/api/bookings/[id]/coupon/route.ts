import { z } from 'zod';
import { assertSameOrigin } from '@/server/auth/csrf';
import { applyBookingCoupon, removeBookingCoupon } from '@/server/api/coupon';
import { errorResponse, positiveId } from '@/server/api/route-utils';
type Context = { params: Promise<{ id: string }> };
export async function PUT(request: Request, context: Context) {
  try {
    assertSameOrigin(request.headers);
    const { id } = await context.params;
    const body = z
      .object({ issuedCouponId: positiveId })
      .parse(await request.json());
    return Response.json(
      await applyBookingCoupon(positiveId.parse(id), body.issuedCouponId),
    );
  } catch (error) {
    return errorResponse(error);
  }
}
export async function DELETE(request: Request, context: Context) {
  try {
    assertSameOrigin(request.headers);
    const { id } = await context.params;
    return Response.json(await removeBookingCoupon(positiveId.parse(id)));
  } catch (error) {
    return errorResponse(error);
  }
}
