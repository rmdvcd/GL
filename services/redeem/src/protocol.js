/**
 * Transcripts firmados del protocolo de canje.
 *
 * Cada operacion tiene su propio dominio para que una firma de canje no pueda
 * reutilizarse como firma de estado, ni una licencia como lote.
 */

import { canonical } from "./canonical.js";

export const REDEEM_REQUEST_DOMAIN = "GL-REDEEM-REQUEST1";
export const STATUS_REQUEST_DOMAIN = "GL-REDEEM-STATUS-REQUEST1";
export const BATCH_DOMAIN = "GL-BATCH1";
export const BATCH_ENTRY_DOMAIN = "GL-BATCH1-ENTRY";
export const LICENSE_DOMAIN = "GL-REDEEM-LICENSE1";
export const STATUS_DOMAIN = "GL-REDEEM-STATUS1";

export function redeemProofBytes(request) {
  return canonical([
    REDEEM_REQUEST_DOMAIN,
    String(request.v),
    request.appId,
    request.code,
    request.deviceKey,
    request.requestId,
    request.requestedAt,
    request.nonce,
  ]);
}

export function statusProofBytes(request) {
  return canonical([
    STATUS_REQUEST_DOMAIN,
    String(request.v),
    request.appId,
    request.licenseId,
    request.deviceKey,
    request.requestId,
    request.requestedAt,
    request.nonce,
  ]);
}
