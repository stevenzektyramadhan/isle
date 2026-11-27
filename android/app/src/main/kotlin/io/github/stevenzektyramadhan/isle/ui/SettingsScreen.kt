package io.github.stevenzektyramadhan.isle.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stevenzektyramadhan.isle.R
import io.github.stevenzektyramadhan.isle.model.PlayerState
import io.github.stevenzektyramadhan.isle.update.AppUpdateResult
import io.github.stevenzektyramadhan.isle.update.ManualUpdateResult
import io.github.stevenzektyramadhan.isle.update.YtDlpUpdater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    playerState: PlayerState,
    appUpdate: AppUpdateViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
    }
    val updater = remember { YtDlpUpdater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var ytdlpChecking by remember { mutableStateOf(false) }
    var ytdlpResult by remember { mutableStateOf<ManualUpdateResult?>(null) }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Top bar
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_chevron_down),
                        contentDescription = stringResource(R.string.settings_back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, color = IsleColors.textPrimary)
            }

            // About card
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_about_section), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = IsleColors.textPrimary)
                            if (versionName.isNotEmpty()) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Text(
                                        "v$versionName",
                                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.settings_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Isle update card
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_update_section), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(
                        onClick = { appUpdate.check(true) },
                        enabled = !appUpdate.checking,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, IsleColors.hairline),
                    ) {
                        Text(if (appUpdate.checking) stringResource(R.string.settings_checking_isle) else stringResource(R.string.settings_check_isle_update))
                    }
                    if (appUpdate.manual && !appUpdate.checking) {
                        when (appUpdate.result) {
                            AppUpdateResult.Current -> Text(stringResource(R.string.settings_isle_up_to_date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AppUpdateResult.Error -> Text(stringResource(R.string.settings_isle_update_error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            else -> Unit
                        }
                    }
                }
            }

            // yt-dlp engine card
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_engine_section), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        // Status pill: only if ytdlpAvailable is non-null
                        playerState.ytdlpAvailable?.let { available ->
                            val (pillText, pillColor) = if (available)
                                stringResource(R.string.settings_ytdlp_active) to MaterialTheme.colorScheme.primary
                            else
                                stringResource(R.string.settings_ytdlp_unavailable) to MaterialTheme.colorScheme.error
                            Surface(shape = CircleShape, color = pillColor.copy(alpha = 0.12f)) {
                                Text(pillText, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = pillColor)
                            }
                        }
                    }
                    Text(stringResource(R.string.settings_engine_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(
                        onClick = {
                            ytdlpChecking = true
                            ytdlpResult = null
                            scope.launch {
                                try { ytdlpResult = updater.checkManually() } finally { ytdlpChecking = false }
                            }
                        },
                        enabled = !ytdlpChecking,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = CircleShape,
                    ) {
                        Text(if (ytdlpChecking) stringResource(R.string.settings_checking_ytdlp) else stringResource(R.string.settings_check_ytdlp))
                    }
                    when (ytdlpResult) {
                        ManualUpdateResult.SUCCESS -> Text(stringResource(R.string.settings_ytdlp_success), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ManualUpdateResult.FAILURE -> Text(stringResource(R.string.settings_ytdlp_failure), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        ManualUpdateResult.INTEGRATION_REQUIRED -> Text(stringResource(R.string.settings_ytdlp_integration), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        null -> Unit
                    }
                }
            }
        }
    }
}
