import Link from 'next/link';
import { Suspense } from 'react';
import { AuthForm } from '@/features/auth/auth-form';

async function LoginForm({
  searchParams,
}: {
  searchParams: Promise<{ next?: string }>;
}) {
  const { next } = await searchParams;
  return <AuthForm mode="login" next={next} />;
}

export default function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ next?: string }>;
}) {
  return (
    <main className="auth-page">
      <div className="auth-card">
        <h1>로그인</h1>
        <p className="page-intro">공연 예매를 이어가세요.</p>
        <Suspense fallback={null}>
          <LoginForm searchParams={searchParams} />
        </Suspense>
        <Link href="/signup">회원 가입</Link>
      </div>
    </main>
  );
}
