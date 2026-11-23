/**
 * Prueba de humo del canje contra un Worker (staging o local).
 *
 * Uso:
 *   $env:BASE_URL="http://127.0.0.1:8799"
 *   $env:APP_ID="SHALEV-ANDROID"
 *   $env:SERVICE_PUBLIC_KEY="<SPKI Base64 del servicio>"
 *   node scripts/smoke.mjs "XXXX-XXXX-XXXX-XXXX"
 *
 * Genera una clave de dispositivo efimera en cada ejecucion: el segundo intento
 * con el mismo codigo debe devolver CODE_ALREADY_REDEEMED.
 */
import { RedeemClient, MemoryLicenseStore } from "../src/client/redeem-client.js";
import { generateP256KeyPair } from "../src/keys.js";

const code = process.argv[2];
if (!code) {
  console.error("Falta el codigo como argumento.");
  process.exit(2);
}
if (!process.env.BASE_URL || !process.env.SERVICE_PUBLIC_KEY) {
  console.error("Faltan BASE_URL o SERVICE_PUBLIC_KEY en el entorno.");
  process.exit(2);
}

const client = await RedeemClient.create({
  baseUrl: process.env.BASE_URL,
  appId: process.env.APP_ID ?? "DSHALEV",
  pinnedKeys: { [process.env.KID ?? "gl-redeem-v1"]: process.env.SERVICE_PUBLIC_KEY },
  deviceKeys: await generateP256KeyPair(),
  store: new MemoryLicenseStore(),
  fetchImpl: fetch,
  now: () => Date.now(),
});

try {
  const r = await client.activate(code);
  console.log("OK", r.license.status, r.license.expiresAt, "restored =", r.restored);
} catch (error) {
  console.error("ERROR", error.code ?? error.name, error.status ?? "");
  process.exit(1);
}
