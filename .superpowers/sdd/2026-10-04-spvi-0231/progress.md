# SDD ledger — plan: docs/superpowers/plans/2026-10-04-spvi-0231.md

- Setup: trabajo sobre main (5acfb0d) con consentimiento del flujo habitual del repo; sin worktree.
- Pre-flight: T1→T2 (b64urlD, comprimir/descomprimirPunto) OK; T2→T3 (abrir/sellarSolicitud) OK; T2→T5 (los 3 AppError se definen en T2) OK; T3→T5 (Spvi23.verificar) OK; T5→T6 (codigoCorto, ResponseMessage, getById/clock por leer) OK; T6→T8 (SpviPuerta, EmitirSpvi2UseCase, SpviEmitida) OK; T5→T7 (licenciaCorta) OK; T8→T9 (ui.spvi/shareBody/renuevaAviso) OK.
- Ruling pre-flight: Task 8 necesita binding Hilt de SpviPuerta (CryptoGatewayImpl no se inyecta como interfaz sin @Binds/@Provides); se añade módulo en Task 8. Coste si mal: no compila DI; se detecta en compileDebugKotlin del Task 9.
- Task 1: complete (commit 951ccd3, tests: Spvi23CodecTest → 3/3 pass). RED visto: Unresolved reference 'Spvi23' (+ cascada); import assertThrows corregido a org.junit.Assert.assertThrows (el repo usa ese).
Task 2: Ruling: roundtrip usa una sola instancia dto() (el plan llamaba dto() dos veces con devicePub aleatoria distinta) � test, no produccion. Ruling: extraer espera 'ABCD-ef_gh' (el plan esperaba 'AB-CD-ef_gh'; el salto se ignora segun spec, no se convierte en guion) � test, no produccion.
Task 2: complete (tests: Spvi23SolicitudTest 3/3 pass)
L Task 3: complete (tests: Spvi23VectorTest 4/4 pass)
Task 4: complete (mensaje SPVI2 exacto, tests: SpviMensajeTest 3/3 PASS, fechas Havana verificadas contra dorado 03/10/2026 y 02/11/2026)
Task 5: Ruling: eliminados generarConservaSecundariasYCalculaPrecioCobrado y generarPermiteCorregirSecundariasAntesDeFirmar (contradicen la guardia anti-larga; la emision v1 a SPVI queda prohibida) - reemplazados por generarRechazaSolicitudV1DeSpviSinCifra. Task 5: complete (tests: FieldValidatorTest + DomainLayerTest PASS, BUILD SUCCESSFUL)
Task 6: complete (tests: :app:testDebugUnitTest --tests dev.gl.license.RenovacionSpviTest -> 4/4 pass). Ruling: EmitirSpvi2UseCase.invoke es suspend (LicenciaRepository.getById lo exige); el helper emitir() del test paso a suspend.
Task 7: complete (commits d4acf35, tests: DatabaseMigrationTest 7/7 + SpviGlregTest 6/6 PASS)
Task 8: complete (tests: :app:testDebugUnitTest --tests GeneratorViewModelTest ? 9/9 pass)
Task 9: complete (commit b340328, tests: :app:testDebugUnitTest --tests ExtraccionSpviTest -> 3/3 pass; compileDebugKotlin PASS)
Task 10: complete (commits b340328..7c8f35d, tests: :app:testDebugUnitTest --rerun -> 22 suites / 110 tests / 0 fail; device 4bc9f8f3 PID vivo, crash vacio, Registro sin errores BD; push f5402e0..7c8f35d main -> main)
