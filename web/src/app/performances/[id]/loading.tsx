import { Page } from '@/components/layout/page';
import { Skeleton } from '@/components/ui/feedback';

export default function Loading() {
  return (
    <Page>
      <div role="status" aria-label="불러오는 중" className="grid gap-8">
        <div className="grid gap-6 sm:grid-cols-[240px_1fr] lg:grid-cols-[300px_1fr]">
          <Skeleton className="aspect-[3/4] w-full max-w-60 rounded-card sm:max-w-none" />
          <div className="grid content-start gap-3">
            <Skeleton className="h-9 w-2/3" />
            <Skeleton className="h-5 w-1/3" />
            <Skeleton className="h-20 w-full" />
          </div>
        </div>
        <Skeleton className="h-32 w-full" />
        <Skeleton className="h-32 w-full" />
      </div>
    </Page>
  );
}
