/**
 * Cliente de referencia del canje (runtime JS). La version Android debe usar
 * Android Keystore para la clave del dispositivo; este modulo define el
 * contrato y sirve para pruebas de extremo a extremo.
 *
 * Principios:
 *  - el formato 4x4 valido NO activa nada: la activacion exige canje online y
 *    una licencia firmada que verifique contra la clave fijada;
 *  - la licencia se verifica antes de persistirse;
 *  - los reintentos conservan el requestId;
 *  - con conexion perdida tras el canje, la licencia no se guarda a medias;
 *  - sin conexion, la licencia vale hasta su vencimiento (politica elegida).
 */

import { fromBase64, toBase64 } from "../canonical.js";
import {
  formatCode,
  generateNormalizedCode,
  isValidCode,
  normalizeCode,
} from "../code.js";
import {
  effectiveStatus,
  verifyLicenseEnvelope,
  verifyStatusDoc,
} from "../license.js";
import { redeemProofBytes, statusProofBytes } from "../protocol.js";
import { createSubToken as createSubTokenSigned, verifySubToken } from "../subtoken.js";
import {
  exportSpkiB64,
  generateP256KeyPair,
  importSpkiB64,
  keyThumbprint,
  signDer,
} from "../keys.js";

export class RedeemError extends Error {
  constructor(code, options = {}) {
    super(options.message ?? code);
    this.name = "RedeemError";
    this.code = code;
    this.status = options.status ?? 0;
    this.requestId = options.requestId ?? null;
  }
}

export class RedeemNetworkError extends RedeemError {
  constructor(message, requestId) {
    super("NETWORK", { message: message ?? "network", requestId });
    this.name = "RedeemNetworkError";
  }
}

export class MemoryLicenseStore {
  constructor(initial = null) {
    this.value = initial;
  }

  async get() {
    return this.value;
  }

  async set(value) {
    this.value = value;
  }

  async clear() {
    this.value = null;
  }
}

const REQUEST_ID_RE = /^[A-Za-z0-9_-]{8,64}$/;
const STORE_VERSION = 1;

function randomId() {
  return crypto.randomUUID();
}

function isoNow(nowMs) {
  return new Date(nowMs).toISOString();
}

export class RedeemClient {
  constructor(options) {
    const {
      baseUrl,
      appId,
      pinnedKeys,
      deviceKeys,
      store = null,
      fetchImpl = globalThis.fetch,
      now = () => Date.now(),
      timeoutMs = 15_000,
      clockRollbackToleranceMs = 2 * 60 * 60 * 1000,
      legacyVerifier = null,
    } = options;
    if (typeof baseUrl !== "string" || baseUrl.length === 0) throw new Error("base-url-required");
    if (typeof appId !== "string" || appId.length === 0) throw new Error("app-id-required");
    if (!pinnedKeys || Object.keys(pinnedKeys).length === 0) throw new Error("pinned-keys-required");
    if (typeof fetchImpl !== "function") throw new Error("fetch-required");
    this.baseUrl = baseUrl.replace(/\/+$/, "");
    this.appId = appId;
    this.pinnedKeys = { ...pinnedKeys };
    this.store = store;
    this.fetchImpl = fetchImpl;
    this.now = now;
    this.timeoutMs = timeoutMs;
    this.clockRollbackToleranceMs = clockRollbackToleranceMs;
    this.legacyVerifier = legacyVerifier;
    this.deviceKeys = deviceKeys;
    this.ready = this.#init();
  }

  /**
   * Exige una identidad de dispositivo ya persistida. Generar claves nuevas en
   * cada arranque perderia la licencia y permitiria canjear el codigo una sola
   * vez por instalacion; para pruebas existe `createEphemeral`.
   */
  static async create(options) {
    if (!options.deviceKeys) throw new Error("device-keys-required");
    return new RedeemClient(options);
  }

  /** Solo para pruebas o demos desechables: genera un par nuevo cada arranque. */
  static async createEphemeral(options) {
    return new RedeemClient({ ...options, deviceKeys: await generateP256KeyPair() });
  }

