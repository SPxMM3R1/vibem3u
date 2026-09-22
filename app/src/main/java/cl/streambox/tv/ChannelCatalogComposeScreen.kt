package cl.streambox.tv

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private val CatalogPanel = Color(0xCC12202A)
private val CatalogPanelRaised = Color(0xE61A2D39)
private val CatalogText = Color(0xFFF5FAFC)
private val CatalogMuted = Color(0xFFB7C8CE)
private val CatalogCyan = Color(0xFF00D8F5)
private val CatalogAmber = Color(0xFFFFC857)
private val CatalogDisabled = Color(0xFF24343D)
private val CatalogShape = RoundedCornerShape(12.dp)

/** Compose host used by SettingsActivity without converting the whole activity. */
class ChannelCatalogComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun bind(
        controller: ChannelCatalogComposeController,
        onOpenTvVoo: Runnable,
        onOpenHighfly: Runnable,
    ) {
        controllerState = controller
        openTvVooState = { onOpenTvVoo.run() }
        openHighflyState = { onOpenHighfly.run() }
    }

    private var controllerState by mutableStateOf<ChannelCatalogComposeController?>(null)
    private var openTvVooState by mutableStateOf<(() -> Unit)?>(null)
    private var openHighflyState by mutableStateOf<(() -> Unit)?>(null)

    @Composable
    override fun Content() {
        val controller = controllerState ?: return
        ChannelCatalogScreen(
            controller = controller,
            onOpenTvVoo = openTvVooState ?: {},
            onOpenHighfly = openHighflyState ?: {},
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCatalogScreen(
    controller: ChannelCatalogComposeController,
    onOpenTvVoo: () -> Unit,
    onOpenHighfly: () -> Unit,
) {
    val firstFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var revision by remember { mutableStateOf(0) }
    var selectedKey by rememberSaveable { mutableStateOf("") }
    var quickActionsKey by remember { mutableStateOf<String?>(null) }
    var confirmRemoveKey by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        controller.setOnChangedListener { revision++ }
        onDispose { controller.setOnChangedListener(null) }
    }

    val items = remember(revision) { controller.items }
    LaunchedEffect(items) {
        if (items.none { it.key == selectedKey }) {
            selectedKey = items.firstOrNull()?.key.orEmpty()
        }
    }
    LaunchedEffect(Unit) {
        firstFocusRequester.requestFocus()
    }

    val selectedItem = items.firstOrNull { it.key == selectedKey }
    val selectedIndex = items.indexOfFirst { it.key == selectedKey }
    val canMoveUp = selectedIndex > 0
    val canMoveDown = selectedIndex >= 0 && selectedIndex < items.lastIndex

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Catálogo de canales",
                    color = CatalogCyan,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Ordena, oculta o elimina canales. Mantén OK para acciones rápidas.",
                    color = CatalogMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CatalogCountBadge(items.size)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CatalogButton(
                label = "Añadir TvVoo",
                onClick = onOpenTvVoo,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(firstFocusRequester),
            )
            CatalogButton(
                label = "Añadir Highfly",
                onClick = onOpenHighfly,
                modifier = Modifier.weight(1f),
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val twoPane = maxWidth >= 900.dp
            if (twoPane) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ChannelListPanel(
                        items = items,
                        selectedKey = selectedKey,
                        listState = listState,
                        onSelect = { selectedKey = it },
                        onLongPress = {
                            selectedKey = it
                            quickActionsKey = it
                        },
                        modifier = Modifier.weight(1.55f),
                    )
                    ActionPanel(
                        item = selectedItem,
                        canMoveUp = canMoveUp,
                        canMoveDown = canMoveDown,
                        publishing = publishing,
                        onMoveUp = {
                            if (selectedKey.isNotBlank()) controller.move(selectedKey, -1)
                            status = "Orden local guardado"
                        },
                        onMoveDown = {
                            if (selectedKey.isNotBlank()) controller.move(selectedKey, 1)
                            status = "Orden local guardado"
                        },
                        onToggleVisibility = {
                            if (selectedKey.isNotBlank()) controller.toggleVisibility(selectedKey)
                            status = if (selectedItem?.isHidden == true) "Canal visible" else "Canal oculto"
                        },
                        onRemove = { confirmRemoveKey = selectedKey.takeIf { it.isNotBlank() } },
                        onPublish = {
                            publishing = true
                            status = "Publicando selección…"
                            controller.publish { message, _ ->
                                publishing = false
                                status = message
                            }
                        },
                        modifier = Modifier.widthIn(min = 270.dp, max = 340.dp),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChannelListPanel(
                        items = items,
                        selectedKey = selectedKey,
                        listState = listState,
                        onSelect = { selectedKey = it },
                        onLongPress = {
                            selectedKey = it
                            quickActionsKey = it
                        },
                    )
                    ActionPanel(
                        item = selectedItem,
                        canMoveUp = canMoveUp,
                        canMoveDown = canMoveDown,
                        publishing = publishing,
                        onMoveUp = {
                            if (selectedKey.isNotBlank()) controller.move(selectedKey, -1)
                            status = "Orden local guardado"
                        },
                        onMoveDown = {
                            if (selectedKey.isNotBlank()) controller.move(selectedKey, 1)
                            status = "Orden local guardado"
                        },
                        onToggleVisibility = {
                            if (selectedKey.isNotBlank()) controller.toggleVisibility(selectedKey)
                            status = if (selectedItem?.isHidden == true) "Canal visible" else "Canal oculto"
                        },
                        onRemove = { confirmRemoveKey = selectedKey.takeIf { it.isNotBlank() } },
                        onPublish = {
                            publishing = true
                            status = "Publicando selección…"
                            controller.publish { message, _ ->
                                publishing = false
                                status = message
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                text = status,
                color = CatalogMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    val quickItem = items.firstOrNull { it.key == quickActionsKey }
    if (quickItem != null) {
        QuickActionsDialog(
            item = quickItem,
            onDismiss = { quickActionsKey = null },
            onToggleVisibility = {
                controller.toggleVisibility(quickItem.key)
                selectedKey = quickItem.key
                quickActionsKey = null
                status = if (quickItem.isHidden) "Canal visible" else "Canal oculto"
            },
            onRemove = {
                quickActionsKey = null
                confirmRemoveKey = quickItem.key
            },
        )
    }

    val removeItem = items.firstOrNull { it.key == confirmRemoveKey }
    if (removeItem != null) {
        ConfirmRemoveDialog(
            item = removeItem,
            onDismiss = { confirmRemoveKey = null },
            onConfirm = {
                controller.remove(removeItem.key)
                confirmRemoveKey = null
                status = "Canal eliminado del catálogo"
            },
        )
    }
}

@Composable
private fun CatalogCountBadge(count: Int) {
    Box(
        modifier = Modifier
            .background(Color(0x3318D9F2), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$count canales",
            color = CatalogCyan,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelListPanel(
    items: List<ChannelCatalogComposeController.Item>,
    selectedKey: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        PanelTitle(title = "Canales seleccionados", detail = "Lista M3U · TvVoo · Highfly")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 370.dp)
                .background(CatalogPanel, CatalogShape)
                .padding(10.dp),
        ) {
            if (items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No hay canales seleccionados",
                        color = CatalogText,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Añade TvVoo o Highfly, o activa una lista M3U.",
                        color = CatalogMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRestorer(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                        ChannelRow(
                            index = index,
                            item = item,
                            selected = item.key == selectedKey,
                            onSelect = { onSelect(item.key) },
                            onLongPress = { onLongPress(item.key) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    index: Int,
    item: ChannelCatalogComposeController.Item,
    selected: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val details = buildString {
        append(item.sourceLabel)
        append(" · ")
        append(item.group)
        if (item.category.isNotBlank() && !item.category.equals(item.group, ignoreCase = true)) {
            append(" · ")
            append(item.category)
        }
        if (item.isHidden) append(" · Oculto")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .scale(if (focused) 1.02f else 1f)
            .clip(shape)
            .background(
                when {
                    focused -> CatalogPanelRaised
                    selected -> Color(0xAA20343F)
                    else -> Color(0x9916222B)
                },
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) CatalogCyan else Color.Transparent,
                shape = shape,
            )
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongPress,
            )
            .focusable()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onSelect()
            }
            .semantics {
                contentDescription = "${index + 1}. ${item.name}. $details"
                role = Role.Button
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${index + 1}. ${item.name}",
                color = if (item.isHidden) CatalogMuted else CatalogText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = details,
                color = if (item.isHidden) CatalogAmber else CatalogMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Text(
                text = "ACTIVO",
                color = CatalogCyan,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ActionPanel(
    item: ChannelCatalogComposeController.Item?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    publishing: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleVisibility: () -> Unit,
    onRemove: () -> Unit,
    onPublish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(CatalogPanel, CatalogShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PanelTitle(title = "Acciones", detail = "Selecciona un canal para editarlo")
        if (item == null) {
            Text(
                text = "Elige un canal de la lista.",
                color = CatalogMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                text = item.name,
                color = CatalogText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CatalogButton(
                    label = "Subir",
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.weight(1f),
                )
                CatalogButton(
                    label = "Bajar",
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.weight(1f),
                )
            }
            CatalogButton(
                label = if (item.isHidden) "Mostrar" else "Ocultar",
                onClick = onToggleVisibility,
                modifier = Modifier.fillMaxWidth(),
            )
            CatalogButton(
                label = "Eliminar",
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        CatalogButton(
            label = if (publishing) "Publicando…" else "Publicar selección en GitHub",
            onClick = onPublish,
            enabled = !publishing,
            primary = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PanelTitle(title: String, detail: String) {
    Column {
        Text(
            text = title,
            color = CatalogCyan,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = detail,
            color = CatalogMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CatalogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) Color(0xFF0E4E5A) else Color(0xFF1C303A),
            contentColor = CatalogText,
            focusedContainerColor = CatalogCyan,
            focusedContentColor = Color(0xFF071015),
            pressedContainerColor = CatalogCyan,
            pressedContentColor = Color(0xFF071015),
            disabledContainerColor = CatalogDisabled,
            disabledContentColor = Color(0xFF70838B),
        ),
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun QuickActionsDialog(
    item: ChannelCatalogComposeController.Item,
    onDismiss: () -> Unit,
    onToggleVisibility: () -> Unit,
    onRemove: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Column(
            modifier = Modifier
                .widthIn(min = 500.dp, max = 720.dp)
                .background(CatalogPanelRaised, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Acciones para ${item.name}",
                color = CatalogText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Elige una acción para este canal.",
                color = CatalogMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CatalogButton(
                    label = if (item.isHidden) "Mostrar" else "Ocultar",
                    onClick = onToggleVisibility,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                )
                CatalogButton(
                    label = "Eliminar",
                    onClick = onRemove,
                    modifier = Modifier.weight(1f),
                )
                CatalogButton(
                    label = "Cancelar",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ConfirmRemoveDialog(
    item: ChannelCatalogComposeController.Item,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Column(
            modifier = Modifier
                .widthIn(min = 500.dp, max = 720.dp)
                .background(CatalogPanelRaised, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Eliminar canal",
                color = CatalogText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "¿Eliminar ${item.name} del catálogo? Esta acción no publica cambios por sí sola.",
                color = CatalogMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CatalogButton(
                    label = "Eliminar",
                    onClick = onConfirm,
                    primary = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                )
                CatalogButton(
                    label = "Cancelar",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
