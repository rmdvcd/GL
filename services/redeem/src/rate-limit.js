/** Limitador de intentos respaldado por el mismo almacen (D1/memoria). */

import { hmacSha256, toBase64Url, utf8 } from "./canonical.js";

export class StoreRateLimiter {
  constructor({ store, limit, windowMs }) {
    if (!store) throw new Error("store-required");
    if (!Number.isInteger(limit) || limit < 1) throw new Error("invalid-limit");
    if (!Number.isInteger(windowMs) || windowMs < 1000) throw new Error("invalid-window");
    this.store = store;
    this.limit = limit;
    this.windowMs = windowMs;
  }

  async check(key, nowMs) {
    const windowStart = Math.floor(nowMs / this.windowMs) * this.windowMs;
    const count = await this.store.incrementCounter(`rl:${key}`, windowStart);
    return count <= this.limit;
  }
}

/**
 * Deriva una clave de limitador sin guardar la IP. El pepper es el mismo
 * secreto del servicio; la IP nunca se persiste ni se registra.
 */
export async function hashRateKey(pepper, scope, value) {
  return toBase64Url(await hmacSha256(pepper, utf8(`${scope}|${value}`)));
}
