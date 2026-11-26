import { expect, test } from '@playwright/test';
import { resolve } from 'node:path';
import { seed } from './support/seed';

test('주최자가 올린 포스터를 상세와 장르별 목록에서 볼 수 있다', async ({
  page,
  request,
}) => {
  const { organizer, title: baseTitle } = await seed(request);
  const title = `${baseTitle} 포스터`;
  await page.goto('/login');
  await page.getByLabel('이메일').fill(organizer.email);
  await page.getByLabel('비밀번호').fill(organizer.password);
  await page.getByRole('button', { name: '로그인' }).click();
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();

  await page.goto('/organizer/new');
  await page.getByLabel('공연 제목').fill(title);
  await page.getByLabel('공연 설명').fill('포스터가 있는 E2E 공연');
  await page.getByLabel('장르').selectOption('CONCERT');
  await page
    .getByLabel('포스터 (선택)')
    .setInputFiles(resolve('../scripts/dev/posters/neon-city-live.webp'));
  const tomorrowInSeoul = new Date(Date.now() + 33 * 3600_000);
  await page
    .getByLabel('회차 시작 시각 (한국 시간)')
    .fill(tomorrowInSeoul.toISOString().slice(0, 16));
  await page.getByLabel('공연장 이름').fill('포스터 테스트 공연장');
  await page.getByLabel('구역 (쉼표 구분)').fill('A');
  await page.getByLabel('열 수', { exact: true }).fill('1');
  await page.getByLabel('열당 좌석 수').fill('2');
  await page.getByLabel('A 열 수').fill('1');
  await page.getByLabel('A 가격').fill('10000');
  await page.getByRole('button', { name: '등록', exact: true }).click();

  await expect(page).toHaveURL(/\/performances\/\d+$/);
  await expect(page.getByText('콘서트', { exact: true })).toBeVisible();
  await expect(page.getByRole('img', { name: title })).toHaveJSProperty(
    'naturalWidth',
    576,
  );

  await page.goto('/');
  const genres = page.getByRole('navigation', { name: '공연 장르' });
  await genres.getByRole('link', { name: '콘서트' }).click();
  await expect(genres.getByRole('link', { name: '콘서트' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(page.getByRole('img', { name: title })).toHaveJSProperty(
    'naturalWidth',
    576,
  );
  await genres.getByRole('link', { name: '뮤지컬' }).click();
  await expect(page).toHaveURL(/genre=MUSICAL/);
  await expect(page.getByRole('heading', { name: title })).toHaveCount(0);
});
