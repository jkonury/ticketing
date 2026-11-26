import { Suspense } from 'react';
import { Page as PageShell, PageHeader } from '@/components/layout/page';
import { RouteLoading } from '@/components/route-loading';
import { ClaimList } from '@/features/coupons/claim-list';
import { listClaimableCoupons } from '@/server/api/coupon';
import { requireUser, withSessionRedirect } from '@/server/auth/require-user';

export default function Page() {
  return (
    <Suspense fallback={<RouteLoading />}>
      <Coupons />
    </Suspense>
  );
}

async function Coupons() {
  await requireUser();
  const coupons = await withSessionRedirect(() => listClaimableCoupons());
  return (
    <PageShell>
      <PageHeader title="쿠폰 받기" />
      <ClaimList coupons={coupons} />
    </PageShell>
  );
}
