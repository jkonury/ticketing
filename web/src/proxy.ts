import { NextResponse, type NextRequest } from 'next/server';

export function proxy(request: NextRequest) {
  if (request.cookies.has('ticketing_session')) return NextResponse.next();
  const login = new URL('/login', request.url);
  login.searchParams.set(
    'next',
    request.nextUrl.pathname + request.nextUrl.search,
  );
  return NextResponse.redirect(login);
}
export const config = {
  matcher: [
    '/my/:path*',
    '/organizer/:path*',
    '/booking/:path*',
    '/admin/:path*',
  ],
};
