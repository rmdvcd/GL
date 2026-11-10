import { MemoryStore } from "../src/store.js";
import { RedeemService } from "../src/service.js";
import { createHandler } from "../src/http.js";
import { createLogger } from "../src/log.js";
import { StoreRateLimiter } from "../src/rate-limit.js";
import { createSignedBatch } from "../src/batch.js";
import { MemoryLicenseStore, RedeemClient } from "../src/client/redeem-client.js";
import { exportSpkiB64, generateP256KeyPair } from "../src/keys.js";

export const APP_ID = "DSHALEV";
export const KID = "gl-redeem-v1";
export const START = Date.parse("2026-11-10T12:00:00Z");
export const DAY_MS = 24 * 60 * 60 * 1000;

export function newCodes(count = 1) {
  return Array.from({ length: count }, () => RedeemClient.generateCode());
}

export async function makeEnvironment(options = {}) {
  const {
    allowedApps = [APP_ID],
    rateLimit = 1000,
    httpRateLimit = 1000,
    clockSkewMs = 5 * 60 * 1000,
    maxSecundarias = 0,
  } = options;

  const nowRef = { value: options.start ?? START };
  const glKeys = await generateP256KeyPair();
  const serviceKeys = await generateP256KeyPair();
  const servicePublic = await exportSpkiB64(serviceKeys.publicKey);
  const glPublic = await exportSpkiB64(glKeys.publicKey);
  const pepper = crypto.getRandomValues(new Uint8Array(32));
  const store = options.store ?? new MemoryStore();
  const lines = [];
  const logger = createLogger({
    sink: { log: (line) => lines.push(JSON.parse(line)) },
  });

  const service = new RedeemService({
    store,
    pepper,
    glPublicKey: glKeys.publicKey,
    servicePrivateKey: serviceKeys.privateKey,
    servicePublicKey: serviceKeys.publicKey,
    serviceKid: KID,
    allowedApps,
    maxClockSkewMs: clockSkewMs,
    maxSecundarias,
    now: () => nowRef.value,
    logger,
    rateLimiter: new StoreRateLimiter({ store, limit: rateLimit, windowMs: 60_000 }),
  });

  const handler = createHandler({
    service,
    logger,
    pepper,
    httpRateLimiter: new StoreRateLimiter({ store, limit: httpRateLimit, windowMs: 60_000 }),
  });

  const serviceFetch = async (url, init) => handler(new Request(url, init));

  const makeBatch = async (entries, extra = {}) => {
    const batch = await createSignedBatch({
      signingPrivateKey: glKeys.privateKey,
      appId: extra.appId ?? APP_ID,
      batchId: extra.batchId ?? crypto.randomUUID(),
      createdAt: new Date(nowRef.value).toISOString(),
      entries,
      ...extra,
    });
    return batch;
  };

  const importCodes = async (codes, extra = {}) => {
    const batch = await makeBatch(
      codes.map((code) => ({
        code,
        type: extra.type ?? "MENSUAL",
        secundarias: extra.secundarias ?? 0,
        ...(extra.redeemBefore ? { redeemBefore: extra.redeemBefore } : {}),
      })),
      extra,
    );
    const result = await service.importBatch(batch);
    return { batch, result };
  };

  const makeClient = async (clientOptions = {}) => {
    const deviceKeys = await generateP256KeyPair();
    const licenseStore = new MemoryLicenseStore();
    const client = await RedeemClient.create({
      baseUrl: "https://redeem.test",
      appId: APP_ID,
      pinnedKeys: { [KID]: servicePublic },
      deviceKeys,
      store: licenseStore,
      fetchImpl: clientOptions.fetchImpl ?? serviceFetch,
      now: () => nowRef.value,
      ...clientOptions,
    });
    return { client, deviceKeys, licenseStore };
  };

  return {
    nowRef,
    glKeys,
    serviceKeys,
    servicePublic,
    glPublic,
    pepper,
    store,
    logs: lines,
    service,
    handler,
    serviceFetch,
    makeBatch,
    importCodes,
    makeClient,
  };
}

/** Envoltorio que simula una respuesta perdida: el servicio procesa y el cliente no la ve. */
export function lossyFetch(handler) {
  let calls = 0;
  const fetchImpl = async (url, init) => {
    calls += 1;
    await handler(new Request(url, init));
    throw new TypeError("network-lost");
  };
  return { fetchImpl, calls: () => calls };
}
