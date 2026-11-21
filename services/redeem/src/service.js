/**
 * Nucleo del servicio de canje. Independiente del transporte (Workers/Node) y
 * del almacen (memoria/D1).
 *
 * Reglas de seguridad implementadas aqui:
 *  - el codigo solo se usa para HMAC y busqueda; nunca se registra;
 *  - la prueba de posesion de la clave del dispositivo cubre codigo, app,
 *    requestId, instante y nonce;
 *  - un codigo tiene un unico canje activo;
 *  - reintentos con el mismo requestId devuelven la misma licencia;
 *  - reintentos con otro requestId desde la misma clave devuelven la licencia
 *    existente (restauracion) sin ampliar el vencimiento;
 *  - otro dispositivo recibe un error generico sin datos de la licencia.
 */

import { fromBase64, toBase64 } from "./canonical.js";
import { hashRateKey } from "./rate-limit.js";
import { formatCode, generateNormalizedCode, hashCode, normalizeCode } from "./code.js";
import { ApiError } from "./errors.js";
import {
  LICENSE_TYPES,
  deviceThumbprintOfSpkiBytes,
  licensePayloadFromRow,
  signLicenseEnvelope,
  signStatusDoc,
} from "./license.js";
import { importSpkiB64, verifyDer } from "./keys.js";
import { verifyBatch } from "./batch.js";
import { redeemProofBytes, statusProofBytes } from "./protocol.js";

export const SERVICE_VERSION = 1;

const APP_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$/;
const REQUEST_ID_RE = /^[A-Za-z0-9_-]{8,64}$/;
const NONCE_RE = /^[A-Za-z0-9+/=_-]{16,128}$/;
const LICENSE_ID_RE = /^[A-Za-z0-9._:-]{8,64}$/;
const MAX_BODY_FIELDS = 16;

function requireString(value, name, maxLength = 256) {
  if (typeof value !== "string" || value.length === 0 || value.length > maxLength) {
    throw new ApiError("INVALID_REQUEST", { detail: name });
  }
  return value;
}

function requireInstantInWindow(value, nowMs, skewMs) {
  requireString(value, "instant", 40);
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed) || !value.endsWith("Z")) throw new ApiError("INVALID_REQUEST");
  if (Math.abs(nowMs - parsed) > skewMs) throw new ApiError("INVALID_REQUEST");
  return parsed;
}

export class RedeemService {
  constructor(options) {
    const {
      store,
      pepper,
      glPublicKey,
      servicePrivateKey,
      servicePublicKey,
      serviceKid = "gl-redeem-v1",
      glKid = "gl-sign-v1",
      allowedApps = [],
      now = () => Date.now(),
      maxClockSkewMs = 5 * 60 * 1000,
      maxEntries = 5000,
      maxSecundarias = 0,
      logger = null,
      rateLimiter = null,
    } = options;
    if (!store) throw new Error("store-required");
    if (!(pepper instanceof Uint8Array) || pepper.length < 32) throw new Error("pepper-required");
    if (!glPublicKey || !servicePrivateKey || !servicePublicKey) throw new Error("keys-required");
    this.store = store;
    this.pepper = pepper;
    this.glPublicKey = glPublicKey;
    this.servicePrivateKey = servicePrivateKey;
    this.servicePublicKey = servicePublicKey;
    this.serviceKid = serviceKid;
    this.glKid = glKid;
    this.allowedApps = allowedApps;
    this.now = now;
    this.maxClockSkewMs = maxClockSkewMs;
    this.maxEntries = maxEntries;
    this.maxSecundarias = maxSecundarias;
    this.logger = logger;
    this.rateLimiter = rateLimiter;
  }

  log(event, fields = {}) {
    if (this.logger) this.logger(event, fields);
  }

  async checkLimit(key) {
    if (!this.rateLimiter) return;
    // El contador guarda un HMAC, no el thumbprint ni la IP.
    const hashed = await hashRateKey(this.pepper, "limit", key);
    const allowed = await this.rateLimiter.check(hashed, this.now());
    if (!allowed) {
      this.log("rate_limited", {});
      throw new ApiError("RATE_LIMITED");
    }
  }

