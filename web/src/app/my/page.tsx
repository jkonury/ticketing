import Link from 'next/link';
import { Page as PageShell } from '@/components/layout/page';
import { buttonClassName } from '@/components/ui/button';
import { BookingList } from '@/features/my/booking-list';
import { getMyPage } from '@/server/aggregate/my-page';
import { requireUser, withSessionRedirect } from '@/server/auth/require-user';
export default async function Page({
  searchParams,
}: {
  searchParams: Promise<{ cursor?: string }>;
}) {
  await requireUser();
  const cursor = Number((await searchParams).cursor);
  const view = await withSessionRedirect(() =>
    getMyPage(Number.isSafeInteger(cursor) && cursor > 0 ? cursor : undefined),
  );
  return (
    <PageShell>
      <BookingList view={view} />
      {view.nextCursor != null && (
        <div className="mt-6 flex justify-center">
          <Link
            href={`/my?cursor=${view.nextCursor}`}
            className={buttonClassName({ variant: 'secondary' })}
          >
            더 보기
          </Link>
        </div>
      )}
    </PageShell>
  );
}
