import { AuthForm } from '@/features/auth/auth-form';

export default function SignupPage() {
  return (
    <main className="auth-page">
      <div className="auth-card">
        <h1>회원 가입</h1>
        <AuthForm mode="signup" />
      </div>
    </main>
  );
}
