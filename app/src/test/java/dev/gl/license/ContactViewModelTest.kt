package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.ContactKind
import dev.gl.license.domain.model.ContactoMetodo
import dev.gl.license.domain.repository.ContactMethodRepository
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.presentation.contact.ContactViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private val repo = FakeContactRepo()
    private lateinit var vm: ContactViewModel

    // El VM se construye aquí y no como inicializador de campo: viewModelScope
    // toca Dispatchers.Main al crearse, y setMain aún no ha corrido en ese punto.
    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        vm = ContactViewModel(repo, FakeClock())
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun addingACardStripsSpacesAndSelectsIt() = runTest {
        vm.addCard(" 4242 4242 4242 4242 ")

        // El portapapeles es lo que pega el usuario en la pasarela: un número
        // con espacios de por medio se rechaza o se parte en dos campos.
        assertThat(vm.ui.value.selectedCard).isEqualTo("4242424242424242")
        assertThat(vm.ui.value.cards).containsExactly("4242424242424242")
    }

    @Test
    fun blankInputIsIgnoredInsteadOfStored() = runTest {
        vm.addCard("   ")
        vm.addPhone("\t\n")

        // Una opción en blanco se vería como real en el combobox y el usuario
        // podría acabar copiándola a la pasarela de pago.
        assertThat(vm.ui.value.cards).isEmpty()
        assertThat(vm.ui.value.phones).isEmpty()
        assertThat(vm.ui.value.selectedCard).isNull()
        assertThat(vm.ui.value.selectedPhone).isNull()
    }

    @Test
    fun addingTheSameValueTwiceLeavesOneEntry() = runTest {
        vm.addCard("4242 4242 4242 4242")
        vm.addCard("4242424242424242")

        // Mismo dato escrito con y sin espacios = un solo elemento. Lo resuelve
        // el id determinista con el REPLACE de Room, no una lista de duplicados.
        assertThat(vm.ui.value.cards).containsExactly("4242424242424242")
    }

    @Test
    fun phonesOnlyLoseOuterWhitespaceNotInner() = runTest {
        vm.addPhone("  +34 600 00 00 00  ")

        // Asimetría deliberada con la tarjeta: el teléfono conserva los espacios
        // internos porque hay formatos que los llevan y quitarlos sería perder
        // información que el usuario sí escribió.
        assertThat(vm.ui.value.selectedPhone).isEqualTo("+34 600 00 00 00")
    }

    @Test
    fun addingAnAppTrimsOuterWhitespaceAndSelectsIt() = runTest {
        vm.addApp("  Mi App  ")

        assertThat(vm.ui.value.selectedApp).isEqualTo("Mi App")
        assertThat(vm.ui.value.apps).containsExactly("Mi App")
    }

    @Test
    fun blankAppIsIgnoredInsteadOfStored() = runTest {
        vm.addApp("   ")

        assertThat(vm.ui.value.apps).isEmpty()
        assertThat(vm.ui.value.selectedApp).isNull()
    }

    @Test
    fun copyPayloadIsRefusedUntilAllThreePartsArePicked() = runTest {
        vm.addCard("4242424242424242")
        assertThat(vm.copyPayload()).isNull()

        vm.addPhone("+34600000000")
        assertThat(vm.copyPayload()).isNull()

        vm.addApp("SPVI")
        assertThat(vm.copyPayload()).isNotNull()
    }

    // ContactClipboardTest ya cubre el texto en sí. Este cubre el cableado: que
    // el ViewModel pase la app antes que la tarjeta y el teléfono, no solo que
    // la función pura concatene bien.
    @Test
    fun copyPayloadIsHeaderThenAppThenCardThenPhone() = runTest {
        vm.addCard("ES9121000418450200051332")
        vm.addPhone("+34600000000")
        vm.addApp("SPVI")

        assertThat(vm.copyPayload()).isEqualTo(
            "Tarjeta y numero a confirmar para compra de la licencia\n" +
                "SPVI\n" +
                "ES9121000418450200051332\n" +
                "+34600000000"
        )
    }

    @Test
    fun removingTheSelectedAppClearsItsSelection() = runTest {
        vm.addCard("4242424242424242")
        vm.addPhone("+34600000000")
        vm.addApp("SPVI")
        vm.removeApp("SPVI")

        // Si la selección sobreviviera al borrado, Copiar pegaría una app que
        // ya no existe en la lista.
        assertThat(vm.ui.value.apps).isEmpty()
        assertThat(vm.ui.value.selectedApp).isNull()
        assertThat(vm.copyPayload()).isNull()
    }

    @Test
    fun editingAnAppReplacesTheOldValueInsteadOfDuplicating() = runTest {
        vm.addApp("SPVI")

        assertThat(vm.beginEditApp("SPVI")).isEqualTo("SPVI")
        vm.addApp("MiApp")

        assertThat(vm.ui.value.apps).containsExactly("MiApp")
        assertThat(vm.ui.value.selectedApp).isEqualTo("MiApp")
    }

    @Test
    fun addingAnAppConfirmsItWasSaved() = runTest {
        vm.addApp("SPVI")

        assertThat(vm.ui.value.message).isEqualTo("Añadido a la lista.")
        assertThat(vm.ui.value.feedbackKind).isEqualTo(ContactKind.APP)
    }

    @Test
    fun removingTheSelectedCardClearsItsSelection() = runTest {
        vm.addCard("4242424242424242")
        vm.addPhone("+34600000000")
        vm.removeCard("4242424242424242")

        // Si la selección sobreviviera al borrado, Copiar pegaría un número
        // que ya no existe en la lista.
        assertThat(vm.ui.value.cards).isEmpty()
        assertThat(vm.ui.value.selectedCard).isNull()
        assertThat(vm.ui.value.selectedPhone).isEqualTo("+34600000000")
        assertThat(vm.copyPayload()).isNull()
    }

    @Test
    fun removingAnUnselectedValueKeepsTheSelection() = runTest {
        vm.addCard("1111222233334444")
        vm.addCard("5555666677778888")
        vm.removeCard("1111222233334444")

        assertThat(vm.ui.value.cards).containsExactly("5555666677778888")
        assertThat(vm.ui.value.selectedCard).isEqualTo("5555666677778888")
    }

    @Test
    fun editingReplacesTheOldValueInsteadOfDuplicating() = runTest {
        vm.addCard("1111222233334444")

        // beginEdit devuelve el valor para precargarlo en el campo; el Añadir
        // posterior sustituye la fila vieja en vez de sumar otra.
        assertThat(vm.beginEditCard("1111222233334444")).isEqualTo("1111222233334444")
        vm.addCard("5555666677778888")

        assertThat(vm.ui.value.cards).containsExactly("5555666677778888")
        assertThat(vm.ui.value.selectedCard).isEqualTo("5555666677778888")
    }

    @Test
    fun addingACardConfirmsItWasSaved() = runTest {
        vm.addCard("4242424242424242")

        // Sin esta confirmación, la pantalla tras Añadir es idéntica a la de
        // antes de pulsar: el campo muestra el valor guardado, igual que lo
        // tecleado, y parece que el botón no hizo nada.
        assertThat(vm.ui.value.message).isEqualTo("Añadido a la lista.")
    }

    @Test
    fun addingAPhoneConfirmsItWasSaved() = runTest {
        vm.addPhone("+34600000000")

        assertThat(vm.ui.value.message).isEqualTo("Añadido a la lista.")
    }

    private class FakeClock : SecureClock {
        override fun nowEpochMillis(): Long = 0L
        override fun nowIso(): String = "2026-01-01T00:00:00Z"
    }

    private class FakeContactRepo : ContactMethodRepository {
        private val items = MutableStateFlow<List<ContactoMetodo>>(emptyList())

        override fun observe(kind: String): Flow<List<String>> =
            items.map { list -> list.filter { it.kind == kind }.map { it.value } }

        override suspend fun add(item: ContactoMetodo) {
            items.value = items.value.filter { it.id != item.id } + item
        }

        override suspend fun remove(kind: String, value: String) {
            items.value = items.value.filter { it.id != "$kind:$value" }
        }
    }
}
