import { AuthForm } from '@/features/auth/auth-form';
import { Page, PageHeader } from '@/components/layout/page';
import { Card } from '@/components/ui/card';

export default function SignupPage() {
  return (
    <Page narrow className="md:pt-16">
      <Card padding="lg">
        <PageHeader title="회원 가입" />
        <AuthForm mode="signup" />
      </Card>
    </Page>
  );
}
