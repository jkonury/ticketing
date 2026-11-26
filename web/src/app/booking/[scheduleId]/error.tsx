'use client';

import { Page } from '@/components/layout/page';
import { Button } from '@/components/ui/button';
import { ErrorState } from '@/components/ui/feedback';

export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <Page>
      <ErrorState
        title="예매 정보를 불러오지 못했습니다."
        action={
          <Button variant="secondary" onClick={reset}>
            다시 시도
          </Button>
        }
      />
    </Page>
  );
}
