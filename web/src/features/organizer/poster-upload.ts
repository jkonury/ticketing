import { z } from 'zod';

export const posterTypes = ['image/jpeg', 'image/png', 'image/webp'];
export const posterMaxSize = 5 * 1024 * 1024;

const uploadSchema = z.object({
  uploadId: z.uuid(),
  url: z.url({ protocol: /^https?$/ }),
  headers: z.record(z.string(), z.string()),
});

async function posterRequest(payload: {
  performanceId: number;
  contentType?: string;
  uploadId?: string;
}) {
  const response = await fetch('/api/poster-uploads', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    const problem = z
      .object({ code: z.string() })
      .safeParse(await response.json().catch(() => null));
    throw new Error(problem.data?.code ?? 'POSTER_UPLOAD_FAILED');
  }
  return response.json();
}

export async function uploadPoster(performanceId: number, file: File) {
  const upload = uploadSchema.parse(
    await posterRequest({ performanceId, contentType: file.type }),
  );
  const response = await fetch(upload.url, {
    method: 'PUT',
    credentials: 'omit',
    headers: upload.headers,
    body: file,
  });
  if (!response.ok) throw new Error('POSTER_UPLOAD_FAILED');
  return upload.uploadId;
}

export async function confirmPoster(performanceId: number, uploadId: string) {
  await posterRequest({ performanceId, uploadId });
}
