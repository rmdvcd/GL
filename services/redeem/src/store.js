/**
 * Almacen en memoria con la misma semantica que el adaptador D1.
 *
 * Invariantes que el adaptador de produccion debe reproducir con indices
 * unicos:
 *  - un codigo tiene como maximo un canje activo;
 *  - un requestId pertenece a una sola licencia;
 *  - un licenseId es unico.
 */

export class MemoryStore {
  constructor() {
    this.codes = new Map();
    this.redemptions = new Map();
    this.byRequest = new Map();
    this.byCodeActive = new Map();
    this.latestByCode = new Map();
    this.pendingTransfers = new Map();
    this.counters = new Map();
  }

  async importCodes(entries, batch) {
    let imported = 0;
    let skipped = 0;
    const conflicts = [];
    for (const entry of entries) {
      const existing = this.codes.get(entry.codeHash);
      if (existing) {
        const same =
          existing.batchId === batch.batchId &&
          existing.appId === batch.appId &&
          existing.type === entry.type &&
          existing.secundarias === entry.secundarias &&
          (existing.redeemBefore ?? null) === (entry.redeemBefore ?? null);
        if (same) skipped += 1;
        else conflicts.push(entry.codeHash);
        continue;
      }
      this.codes.set(entry.codeHash, {
        codeHash: entry.codeHash,
        appId: batch.appId,
        type: entry.type,
        secundarias: entry.secundarias,
        batchId: batch.batchId,
        redeemBefore: entry.redeemBefore ?? null,
        state: "UNUSED",
        createdAt: batch.createdAt,
      });
      imported += 1;
    }
    return { imported, skipped, conflicts };
  }

  async getCode(codeHash) {
    return this.codes.get(codeHash) ?? null;
  }

  async getActiveByCode(codeHash) {
    const licenseId = this.byCodeActive.get(codeHash);
    return licenseId ? this.redemptions.get(licenseId) ?? null : null;
  }

  async getLatestByCode(codeHash) {
    const licenseId = this.latestByCode.get(codeHash);
    return licenseId ? this.redemptions.get(licenseId) ?? null : null;
  }

  async getByRequest(requestId) {
    const licenseId = this.byRequest.get(requestId);
    return licenseId ? this.redemptions.get(licenseId) ?? null : null;
  }

  async getByLicenseId(licenseId) {
    return this.redemptions.get(licenseId) ?? null;
  }

  /**
   * Licencia activa mas reciente de una instalacion para una app. Se usa para
   * conservar los dias restantes al canjear un codigo nuevo (rollover).
   */
  async getActiveByDevice(appId, deviceThumbprint) {
    let best = null;
    for (const row of this.redemptions.values()) {
      if (row.appId !== appId || row.deviceThumbprint !== deviceThumbprint) continue;
      if (!row.active || row.status === "REVOCADA" || !row.expiresAt) continue;
      if (!best || Date.parse(row.expiresAt) > Date.parse(best.expiresAt)) best = row;
    }
    return best;
  }

  /**
   * Reserva atomica. Devuelve `{ok:true,row}` o, si ya existe, el estado actual
   * para que el servicio decida entre idempotencia y conflicto.
   */
  async getPendingTransfer(codeHash) {
    return this.pendingTransfers.get(codeHash) ?? null;
  }

  async updatePendingTransfer(codeHash, toThumbprint) {
    const pending = this.pendingTransfers.get(codeHash);
    if (!pending) return null;
    if (typeof toThumbprint !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(toThumbprint)) {
      return null;
    }
    pending.toThumbprint = toThumbprint;
    return pending;
  }

  async claim(row) {
    const requestRow = await this.getByRequest(row.requestId);
    if (requestRow) return { ok: false, reason: "request-exists", existing: requestRow };
    const active = await this.getActiveByCode(row.codeHash);
    if (active) return { ok: false, reason: "code-active", existing: active };
    this.redemptions.set(row.licenseId, row);
    this.byRequest.set(row.requestId, row.licenseId);
    this.byCodeActive.set(row.codeHash, row.licenseId);
    this.latestByCode.set(row.codeHash, row.licenseId);
    this.pendingTransfers.delete(row.codeHash);
    return { ok: true, row };
  }

  async setStatus(licenseId, status) {
    const row = this.redemptions.get(licenseId);
    if (!row) return null;
    row.status = status;
    if (status === "REVOCADA") {
      row.active = false;
      if (this.byCodeActive.get(row.codeHash) === licenseId) {
        this.byCodeActive.delete(row.codeHash);
      }
      this.pendingTransfers.delete(row.codeHash);
    }
    return row;
  }

  /**
   * Autoriza una transferencia hacia una instalacion concreta. Sin el
   * thumbprint de destino, cualquiera con el codigo podria quedarse la licencia
   * durante la ventana entre la autorizacion y el canje.
   */
  revokeMatching(matches) {
    const affected = [];
    for (const row of this.redemptions.values()) {
      if (!row.active || !matches(row)) continue;
      row.active = false;
      row.status = "REVOCADA";
      this.byCodeActive.delete(row.codeHash);
      this.pendingTransfers.delete(row.codeHash);
      affected.push(row.licenseId);
    }
    return affected;
  }

  absorbed(row, anchor) {
    return anchor && row.appId === anchor.appId && row.deviceThumbprint === anchor.deviceThumbprint &&
      row.expiresAt && anchor.expiresAt && row.expiresAt <= anchor.expiresAt;
  }

  async deactivateForTransfer(licenseId, toThumbprint) {
    const row = this.redemptions.get(licenseId);
    if (!row || row.status === "REVOCADA" || row.active === false) return null;
    if (typeof toThumbprint !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(toThumbprint)) {
      return null;
    }
    this.revokeMatching(other => other === row || this.absorbed(other, row));
    this.pendingTransfers.set(row.codeHash, {
      codeHash: row.codeHash,
      fromLicenseId: licenseId,
      toThumbprint,
      createdAt: new Date().toISOString(),
    });
    return row;
  }

  async revokeCodeCascade(codeHash, licenseId = null) {
    const code = this.codes.get(codeHash);
    if (!code) return [];
    const anchor = this.redemptions.get(licenseId);
    const affected = this.revokeMatching(row => row.codeHash === codeHash || this.absorbed(row, anchor));
    code.state = "REVOKED";
    this.pendingTransfers.delete(codeHash);
    return affected;
  }

  async incrementCounter(key, windowStart) {
    const current = this.counters.get(key);
    if (!current || current.windowStart !== windowStart) {
      this.counters.set(key, { windowStart, count: 1 });
      return 1;
    }
    current.count += 1;
    return current.count;
  }
}
