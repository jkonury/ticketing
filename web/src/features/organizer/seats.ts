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
