'use client';
export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <main>
      <p>예매 정보를 불러오지 못했습니다.</p>
      <button onClick={reset}>다시 시도</button>
    </main>
  );
}
