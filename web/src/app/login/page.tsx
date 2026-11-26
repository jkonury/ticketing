import Link from 'next/link';
import { Suspense } from 'react';
import { AuthForm } from '@/features/auth/auth-form';
import { Page, PageHeader } from '@/components/layout/page';
import { Card } from '@/components/ui/card';

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
    <Page narrow className="md:pt-16">
      <Card padding="lg">
        <PageHeader title="로그인" description="공연 예매를 이어가세요." />
        <Suspense fallback={null}>
          <LoginForm searchParams={searchParams} />
        </Suspense>
        <Link href="/signup" className="mt-5 inline-flex min-h-11 items-center">
          회원 가입
        </Link>
      </Card>
    </Page>
  );
}
