/**
 * Log estructurado con lista blanca de campos.
 *
 * Ni codigos, ni pruebas, ni claves, ni cuerpos de solicitud, ni IPs. Los
 * identificadores que se registran (appId, batchId, tipo, estado) no son
 * personales. Cualquier otro campo se descarta aunque el llamante lo pase.
 */

const ALLOWED_FIELDS = new Set([
  "appId",
  "batchId",
  "type",
  "status",
  "imported",
  "skipped",
  "conflicts",
  "reason",
  "transferred",
  "limit",
  "windowMs",
]);

const ALLOWED_EVENTS = new Set([
  "batch_imported",
  "batch_rejected",
  "batch_conflict",
  "redeem_ok",
  "redeem_unavailable",
  "redeem_other_device",
  "redeem_race_other_device",
  "status_ok",
  "license_revoked",
  "license_transfer_authorized",
  "license_transfer_updated",
  "code_revoked",
  "rate_limited",
  "http_error",
  "http_unexpected",
  "server_started",
]);

const MAX_VALUE_LENGTH = 64;

function cleanValue(value) {
  if (typeof value === "boolean") return value;
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    return value.replace(/[^A-Za-z0-9._:-]/g, "").slice(0, MAX_VALUE_LENGTH);
  }
  return undefined;
}

export function createLogger({ sink = console, enabled = true } = {}) {
  return function log(event, fields = {}) {
    if (!enabled) return;
    const safeEvent = ALLOWED_EVENTS.has(event) ? event : "http_unexpected";
    const record = { ts: new Date().toISOString(), event: safeEvent };
    for (const [key, value] of Object.entries(fields)) {
      if (!ALLOWED_FIELDS.has(key)) continue;
      const cleaned = cleanValue(value);
      if (cleaned !== undefined) record[key] = cleaned;
    }
    sink.log(JSON.stringify(record));
  };
}

export const LOG_ALLOWED_FIELDS = Object.freeze([...ALLOWED_FIELDS]);
