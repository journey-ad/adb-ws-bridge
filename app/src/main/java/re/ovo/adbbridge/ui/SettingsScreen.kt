package re.ovo.adbbridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import re.ovo.adbbridge.R
import re.ovo.adbbridge.data.AppLanguage
import re.ovo.adbbridge.data.AppPrefs
import re.ovo.adbbridge.data.ThemeMode

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val themeMode by AppPrefs.themeMode.collectAsState()
    val language by AppPrefs.language.collectAsState()
    val wsPort by AppPrefs.wsPort.collectAsState()
    val passwordEnabled by viewModel.passwordEnabled.collectAsState()
    var portDialog by remember { mutableStateOf(false) }
    var passwordDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ConnectionCard(
                port = "$wsPort",
                passwordEnabled = passwordEnabled,
                onChangePort = { portDialog = true },
                onChangePassword = { passwordDialog = true },
            )
        }
        item {
            AppearanceCard(mode = themeMode, onSelect = { AppPrefs.setThemeMode(it) })
        }
        item {
            LanguageCard(mode = language, onSelect = { AppPrefs.setLanguage(it) })
        }
        item {
            AboutCard(version = viewModel.versionName)
        }
        item {
            CreditsCard()
        }
    }

    if (portDialog) {
        PortDialog(
            current = wsPort,
            onDismiss = { portDialog = false },
            onConfirm = { value ->
                viewModel.setWsPort(value)
                portDialog = false
            },
        )
    }

    if (passwordDialog) {
        PasswordDialog(
            passwordEnabled = passwordEnabled,
            onDismiss = { passwordDialog = false },
            onConfirm = { value ->
                viewModel.setPassword(value)
                passwordDialog = false
            },
            onClear = {
                viewModel.clearPassword()
                passwordDialog = false
            },
        )
    }
}

@Composable
private fun ConnectionCard(
    port: String,
    passwordEnabled: Boolean,
    onChangePort: () -> Unit,
    onChangePassword: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_connection)) {
        InfoRow(
            label = stringResource(R.string.label_port),
            value = port,
            action = {
                SmallActionButton(
                    text = stringResource(R.string.action_edit),
                    icon = Icons.Outlined.Edit,
                    onClick = onChangePort,
                )
            },
        )
        InfoRow(
            label = stringResource(R.string.label_auth),
            value = if (passwordEnabled) {
                stringResource(R.string.auth_required)
            } else {
                stringResource(R.string.auth_none)
            },
            action = {
                SmallActionButton(
                    text = stringResource(R.string.action_edit),
                    icon = Icons.Outlined.Edit,
                    onClick = onChangePassword,
                )
            },
        )
    }
}

@Composable
private fun AppearanceCard(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SectionCard(title = stringResource(R.string.settings_appearance)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeOption(label = stringResource(R.string.theme_system), option = ThemeMode.SYSTEM, mode = mode, onSelect = onSelect)
            ThemeOption(label = stringResource(R.string.theme_light), option = ThemeMode.LIGHT, mode = mode, onSelect = onSelect)
            ThemeOption(label = stringResource(R.string.theme_dark), option = ThemeMode.DARK, mode = mode, onSelect = onSelect)
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    option: ThemeMode,
    mode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    FilterChip(
        selected = mode == option,
        onClick = { onSelect(option) },
        label = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
private fun LanguageCard(mode: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    SectionCard(title = stringResource(R.string.settings_language)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageOption(
                label = stringResource(R.string.language_system),
                option = AppLanguage.SYSTEM,
                mode = mode,
                onSelect = onSelect,
            )
            LanguageOption(
                label = stringResource(R.string.language_english),
                option = AppLanguage.ENGLISH,
                mode = mode,
                onSelect = onSelect,
            )
            LanguageOption(
                label = stringResource(R.string.language_chinese),
                option = AppLanguage.CHINESE,
                mode = mode,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun LanguageOption(
    label: String,
    option: AppLanguage,
    mode: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    FilterChip(
        selected = mode == option,
        onClick = { onSelect(option) },
        label = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
private fun AboutCard(version: String) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.settings_about)) {
        InfoRow(label = stringResource(R.string.label_version), value = version)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.about_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        LinkRow(
            label = stringResource(R.string.link_source),
            url = SOURCE_URL,
            onClick = { openUrl(context, SOURCE_URL) },
        )
    }
}

@Composable
private fun CreditsCard() {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.settings_credits)) {
        CREDITS.forEach { credit ->
            LinkRow(
                label = credit.first,
                url = credit.second,
                onClick = { openUrl(context, credit.second) },
            )
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 76.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = url.removePrefix("https://"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private const val SOURCE_URL = "https://github.com/journey-ad/adb-ws-bridge"
private const val ISSUES_URL = "$SOURCE_URL/issues"
private const val LICENSE_URL = "$SOURCE_URL/blob/main/LICENSE"

private val CREDITS = listOf(
    "Shizuku" to "https://github.com/RikkaApps/Shizuku",
    "Ktor" to "https://github.com/ktorio/ktor",
)
