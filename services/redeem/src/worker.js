/**
 * Entrada Cloudflare Workers. Bindings esperados (secrets salvo DB):
 *   DB (D1), GL_ISSUER_PUBLIC_KEY, SERVICE_PRIVATE_KEY, SERVICE_PUBLIC_KEY,
 *   CODE_PEPPER, ALLOWED_APPS, MAX_SECUNDARIAS, SERVICE_KID, RATE_LIMIT_MAX,
 *   RATE_LIMIT_WINDOW_MS.
 */

import { buildServiceFromEnv } from "./config.js";
import { createHandler } from "./http.js";
import { StoreRateLimiter } from "./rate-limit.js";

let cached = null;
let cachedEnvKey = null;

async function getHandler(env) {
  if (cached && cachedEnvKey === env) return cached;
  const { service, store, pepper, logger } = await buildServiceFromEnv(env);
  const httpRateLimiter = new StoreRateLimiter({
    store,
    limit: Number(env.HTTP_RATE_LIMIT_MAX ?? 120),
    windowMs: Number(env.HTTP_RATE_LIMIT_WINDOW_MS ?? 60_000),
  });
  const handle = createHandler({
    service,
    logger,
    pepper,
    httpRateLimiter,
    allowOrigin: env.ALLOWED_ORIGIN ?? null,
  });
  cached = handle;
  cachedEnvKey = env;
  return handle;
}

export default {
  async fetch(request, env) {
    try {
      const handle = await getHandler(env);
      return await handle(request);
    } catch {
      return new Response(
        JSON.stringify({
          v: 1,
          ok: false,
          error: { code: "SERVER_ERROR", message: "No se pudo completar la operacion." },
        }),
        { status: 500, headers: { "content-type": "application/json", "cache-control": "no-store" } },
      );
    }
  },
};

