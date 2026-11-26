import { revalidatePath } from 'next/cache';
import { z } from 'zod';
import { completePosterUpload, issuePosterUpload } from '@/server/api/catalog';
import { errorResponse, positiveId } from '@/server/api/route-utils';
import { assertSameOrigin } from '@/server/auth/csrf';

const input = z.union([
  z.object({ performanceId: positiveId, contentType: z.string().min(1) }),
  z.object({ performanceId: positiveId, uploadId: z.uuid() }),
]);

export async function POST(request: Request) {
  try {
    assertSameOrigin(request.headers);
    const data = input.parse(await request.json());
    if ('contentType' in data) {
      const upload = await issuePosterUpload(
        data.performanceId,
        data.contentType,
      );
      return Response.json(upload, {
        headers: { 'Cache-Control': 'no-store' },
      });
    }

    const result = await completePosterUpload(
      data.performanceId,
      data.uploadId,
    );
    revalidatePath('/');
    revalidatePath(`/performances/${data.performanceId}`);
    return Response.json(result);
  } catch (error) {
    return errorResponse(error);
  }
}
