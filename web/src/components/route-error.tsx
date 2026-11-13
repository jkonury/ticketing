'use client';
export function RouteError({ reset }: { error: Error; reset: () => void }) {
  return (
    <main>
      <p role="alert">화면을 불러오지 못했습니다.</p>
      <button onClick={reset}>다시 시도</button>
    </main>
  );
}
