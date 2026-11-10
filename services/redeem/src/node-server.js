/**
 * Servidor HTTP para desarrollo y VPS. En Cloudflare se usa `worker.js`.
 * Escucha en 0.0.0.0 para poder exponerse tras un proxy.
 */

import { createServer } from "node:http";
import { pathToFileURL } from "node:url";
import { buildServiceFromEnv } from "./config.js";
import { JsonFileStore } from "./file-store.js";
import { createHandler, MAX_BODY_BYTES } from "./http.js";
import { createLogger } from "./log.js";
import { StoreRateLimiter } from "./rate-limit.js";
import { MemoryStore } from "./store.js";

export class BodyTooLargeError extends Error {
  constructor() {
    super("body-too-large");
    this.name = "BodyTooLargeError";
  }
}

export function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let rejected = false;
    request.on("data", (chunk) => {
      if (rejected) return;
      total += chunk.length;
      if (total > MAX_BODY_BYTES) {
        rejected = true;
        chunks.length = 0;
        // Se descarta el resto sin acumular; el servidor responde 413.
        request.resume();
        reject(new BodyTooLargeError());
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => {
      if (!rejected) resolve(Buffer.concat(chunks));
    });
    request.on("error", (error) => {
      if (!rejected) reject(error);
    });
  });
}

/** Crea el servidor sin escuchar. Util para pruebas de socket reales. */
export function createNodeServer(handle) {
  return createServer(async (req, res) => {
    try {
      const body = req.method === "GET" || req.method === "HEAD" ? undefined : await readBody(req);
      const url = new URL(req.url, `http://${req.headers.host ?? "localhost"}`);
      const headers = new Headers();
      for (const [name, value] of Object.entries(req.headers)) {
        if (Array.isArray(value)) headers.set(name, value.join(","));
        else if (value !== undefined) headers.set(name, value);
      }
      if (!headers.has("x-forwarded-for") && req.socket.remoteAddress) {
        headers.set("x-forwarded-for", req.socket.remoteAddress);
      }
      const response = await handle(new Request(url, { method: req.method, headers, body }));
      res.statusCode = response.status;
      response.headers.forEach((value, name) => res.setHeader(name, value));
      res.end(Buffer.from(await response.arrayBuffer()));
    } catch (error) {
      if (error instanceof BodyTooLargeError) {
        res.statusCode = 413;
        res.setHeader("content-type", "application/json; charset=utf-8");
        res.setHeader("connection", "close");
        res.end(
          JSON.stringify({
            v: 1,
            ok: false,
            error: { code: "PAYLOAD_TOO_LARGE", message: "La solicitud es demasiado grande." },
          }),
        );
        return;
      }
      res.statusCode = 500;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ v: 1, ok: false, error: { code: "SERVER_ERROR" } }));
    }
  });
}

export async function buildFromEnv(env) {
  const logger = createLogger();
  const store = env.STORE_FILE ? new JsonFileStore(env.STORE_FILE) : new MemoryStore();
  const { service, pepper } = await buildServiceFromEnv(env, { store, logger });
  const httpRateLimiter = new StoreRateLimiter({
    store: service.store,
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
  return { service, store, pepper, handle };
}

async function main() {
  const port = Number(process.env.PORT ?? 8787);
  const { handle } = await buildFromEnv(process.env);
  const server = createNodeServer(handle);
  server.listen(port, "0.0.0.0", () => {
    console.log(`GL redeem service listening on 0.0.0.0:${port}`);
  });
}

const invokedDirectly =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
  main().catch((error) => {
    console.error(`startup failed: ${error?.message ?? "unknown"}`);
    process.exit(1);
  });
}
