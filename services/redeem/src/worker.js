/**
 * Entrada Cloudflare Workers. Bindings esperados (secrets salvo DB):
 *   DB (D1), GL_ISSUER_PUBLIC_KEY, SERVICE_PRIVATE_KEY, SERVICE_PUBLIC_KEY,
 *   CODE_PEPPER, ALLOWED_APPS, MAX_SECUNDARIAS, SERVICE_KID, RATE_LIMIT_MAX,
 *   RATE_LIMIT_WINDOW_MS.
 */

import { buildServiceFromEnv } from "./config.js";
import { createHandler } from "./http.js";
import { BindingRateLimiter } from "./rate-limit.js";

let handlerPromise;

async function buildHandler(env) {
  const httpRateLimiter = new BindingRateLimiter(env, "RL_IP");
  if (!env.DB) throw new Error("missing-binding:DB");
  const { service, pepper, logger } = await buildServiceFromEnv(env);
  const handle = createHandler({
    service,
    logger,
    pepper,
    httpRateLimiter,
    allowOrigin: env.ALLOWED_ORIGIN ?? null,
  });
  return handle;
}

function getHandler(env) {
  return handlerPromise ??= buildHandler(env).catch(error => {
    handlerPromise = undefined;
    throw error;
  });
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

