/**
 * Adaptador Cloudflare D1. La atomicidad del canje se apoya en indices unicos:
 *  - `redemptions.license_id` PK
 *  - `redemptions.request_id` UNIQUE
 *  - indice parcial UNIQUE(code_hash) WHERE active = 1
 *
 * No se ha ejecutado contra un D1 real en este entorno; ver README.
 */

import { absorbedBy } from "./cascade.js";
import { MemoryStore } from "./store.js";

const COLUMNS = `license_id, redemption_id, request_id, code_hash, app_id, type,
  secundarias, status, issued_at, expires_at, device_thumbprint,
  device_public_key, active, transferred_from, created_at`;

function mapRow(row) {
  if (!row) return null;
  return {
    licenseId: row.license_id,
    redemptionId: row.redemption_id,
    requestId: row.request_id,
    codeHash: row.code_hash,
    appId: row.app_id,
    type: row.type,
    secundarias: Number(row.secundarias ?? 0),
    status: row.status,
    issuedAt: row.issued_at,
    expiresAt: row.expires_at ?? null,
    deviceThumbprint: row.device_thumbprint,
    devicePublicKey: row.device_public_key ?? null,
    active: Number(row.active) === 1,
    transferredFrom: row.transferred_from ?? null,
    createdAt: row.created_at,
  };
}

export class D1Store extends MemoryStore {
  constructor(db) {
    super();
    if (!db) throw new Error("d1-required");
    this.db = db;
  }

  async importCodes(entries, batch) {
    let imported = 0;
    let skipped = 0;
    const conflicts = [];
    for (const entry of entries) {
      const existing = await this.db
        .prepare("SELECT * FROM codes WHERE code_hash = ?")
        .bind(entry.codeHash)
        .first();
      if (existing) {
        const same =
          existing.batch_id === batch.batchId &&
          existing.app_id === batch.appId &&
          existing.type === entry.type &&
          Number(existing.secundarias) === entry.secundarias &&
          (existing.redeem_before ?? null) === (entry.redeemBefore ?? null);
        if (same) skipped += 1;
        else conflicts.push(entry.codeHash);
        continue;
      }
      await this.db
        .prepare(
          `INSERT INTO codes (code_hash, app_id, type, secundarias, batch_id,
             redeem_before, state, created_at)
           VALUES (?, ?, ?, ?, ?, ?, 'UNUSED', ?)`,
        )
        .bind(
          entry.codeHash,
          batch.appId,
          entry.type,
          entry.secundarias,
          batch.batchId,
          entry.redeemBefore ?? null,
          batch.createdAt,
        )
        .run();
      imported += 1;
    }
    return { imported, skipped, conflicts };
  }

  async getCode(codeHash) {
    const row = await this.db.prepare("SELECT * FROM codes WHERE code_hash = ?").bind(codeHash).first();
    if (!row) return null;
    return {
      codeHash: row.code_hash,
      appId: row.app_id,
      type: row.type,
      secundarias: Number(row.secundarias ?? 0),
      batchId: row.batch_id,
      redeemBefore: row.redeem_before ?? null,
      state: row.state,
      createdAt: row.created_at,
    };
  }

  async getActiveByCode(codeHash) {
    const row = await this.db
      .prepare("SELECT * FROM redemptions WHERE code_hash = ? AND active = 1 LIMIT 1")
      .bind(codeHash)
      .first();
    return mapRow(row);
  }

  async getLatestByCode(codeHash) {
    const row = await this.db
      .prepare(
        "SELECT * FROM redemptions WHERE code_hash = ? ORDER BY created_at DESC, license_id DESC LIMIT 1",
      )
      .bind(codeHash)
      .first();
    return mapRow(row);
  }

  async getByRequest(requestId) {
    const row = await this.db
      .prepare("SELECT * FROM redemptions WHERE request_id = ? LIMIT 1")
      .bind(requestId)
      .first();
    return mapRow(row);
  }

  async getByLicenseId(licenseId) {
    const row = await this.db
      .prepare("SELECT * FROM redemptions WHERE license_id = ? LIMIT 1")
      .bind(licenseId)
      .first();
    return mapRow(row);
  }

  async getActiveByDevice(appId, deviceThumbprint) {
    const row = await this.db
      .prepare(
        `SELECT * FROM redemptions
         WHERE app_id = ? AND device_thumbprint = ? AND active = 1
           AND status != 'REVOCADA' AND expires_at IS NOT NULL
         ORDER BY expires_at DESC LIMIT 1`,
      )
      .bind(appId, deviceThumbprint)
      .first();
    return mapRow(row);
  }

  async getPendingTransfer(codeHash) {
    const row = await this.db
      .prepare("SELECT * FROM transfers WHERE code_hash = ? LIMIT 1")
      .bind(codeHash)
      .first();
    if (!row) return null;
    return {
      codeHash: row.code_hash,
      fromLicenseId: row.from_license_id,
      toThumbprint: row.to_thumbprint,
      createdAt: row.created_at,
    };
  }

