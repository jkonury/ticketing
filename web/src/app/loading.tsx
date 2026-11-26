import { Page } from '@/components/layout/page';
import { Skeleton } from '@/components/ui/feedback';

export default function Loading() {
  return (
    <Page>
      <div role="status" aria-label="불러오는 중" className="grid gap-6">
        <Skeleton className="h-9 w-40" />
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 sm:gap-5 lg:grid-cols-4">
          {Array.from({ length: 8 }, (_, i) => (
            <Skeleton key={i} className="aspect-[3/4] w-full rounded-card" />
          ))}
        </div>
      </div>
    </Page>
  );
}
