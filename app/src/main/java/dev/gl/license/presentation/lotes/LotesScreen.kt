package dev.gl.license.presentation.lotes

import dev.gl.license.presentation.components.MessageActions
import dev.gl.license.presentation.components.copySensitiveText
import dev.gl.license.presentation.components.clipboardNotice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gl.license.domain.model.TipoLicencia
import kotlinx.coroutines.launch

@Composable
fun LotesScreen(vm: LotesViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var app by remember { mutableStateOf("DSHALEV") }; var type by remember { mutableStateOf(TipoLicencia.MENSUAL) }
    var count by remember { mutableStateOf("1") }; var deadline by remember { mutableStateOf("") }
    var exportId by rememberSaveable { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { vm.export(exportId) { json -> requireNotNull(ctx.contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use { it.write(json) } } }
    }
    val batches by vm.batches.collectAsStateWithLifecycle(initialValue = emptyList())
    // Cambiar de lote descarta el inventario anterior antes de recibir filas de Room.
    val inventory = key(vm.selected) {
        remember(vm.selected) { vm.codes(vm.selected) }.collectAsStateWithLifecycle(initialValue = emptyList()).value
    }
    var filtro by remember(vm.selected) { mutableStateOf("Todos") }
    var busqueda by remember(vm.selected) { mutableStateOf("") }
    val visibles = remember(inventory, filtro, busqueda) {
        val texto = busqueda.trim()
        inventory.filter { fila ->
            (filtro == "Todos" || fila.estado == filtro) &&
                (texto.isEmpty() || fila.nota.contains(texto, ignoreCase = true) ||
                    fila.code.takeLast(4).endsWith(texto, ignoreCase = true))
        }
    }
    var confirmarCopia by remember(vm.selected) { mutableStateOf<String?>(null) }
    inventory.firstOrNull { it.codeHash == confirmarCopia && it.estado == "ENTREGADO" }?.let { fila ->
        AlertDialog(
            onDismissRequest = { confirmarCopia = null },
            title = { Text("¿Volver a copiar un código entregado?") },
            text = { Text("Código terminado en ${fila.code.takeLast(4)}. Entrega registrada: ${fila.nota}. Comprueba el destinatario antes de reenviarlo.") },
            confirmButton = { TextButton(onClick = { copySensitiveText(ctx, fila.code); confirmarCopia = null }) { Text("Volver a copiar") } },
            dismissButton = { TextButton(onClick = { confirmarCopia = null }) { Text("Cancelar") } },
        )
    }
    DisposableEffect(Unit) { onDispose { vm.select(vm.selected) } }
    LazyColumn(modifier=Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
        item {
            Text("Lotes de canje",style=MaterialTheme.typography.headlineSmall)
            Choice("Aplicación",app,vm.appOptions){app=it}
            Text(vm.appNotice(app),style=MaterialTheme.typography.bodySmall)
            Choice("Tipo",type.name,TipoLicencia.values().map { it.name }) { type=TipoLicencia.valueOf(it) }
            OutlinedTextField(count,{count=it.take(3)},label={Text("Cantidad (1–500)")})
            OutlinedTextField(deadline,{deadline=it.take(32)},label={Text("Canjear antes de (UTC ISO, opcional)")})
            Text("Secundarias: 0")
            Button(onClick={vm.create(app,count.toIntOrNull() ?: 0,type,deadline.trim().ifEmpty { null })},enabled=!vm.busy){Text("Crear lote")}
            Text(vm.message)
        }
        if(vm.preview.isNotEmpty()) {
            item { Text("Códigos recién creados"); TextButton(onClick={vm.select(vm.selected)}){Text("Ocultar lista") } }
            items(vm.preview){Text(it)}
        }
        item {
            Text("Inventario guardado")
            var abierto by remember { mutableStateOf(false) }
            val actual = batches.firstOrNull { it.batchId == vm.selected }
            Box {
                TextButton(onClick = { abierto = true }, enabled = batches.isNotEmpty()) {
                    Text(actual?.let { "${it.appId} · ${it.createdAt}\n${it.batchId}" } ?: "Seleccionar lote")
                }
                DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
                    batches.forEach { lote ->
                        DropdownMenuItem(
                            text = { Text("${lote.appId} · ${lote.createdAt}\n${lote.batchId}") },
                            onClick = { abierto = false; vm.select(lote.batchId) },
                        )
                    }
                }
            }
        }
        if(vm.selected.isNotEmpty()) item {
            Button(onClick={exportId=vm.selected;export.launch("lote-${vm.selected}.json")}){Text("Exportar lote firmado")}
            Text(clipboardNotice)
        }
        if (vm.selected.isNotEmpty()) item {
            val libres = inventory.count { it.estado == "LIBRE" }
            val entregados = inventory.count { it.estado == "ENTREGADO" }
            Text("Libres: $libres · Entregados: $entregados · Total: ${inventory.size}")
            Choice("Estado", filtro, listOf("Todos", "LIBRE", "ENTREGADO")) { filtro = it }
            OutlinedTextField(value = busqueda, onValueChange = { busqueda = it.take(160) },
                singleLine = true, label = { Text("Nota, transacción o últimos 4 caracteres") })
            if (visibles.isEmpty() && inventory.isNotEmpty()) Text("Ningún código coincide con estos filtros.")
        }
        items(visibles,key={it.codeHash}) { row ->
            var note by remember(row.codeHash){ mutableStateOf("") }
            Text("•••• ${row.code.takeLast(4)} · ${row.estado}")
            TextButton(onClick = {
                if (row.estado == "LIBRE") copySensitiveText(ctx, row.code) else confirmarCopia = row.codeHash
            }) { Text(if (row.estado == "LIBRE") "Copiar código" else "Volver a copiar código") }
            if(row.estado=="LIBRE") {
                var mostrar by remember { mutableStateOf(false) }
                TextButton(onClick = { mostrar = true }) { Text("Mensaje para el cliente") }
                if (mostrar) {
                    val mensaje by produceState<String?>(null, row.batchId, row.codeHash) {
                        value = vm.customerMessage(row.batchId, row.code)
                            ?: ""
                    }
                    AlertDialog(onDismissRequest = { mostrar = false },
                        title = { Text("Mensaje para el cliente") },
                        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                            val body = mensaje
                            when {
                                body == null -> Text("Cargando mensaje…")
                                body.isEmpty() -> Text("No se pudo leer el código del lote.")
                                else -> {
                                    Text(body)
                                    MessageActions(body)
                                    Text("Tras comprobar el envío, registra la entrega manualmente.")
                                }
                            }
                        } },
                        confirmButton = { TextButton(onClick = { mostrar = false }) { Text("Volver a entrega") } })
                }
                OutlinedTextField(note,{note=it.take(160)},label={Text("Nombre y nº de transferencia")})
                TextButton(onClick={vm.deliver(row.codeHash,note)},enabled=note.isNotBlank()){Text("Marcar entregado")}
            } else Text(row.nota)
        }
    }
}
@Composable private fun Choice(label:String,value:String,options:List<String>,choose:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { TextButton(onClick={expanded=true}) { Text("$label: $value") }; DropdownMenu(expanded,{expanded=false}) {
        options.forEach { DropdownMenuItem(text={Text(it)},onClick={expanded=false;choose(it)}) }
    } }
}