  async verifyDevice(request, proofBytes) {
    let publicKey;
    let spkiBytes;
    try {
      spkiBytes = fromBase64(request.deviceKey);
      if (spkiBytes.length < 60 || spkiBytes.length > 200) throw new Error("spki-length");
      publicKey = await importSpkiB64(request.deviceKey);
    } catch {
      throw new ApiError("INVALID_PROOF");
    }
    let signature;
    try {
      signature = fromBase64(request.proof);
      if (signature.length === 0 || signature.length > 128) throw new Error("sig-length");
    } catch {
      throw new ApiError("INVALID_PROOF");
    }
    const ok = await verifyDer(publicKey, proofBytes, signature);
    if (!ok) throw new ApiError("INVALID_PROOF");
    return {
      publicKey,
      publicKeyB64: toBase64(spkiBytes),
      thumbprint: await deviceThumbprintOfSpkiBytes(spkiBytes),
    };
  }

  validateEnvelope(body) {
    if (!body || typeof body !== "object" || Array.isArray(body)) {
      throw new ApiError("INVALID_REQUEST");
    }
    if (Object.keys(body).length > MAX_BODY_FIELDS) throw new ApiError("INVALID_REQUEST");
    if (body.v !== SERVICE_VERSION) throw new ApiError("INVALID_REQUEST");
    if (typeof body.appId !== "string" || !APP_ID_RE.test(body.appId)) {
      throw new ApiError("INVALID_REQUEST");
    }
    if (typeof body.requestId !== "string" || !REQUEST_ID_RE.test(body.requestId)) {
      throw new ApiError("INVALID_REQUEST");
    }
    if (typeof body.nonce !== "string" || !NONCE_RE.test(body.nonce)) {
      throw new ApiError("INVALID_REQUEST");
    }
    requireString(body.deviceKey, "deviceKey", 400);
    requireString(body.proof, "proof", 400);
    const nowMs = this.now();
    requireInstantInWindow(body.requestedAt, nowMs, this.maxClockSkewMs);
    return { nowMs };
  }

  /** Importa un lote firmado por GL. Devuelve contadores; nunca el codigo. */
  async importBatch(batch) {
    let verified;
    try {
      verified = await verifyBatch(batch, {
        glPublicKey: this.glPublicKey,
        allowedApps: this.allowedApps,
        maxEntries: this.maxEntries,
        maxSecundarias: this.maxSecundarias,
      });
    } catch (error) {
      this.log("batch_rejected", { reason: String(error?.message ?? "unknown").slice(0, 64) });
      throw new ApiError("BATCH_INVALID", { detail: error?.message });
    }
    const entries = [];
    for (const entry of verified.entries) {
      entries.push({
        codeHash: await hashCode(entry.code, this.pepper),
        type: entry.type,
        secundarias: entry.secundarias,
        redeemBefore: entry.redeemBefore,
      });
    }
    const result = await this.store.importCodes(entries, verified);
    if (result.conflicts.length > 0) {
      this.log("batch_conflict", { batchId: verified.batchId, conflicts: result.conflicts.length });
      throw new ApiError("BATCH_INVALID", { detail: "code-conflict" });
    }
    this.log("batch_imported", {
      batchId: verified.batchId,
      appId: verified.appId,
      imported: result.imported,
      skipped: result.skipped,
    });
    return { imported: result.imported, skipped: result.skipped };
  }

