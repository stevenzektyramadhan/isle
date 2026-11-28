package io.github.stevenzektyramadhan.isle.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import java.text.DateFormat
import java.util.Date

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
    var ytdlpVersion by remember { mutableStateOf<String?>(null) }
    var ytdlpLastCheckSecs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(updater) {
        ytdlpVersion = updater.installedVersion()
        ytdlpLastCheckSecs = updater.lastCheckEpochSecs()
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Native Top App Bar
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IsleIconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.settings_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // About group
                SettingsSection(title = stringResource(R.string.settings_about_section)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (versionName.isNotEmpty()) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Text(
                                        "v$versionName",
                                        Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                        style = IsleTypography.tabularDuration,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.settings_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // App updates group
                SettingsSection(title = stringResource(R.string.settings_update_section)) {
                    Column {
                        SettingsActionRow(
                            title = if (appUpdate.checking) stringResource(R.string.settings_checking_isle) else stringResource(R.string.settings_check_isle_update),
                            subtitle = if (appUpdate.manual && !appUpdate.checking) {
                                when (appUpdate.result) {
                                    AppUpdateResult.Current -> stringResource(R.string.settings_isle_up_to_date)
                                    AppUpdateResult.Error -> stringResource(R.string.settings_isle_update_error)
                                    else -> null
                                }
                            } else null,
                            subtitleError = appUpdate.manual && !appUpdate.checking && appUpdate.result == AppUpdateResult.Error,
                            enabled = !appUpdate.checking,
                            loading = appUpdate.checking,
                            onClick = { appUpdate.check(true) },
                        )
                    }
                }

                if (appUpdate.result is AppUpdateResult.Available) {
                    AppUpdateNotice(appUpdate, context, Modifier.fillMaxWidth())
                }

                // Audio engine group
                SettingsSection(
                    title = stringResource(R.string.settings_engine_section),
                    trailingBadge = {
                        playerState.ytdlpAvailable?.let { available ->
                            val (pillText, pillColor) = if (available)
                                stringResource(R.string.settings_ytdlp_active) to MaterialTheme.colorScheme.primary
                            else
                                stringResource(R.string.settings_ytdlp_unavailable) to MaterialTheme.colorScheme.error
                            Surface(shape = CircleShape, color = pillColor.copy(alpha = 0.14f)) {
                                Text(
                                    pillText,
                                    Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = pillColor,
                                )
                            }
                        }
                    },
                ) {
                    Column {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.settings_engine_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val formattedLastCheck = remember(ytdlpLastCheckSecs) {
                                ytdlpLastCheckSecs?.let { secs ->
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(secs * 1_000L))
                                }
                            }
                            if (ytdlpVersion != null || formattedLastCheck != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    ytdlpVersion?.let { version ->
                                        Text(
                                            stringResource(R.string.settings_ytdlp_version, version),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        formattedLastCheck?.let { stringResource(R.string.settings_ytdlp_last_checked, it) }
                                            ?: stringResource(R.string.settings_ytdlp_never_checked),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = IsleColors.divider)
                        SettingsActionRow(
                            title = if (ytdlpChecking) stringResource(R.string.settings_checking_ytdlp) else stringResource(R.string.settings_check_ytdlp),
                            subtitle = when (ytdlpResult) {
                                ManualUpdateResult.SUCCESS -> stringResource(R.string.settings_ytdlp_success)
                                ManualUpdateResult.FAILURE -> stringResource(R.string.settings_ytdlp_failure)
                                ManualUpdateResult.INTEGRATION_REQUIRED -> stringResource(R.string.settings_ytdlp_integration)
                                null -> null
                            },
                            subtitleError = ytdlpResult == ManualUpdateResult.FAILURE,
                            enabled = !ytdlpChecking,
                            loading = ytdlpChecking,
                            onClick = {
                                ytdlpChecking = true
                                ytdlpResult = null
                                scope.launch {
                                    try {
                                        ytdlpResult = updater.checkManually()
                                        ytdlpVersion = updater.installedVersion()
                                        ytdlpLastCheckSecs = updater.lastCheckEpochSecs()
                                    } finally {
                                        ytdlpChecking = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    trailingBadge: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            trailingBadge?.invoke()
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String?,
    subtitleError: Boolean,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subtitleError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        } else {
            Icon(
                painterResource(R.drawable.ic_refresh),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
