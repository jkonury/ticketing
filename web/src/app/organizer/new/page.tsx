import { RegistrationForm } from '@/features/organizer/registration-form';
import { requireUser } from '@/server/auth/require-user';
export default async function Page() {
  const user = await requireUser();
  return user.role === 'ORGANIZER' ? (
    <main>
      <h1>공연 등록</h1>
      <RegistrationForm />
    </main>
  ) : (
    <main>
      <h1>접근 권한이 없습니다 (403)</h1>
    </main>
  );
}
