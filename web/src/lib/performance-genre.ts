import { z } from 'zod';

export const performanceGenre = z.enum([
  'CONCERT',
  'MUSICAL',
  'PLAY',
  'CLASSICAL',
  'SPORTS',
  'EXHIBITION',
]);
export type PerformanceGenre = z.infer<typeof performanceGenre>;

export const genreLabels: Record<PerformanceGenre, string> = {
  CONCERT: '콘서트',
  MUSICAL: '뮤지컬',
  PLAY: '연극',
  CLASSICAL: '클래식',
  SPORTS: '스포츠',
  EXHIBITION: '전시',
};