  async updatePendingTransfer(codeHash, toThumbprint) {
    if (typeof toThumbprint !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(toThumbprint)) {
      return null;
    }
    const pending = await this.getPendingTransfer(codeHash);
    if (!pending) return null;
    await this.db
      .prepare("UPDATE transfers SET to_thumbprint = ? WHERE code_hash = ?")
      .bind(toThumbprint, codeHash)
      .run();
    return { ...pending, toThumbprint };
  }

  async claim(row, days) {
    try {
      await this.db.batch([
        this.db
          .prepare(
            `INSERT INTO redemptions (${COLUMNS}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ${days ? `strftime('%Y-%m-%dT%H:%M:%fZ',
              max(julianday(?), coalesce((SELECT max(julianday(expires_at)) FROM redemptions
                WHERE active=1 AND app_id=? AND device_thumbprint=?), 0)) + ?)` : '?'}, ?, ?, ?, ?, ?)`,
          )
          .bind(
            row.licenseId,
            row.redemptionId,
            row.requestId,
            row.codeHash,
            row.appId,
            row.type,
            row.secundarias,
            row.status,
            row.issuedAt,
            ...(days ? [row.issuedAt, row.appId, row.deviceThumbprint, days] : [row.expiresAt]),
            row.deviceThumbprint,
            row.devicePublicKey,
            row.active ? 1 : 0,
            row.transferredFrom,
            row.createdAt,
          ),
        this.db.prepare("DELETE FROM transfers WHERE code_hash = ?").bind(row.codeHash),
      ]);
      return { ok: true, row: await this.getByRequest(row.requestId) };
    } catch {
      const byRequest = await this.getByRequest(row.requestId);
      if (byRequest) return { ok: false, reason: "request-exists", existing: byRequest };
      const active = await this.getActiveByCode(row.codeHash);
      if (active) return { ok: false, reason: "code-active", existing: active };
      throw new Error("claim-failed");
    }
  }

  async setStatus(licenseId, status) {
    const active = status === "REVOCADA" ? 0 : 1;
    await this.db.batch([
      this.db
        .prepare("UPDATE redemptions SET status = ?, active = ? WHERE license_id = ?")
        .bind(status, active, licenseId),
      this.db
        .prepare(
          "DELETE FROM transfers WHERE code_hash = (SELECT code_hash FROM redemptions WHERE license_id = ?)",
        )
        .bind(licenseId),
    ]);
    return this.getByLicenseId(licenseId);
  }

  async deactivateForTransfer(licenseId, toThumbprint) {
    const row = await this.getByLicenseId(licenseId);
    if (!row || row.status === "REVOCADA" || row.active === false) return null;
    if (typeof toThumbprint !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(toThumbprint)) {
      return null;
    }
    const createdAt = new Date().toISOString();
    await this.db.batch([
      this.db
        .prepare(`UPDATE redemptions SET status = 'REVOCADA', active = 0 WHERE active=1 AND (license_id = ? OR ${absorbedBy('?')})`)
        .bind(licenseId, licenseId),
      this.db
        .prepare(
          `INSERT INTO transfers (code_hash, from_license_id, to_thumbprint, created_at)
           VALUES (?, ?, ?, ?)
           ON CONFLICT(code_hash) DO UPDATE SET
             from_license_id = excluded.from_license_id,
             to_thumbprint = excluded.to_thumbprint,
             created_at = excluded.created_at`,
        )
        .bind(row.codeHash, licenseId, toThumbprint, createdAt),
    ]);
    return { ...row, active: false, status: "REVOCADA" };
  }

  async revokeCodeCascade(codeHash, licenseId = null) {
    const where = `active=1 AND (code_hash=? OR ${absorbedBy("?")})`;
    const [affected] = await this.db.batch([
      this.db.prepare(`SELECT license_id FROM redemptions WHERE ${where}`).bind(codeHash, licenseId),
      this.db.prepare(`UPDATE redemptions SET status = 'REVOCADA', active = 0 WHERE ${where}`).bind(codeHash, licenseId),
      this.db.prepare("UPDATE codes SET state = 'REVOKED' WHERE code_hash = ?").bind(codeHash),
      this.db.prepare("DELETE FROM transfers WHERE code_hash = ?").bind(codeHash),
    ]);
    return affected.results.map(row => row.license_id);
  }

  async incrementCounter(key, windowStart) {
    if (Math.random() < 0.01) await this.db.prepare("DELETE FROM counters WHERE window_start < ?").bind(windowStart - 86400000).run();
    await this.db
      .prepare(
        `INSERT INTO counters (key, window_start, count) VALUES (?, ?, 1)
         ON CONFLICT(key) DO UPDATE SET
           count = CASE WHEN counters.window_start = excluded.window_start
                        THEN counters.count + 1 ELSE 1 END,
           window_start = excluded.window_start`,
      )
      .bind(key, windowStart)
      .run();
    const row = await this.db
      .prepare("SELECT count FROM counters WHERE key = ?")
      .bind(key)
      .first();
    return Number(row?.count ?? 1);
  }
}
