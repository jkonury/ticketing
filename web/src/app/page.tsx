import Link from 'next/link';
import { getPerformances } from '@/server/api/catalog';
import { formatSeoul } from '@/lib/datetime';
import { Page, PageHeader } from '@/components/layout/page';
import { Badge } from '@/components/ui/badge';
import { buttonClassName } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { EmptyState } from '@/components/ui/feedback';
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
      {page.items.length === 0 && (
        <EmptyState title="등록된 공연이 없습니다." />
      )}
      <ul className="m-0 grid list-none grid-cols-1 gap-5 p-0 md:grid-cols-2 lg:grid-cols-3">
        {page.items.map((item) => (
          <li key={item.id} className="relative">
            <Card
              padding="lg"
              className="grid h-full min-h-48 content-start gap-1 transition-colors focus-within:outline-3 focus-within:outline-brand hover:border-brand-line"
            >
              <Badge tone="brand" className="mb-3 justify-self-start">
                PERFORMANCE
              </Badge>
              <Link
                href={`/performances/${item.id}`}
                className="text-fg no-underline after:absolute after:inset-0 after:content-[''] hover:no-underline focus-visible:outline-none"
              >
                <h2 className="mb-2 text-heading font-bold">{item.title}</h2>
              </Link>
              <p className="text-body text-fg-muted">{item.venueName}</p>
              <p className="text-body text-fg-muted">
                {item.firstStartsAt
                  ? formatSeoul(item.firstStartsAt)
                  : '회차 미정'}
              </p>
            </Card>
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
