package com.alexandr5476.lifetracing

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferencesRepository
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import com.alexandr5476.lifetracing.ui.theme.spacing

class MainActivity : AppCompatActivity() {
    private val appearancePreferences by lazy { AppearancePreferencesRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appearance by appearancePreferences.preferences.collectAsState(initial = AppearancePreferences())
            LifeTracingApp(appearance)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
fun LifeTracingApp(
    appearance: AppearancePreferences = AppearancePreferences(),
    systemIsDark: Boolean = isSystemInDarkTheme(),
) {
    LifeTracingTheme(
        themeMode = appearance.themeMode,
        accentPaletteId = appearance.accentPaletteId,
        systemIsDark = systemIsDark,
    ) {
        DesignFoundationPreview()
    }
}

@Composable
@Suppress("FunctionNaming")
private fun DesignFoundationPreview() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            Text(
                text = stringResource(R.string.design_preview_title),
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                text = stringResource(R.string.design_preview_heading),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.design_preview_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {},
            ) {
                Text(stringResource(R.string.design_preview_primary_action))
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {},
            ) {
                Text(stringResource(R.string.design_preview_secondary_action))
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = stringResource(R.string.design_preview_input_value),
                onValueChange = {},
                label = { Text(stringResource(R.string.design_preview_input_label)) },
                readOnly = true,
            )
            DesignFoundationCard()
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun DesignFoundationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(
                text = stringResource(R.string.design_preview_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.design_preview_card_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.design_preview_row),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.design_preview_progress),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = { 0.65f },
            )
        }
    }
}
