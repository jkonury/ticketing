import { Page, PageHeader } from '@/components/layout/page';
import { CouponForm } from '@/features/admin/coupon-forms';

export default function NewCouponPage() {
  return (
    <Page>
      <PageHeader title="쿠폰 생성" />
      <CouponForm />
    </Page>
  );
}
