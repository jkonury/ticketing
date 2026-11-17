import { ClaimList } from '@/features/coupons/claim-list';
import { listClaimableCoupons } from '@/server/api/coupon';
import { requireUser, withSessionRedirect } from '@/server/auth/require-user';

export default async function Page() {
  await requireUser();
  const coupons = await withSessionRedirect(() => listClaimableCoupons());
  return (
    <main>
      <h1>쿠폰 받기</h1>
      <ClaimList coupons={coupons} />
    </main>
  );
}
