import { Page, PageHeader } from '@/components/layout/page';
import { RegistrationForm } from '@/features/organizer/registration-form';
import { requireUser } from '@/server/auth/require-user';

export default async function NewPage() {
  const user = await requireUser();
  return (
    <Page>
      {user.role === 'ORGANIZER' ? (
        <>
          <PageHeader title="공연 등록" />
          <RegistrationForm />
        </>
      ) : (
        <PageHeader title="접근 권한이 없습니다 (403)" />
      )}
    </Page>
  );
}
