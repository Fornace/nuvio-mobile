package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.aisubtitle.AiSubtitleRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_ai_subtitles_api_key
import nuvio.composeapp.generated.resources.settings_ai_subtitles_api_key_set
import nuvio.composeapp.generated.resources.settings_ai_subtitles_api_key_unset
import nuvio.composeapp.generated.resources.settings_ai_subtitles_base_url
import nuvio.composeapp.generated.resources.settings_ai_subtitles_enabled
import nuvio.composeapp.generated.resources.settings_ai_subtitles_enabled_description
import nuvio.composeapp.generated.resources.settings_ai_subtitles_model
import nuvio.composeapp.generated.resources.settings_ai_subtitles_section_title
import nuvio.composeapp.generated.resources.settings_ai_subtitles_target_language
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.aiSubtitlesSettingsContent(isTablet: Boolean) {
    item {
        val config by remember {
            AiSubtitleRepository.ensureLoaded()
            AiSubtitleRepository.config
        }.collectAsStateWithLifecycle()

        SettingsSection(
            title = stringResource(Res.string.settings_ai_subtitles_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_ai_subtitles_enabled),
                    description = stringResource(Res.string.settings_ai_subtitles_enabled_description),
                    checked = config.enabled,
                    isTablet = isTablet,
                    onCheckedChange = AiSubtitleRepository::setEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                AiSubtitleTextFieldRow(
                    label = stringResource(Res.string.settings_ai_subtitles_base_url),
                    value = config.baseUrl,
                    onCommitted = AiSubtitleRepository::setBaseUrl,
                )
                SettingsGroupDivider(isTablet = isTablet)
                AiSubtitleTextFieldRow(
                    label = stringResource(Res.string.settings_ai_subtitles_model),
                    value = config.model,
                    onCommitted = AiSubtitleRepository::setModel,
                )
                SettingsGroupDivider(isTablet = isTablet)
                AiSubtitleTextFieldRow(
                    label = stringResource(Res.string.settings_ai_subtitles_target_language),
                    value = config.targetLanguage,
                    onCommitted = AiSubtitleRepository::setTargetLanguage,
                )
                SettingsGroupDivider(isTablet = isTablet)
                AiSubtitleApiKeyRow(
                    label = stringResource(Res.string.settings_ai_subtitles_api_key),
                    storedLabel = stringResource(Res.string.settings_ai_subtitles_api_key_set),
                    notSetLabel = stringResource(Res.string.settings_ai_subtitles_api_key_unset),
                )
            }
        }
    }
}

@Composable
private fun AiSubtitleTextFieldRow(
    label: String,
    value: String,
    onCommitted: (String) -> Unit,
) {
    val horizontalPadding = 16.dp
    val verticalPadding = 14.dp
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedDraft = draft.trim()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (normalizedDraft.isNotBlank()) {
                        draft = normalizedDraft
                        onCommitted(normalizedDraft)
                    }
                },
                enabled = normalizedDraft != value && normalizedDraft.isNotBlank(),
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

@Composable
private fun AiSubtitleApiKeyRow(
    label: String,
    storedLabel: String,
    notSetLabel: String,
) {
    val horizontalPadding = 16.dp
    val verticalPadding = 14.dp
    // Never prefill the stored key; show only whether one is set.
    var draft by rememberSaveable { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(AiSubtitleRepository.hasApiKey()) }
    val normalizedDraft = draft.trim()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (hasKey) storedLabel else notSetLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        SettingsSecretTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = label,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    AiSubtitleRepository.setApiKey(normalizedDraft)
                    hasKey = AiSubtitleRepository.hasApiKey()
                    draft = ""
                },
                enabled = normalizedDraft.isNotBlank(),
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}
