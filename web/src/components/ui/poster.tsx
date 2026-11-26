import { cx } from '@/lib/cx';

const backgrounds = [
  'bg-poster-1',
  'bg-poster-2',
  'bg-poster-3',
  'bg-poster-4',
  'bg-poster-5',
  'bg-poster-6',
  'bg-poster-7',
  'bg-poster-8',
] as const;

/** FNV-1a 32비트. 같은 입력은 항상 같은 값을 낸다. */
export function posterHash(id: number | string, title: string): number {
  const input = `${id}:${title}`;
  let hash = 0x811c9dc5;
  for (let i = 0; i < input.length; i += 1) {
    hash ^= input.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash >>> 0;
}

export const posterPaletteSize = backgrounds.length;

export function posterPaletteIndex(id: number | string, title: string) {
  return posterHash(id, title) % backgrounds.length;
}

function Pattern({ hash }: { hash: number }) {
  const kind = (hash >>> 8) % 3;
  const a = (hash >>> 11) % 60;
  const b = (hash >>> 17) % 60;
  const common = 'fill-white/15';
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 100 133"
      preserveAspectRatio="xMidYMid slice"
      className="absolute inset-0 size-full"
    >
      {kind === 0 && (
        <>
          <circle cx={20 + a} cy={30 + b / 2} r={38} className={common} />
          <circle
            cx={80 - a / 2}
            cy={110 - b / 2}
            r={30}
            className="fill-white/10"
          />
        </>
      )}
      {kind === 1 && (
        <>
          <polygon
            points={`0,${40 + a} 100,${b} 100,133 0,133`}
            className={common}
          />
          <polygon
            points={`0,${90 + b / 3} 100,${70 + a / 2} 100,133 0,133`}
            className="fill-white/10"
          />
        </>
      )}
      {kind === 2 && (
        <>
          <rect
            x={a / 2}
            y={10 + b / 2}
            width={55}
            height={55}
            className={common}
            transform={`rotate(${a} 50 66)`}
          />
          <rect
            x={30 + b / 2}
            y={70}
            width={50}
            height={50}
            className="fill-white/10"
            transform={`rotate(${-b} 50 66)`}
          />
        </>
      )}
    </svg>
  );
}

export interface PosterProps {
  id: number | string;
  title: string;
  /** 포스터 이미지가 있으면 생성형 대신 쓴다. */
  imageUrl?: string | null;
  className?: string;
}

export function Poster({ id, title, imageUrl, className }: PosterProps) {
  const hash = posterHash(id, title);
  return (
    <div
      data-visual-mask
      className={cx(
        'relative aspect-[3/4] w-full overflow-hidden',
        imageUrl ? 'bg-line' : backgrounds[hash % backgrounds.length],
        className,
      )}
    >
      {imageUrl ? (
        // eslint-disable-next-line @next/next/no-img-element -- 외부 포스터 호스트가 아직 정해지지 않았다.
        <img src={imageUrl} alt={title} className="size-full object-cover" />
      ) : (
        <>
          <Pattern hash={hash} />
          <div aria-hidden="true" className="absolute inset-x-0 bottom-0 p-4">
            <p className="line-clamp-4 break-keep text-heading font-extrabold text-white">
              {title}
            </p>
          </div>
        </>
      )}
    </div>
  );
}
