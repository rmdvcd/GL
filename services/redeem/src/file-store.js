/**
 * Almacen JSON para desarrollo local y CLI. NO apto para produccion: no tiene
 * bloqueo entre procesos ni transacciones. En Cloudflare se usa D1.
 */

import { readFile, writeFile } from "node:fs/promises";
import { MemoryStore } from "./store.js";

export class JsonFileStore extends MemoryStore {
  constructor(path) {
    super();
    this.path = path;
    this.loaded = false;
    this.saving = Promise.resolve();
  }

  async load() {
    if (this.loaded) return;
    let raw;
    try {
      raw = await readFile(this.path, "utf8");
    } catch {
      // Un fichero ausente es un almacen vacio; uno corrupto debe fallar y no
      // marcar el almacen como cargado (si no, se perderian licencias).
      this.loaded = true;
      return;
    }
    const data = JSON.parse(raw);
    for (const code of data.codes ?? []) this.codes.set(code.codeHash, code);
    for (const row of data.redemptions ?? []) {
      this.redemptions.set(row.licenseId, row);
      this.byRequest.set(row.requestId, row.licenseId);
      this.latestByCode.set(row.codeHash, row.licenseId);
      if (row.active) this.byCodeActive.set(row.codeHash, row.licenseId);
    }
    for (const [key, value] of data.counters ?? []) this.counters.set(key, value);
    for (const pending of data.pendingTransfers ?? []) {
      this.pendingTransfers.set(pending.codeHash, pending);
    }
    this.loaded = true;
  }

  async persist() {
    this.saving = this.saving.then(async () => {
      const data = {
        v: 1,
        codes: [...this.codes.values()],
        redemptions: [...this.redemptions.values()],
        pendingTransfers: [...this.pendingTransfers.values()],
        counters: [...this.counters.entries()],
      };
      await writeFile(this.path, JSON.stringify(data), { mode: 0o600 });
    });
    return this.saving;
  }

  async importCodes(entries, batch) {
    await this.load();
    const result = await super.importCodes(entries, batch);
    await this.persist();
    return result;
  }

  async getCode(codeHash) {
    await this.load();
    return super.getCode(codeHash);
  }

  async getActiveByCode(codeHash) {
    await this.load();
    return super.getActiveByCode(codeHash);
  }

  async getLatestByCode(codeHash) {
    await this.load();
    return super.getLatestByCode(codeHash);
  }

  async getByRequest(requestId) {
    await this.load();
    return super.getByRequest(requestId);
  }

  async getByLicenseId(licenseId) {
    await this.load();
    return super.getByLicenseId(licenseId);
  }

  async getPendingTransfer(codeHash) {
    await this.load();
    return super.getPendingTransfer(codeHash);
  }

  async updatePendingTransfer(codeHash, toThumbprint) {
    await this.load();
    const pending = await super.updatePendingTransfer(codeHash, toThumbprint);
    if (pending) await this.persist();
    return pending;
  }

  async claim(row) {
    await this.load();
    const result = await super.claim(row);
    if (result.ok) await this.persist();
    return result;
  }

  async setStatus(licenseId, status) {
    await this.load();
    const row = await super.setStatus(licenseId, status);
    if (row) await this.persist();
    return row;
  }

  async deactivateForTransfer(licenseId, toThumbprint) {
    await this.load();
    const row = await super.deactivateForTransfer(licenseId, toThumbprint);
    if (row) await this.persist();
    return row;
  }

  async revokeCodeCascade(codeHash) {
    await this.load();
    const code = await super.revokeCodeCascade(codeHash);
    if (code) await this.persist();
    return code;
  }

  async incrementCounter(key, windowStart) {
    await this.load();
    return super.incrementCounter(key, windowStart);
  }
}
