package dev.gl.license.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gl.license.R
import dev.gl.license.domain.model.Licencia
import dev.gl.license.presentation.registry.RegistryCountdown
import dev.gl.license.presentation.theme.GlColors
import dev.gl.license.presentation.theme.GlDimens
import dev.gl.license.presentation.theme.GlMotion
import kotlinx.coroutines.delay

/** Encabezado principal consistente para las cuatro secciones de GL. */
@Composable
fun ScreenTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
    )
}

/** Título con contexto breve: evita que una pantalla dependa solo de sus controles. */
@Composable
fun ScreenHeader(
    title: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenTitle(title)
        if (!supportingText.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Etiqueta de sección para ordenar formularios largos sin convertirlos en una lista de tarjetas. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() },
    )
}

@Composable
fun GlPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .height(GlDimens.touch),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text)
        }
    }
}

@Composable
fun GlSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(GlDimens.touch),
    ) { Text(text) }
}

/**
 * Resultado de una acción. Ambos estados se anuncian a lectores de pantalla sin
 * mover el foco ni tapar el contenido que el operador estaba revisando.
 */
@Composable
fun StatusBanner(error: String?, success: String?) {
    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn(tween(GlMotion.normal)),
        exit = fadeOut(tween(GlMotion.fast)),
    ) {
        if (error != null) {
            val description = stringResource(R.string.cd_error, error)
            Surface(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                contentColor = MaterialTheme.colorScheme.error,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.34f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = error,
                    modifier = Modifier
                        .padding(12.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = description
                        },
                )
            }
        }
    }
    AnimatedVisibility(
        visible = success != null && error == null,
        enter = fadeIn(tween(GlMotion.normal)),
        exit = fadeOut(tween(GlMotion.fast)),
    ) {
        if (success != null) {
            val description = stringResource(R.string.cd_success, success)
            Surface(
                color = GlColors.ok.copy(alpha = 0.14f),
                contentColor = GlColors.ok,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, GlColors.ok.copy(alpha = 0.30f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = success,
                    modifier = Modifier
                        .padding(12.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = description
                        },
                )
            }
        }
    }
}

/** Superficie de agrupación con separación suficiente para el contenido sensible. */
@Composable
fun GlCard(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = contentAlignment,
            content = content,
        )
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun CountdownText(item: Licencia, nowMillis: Long) {
    val label = RegistryCountdown.label(item, nowMillis)
    val color = when (label) {
        "Vencida" -> MaterialTheme.colorScheme.error
        "Perpetua" -> GlColors.ok
        "—" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val description = stringResource(R.string.cd_countdown, label)
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
    )
}

@Composable
fun LiveCountdown(item: Licencia, running: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running, item.id) {
        if (!running) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    CountdownText(item, now)
}

/**
 * Fila que sigue funcionando con fuente grande: la etiqueta tiene un ancho
 * acotado y el valor conserva el resto, se ajusta a varias líneas y se anuncia
 * como una sola unidad a TalkBack.
 */
@Composable
fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label $value" },
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 88.dp, max = 124.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
