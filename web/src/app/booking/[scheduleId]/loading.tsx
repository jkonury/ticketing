import { Page } from '@/components/layout/page';
import { LoadingState } from '@/components/ui/feedback';

export default function Loading() {
  return (
    <Page>
      <LoadingState label="예매 정보를 불러오는 중입니다." />
    </Page>
  );
}
