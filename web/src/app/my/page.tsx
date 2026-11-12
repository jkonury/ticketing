import Link from 'next/link';
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
    <main>
      <BookingList view={view} />
      {view.nextCursor != null && (
        <Link href={`/my?cursor=${view.nextCursor}`}>더 보기</Link>
      )}
    </main>
  );
}
