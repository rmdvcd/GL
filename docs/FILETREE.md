# Árbol del proyecto

Vista **curada, no exhaustiva**; las clases de prueba se agrupan para evitar que esta lista quede desfasada con cada test nuevo.

```text
GL/
├── AGENTS.md
├── README.md
├── SECURITY.md
├── CONTEXTO_LICENCIAS.md
├── SISTEMA_LICENCIAS_CLIENTES.md
├── GUIA_IA_TESTS_USB.md
├── AUDIT_2026-10-14.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
├── docs/
│   ├── ARCHITECTURE.md
│   ├── FILETREE.md
│   ├── GLREG.md
│   ├── INTEGRATION.md
│   ├── REVIEW.md
│   ├── TESTING.md
│   └── …
└── app/
    ├── build.gradle.kts
    ├── schemas/                     # esquemas Room exportados
    └── src/
        ├── main/java/dev/gl/license/
        │   ├── core/                # errores, DI, reloj, inicialización
        │   ├── data/local/          # Room, migraciones y repositorios
        │   ├── data/mapper/
        │   ├── domain/model/        # Models.kt, licencia, solicitud y tipos
        │   ├── domain/repository/
        │   ├── domain/usecase/      # validación, emisión e historial
        │   ├── presentation/
        │   │   ├── contact/
        │   │   ├── generator/
        │   │   ├── navigation/
        │   │   ├── registry/
        │   │   ├── theme/
        │   │   └── trust/
        │   └── security/            # CryptoEngine, HybridBox, SPVI, .glreg
        ├── test/java/dev/gl/license/ # 27 clases; conteo anotado en docs/TESTING.md
        └── androidTest/java/dev/gl/license/
            ├── KeystoreCryptoTest.kt
            ├── LicenseGenerationE2ETest.kt  # @Ignore hasta runner Android compatible
            ├── MainFlowsTest.kt
            ├── SmokeTest.kt
            └── HiltTestRunner.kt
```

El modelo de dominio está en `app/src/main/java/dev/gl/license/domain/model/Models.kt` (no `Modelos.kt`). No existe `presentation/lock/`: el módulo de autenticación se retiró. El repositorio tampoco incluye `gradlew` ni `gradle-wrapper.jar`; ver `docs/TESTING.md` para el entorno de compilación y los límites actuales de verificación.
