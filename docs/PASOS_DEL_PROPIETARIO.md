# Pasos del propietario — GL

Ordenados. **Los ejecutas tú, no el agente**: casi todos manejan claves,
passphrases o producción. Ejecuta en PowerShell desde `C:\ruta\a\GL` salvo donde
se indique. Los comandos marcados «agente» sí los puede ejecutar el agente.

## 0. Antes de empezar

- Ten una copia de seguridad del repositorio y de `services/redeem/secrets/`.
- No pegues claves privadas ni passphrases en el chat del agente. Solo las SPKI
  públicas y las huellas.
- `services/redeem/secrets/` y `services/redeem/data/` están ignorados por git.

## 1. Generar las dos raíces del directorio (offline)

Ejecuta en un equipo offline, con `node` disponible. Los ficheros salen cifrados
con AES-256-GCM y se derivan con scrypt desde la passphrase.

```powershell
$env:DIR_PASSPHRASE = "<passphrase de la raíz A, larga>"
node tools\directorio\cli.js gen-root --out "$env:USERPROFILE\directorio\raiz-A.key.enc"
Remove-Item Env:\DIR_PASSPHRASE

$env:DIR_PASSPHRASE = "<otra passphrase, distinta>"
node tools\directorio\cli.js gen-root --out "$env:USERPROFILE\directorio\raiz-B.key.enc"
Remove-Item Env:\DIR_PASSPHRASE
```

Cada `gen-root` imprime `SPKI_BASE64` y `sha256:…`. Guarda:

- dos copias **cifradas** de cada `.key.enc` en medios distintos, fuera del PC;
- cada passphrase en papel, separada de su copia;
- las SPKI y huellas en un fichero de texto para pasárselas al agente.

Nunca envíes `raiz-*.key.enc` ni la passphrase. El agente solo necesita
`RAIZ_A_SPKI` y `RAIZ_B_SPKI` (y sus huellas para compararlas por otro canal).

## 2. Firmar `directorio.json` con las claves actuales

En **GL → Claves**, para cada una de las dos claves, toca
**Copiar entrada de directorio** y pega el JSON en un archivo:

- `entrada-ecdh.json` (uso `ecdh`, kid `gl-ecdh-v1`)
- `entrada-firma.json` (uso `firma`, kid `gl-sign-v1`)

```powershell
mkdir "$env:USERPROFILE\directorio" -Force

# Inicializa la fuente (seq 0, sin entradas).
node tools\directorio\cli.js bootstrap-from-trust --out "$env:USERPROFILE\directorio\directorio.src.json"

# Añade las claves del emisor (las que copiaste de la pestaña Claves).
node tools\directorio\cli.js add-key --src "$env:USERPROFILE\directorio\directorio.src.json" --entry "$env:USERPROFILE\directorio\entrada-ecdh.json"
node tools\directorio\cli.js add-key --src "$env:USERPROFILE\directorio\directorio.src.json" --entry "$env:USERPROFILE\directorio\entrada-firma.json"

# Añade la pública del servicio de canje (es pública: sale de service.json).
cd services\redeem
$spki = (Get-Content .\secrets\service.json -Raw | ConvertFrom-Json).publicKey
cd ..\..
node tools\directorio\cli.js add-key --src "$env:USERPROFILE\directorio\directorio.src.json" --kid gl-redeem-v1 --uso canje --spki $spki --desde ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())

# Firma con la raíz A y verifica contra A y B.
$env:DIR_PASSPHRASE = "<passphrase de la raíz A>"
node tools\directorio\cli.js sign --src "$env:USERPROFILE\directorio\directorio.src.json" --root "$env:USERPROFILE\directorio\raiz-A.key.enc" --raiz A --out "$env:USERPROFILE\directorio\directorio.json"
Remove-Item Env:\DIR_PASSPHRASE

node tools\directorio\cli.js verify --file "$env:USERPROFILE\directorio\directorio.json" --root-pub "<RAIZ_A_SPKI>" --root-pub "<RAIZ_B_SPKI>"
```

Debe imprimir `Directorio válido: seq=1`.

- Conserva `directorio.src.json`: lleva el contador `seq`. Las siguientes
  versiones se firman desde él.
- Embebe esta primera versión verificada como `directorio-bootstrap.json` en el
  próximo build de Shalev (Android, iOS y DShalev).
- Publica `directorio.json` en la release **`revocaciones`** del repositorio de
  releases de Shalev. Confirma antes cuál es ese repositorio: en el código sigue
  el marcador `<REPO_SHALEV_RELEASES>`.

Cada vez que reinstales GL o cambies de teléfono, sus claves cambian: antes de
añadir las nuevas, marca las viejas como `retirada` con `hasta` = segundo del
cambio, y firma con `seq` mayor.

## 3. Instalar el APK nuevo en el teléfono emisor

El APK de esta rama incluye la ventana de 7 días, la precarga de `tx` y el
selector de apps en Lotes.

```powershell
adb devices
adb install -r "app\build\outputs\apk\debug\app-debug.apk"
```

**No** ejecutes `adb shell pm clear`: regenera las claves del Keystore y cambia
las huellas que el cliente ya tenga fijadas. Si tuvieras que hacerlo, exporta las
claves nuevas desde la pestaña **Claves** *después* del último `pm clear`.