  async redeem(body) {
    const { nowMs } = this.validateEnvelope(body);
    if (typeof body.code !== "string" || body.code.length > 64) {
      throw new ApiError("CODE_UNAVAILABLE");
    }
    let normalized;
    try {
      normalized = normalizeCode(body.code);
    } catch {
      throw new ApiError("CODE_UNAVAILABLE");
    }
    const request = {
      v: body.v,
      appId: body.appId,
      code: normalized,
      deviceKey: body.deviceKey,
      requestId: body.requestId,
      requestedAt: body.requestedAt,
      nonce: body.nonce,
      proof: body.proof,
    };
    const { thumbprint, publicKeyB64 } = await this.verifyDevice(
      request,
      redeemProofBytes(request),
    );
    await this.checkLimit(`redeem:${thumbprint}`);

    const codeHash = await hashCode(normalized, this.pepper);

    // Idempotencia: un requestId pertenece a una sola licencia y dispositivo.
    const byRequest = await this.store.getByRequest(request.requestId);
    if (byRequest) {
      if (byRequest.deviceThumbprint !== thumbprint || byRequest.codeHash !== codeHash) {
        throw new ApiError("IDEMPOTENCY_CONFLICT");
      }
      return this.finishResponse(byRequest, nowMs, true);
    }

    const code = await this.store.getCode(codeHash);
    if (!code || code.state === "REVOKED" || code.appId !== request.appId) {
      this.log("redeem_unavailable", { appId: request.appId });
      throw new ApiError("CODE_UNAVAILABLE");
    }
    if (code.redeemBefore && Date.parse(code.redeemBefore) <= nowMs) {
      throw new ApiError("CODE_EXPIRED");
    }

    const active = await this.store.getActiveByCode(codeHash);
    if (active) {
      if (active.deviceThumbprint !== thumbprint) {
        this.log("redeem_other_device", { appId: request.appId });
        throw new ApiError("CODE_ALREADY_REDEEMED");
      }
      return this.finishResponse(active, nowMs, true);
    }

    // Reclamo nuevo. Si ya existe un canje inactivo para este codigo, solo se
    // admite una transferencia autorizada por el operador hacia esta misma
    // instalacion; nunca se regala un periodo nuevo.
    const previous = await this.store.getLatestByCode(codeHash);
    const issuedAt = new Date(nowMs).toISOString();
    let expiresAt = null;
    let transferredFrom = null;

    if (previous) {
      const transfer = await this.store.getPendingTransfer(codeHash);
      if (!transfer || transfer.fromLicenseId !== previous.licenseId) {
        throw new ApiError("CODE_UNAVAILABLE");
      }
      if (transfer.toThumbprint !== thumbprint) {
        throw new ApiError("CODE_ALREADY_REDEEMED");
      }
      transferredFrom = previous.licenseId;
      if (code.type !== "PERPETUA") {
        const remaining = Date.parse(previous.expiresAt ?? "");
        if (!Number.isFinite(remaining)) throw new ApiError("SERVER_ERROR");
        if (remaining <= nowMs) {
          // Una transferencia no regala un periodo nuevo: el operador debe
          // emitir otro codigo.
          throw new ApiError("CODE_EXPIRED");
        }
        expiresAt = new Date(remaining).toISOString();
      }
    } else if (code.type !== "PERPETUA") {
      const days = LICENSE_TYPES[code.type];
      if (!Number.isInteger(days) || days <= 0) throw new ApiError("SERVER_ERROR");
      // Rollover: un codigo nuevo no hace perder los dias que le quedan a la
      // licencia activa de esta misma instalacion y app. Un reintento no pasa
      // por aqui (el canje ya existe), asi que no se duplica el periodo.
      let baseMs = nowMs;
      const current = await this.store.getActiveByDevice(code.appId, thumbprint);
      if (current?.expiresAt) {
        const currentExpiry = Date.parse(current.expiresAt);
        if (Number.isFinite(currentExpiry) && currentExpiry > nowMs) baseMs = currentExpiry;
      }
      expiresAt = new Date(baseMs + days * 24 * 60 * 60 * 1000).toISOString();
    }

    const row = {
      licenseId: crypto.randomUUID(),
      redemptionId: crypto.randomUUID(),
      requestId: request.requestId,
      codeHash,
      appId: code.appId,
      type: code.type,
      secundarias: code.secundarias ?? 0,
      status: code.type === "PERPETUA" ? "PERPETUA" : "ACTIVA",
      issuedAt,
      expiresAt,
      deviceThumbprint: thumbprint,
      devicePublicKey: publicKeyB64,
      active: true,
      transferredFrom,
      createdAt: issuedAt,
    };

    const claim = await this.store.claim(row);
    if (!claim.ok) {
      if (claim.reason === "request-exists") {
        if (
          claim.existing.deviceThumbprint !== thumbprint ||
          claim.existing.codeHash !== codeHash
        ) {
          throw new ApiError("IDEMPOTENCY_CONFLICT");
        }
        return this.finishResponse(claim.existing, nowMs, true);
      }
      if (claim.existing.deviceThumbprint !== thumbprint) {
        this.log("redeem_race_other_device", { appId: request.appId });
        throw new ApiError("CODE_ALREADY_REDEEMED");
      }
      return this.finishResponse(claim.existing, nowMs, true);
    }

    this.log("redeem_ok", { appId: row.appId, type: row.type, transferred: Boolean(transferredFrom) });
    return this.finishResponse(claim.row, nowMs, false);
  }

