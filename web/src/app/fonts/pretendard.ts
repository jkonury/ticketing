import localFont from 'next/font/local';

// KS X 1001 서브셋 정적 파일이다. 굵기 네 개를 모두 미리 받으면 약 1MB라서
// preload를 끄고, 페이지가 실제로 쓰는 굵기만 받게 한다.
export const pretendard = localFont({
  src: [
    {
      path: './Pretendard-Regular.subset.woff2',
      weight: '400',
      style: 'normal',
    },
    {
      path: './Pretendard-SemiBold.subset.woff2',
      weight: '600',
      style: 'normal',
    },
    { path: './Pretendard-Bold.subset.woff2', weight: '700', style: 'normal' },
    {
      path: './Pretendard-ExtraBold.subset.woff2',
      weight: '800',
      style: 'normal',
    },
  ],
  variable: '--font-pretendard',
  display: 'swap',
  preload: false,
});
