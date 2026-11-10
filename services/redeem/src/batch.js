/**
 * Lote de codigos acuñado por GL.
 *
 * GL firma el lote con su clave ECDSA de emisor (`gl-sign-v1`) y lo exporta como
 * JSON. El servicio solo acepta lotes cuya firma verifique contra la clave
 * publica fijada. El codigo en claro existe en el lote; el servicio lo convierte
 * a HMAC y lo descarta.
 */

import { canonical, fromBase64, sha256, toBase64, toBase64Url } from "./canonical.js";
import { normalizeCode, isValidCode } from "./code.js";
import { LICENSE_TYPES, isIsoInstant } from "./license.js";
import { signDer, verifyDer } from "./keys.js";

export const BATCH_VERSION = 1;
export const BATCH_ALG = "GL-BATCH-P256-SHA256-v1";
export const BATCH_DOMAIN = "GL-BATCH1";
export const BATCH_ENTRY_DOMAIN = "GL-BATCH1-ENTRY";
export const DEFAULT_MAX_ENTRIES = 5000;

const APP_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$/;
const BATCH_ID_RE = /^[A-Za-z0-9._:-]{8,64}$/;

export function canonicalBatchEntry(entry) {
  const normalized = normalizeCode(entry.code);
  return canonical([
    BATCH_ENTRY_DOMAIN,
    normalized,
    entry.type,
    String(entry.secundarias),
    entry.redeemBefore ?? "",
  ]);
}

export async function batchSigningBytes(batch) {
  const parts = [
    BATCH_DOMAIN,
    String(batch.v),
    batch.alg,
    batch.batchId,
    batch.issuerKid,
    batch.createdAt,
    batch.appId,
    String(batch.entries.length),
  ];
  for (const entry of batch.entries) {
    const digest = new Uint8Array(await sha256(canonicalBatchEntry(entry)));
    parts.push(toBase64Url(digest));
  }
  return canonical(parts);
}

/**
 * Valida estructura + firma. Devuelve un lote normalizado (codigos sin guiones)
 * o lanza `batch-error:<motivo>`. No toca el almacen.
 */
export async function verifyBatch(batch, options) {
  const {
    glPublicKey,
    allowedApps,
    maxEntries = DEFAULT_MAX_ENTRIES,
    maxSecundarias = 0,
    requireFutureDeadline = false,
    nowMs,
  } = options;
  if (!batch || typeof batch !== "object" || Array.isArray(batch)) throw new Error("batch-error:shape");
  if (batch.v !== BATCH_VERSION) throw new Error("batch-error:version");
  if (batch.alg !== BATCH_ALG) throw new Error("batch-error:alg");
  if (typeof batch.batchId !== "string" || !BATCH_ID_RE.test(batch.batchId)) {
    throw new Error("batch-error:batch-id");
  }
  if (batch.issuerKid !== "gl-sign-v1") throw new Error("batch-error:kid");
  if (!isIsoInstant(batch.createdAt)) throw new Error("batch-error:created-at");
  if (typeof batch.appId !== "string" || !APP_ID_RE.test(batch.appId)) {
    throw new Error("batch-error:app-id");
  }
  if (Array.isArray(allowedApps) && allowedApps.length > 0 && !allowedApps.includes(batch.appId)) {
    throw new Error("batch-error:app-not-allowed");
  }
  if (!Array.isArray(batch.entries) || batch.entries.length === 0) {
    throw new Error("batch-error:entries");
  }
  if (batch.entries.length > maxEntries) throw new Error("batch-error:too-many-entries");
  if (typeof batch.sig !== "string" || batch.sig.length === 0) throw new Error("batch-error:sig");

  const seen = new Set();
  const entries = batch.entries.map((entry, index) => {
    if (!entry || typeof entry !== "object") throw new Error(`batch-error:entry-${index}`);
    if (typeof entry.code !== "string" || !isValidCode(entry.code)) {
      throw new Error(`batch-error:entry-code-${index}`);
    }
    const normalized = normalizeCode(entry.code);
    if (seen.has(normalized)) throw new Error(`batch-error:duplicate-code-${index}`);
    seen.add(normalized);
    if (!Object.prototype.hasOwnProperty.call(LICENSE_TYPES, entry.type)) {
      throw new Error(`batch-error:entry-type-${index}`);
    }
    const secundarias = entry.secundarias ?? 0;
    if (!Number.isInteger(secundarias) || secundarias < 0 || secundarias > 10) {
      throw new Error(`batch-error:entry-secondary-${index}`);
    }
    if (secundarias > maxSecundarias) {
      throw new Error(`batch-error:entry-secondary-not-allowed-${index}`);
    }
    if (entry.redeemBefore !== undefined && entry.redeemBefore !== null) {
      if (!isIsoInstant(entry.redeemBefore)) throw new Error(`batch-error:entry-deadline-${index}`);
      if (requireFutureDeadline && Number.isFinite(nowMs) && Date.parse(entry.redeemBefore) <= nowMs) {
        throw new Error(`batch-error:entry-deadline-past-${index}`);
      }
    }
    return {
      code: normalized,
      type: entry.type,
      secundarias,
      redeemBefore: entry.redeemBefore ?? null,
    };
  });

  const signature = fromBase64(batch.sig);
  const ok = await verifyDer(glPublicKey, await batchSigningBytes(batch), signature);
  if (!ok) throw new Error("batch-error:signature");

  return {
    v: batch.v,
    alg: batch.alg,
    batchId: batch.batchId,
    issuerKid: batch.issuerKid,
    createdAt: batch.createdAt,
    appId: batch.appId,
    entries,
  };
}

/** Acuña un lote. Equivalente JS del caso de uso Kotlin de GL (para pruebas/CLI). */
export async function createSignedBatch(options) {
  const {
    signingPrivateKey,
    issuerKid = "gl-sign-v1",
    appId,
    entries,
    batchId,
    createdAt,
  } = options;
  const batch = {
    v: BATCH_VERSION,
    alg: BATCH_ALG,
    batchId,
    issuerKid,
    createdAt,
    appId,
    entries: entries.map((entry) => ({
      code: entry.code,
      type: entry.type,
      secundarias: entry.secundarias ?? 0,
      ...(entry.redeemBefore ? { redeemBefore: entry.redeemBefore } : {}),
    })),
  };
  const signature = await signDer(signingPrivateKey, await batchSigningBytes(batch));
  batch.sig = toBase64(signature);
  return batch;
}

/** Vector estable para comprobar que Kotlin y JS canonicalizan igual. */
export function canonicalEntryVectorForTests(entry) {
  return canonicalBatchEntry(entry);
}