  async finishResponse(row, nowMs, restored) {
    const payload = licensePayloadFromRow(row, nowMs);
    const envelope = await signLicenseEnvelope(payload, this.servicePrivateKey, this.serviceKid);
    return { license: envelope, restored, licenseId: payload.licenseId };
  }

  async status(body) {
    const { nowMs } = this.validateEnvelope(body);
    if (typeof body.licenseId !== "string" || !LICENSE_ID_RE.test(body.licenseId)) {
      throw new ApiError("INVALID_REQUEST");
    }
    const request = {
      v: body.v,
      appId: body.appId,
      licenseId: body.licenseId,
      deviceKey: body.deviceKey,
      requestId: body.requestId,
      requestedAt: body.requestedAt,
      nonce: body.nonce,
      proof: body.proof,
    };
    const { thumbprint } = await this.verifyDevice(request, statusProofBytes(request));
    await this.checkLimit(`status:${thumbprint}`);

    const row = await this.store.getByLicenseId(request.licenseId);
    if (!row || row.deviceThumbprint !== thumbprint || row.appId !== request.appId) {
      throw new ApiError("LICENSE_NOT_FOUND");
    }
    const payload = licensePayloadFromRow(row, nowMs);
    const doc = {
      ...payload,
      checkedAt: new Date(nowMs).toISOString(),
    };
    const envelope = await signStatusDoc(doc, this.servicePrivateKey, this.serviceKid);
    this.log("status_ok", { appId: doc.appId, status: doc.status });
    return { status: envelope };
  }

  /** Operacion de operador, no expuesta por HTTP. */
  async revokeLicense(licenseId) {
    if (typeof licenseId !== "string" || !LICENSE_ID_RE.test(licenseId)) {
      throw new ApiError("INVALID_REQUEST");
    }
    const row = await this.store.getByLicenseId(licenseId);
    if (!row) throw new ApiError("LICENSE_NOT_FOUND");
    const affected = await this.store.revokeCodeCascade(row.codeHash);
    this.log("license_revoked", { appId: row.appId });
    return { licenseId, status: "REVOCADA", affected };
  }

  /**
   * Autoriza una transferencia: el canje activo deja de serlo y la proxima
   * instalacion que presente el mismo codigo hereda el vencimiento restante.
   * La licencia anterior queda REVOCADA.
   */
  async authorizeTransfer(licenseId, toThumbprint) {
    if (typeof licenseId !== "string" || !LICENSE_ID_RE.test(licenseId)) {
      throw new ApiError("INVALID_REQUEST");
    }
    if (typeof toThumbprint !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(toThumbprint)) {
      throw new ApiError("INVALID_REQUEST");
    }
    const existing = await this.store.getByLicenseId(licenseId);
    if (!existing) throw new ApiError("LICENSE_NOT_FOUND");
    if (existing.active) {
      const row = await this.store.deactivateForTransfer(licenseId, toThumbprint);
      if (!row) throw new ApiError("LICENSE_NOT_FOUND");
      this.log("license_transfer_authorized", { appId: row.appId });
      return { licenseId, status: "REVOCADA", toThumbprint };
    }
    // Permitir corregir el destino de una transferencia ya autorizada (por
    // ejemplo, un thumbprint mal copiado) sin dejar el codigo bloqueado.
    const pending = await this.store.getPendingTransfer(existing.codeHash);
    if (!pending || pending.fromLicenseId !== licenseId) {
      throw new ApiError("LICENSE_NOT_FOUND");
    }
    const updated = await this.store.updatePendingTransfer(existing.codeHash, toThumbprint);
    if (!updated) throw new ApiError("LICENSE_NOT_FOUND");
    this.log("license_transfer_updated", { appId: existing.appId });
    return { licenseId, status: "REVOCADA", toThumbprint };
  }

  async revokeCode(code) {
    let normalized;
    try {
      normalized = normalizeCode(code);
    } catch {
      throw new ApiError("INVALID_REQUEST");
    }
    const codeHash = await hashCode(normalized, this.pepper);
    const affected = await this.store.revokeCodeCascade(codeHash);
    this.log("code_revoked", {});
    return { affected, status: "REVOKED" };
  }

  /** Utilidad de operador para pruebas manuales: no forma parte del protocolo. */
  static generateCode() {
    return formatCode(generateNormalizedCode());
  }
}

