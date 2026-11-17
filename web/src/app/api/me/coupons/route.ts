import { listMyCoupons } from '@/server/api/coupon';
import { errorResponse } from '@/server/api/route-utils';
export async function GET() {
  try {
    return Response.json(await listMyCoupons());
  } catch (error) {
    return errorResponse(error);
  }
}
