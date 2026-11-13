import Link from 'next/link';
import { getPerformances } from '@/server/api/catalog';
import { formatSeoul } from '@/lib/datetime';
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
    <main>
      <h1>공연 목록</h1>
      <p className="page-intro">지금 만날 수 있는 공연을 살펴보세요.</p>
      {page.items.length === 0 && (
        <p className="empty-state">등록된 공연이 없습니다.</p>
      )}
      <ul className="performance-grid">
        {page.items.map((item) => (
          <li className="performance-card" key={item.id}>
            <span className="card-label">PERFORMANCE</span>
            <Link href={`/performances/${item.id}`}>
              <h2>{item.title}</h2>
            </Link>
            <p>{item.venueName}</p>
            <p>
              {item.firstStartsAt
                ? formatSeoul(item.firstStartsAt)
                : '회차 미정'}
            </p>
          </li>
        ))}
      </ul>
      {page.nextCursor != null && (
        <Link
          className="button button-secondary more-link"
          href={`/?cursor=${page.nextCursor}`}
        >
          더 보기
        </Link>
      )}
    </main>
  );
}
