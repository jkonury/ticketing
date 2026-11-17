import { expect, test } from '@playwright/test';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { attachJwtGuard } from './support/jwt-guard';

test('JWT 검사기가 응답 본문의 토큰을 감지한다', async ({ context, page }) => {
  // 검사기는 실제 네트워크 응답을 가로채므로 page.route 대신 로컬 서버를 쓴다.
  const server = createServer((_, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html' });
    response.end('<p>eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature</p>');
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const guard = await attachJwtGuard(context);
    const { port } = server.address() as AddressInfo;
    await page.goto(`http://127.0.0.1:${port}/jwt-guard-fixture`);
    expect(await guard.violations()).toContainEqual(
      expect.stringContaining('응답 본문'),
    );
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