Después, sigue `docs/PRUEBA_MANUAL_GL.md` (guion completo). Solo cuando el APK
nuevo esté instalado y probado tiene sentido que Shalev empiece a enviar `tx` y
el aviso «Vale 7 días», y que `ENVIAR_TX_EN_SOLICITUD` pase a `true` allí.

## 4. Autorizar las tres apps en el Worker (producción)

Con `wrangler` autenticado y **fuera** del índice de git:

```powershell
cd services\redeem
Copy-Item .\examples\wrangler.production.example.jsonc .\wrangler.production.jsonc
notepad .\wrangler.production.jsonc
```

Rellena `account_id` y `database_id`, y deja:

```jsonc
"vars": {
  "ALLOWED_APPS": "DSHALEV,SHALEV-ANDROID,SHALEV-IOS",
  "MAX_SECUNDARIAS": "0",
  "SERVICE_KID": "gl-redeem-v1",
  "MAX_CLOCK_SKEW_MS": "300000"
}
```

`MAX_SECUNDARIAS` se queda en `0` hasta que DShalev emita sub-tokens de verdad.

```powershell
npx wrangler deploy --config .\wrangler.production.jsonc
curl.exe https://gl-redeem.rmdvcd.workers.dev/health
```

## 5. Un lote por app: acuñar en GL, importar en D1, probar el humo

Acuña en **GL → Lotes**, una vez por app (`DSHALEV`, `SHALEV-ANDROID`,
`SHALEV-IOS`), y exporta cada lote firmado. Guárdalos en
`services/redeem/secrets/` (ignorado por git).

```powershell
cd services\redeem
$env:CODE_PEPPER = "<el pepper de producción, desde tu gestor>"
$env:GL_ISSUER_PUBLIC_KEY = (Get-Content .\secrets\gl-issuer.json -Raw | ConvertFrom-Json).publicKey

node src\cli.js sql-import --batch .\secrets\lote-SHALEV-ANDROID.json --public-key $env:GL_ISSUER_PUBLIC_KEY --out .\secrets\import.sql
npx wrangler d1 execute gl-redeem --remote --file .\secrets\import.sql
Remove-Item .\secrets\import.sql
```

El SQL es reintentable (`INSERT OR IGNORE`). **No** le añadas `BEGIN`/`COMMIT`.

Prueba de humo por app (lee un código del inventario de GL y cánjealo):

```powershell
$env:BASE_URL = "https://gl-redeem.rmdvcd.workers.dev"
$env:APP_ID = "SHALEV-ANDROID"
$env:SERVICE_PUBLIC_KEY = (Get-Content .\secrets\service.json -Raw | ConvertFrom-Json).publicKey
node scripts\smoke.mjs "XXXX-XXXX-XXXX-XXXX"
```

Esperado: `OK ACTIVA …`. Repite con el mismo código: debe dar
`ERROR CODE_ALREADY_REDEEMED`. Un código de `SHALEV-ANDROID` con
`APP_ID=DSHALEV` debe dar `ERROR CODE_UNAVAILABLE`.

Al terminar:

```powershell
Remove-Item Env:\CODE_PEPPER, Env:\GL_ISSUER_PUBLIC_KEY, Env:\SERVICE_PUBLIC_KEY
```

## 6. Decidir la purga del historial de Git (G1)

`wrangler.production.jsonc` y `.wrangler/` ya están **fuera del índice**, pero
los identificadores de cuenta/D1 siguen en commits antiguos. Purgarlos reescribe
el historial: todos los clones y ramas abiertas quedan inservibles hasta
reclonar.

**No ejecutes esto sin copia de seguridad y sin avisar a quien tenga clones.**

```powershell
# Solo en un clon dedicado y con copia previa.
pip install git-filter-repo
git filter-repo --invert-paths `
  --path "services/redeem/wrangler.production.jsonc" `
  --path-glob "*/.wrangler/*" --force
```

Después hay que rotar cualquier credencial que estuviera en esos ficheros y
volver a publicar el remoto.

## 7. Pruebas físicas pendientes

1. SMS reales a tu propio número: cuenta las partes de la respuesta SPVI2
   (esperado **3 segmentos GSM-7**).
2. Entrega por WhatsApp y por SMS desde Generator y desde el detalle.
3. Sin conexión: generar y verificar licencias offline.
4. Reloj desfasado ±1 h en el emisor: la solicitud debe seguir siendo aceptada;
   a +3 h debe rechazarse como `EmisorDesajustado`.
5. Sesión iPhone ↔ Android pendiente desde el 2026-11-08.
6. Revocación: exportar `revocadas.json`, firmar el directorio nuevo y comprobar
   que un cliente con `directorio-bootstrap.json` acepta el `seq` mayor.

## Pendiente de datos tuyos

- `RAIZ_A_SPKI` / `RAIZ_B_SPKI` y sus huellas (paso 1). El agente no las genera.
- `<REPO_SHALEV_RELEASES>`: confirmar el repositorio de la release `revocaciones`.
- Lotes por app: confirmar que `DSHALEV`, `SHALEV-ANDROID` y `SHALEV-IOS` son los
  `appId` definitivos antes de acuñar en producción.
