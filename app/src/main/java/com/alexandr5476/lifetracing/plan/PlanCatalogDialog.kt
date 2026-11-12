@file:Suppress("FunctionNaming", "LongMethod")

package com.alexandr5476.lifetracing.plan

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.util.concurrent.atomic.AtomicLong

/** One native window owns focus. Publication only recovers an actually removed keyboard target. */
private class CatalogFocus {
    val search = FocusRequester()
    val close = FocusRequester()
    var owner: Any? = null
    var removed by mutableStateOf<Any?>(null)
}

@Composable
private fun catalogFocusTarget(
    focus: CatalogFocus,
    fallbackToClose: Boolean = false,
): Modifier {
    val token = remember { Any() }
    return Modifier
        .onFocusTargetAttachmentChanged { attached ->
            if (!attached && focus.owner === token) focus.removed = fallbackToClose
        }.onFocusChanged {
            // A framework fallback assigned during removal must not cancel explicit recovery.
            if (it.isFocused && focus.removed == null) focus.owner = token
        }
}

/** Preserve multiline editing while making native Tab a traversal event, as in the editor. */
@Composable
private fun catalogSearchFocusTarget(focus: CatalogFocus): Modifier {
    val manager = LocalFocusManager.current
    return catalogFocusTarget(focus)
        .focusRequester(focus.search)
        .onPreviewKeyEvent {
            if (it.key == Key.Tab) {
                if (it.type == KeyEventType.KeyDown) {
                    focus.removed = null
                    manager.moveFocus(if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                }
                true
            } else {
                false
            }
        }.semantics { testTag = "plan-catalog-search" }
}

@Composable
internal fun PlanCatalogDialog(
    catalog: PlanCatalogState,
    onAction: (PlanAction) -> Unit,
) {
    val focus = remember { CatalogFocus() }
    val searchEvents = remember(catalog.context) { AtomicLong() }
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Android creates another composition root for Dialog, and Material overrides button targets.
    // Reapply the app presentation inside each slot, keeping the dialog's own native focus locals.
    val present: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides density,
        ) { LifeTracingAccessibleTargets(content) }
    }
    AlertDialog(
        onDismissRequest = { onAction(PlanAction.DismissCatalog(catalog.context)) },
        title = { present { Text(stringResource(R.string.plan_choose_template)) } },
        text = {
            present {
                val window = LocalWindowInfo.current
                val mode = LocalInputModeManager.current.inputMode
                var landing by remember { mutableStateOf(true) }
                LaunchedEffect(window.isWindowFocused, mode, focus.removed, landing) {
                    if (window.isWindowFocused && (landing || focus.removed != null)) {
                        val target = if (focus.removed == true) focus.close else focus.search
                        if (target === focus.search) scroll.scrollTo(0)
                        withFrameNanos { }
                        focus.removed = null
                        focus.owner = null
                        landing = !target.requestFocus()
                    }
                }
                Column(
                    Modifier.fillMaxWidth().verticalScroll(scroll).focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    LifeTracingOutlinedTextField(
                        catalog.query,
                        {
                            onAction(
                                PlanAction.SearchCatalog(
                                    it,
                                    catalog.context,
                                    editSequence = searchEvents.incrementAndGet(),
                                ),
                            )
                        },
                        modifier = catalogSearchFocusTarget(focus),
                        label = { Text(stringResource(R.string.plan_search)) },
                    )
                    if (catalog.query.isNotEmpty()) Text(stringResource(R.string.plan_catalog_query, catalog.query))
                    Text(
                        catalogStatus(catalog),
                        Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            testTag = "plan-catalog-status"
                        },
                    )
                    val descriptions = catalogDescriptions(catalog.items)
                    catalog.items.forEachIndexed { index, item ->
                        key(item.id) {
                            TextButton(
                                modifier =
                                    catalogFocusTarget(focus).fillMaxWidth().semantics {
                                        contentDescription = descriptions[index]
                                        testTag = catalogResultTag(item.id)
                                    },
                                onClick = { onAction(PlanAction.SelectCatalogItem(item, catalog.context)) },
                            ) { Text(descriptions[index]) }
                        }
                    }
                    if (catalog.failure != null || catalog.retrying) {
                        TextButton(
                            modifier = catalogFocusTarget(focus).semantics { testTag = "plan-catalog-retry" },
                            onClick = {
                                if (!catalog.loading) {
                                    if (catalog.items.isEmpty()) {
                                        onAction(PlanAction.SearchCatalog(catalog.query, catalog.context, retry = true))
                                    } else {
                                        onAction(PlanAction.LoadMoreCatalog(catalog.context))
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.plan_retry)) }
                    }
                    if (catalog.hasNextPage) {
                        TextButton(
                            modifier =
                                catalogFocusTarget(focus, fallbackToClose = true)
                                    .semantics { testTag = "plan-catalog-more" },
                            onClick = { onAction(PlanAction.LoadMoreCatalog(catalog.context)) },
                        ) { Text(stringResource(R.string.plan_load_more)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            present {
                TextButton(
                    modifier =
                        catalogFocusTarget(focus)
                            .focusRequester(focus.close)
                            .semantics { testTag = "plan-catalog-close" },
                    onClick = { onAction(PlanAction.DismissCatalog(catalog.context)) },
                ) { Text(stringResource(R.string.plan_close)) }
            }
        },
    )
}

@Composable
private fun catalogStatus(catalog: PlanCatalogState): String =
    stringResource(
        when {
            catalog.retrying -> R.string.plan_catalog_retrying
            catalog.loading && catalog.items.isNotEmpty() -> R.string.plan_catalog_appending
            catalog.loading && catalog.query.isNotEmpty() -> R.string.plan_catalog_searching
            catalog.loading -> R.string.plan_catalog_loading
            catalog.failure != null && catalog.items.isNotEmpty() -> R.string.plan_catalog_append_failed
            catalog.failure != null -> R.string.plan_catalog_search_failed
            catalog.items.isEmpty() -> R.string.plan_catalog_empty
            else -> R.string.plan_catalog_results
        },
        catalog.items.size,
    )

/** Two linear passes over loaded pages, with no source hydration or identity derived from names. */
@Composable
private fun catalogDescriptions(items: List<ReusablePlanCatalogItem>): List<String> {
    val names =
        items.map { item ->
            stringResource(
                R.string.plan_catalog_result,
                item.name,
                stringResource(
                    if (item.id is LibraryTemplateId.Activity) {
                        R.string.plan_execution_activity_kind
                    } else {
                        R.string.plan_execution_sequence
                    },
                ),
            )
        }
    val counts = names.groupingBy { it }.eachCount()
    val positions = mutableMapOf<String, Int>()
    return names.map { name ->
        val count = counts.getValue(name)
        val ordinal = positions.getOrDefault(name, 0) + 1
        positions[name] = ordinal
        if (count == 1) name else stringResource(R.string.plan_catalog_ordinal, name, ordinal, count)
    }
}

internal fun catalogResultTag(id: LibraryTemplateId): String =
    "plan-catalog-${if (id is LibraryTemplateId.Activity) "activity" else "sequence"}-${id.value}"
