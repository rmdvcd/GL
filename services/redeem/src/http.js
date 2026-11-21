/**
 * Adaptador HTTP sobre Fetch API (Node 20+, Cloudflare Workers, Deno).
 * No conoce claves: recibe el servicio ya construido.
 */

import { ApiError, asApiError } from "./errors.js";
import { hashRateKey } from "./rate-limit.js";

export const MAX_BODY_BYTES = 16 * 1024;

function jsonResponse(body, status, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
      ...extraHeaders,
    },
  });
}

function clientIp(request) {
  const direct = request.headers.get("cf-connecting-ip") ?? request.headers.get("x-real-ip");
  if (direct) return direct.trim();
  const forwarded = request.headers.get("x-forwarded-for");
  if (forwarded) return forwarded.split(",")[0].trim();
  return "unknown";
}

async function readJson(request, maxBytes) {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(declared) && declared > maxBytes) throw new ApiError("PAYLOAD_TOO_LARGE");
  const type = (request.headers.get("content-type") ?? "").toLowerCase();
  if (!type.startsWith("application/json")) throw new ApiError("INVALID_REQUEST");
  const text = await request.text();
  const bytes = new TextEncoder().encode(text);
  if (bytes.length === 0) throw new ApiError("INVALID_REQUEST");
  if (bytes.length > maxBytes) throw new ApiError("PAYLOAD_TOO_LARGE");
  try {
    return JSON.parse(text);
  } catch {
    throw new ApiError("INVALID_REQUEST");
  }
}

export function createHandler(options) {
  const {
    service,
    logger = () => {},
    pepper,
    maxBodyBytes = MAX_BODY_BYTES,
    httpRateLimiter = null,
    allowOrigin = null,
  } = options;

  const corsHeaders = allowOrigin
    ? {
        "access-control-allow-origin": allowOrigin,
        "access-control-allow-methods": "POST, GET, OPTIONS",
        "access-control-allow-headers": "content-type",
      }
    : {};

  async function limitByIp(request) {
    if (!httpRateLimiter || !pepper) return;
    const key = await hashRateKey(pepper, "http", clientIp(request));
    const allowed = await httpRateLimiter.check(key, service.now());
    if (!allowed) throw new ApiError("RATE_LIMITED");
  }

  return async function handle(request) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS" && allowOrigin) {
      return new Response(null, { status: 204, headers: { ...corsHeaders } });
    }

    if (url.pathname === "/health" && request.method === "GET") {
      return jsonResponse({ ok: true, service: "gl-redeem", v: 1 }, 200, corsHeaders);
    }

    const time = url.pathname === "/v1/time";
    if (!time && url.pathname !== "/v1/redeem" && url.pathname !== "/v1/status") {
      return jsonResponse(
        { v: 1, ok: false, error: { code: "NOT_FOUND", message: "Ruta no encontrada." } },
        404,
        corsHeaders,
      );
    }
    if (request.method !== (time ? "GET" : "POST")) {
      return jsonResponse(
        { v: 1, ok: false, error: { code: "METHOD_NOT_ALLOWED", message: "Metodo no permitido." } },
        405,
        { ...corsHeaders, allow: time ? "GET, OPTIONS" : "POST, OPTIONS" },
      );
    }

    try {
      await limitByIp(request);
      if (time) return jsonResponse({v:1, ok:true, now:new Date(service.now()).toISOString()},200,corsHeaders);
      const body = await readJson(request, maxBodyBytes);
      if (url.pathname === "/v1/redeem") {
        const result = await service.redeem(body);
        return jsonResponse(
          { v: 1, ok: true, license: result.license, restored: result.restored },
          200,
          corsHeaders,
        );
      }
      const result = await service.status(body);
      return jsonResponse({ v: 1, ok: true, status: result.status }, 200, corsHeaders);
    } catch (error) {
      const apiError = asApiError(error);
      if (apiError.code === "SERVER_ERROR") {
        logger("http_unexpected", { reason: error?.name ?? "error" });
      } else {
        logger("http_error", { reason: apiError.code });
      }
      return jsonResponse(
        { v: 1, ok: false, error: { code: apiError.code, message: apiError.publicMessage, ...(apiError.code === "CLOCK_SKEW" ? {serverTime:apiError.detail} : {}) } },
        apiError.status,
        corsHeaders,
      );
    }
  };
}
