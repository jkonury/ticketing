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
      <ul>
        {page.items.map((item) => (
          <li key={item.id}>
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
        <Link href={`/?cursor=${page.nextCursor}`}>더 보기</Link>
      )}
    </main>
  );
}
