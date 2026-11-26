import { render } from '@testing-library/react';
import { expect, it } from 'vitest';
import {
  Poster,
  posterHash,
  posterPaletteIndex,
  posterPaletteSize,
} from './poster';

it('같은 공연은 항상 같은 해시와 팔레트를 쓴다', () => {
  expect(posterHash(1, '공연')).toBe(posterHash(1, '공연'));
  expect(posterHash(1, '공연')).not.toBe(posterHash(2, '공연'));
  const index = posterPaletteIndex(7, '제목');
  expect(index).toBe(posterPaletteIndex(7, '제목'));
  expect(index).toBeLessThan(posterPaletteSize);
});

it('이미지가 있으면 img를 쓴다', () => {
  const { container } = render(
    <Poster id={1} title="공연" imageUrl="/a.png" />,
  );
  expect(container.querySelector('img')).toHaveAttribute('src', '/a.png');
});
