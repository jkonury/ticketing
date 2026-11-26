export const grades = ['VIP', 'R', 'S', 'A'] as const;
export type Grade = (typeof grades)[number];
export function makeSeats(
  sections: string[],
  rows: number,
  perRow: number,
  counts: Record<Grade, number>,
) {
  if (
    !sections.length ||
    sections.some((section) => !section || section.length > 20) ||
    new Set(sections).size !== sections.length ||
    !Number.isInteger(rows) ||
    !Number.isInteger(perRow) ||
    rows < 1 ||
    perRow < 1 ||
    grades.some(
      (grade) => !Number.isInteger(counts[grade]) || counts[grade] < 0,
    )
  )
    throw new Error('INVALID_SEATS');
  if (grades.reduce((sum, grade) => sum + counts[grade], 0) !== rows)
    throw new Error('ROW_COUNT_MISMATCH');
  if (sections.length * rows * perRow > 5000) throw new Error('TOO_MANY_SEATS');
  const rowGrades = grades.flatMap((grade) =>
    Array<Grade>(counts[grade]).fill(grade),
  );
  return sections.flatMap((section) =>
    rowGrades.flatMap((grade, index) =>
      Array.from({ length: perRow }, (_, number) => ({
        section,
        row: String(index + 1),
        number: number + 1,
        grade,
      })),
    ),
  );
}

export function parseSections(text: string) {
  return text
    .split(',')
    .map((section) => section.trim())
    .filter(Boolean);
}

export type LayoutError =
  'INVALID_SEATS' | 'ROW_COUNT_MISMATCH' | 'TOO_MANY_SEATS';

export type LayoutPreview =
  | {
      error: null;
      sections: string[];
      perRow: number;
      /** 앞 열부터의 등급. 모든 구역이 같다. */
      rowGrades: Grade[];
      total: number;
    }
  | { error: LayoutError };

/** 입력 중인 구역·열·좌석 수로 배치를 만든다. 서버 등록과 같은 makeSeats 규칙을 쓴다. */
export function previewLayout(input: {
  sections: string;
  rows: number;
  perRow: number;
  counts: Record<Grade, number>;
}): LayoutPreview {
  const sections = parseSections(input.sections);
  try {
    const seats = makeSeats(sections, input.rows, input.perRow, input.counts);
    const first = sections[0];
    const rowGrades = seats
      .filter((seat) => seat.section === first && seat.number === 1)
      .map((seat) => seat.grade);
    return {
      error: null,
      sections,
      perRow: input.perRow,
      rowGrades,
      total: seats.length,
    };
  } catch (error) {
    const code = error instanceof Error ? error.message : '';
    return {
      error:
        code === 'ROW_COUNT_MISMATCH' || code === 'TOO_MANY_SEATS'
          ? code
          : 'INVALID_SEATS',
    };
  }
}
