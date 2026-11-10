-- Esquema D1 del servicio de canje.
-- No contiene codigos en claro: solo HMAC(code, pepper).

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS codes (
  code_hash TEXT PRIMARY KEY,
  app_id TEXT NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('MENSUAL', 'SEMESTRAL', 'ANUAL', 'PERPETUA')),
  secundarias INTEGER NOT NULL DEFAULT 0 CHECK (secundarias BETWEEN 0 AND 10),
  batch_id TEXT NOT NULL,
  redeem_before TEXT,
  state TEXT NOT NULL DEFAULT 'UNUSED' CHECK (state IN ('UNUSED', 'REVOKED')),
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS redemptions (
  license_id TEXT PRIMARY KEY,
  redemption_id TEXT NOT NULL UNIQUE,
  request_id TEXT NOT NULL UNIQUE,
  code_hash TEXT NOT NULL REFERENCES codes(code_hash),
  app_id TEXT NOT NULL,
  type TEXT NOT NULL,
  secundarias INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL,
  issued_at TEXT NOT NULL,
  expires_at TEXT,
  device_thumbprint TEXT NOT NULL,
  device_public_key TEXT,
  active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
  transferred_from TEXT,
  created_at TEXT NOT NULL
);

-- Un codigo solo puede tener un canje activo; el historial de transferencias
-- queda en filas active = 0.
CREATE UNIQUE INDEX IF NOT EXISTS redemptions_one_active_per_code
  ON redemptions(code_hash) WHERE active = 1;

CREATE INDEX IF NOT EXISTS redemptions_device
  ON redemptions(device_thumbprint);

CREATE TABLE IF NOT EXISTS counters (
  key TEXT PRIMARY KEY,
  window_start INTEGER NOT NULL,
  count INTEGER NOT NULL
);

-- Autorizacion de transferencia: solo la instalacion con este thumbprint puede
-- reclamar el codigo. Se consume en el canje.
CREATE TABLE IF NOT EXISTS transfers (
  code_hash TEXT PRIMARY KEY REFERENCES codes(code_hash),
  from_license_id TEXT NOT NULL,
  to_thumbprint TEXT NOT NULL,
  created_at TEXT NOT NULL
);
