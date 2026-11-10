/** Errores publicos del servicio. Mensajes genericos, sin datos del cliente. */

export const ERROR_CODES = Object.freeze({
  INVALID_REQUEST: { status: 400, message: "La solicitud no es valida." },
  PAYLOAD_TOO_LARGE: { status: 413, message: "La solicitud es demasiado grande." },
  INVALID_PROOF: { status: 401, message: "No se pudo verificar la identidad del dispositivo." },
  CODE_UNAVAILABLE: { status: 404, message: "El codigo no esta disponible." },
  CODE_ALREADY_REDEEMED: {
    status: 409,
    message: "El codigo ya fue canjeado en otra instalacion.",
  },
  CODE_EXPIRED: { status: 410, message: "El codigo ya no se puede canjear." },
  LICENSE_EXPIRED: { status: 410, message: "La licencia esta vencida." },
  LICENSE_NOT_FOUND: { status: 404, message: "No hay una licencia para esta instalacion." },
  RATE_LIMITED: { status: 429, message: "Demasiados intentos. Espera antes de reintentar." },
  IDEMPOTENCY_CONFLICT: {
    status: 409,
    message: "La solicitud ya fue usada con otros datos.",
  },
  SERVER_ERROR: { status: 500, message: "No se pudo completar la operacion." },
  BATCH_INVALID: { status: 400, message: "El lote no es valido." },
});

export class ApiError extends Error {
  constructor(code, options = {}) {
    const entry = ERROR_CODES[code] ?? ERROR_CODES.SERVER_ERROR;
    super(entry.message);
    this.name = "ApiError";
    this.code = ERROR_CODES[code] ? code : "SERVER_ERROR";
    this.status = options.status ?? entry.status;
    this.publicMessage = entry.message;
    this.detail = options.detail;
  }

  toJSON() {
    return { code: this.code, message: this.publicMessage };
  }
}

export function asApiError(error) {
  if (error instanceof ApiError) return error;
  return new ApiError("SERVER_ERROR", { detail: error?.message });
}