  async #init() {
    if (!this.deviceKeys || !this.deviceKeys.privateKey || !this.deviceKeys.publicKey) {
      throw new Error("device-keys-required");
    }
    this.deviceKeyB64 = await exportSpkiB64(this.deviceKeys.publicKey);
    const spkiBytes = fromBase64(this.deviceKeyB64);
    this.deviceThumbprint = await keyThumbprint(spkiBytes);
    this.importedPinned = {};
    for (const [kid, spki] of Object.entries(this.pinnedKeys)) {
      this.importedPinned[kid] = await importSpkiB64(spki);
    }
    // La marca de agua sobrevive reinicios: si el reloj retrocede, se detecta
    // antes de aceptar una licencia nueva.
    this.lastSeenMs = 0;
    if (this.store) {
      const record = await this.store.get();
      if (record && Number.isFinite(record.lastSeenMs)) {
        this.lastSeenMs = record.lastSeenMs;
      }
    }
  }

  #assertClock(nowMs = this.now()) {
    if (nowMs + this.clockRollbackToleranceMs < (this.lastSeenMs ?? 0)) {
      throw new RedeemError("CLOCK_ROLLBACK");
    }
  }

  async #publicKeyFor(kid) {
    await this.ready;
    const key = this.importedPinned[kid];
    if (!key) throw new RedeemError("UNKNOWN_KID");
    return key;
  }

  async #post(path, body, requestId) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      const text = await response.text();
      let parsed;
      try {
        parsed = JSON.parse(text);
      } catch {
        throw new RedeemError("INVALID_RESPONSE", {
          status: response.status,
          requestId,
        });
      }
      if (!response.ok || parsed?.ok !== true) {
        throw new RedeemError(parsed?.error?.code ?? "SERVER_ERROR", {
          status: response.status,
          requestId,
          message: parsed?.error?.message,
        });
      }
      return parsed;
    } catch (error) {
      if (error instanceof RedeemError) throw error;
      throw new RedeemNetworkError(error?.name ?? "network", requestId);
    } finally {
      clearTimeout(timer);
    }
  }

  async #proof(request) {
    await this.ready;
    return toBase64(await signDer(this.deviceKeys.privateKey, redeemProofBytes(request)));
  }

  async #statusProof(request) {
    await this.ready;
    return toBase64(await signDer(this.deviceKeys.privateKey, statusProofBytes(request)));
  }

  #baseRequest(requestId) {
    const nowMs = this.now();
    return {
      v: 1,
      appId: this.appId,
      requestId,
      requestedAt: isoNow(nowMs),
      nonce: toBase64(crypto.getRandomValues(new Uint8Array(16))),
    };
  }

  /**
   * Canjea un codigo. Para reintentar tras un fallo de red, reutiliza el
   * `requestId` del error (`error.requestId`) o simplemente vuelve a llamar:
   * el servicio devuelve la misma licencia sin ampliar el vencimiento.
   */
  async activate(code, options = {}) {
    await this.ready;
    this.#assertClock();
    let normalized;
    try {
      normalized = normalizeCode(code);
    } catch {
      throw new RedeemError("INVALID_CODE");
    }
    const requestId = options.requestId ?? randomId();
    if (!REQUEST_ID_RE.test(requestId)) throw new RedeemError("INVALID_REQUEST_ID");

    const base = this.#baseRequest(requestId);
    const request = {
      ...base,
      code: normalized,
      deviceKey: this.deviceKeyB64,
    };
    request.proof = await this.#proof(request);

    const response = await this.#post("/v1/redeem", request, requestId);
    const payload = await this.#verifyAndBindLicense(response.license);
    const status = effectiveStatus(payload, this.#referenceNow(this.now()));
    if (status === "VENCIDA") throw new RedeemError("LICENSE_EXPIRED", { requestId });
    if (status === "REVOCADA") throw new RedeemError("LICENSE_REVOKED", { requestId });

    await this.#persist(payload, response.license, status);
    return { license: payload, envelope: response.license, restored: Boolean(response.restored) };
  }

  async #verifyEnvelope(envelope) {
    if (!envelope || typeof envelope !== "object") throw new RedeemError("INVALID_LICENSE");
    const publicKey = await this.#publicKeyFor(envelope.kid);
    let payload;
    try {
      payload = await verifyLicenseEnvelope(envelope, publicKey);
    } catch {
      throw new RedeemError("INVALID_LICENSE_SIGNATURE");
    }
    if (payload.appId !== this.appId) throw new RedeemError("LICENSE_WRONG_APP");
    if (payload.devicePublicKey) {
      let derived;
      try {
        derived = await keyThumbprint(fromBase64(payload.devicePublicKey));
      } catch {
        throw new RedeemError("INVALID_LICENSE");
      }
      if (derived !== payload.deviceThumbprint) {
        throw new RedeemError("INVALID_LICENSE");
      }
    }
    return payload;
  }

  async #verifyAndBindLicense(envelope) {
    const payload = await this.#verifyEnvelope(envelope);
    if (payload.deviceThumbprint !== this.deviceThumbprint) {
      throw new RedeemError("LICENSE_WRONG_DEVICE");
    }
    return payload;
  }

  #referenceNow(nowMs) {
    const lastSeen = this.lastSeenMs ?? 0;
    return Math.max(nowMs, lastSeen);
  }

  async #persist(payload, envelope, status) {
    if (!this.store) return;
    const nowMs = this.now();
    this.#assertClock(nowMs);
    const record = {
      v: STORE_VERSION,
      kind: "PRIMARY",
      license: envelope,
      appId: payload.appId,
      deviceThumbprint: payload.deviceThumbprint,
      lastSeenMs: Math.max(nowMs, this.lastSeenMs ?? 0),
      status,
    };
    this.lastSeenMs = record.lastSeenMs;
    await this.store.set(record);
  }

  async #persistSub(payload, envelope, subToken, status) {
    if (!this.store) return;
    const nowMs = this.now();
    this.#assertClock(nowMs);
    const record = {
      v: STORE_VERSION,
      kind: "SUB",
      license: envelope,
      subToken,
      appId: payload.appId,
      deviceThumbprint: this.deviceThumbprint,
      primaryLicenseId: payload.licenseId,
      lastSeenMs: Math.max(nowMs, this.lastSeenMs ?? 0),
      status,
    };
    this.lastSeenMs = record.lastSeenMs;
    await this.store.set(record);
  }

  /**
   * Emite un permiso para una instalacion secundaria. Solo la instalacion
   * primaria (con la clave que aparece en su licencia) puede firmarlo.
   */
  async createSubToken(secondaryThumbprint, slot) {
    await this.ready;
    const record = await this.store?.get();
    if (!record) throw new RedeemError("NO_LICENSE");
    if (record.kind === "SUB") throw new RedeemError("PRIMARY_LICENSE_REQUIRED");
    const payload = await this.#verifyAndBindLicense(record.license);
    const status = effectiveStatus(payload, this.#referenceNow(this.now()));
    if (status === "VENCIDA") throw new RedeemError("LICENSE_EXPIRED");
    if (status === "REVOCADA") throw new RedeemError("LICENSE_REVOKED");
    if (!Number.isInteger(payload.secundarias) || payload.secundarias < 1) {
      throw new RedeemError("NO_SECONDARIES");
    }
    try {
      return await createSubTokenSigned({
        primaryPayload: payload,
        secondaryThumbprint,
        slot,
        signingPrivateKey: this.deviceKeys.privateKey,
      });
    } catch (error) {
      throw new RedeemError("INVALID_SUBTOKEN_REQUEST", { message: error?.message });
    }
  }

  /** Paquete que se entrega a la instalacion secundaria (sin secretos). */
  async exportSubLicense(secondaryThumbprint, slot) {
    const subToken = await this.createSubToken(secondaryThumbprint, slot);
    const record = await this.store.get();
    return { v: 1, primaryLicense: record.license, subToken };
  }

  /**
   * Activa una licencia secundaria a partir del paquete offline. Verifica la
   * licencia primaria, el permiso firmado y que el permiso sea para este
   * dispositivo. No hay llamada al servicio.
   */
  async activateSubLicense(bundle, options = {}) {
    await this.ready;
    this.#assertClock();
    if (!bundle || bundle.v !== 1) throw new RedeemError("INVALID_SUBLICENSE");
    const payload = await this.#verifyEnvelope(bundle.primaryLicense);
    const nowMs = this.#referenceNow(this.now());
    const status = effectiveStatus(payload, nowMs);
    if (status === "VENCIDA") throw new RedeemError("LICENSE_EXPIRED");
    if (status === "REVOCADA") throw new RedeemError("LICENSE_REVOKED");
    let token;
    try {
      token = await verifySubToken(
        bundle.subToken,
        payload,
        this.deviceThumbprint,
        nowMs,
      );
    } catch {
      throw new RedeemError("INVALID_SUBTOKEN");
    }
    const existing = await this.store?.get();
    if (existing && !options.replace) {
      const existingPayload = await this.#verifyEnvelope(existing.license);
      const existingStatus = effectiveStatus(existingPayload, nowMs);
      if (existingStatus === "ACTIVA" || existingStatus === "PERPETUA") {
        throw new RedeemError("LICENSE_ALREADY_ACTIVE");
      }
    }
    await this.#persistSub(payload, bundle.primaryLicense, token, status);
    return { license: payload, subToken: token, status, kind: "SUB" };
  }

  /** Lee y verifica la licencia guardada sin red. Devuelve null si no hay. */
  async loadLicense() {
    await this.ready;
    if (!this.store) return null;
    const record = await this.store.get();
    if (!record) return null;
    if (record.v !== STORE_VERSION) throw new RedeemError("STORE_CORRUPT");
    if (record.kind === "SUB") {
      const payload = await this.#verifyEnvelope(record.license);
      if (payload.licenseId !== record.primaryLicenseId) {
        throw new RedeemError("STORE_CORRUPT");
      }
      try {
        await verifySubToken(
          record.subToken,
          payload,
          this.deviceThumbprint,
          this.now(),
          { allowExpired: true },
        );
      } catch {
        throw new RedeemError("INVALID_SUBTOKEN");
      }
      const nowMs = this.now();
      if (
        Number.isFinite(record.lastSeenMs) &&
        nowMs + this.clockRollbackToleranceMs < record.lastSeenMs
      ) {
        throw new RedeemError("CLOCK_ROLLBACK");
      }
      this.lastSeenMs = Number.isFinite(record.lastSeenMs) ? record.lastSeenMs : 0;
      const status =
        record.status === "REVOCADA"
          ? "REVOCADA"
          : effectiveStatus(payload, this.#referenceNow(nowMs));
      return {
        license: payload,
        envelope: record.license,
        subToken: record.subToken,
        status,
        kind: "SUB",
      };
    }
    const payload = await this.#verifyAndBindLicense(record.license);
    if (payload.deviceThumbprint !== record.deviceThumbprint) {
      throw new RedeemError("STORE_CORRUPT");
    }
    const nowMs = this.now();
    if (
      Number.isFinite(record.lastSeenMs) &&
      nowMs + this.clockRollbackToleranceMs < record.lastSeenMs
    ) {
      throw new RedeemError("CLOCK_ROLLBACK");
    }
    this.lastSeenMs = Number.isFinite(record.lastSeenMs) ? record.lastSeenMs : 0;
    // Una revocacion conocida se conserva offline; es una marca local de mejor
    // esfuerzo, no una prueba criptografica de revocacion.
    const status =
      record.status === "REVOCADA"
        ? "REVOCADA"
        : effectiveStatus(payload, this.#referenceNow(nowMs));
    return { license: payload, envelope: record.license, status };
  }

  /** Marca que la app arranco; la marca solo avanza. */
  async recordSeen(nowMs = this.now()) {
    if (!this.store) return;
    const record = await this.store.get();
    if (!record) return;
    const previous = Number.isFinite(record.lastSeenMs) ? record.lastSeenMs : 0;
    if (nowMs + this.clockRollbackToleranceMs < previous) {
      throw new RedeemError("CLOCK_ROLLBACK");
    }
    record.lastSeenMs = Math.max(previous, nowMs);
    this.lastSeenMs = record.lastSeenMs;
    await this.store.set(record);
  }

  /**
   * Consulta el estado firmado del servidor. Solo es necesario para enterarse
   * de una revocacion: la licencia offline dura hasta su vencimiento.
   */
  async refreshStatus(options = {}) {
    await this.ready;
    this.#assertClock();
    const stored = await this.store?.get();
    if (!stored) throw new RedeemError("NO_LICENSE");
    if (stored.kind === "SUB") throw new RedeemError("SUBTOKEN_STATUS_UNSUPPORTED");
    const license = await this.#verifyAndBindLicense(stored.license);
    const requestId = options.requestId ?? randomId();
    if (!REQUEST_ID_RE.test(requestId)) throw new RedeemError("INVALID_REQUEST_ID");
    const base = this.#baseRequest(requestId);
    const request = {
      ...base,
      licenseId: license.licenseId,
      deviceKey: this.deviceKeyB64,
    };
    request.proof = await this.#statusProof(request);
    const response = await this.#post("/v1/status", request, requestId);
    const publicKey = await this.#publicKeyFor(response.status?.kid);
    let doc;
    try {
      doc = await verifyStatusDoc(response.status, publicKey);
    } catch {
      throw new RedeemError("INVALID_STATUS_SIGNATURE");
    }
    if (doc.licenseId !== license.licenseId) throw new RedeemError("STATUS_WRONG_LICENSE");
    if (doc.appId !== this.appId) throw new RedeemError("STATUS_WRONG_APP");
    if (doc.deviceThumbprint !== this.deviceThumbprint) {
      throw new RedeemError("STATUS_WRONG_DEVICE");
    }
    const localStatus = effectiveStatus(doc, this.now());
    stored.status = doc.status === "REVOCADA" ? "REVOCADA" : localStatus;
    stored.lastSeenMs = Math.max(stored.lastSeenMs ?? 0, this.now());
    this.lastSeenMs = stored.lastSeenMs;
    await this.store.set(stored);
    return doc;
  }

  /**
   * Punto de extension para SPVI2 y envelopes v1. No se usa en `activate`: una
   * cadena `SPVI2:` nunca se interpreta como licencia de canje.
   */
  async verifyLegacy(input) {
    if (typeof this.legacyVerifier !== "function") {
      throw new RedeemError("LEGACY_NOT_CONFIGURED");
    }
    return this.legacyVerifier(input);
  }

  static formatCode(code) {
    return formatCode(normalizeCode(code));
  }

  static isValidCode(code) {
    return isValidCode(code);
  }

  static generateCode() {
    return formatCode(generateNormalizedCode());
  }
}

