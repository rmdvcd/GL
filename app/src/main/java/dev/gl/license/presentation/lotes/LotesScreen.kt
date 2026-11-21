package dev.gl.license.presentation.lotes

import android.content.*
import android.os.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val inventory by remember(vm.selected) { vm.codes(vm.selected) }.collectAsStateWithLifecycle(initialValue = emptyList())
    DisposableEffect(Unit) { onDispose { vm.select(vm.selected) } }
    LazyColumn(modifier=Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
        item {
            Text("Lotes de canje",style=MaterialTheme.typography.headlineSmall)
            Choice("Aplicación",app,listOf("DSHALEV","SHALEV-ANDROID","SHALEV-IOS")){app=it}
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
        item { Text("Inventario guardado"); Choice("Lote",vm.selected,batches.map { it.batchId }, vm::select) }
        if(vm.selected.isNotEmpty()) item {
            Button(onClick={exportId=vm.selected;export.launch("lote-${vm.selected}.json")}){Text("Exportar lote firmado")}
            Text("Al copiar se intentará borrar el código del portapapeles a los 60 s. Si la app está cerrada o en segundo plano, bórralo manualmente.")
        }
        items(inventory,key={it.codeHash}) { row ->
            var note by remember(row.codeHash){ mutableStateOf("") }
            Text("•••• ${row.code.takeLast(4)} · ${row.estado}")
            TextButton(onClick={copyCode(ctx,row.code)}){Text("Copiar código")}
            if(row.estado=="LIBRE") {
                OutlinedTextField(note,{note=it.take(160)},label={Text("Nombre o nº de transacción")})
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
private fun copyCode(context:Context,code:String) {
    val cm=context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val label="GL-"+java.util.UUID.randomUUID()
    val clip=ClipData.newPlainText(label,code)
    clip.description.extras=PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
    cm.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        try { if(cm.primaryClipDescription?.label==label) {
            if(Build.VERSION.SDK_INT>=28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("",""))
        } } catch (_: SecurityException) { /* Android puede impedir leer fuera de primer plano. */ }
    },60000L)
}
