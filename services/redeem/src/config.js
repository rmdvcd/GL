/**
 * Construccion del servicio desde variables de entorno / bindings.
 * Ningun secreto tiene valor por defecto: si falta, el arranque falla.
 */

import { fromBase64 } from "./canonical.js";
import { D1Store } from "./d1-store.js";
import { importPkcs8B64, importSpkiB64, signDer, verifyDer } from "./keys.js";
import { createLogger } from "./log.js";
import { BindingRateLimiter, StoreRateLimiter } from "./rate-limit.js";
import { MemoryStore } from "./store.js";
import { RedeemService } from "./service.js";

function required(env, name) {
  const value = env[name];
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`missing-env:${name}`);
  }
  return value.trim();
}

function optionalNumber(env, name, fallback) {
  const raw = env[name];
  if (raw === undefined || raw === null || raw === "") return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) throw new Error(`invalid-env:${name}`);
  return value;
}

export async function buildServiceFromEnv(env, overrides = {}) {
  const pepper = fromBase64(required(env, "CODE_PEPPER"));
  if (pepper.length < 32) throw new Error("invalid-env:CODE_PEPPER");
  const glPublicKey = await importSpkiB64(required(env, "GL_ISSUER_PUBLIC_KEY"));
  const servicePrivateKey = await importPkcs8B64(required(env, "SERVICE_PRIVATE_KEY"));
  const servicePublicKey = await importSpkiB64(required(env, "SERVICE_PUBLIC_KEY"));

  // Comprobacion de arranque: la privada y la publica deben formar un par.
  // Evita desplegar con una configuracion que firme con una clave y anuncie otra.
  const probe = new TextEncoder().encode("gl-redeem-keycheck");
  const probeSig = await signDer(servicePrivateKey, probe);
  if (!(await verifyDer(servicePublicKey, probe, probeSig))) {
    throw new Error("invalid-env:SERVICE_KEYS_MISMATCH");
  }

  const store = overrides.store ?? (env.DB ? new D1Store(env.DB) : new MemoryStore());

  const allowedApps = (env.ALLOWED_APPS ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);

  const maxSecundariasRaw = env.MAX_SECUNDARIAS ?? "0";
  const maxSecundarias = Number(maxSecundariasRaw);
  if (!Number.isInteger(maxSecundarias) || maxSecundarias < 0 || maxSecundarias > 10) {
    throw new Error("invalid-env:MAX_SECUNDARIAS");
  }

  const logger = overrides.logger ?? createLogger();
  const rateLimitMax = optionalNumber(env, "RATE_LIMIT_MAX", 30);
  const rateLimitWindowMs = optionalNumber(env, "RATE_LIMIT_WINDOW_MS", 60_000);
  const rateLimiter = overrides.rateLimiter ?? (env.DB ? new BindingRateLimiter(env, "RL_DEVICE") : new StoreRateLimiter({
    store,
    limit: rateLimitMax,
    windowMs: rateLimitWindowMs,
  }));

  const service = new RedeemService({
    store,
    pepper,
    glPublicKey,
    servicePrivateKey,
    servicePublicKey,
    serviceKid: env.SERVICE_KID ?? "gl-redeem-v1",
    allowedApps,
    maxSecundarias,
    maxClockSkewMs: optionalNumber(env, "MAX_CLOCK_SKEW_MS", 5 * 60_000),
    logger,
    rateLimiter,
    now: overrides.now,
  });

  return { service, store, pepper, logger, rateLimiter };
}
