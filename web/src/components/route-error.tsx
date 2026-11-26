'use client';
import { Button } from '@/components/ui/button';
import { ErrorState } from '@/components/ui/feedback';

export function RouteError({ reset }: { error: Error; reset: () => void }) {
  return (
    <main>
      <ErrorState
        title="화면을 불러오지 못했어요"
        description="잠시 후 다시 시도해 주세요."
        action={
          <Button variant="secondary" onClick={reset}>
            다시 시도
          </Button>
        }
      />
    </main>
  );
}
