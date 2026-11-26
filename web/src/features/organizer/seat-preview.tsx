import { cx } from '@/lib/cx';
import type { Grade, LayoutPreview } from './seats';

const gradeChip: Record<Grade, string> = {
  VIP: 'border-brand bg-brand text-on-brand',
  R: 'border-brand-line bg-brand-soft text-brand-strong',
  S: 'border-line-strong bg-seat-available text-fg',
  A: 'border-seat-sold bg-seat-sold text-on-seat-sold',
};

/** 화면에 그릴 최대 열·좌석 수. 넘으면 앞부분만 보여 준다. */
const maxRows = 20;
const maxCols = 24;

export function SeatPreview({
  preview,
  message,
}: {
  preview: LayoutPreview;
  message?: string;
}) {
  if (preview.error)
    return (
      <p className="text-caption text-fg-muted">
        {message ??
          '구역, 열 수, 열당 좌석 수와 등급별 열 수를 입력하면 배치를 미리 볼 수 있어요.'}
      </p>
    );
  const rows = preview.rowGrades.slice(0, maxRows);
  const cols = Math.min(preview.perRow, maxCols);
  const used = Array.from(new Set(preview.rowGrades));
  return (
    <div className="grid gap-4">
      <p className="text-caption text-fg-muted">
        구역 {preview.sections.length}개 · 전체 {preview.total}석
      </p>
      <div className="flex flex-wrap gap-2" aria-hidden="true">
        {used.map((grade) => (
          <span
            key={grade}
            className={cx(
              'rounded-pill border px-2 py-0.5 text-micro font-semibold',
              gradeChip[grade],
            )}
          >
            {grade}
          </span>
        ))}
      </div>
      <div
        role="img"
        aria-label={`${preview.sections.join(', ')} 구역, 구역마다 ${preview.rowGrades.length}열 ${preview.perRow}석`}
        className="grid max-h-96 gap-4 overflow-auto"
      >
        {preview.sections.map((section) => (
          <div key={section} className="grid gap-1.5">
            <p className="text-caption font-semibold text-fg">{section}구역</p>
            <div className="grid gap-1">
              {rows.map((grade, row) => (
                <div
                  key={row}
                  className="grid gap-1"
                  style={{ gridTemplateColumns: `repeat(${cols}, 1.25rem)` }}
                >
                  {Array.from({ length: cols }, (_, col) => (
                    <span
                      key={col}
                      className={cx(
                        'h-5 rounded-control border',
                        gradeChip[grade],
                      )}
                    />
                  ))}
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
      {preview.rowGrades.length > maxRows || preview.perRow > maxCols ? (
        <p className="text-micro text-fg-muted">
          큰 배치는 앞부분만 보여 줘요.
        </p>
      ) : null}
    </div>
  );
}
