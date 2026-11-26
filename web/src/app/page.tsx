import Link from 'next/link';
import { getPerformances } from '@/server/api/catalog';
import { formatSeoul } from '@/lib/datetime';
import { Page, PageHeader } from '@/components/layout/page';
import { buttonClassName } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { EmptyState } from '@/components/ui/feedback';
import { Poster } from '@/components/ui/poster';
import { cx } from '@/lib/cx';
import {
  genreLabels,
  performanceGenre,
  type PerformanceGenre,
} from '@/lib/performance-genre';

function catalogHref(genre?: PerformanceGenre, cursor?: number) {
  const query = new URLSearchParams();
  if (genre) query.set('genre', genre);
  if (cursor) query.set('cursor', String(cursor));
  return query.size > 0 ? `/?${query}` : '/';
}

export default async function Home({
  searchParams,
}: {
  searchParams: Promise<{ cursor?: string; genre?: string }>;
}) {
  const { cursor, genre: requestedGenre } = await searchParams;
  const genre = performanceGenre.safeParse(requestedGenre).data;
  const parsed = cursor ? Number(cursor) : undefined;
  const page = await getPerformances(
    parsed && Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined,
    undefined,
    genre,
  );
  return (
    <Page>
      <PageHeader
        title="공연 목록"
        description="지금 만날 수 있는 공연을 살펴보세요."
      />
      <nav aria-label="공연 장르" className="mb-5 flex flex-wrap gap-2">
        {[undefined, ...performanceGenre.options].map((option) => (
          <Link
            key={option ?? 'all'}
            href={catalogHref(option)}
            aria-current={option === genre ? 'page' : undefined}
            className={buttonClassName({
              variant: option === genre ? 'primary' : 'secondary',
            })}
          >
            {option ? genreLabels[option] : '전체'}
          </Link>
        ))}
      </nav>
      {page.items.length === 0 && (
        <EmptyState
          title={
            genre
              ? `${genreLabels[genre]} 공연이 없어요`
              : '등록된 공연이 없어요'
          }
        />
      )}
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
                <Poster
                  id={item.id}
                  title={item.title}
                  imageUrl={item.posterUrl}
                />
                <div className="grid gap-1 p-3">
                  {item.genre && (
                    <p className="text-caption font-semibold text-brand">
                      {genreLabels[item.genre]}
                    </p>
                  )}
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
          href={catalogHref(genre, page.nextCursor)}
        >
          더 보기
        </Link>
      )}
    </Page>
  );
}
