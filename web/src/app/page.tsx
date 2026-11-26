import Link from 'next/link';
import { getPerformances } from '@/server/api/catalog';
import { formatSeoul } from '@/lib/datetime';
import { Page, PageHeader } from '@/components/layout/page';
import { buttonClassName } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { EmptyState } from '@/components/ui/feedback';
import { Poster } from '@/components/ui/poster';
import { cx } from '@/lib/cx';

export default async function Home({
  searchParams,
}: {
  searchParams: Promise<{ cursor?: string }>;
}) {
  const { cursor } = await searchParams;
  const parsed = cursor ? Number(cursor) : undefined;
  const page = await getPerformances(
    parsed && Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined,
  );
  return (
    <Page>
      <PageHeader
        title="공연 목록"
        description="지금 만날 수 있는 공연을 살펴보세요."
      />
      {page.items.length === 0 && <EmptyState title="등록된 공연이 없어요" />}
      <ul className="m-0 grid list-none grid-cols-2 gap-4 p-0 sm:grid-cols-3 sm:gap-5 lg:grid-cols-4">
        {page.items.map((item) => (
          <li key={item.id} className="min-w-0">
            <Link
              href={`/performances/${item.id}`}
              className="block h-full rounded-card text-fg no-underline hover:no-underline focus-visible:outline-3 focus-visible:outline-brand"
            >
              <Card
                padding="none"
                className="h-full transition-colors hover:border-brand-line"
              >
                <Poster id={item.id} title={item.title} />
                <div className="grid gap-1 p-3">
                  <h2 className="line-clamp-2 text-body font-bold">
                    {item.title}
                  </h2>
                  <p className="text-caption text-fg-muted">{item.venueName}</p>
                  <p className="text-caption text-fg-muted">
                    {item.firstStartsAt
                      ? formatSeoul(item.firstStartsAt)
                      : '회차 미정'}
                  </p>
                </div>
              </Card>
            </Link>
          </li>
        ))}
      </ul>
      {page.nextCursor != null && (
        <Link
          className={cx(buttonClassName({ variant: 'secondary' }), 'mt-6')}
          href={`/?cursor=${page.nextCursor}`}
        >
          더 보기
        </Link>
      )}
    </Page>
  );
}
