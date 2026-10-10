# Árbol de archivos

```
GL/
├── README.md
├── SECURITY.md
├── CONTEXTO_LICENCIAS.md
├── GUIA_IA_TESTS_USB.md
├── AGENTS.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── docs/
│   ├── ARCHITECTURE.md
│   ├── DESIGN_SYSTEM.md
│   ├── FILETREE.md
│   ├── GLREG.md
│   ├── INTEGRATION.md
│   ├── MANUAL.md
│   ├── RELEASE.md
│   ├── REVIEW.md
│   ├── TESTING.md
│   ├── UI_AUDIT_FASE1.md
│   └── UI_UX_IX.md
├── app/src/main/java/dev/gl/license/
│   ├── GlApplication.kt
│   ├── core/{AppError.kt,AppRuntime.kt,Di.kt,SecureClockImpl.kt}
│   ├── domain/model/Modelos.kt
│   ├── domain/repository/Repositories.kt
│   ├── domain/usecase/{Validators.kt,UseCases.kt}
│   ├── data/local/{Db.kt,Migrations.kt,LicenseRepositoryImpl.kt,RegistroRepositoryImpl.kt}
│   ├── data/mapper/Mappers.kt
│   ├── security/{KeystoreManager.kt,CryptoEngine.kt,CryptoGatewayImpl.kt,HybridBox.kt,RegistryPack.kt,EnvelopeValidator.kt,SecureUi.kt,ClientRequestHelper.kt}
│   └── presentation/
│       ├── MainActivity.kt
│       ├── components/GlComponents.kt
│       ├── navigation/Routes.kt
│       ├── theme/Theme.kt
│       ├── generator/{GeneratorScreen.kt,GeneratorViewModel.kt}
│       └── registry/{RegistryScreen.kt,RegistryViewModel.kt,DetailScreen.kt,RegistryCountdown.kt}
├── app/src/test/java/dev/gl/license/
│   ├── CryptoEngineTest.kt
│   ├── DomainLayerTest.kt
│   ├── EdgeCasesTest.kt
│   ├── FieldValidatorTest.kt
│   ├── GeneratorViewModelTest.kt
│   ├── InMemoryLicenseRepositoryTest.kt
│   └── RegistryViewModelTest.kt
└── app/src/androidTest/java/dev/gl/license/
    ├── HiltTestRunner.kt
    ├── KeystoreCryptoTest.kt
    ├── MainFlowsTest.kt
    ├── LicenseGenerationE2ETest.kt   # @Ignore
    └── SmokeTest.kt
```

`Modelos.kt` es el nombre real del fichero de modelos (no `Models.kt`). No existe `presentation/lock/`: el módulo de autenticación se retiró por completo.
