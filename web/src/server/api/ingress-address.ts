import 'server-only';

import { timingSafeEqual } from 'node:crypto';
import { isIP } from 'node:net';
import { headers } from 'next/headers';
import { env } from '@/server/env';

const CLIENT_IP = 'X-Ticketing-Client-IP';
const INGRESS_PROOF = 'X-Ticketing-Ingress-Proof';

export function verifiedIngressHeaders(source: Headers, proof: string) {
  const supplied = source.get(INGRESS_PROOF);
  if (
    !proof ||
    !supplied ||
    !/^[0-9a-f]{64}$/.test(supplied) ||
    !timingSafeEqual(Buffer.from(supplied), Buffer.from(proof))
  )
    return null;

  const address = source.get(CLIENT_IP);
  if (!address || address.includes('%') || !isIP(address)) return null;

  return { [CLIENT_IP]: address, [INGRESS_PROOF]: proof };
}

export async function requestIngressHeaders() {
  const proof = env.TICKETING_INGRESS_PROOF;
  return proof ? verifiedIngressHeaders(await headers(), proof) : null;
}
