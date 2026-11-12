import { AuthForm } from '@/features/auth/auth-form';

export default function SignupPage() {
  return (
    <main>
      <h1>회원 가입</h1>
      <AuthForm mode="signup" />
    </main>
  );
}
