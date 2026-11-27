package io.github.stevenzektyramadhan.isle.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.stevenzektyramadhan.isle.R
import io.github.stevenzektyramadhan.isle.update.AppUpdateChecker
import io.github.stevenzektyramadhan.isle.update.AppUpdateResult
import io.github.stevenzektyramadhan.isle.update.AppUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState
    data class Downloading(val progress: Float?) : UpdateInstallState
    data class ReadyToInstall(val apkFile: File) : UpdateInstallState
    data class NeedsInstallPermission(val apkFile: File) : UpdateInstallState
    data object Failed : UpdateInstallState
}

class AppUpdateViewModel(
    private val checker: AppUpdateChecker,
    private val updater: AppUpdater = AppUpdater(),
) : ViewModel() {
    var result by mutableStateOf<AppUpdateResult?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var dismissed by mutableStateOf(false)
        private set
    var manual by mutableStateOf(false)
        private set
    var browserError by mutableStateOf(false)
        private set
    var installState by mutableStateOf<UpdateInstallState>(UpdateInstallState.Idle)
        private set

    private var downloadJob: Job? = null

    init { check(false) }

    fun check(isManual: Boolean) {
        if (checking) {
            if (isManual) manual = true
            return
        }
        downloadJob?.cancel()
        downloadJob = null
        installState = UpdateInstallState.Idle
        checking = true
        manual = isManual
        dismissed = false
        browserError = false
        result = null
        viewModelScope.launch {
            try { result = checker.check() } finally { checking = false }
        }
    }

    fun dismiss() {
        cancelDownload()
        dismissed = true
    }

    fun startUpdate(context: Context, update: AppUpdateResult.Available) {
        val apkUrl = update.apkUrl
        if (apkUrl == null) {
            openRelease(context, update)
            return
        }
        when (val current = installState) {
            is UpdateInstallState.Downloading -> return
            is UpdateInstallState.ReadyToInstall -> {
                launchInstall(context, current.apkFile)
                return
            }
            is UpdateInstallState.NeedsInstallPermission -> {
                launchInstall(context, current.apkFile)
                return
            }
            UpdateInstallState.Idle, UpdateInstallState.Failed -> Unit
        }

        browserError = false
        downloadJob = viewModelScope.launch {
            installState = UpdateInstallState.Downloading(null)
            try {
                val apkFile = updater.downloadApk(
                    context = context.applicationContext,
                    apkUrl = apkUrl,
                    version = update.version,
                ) { progress ->
                    installState = UpdateInstallState.Downloading(progress)
                }
                installState = UpdateInstallState.ReadyToInstall(apkFile)
                launchInstall(context, apkFile)
            } catch (error: CancellationException) {
                installState = UpdateInstallState.Idle
                throw error
            } catch (_: Exception) {
                installState = UpdateInstallState.Failed
            } finally {
                downloadJob = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        if (installState is UpdateInstallState.Downloading) {
            installState = UpdateInstallState.Idle
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        try {
            context.startActivity(AppUpdater.unknownSourcesIntent(context))
        } catch (_: ActivityNotFoundException) {
            installState = UpdateInstallState.Failed
        } catch (_: SecurityException) {
            installState = UpdateInstallState.Failed
        }
    }

    fun onResume(context: Context) {
        val pending = installState as? UpdateInstallState.NeedsInstallPermission ?: return
        if (AppUpdater.canInstallPackages(context)) {
            launchInstall(context, pending.apkFile)
        }
    }

    private fun launchInstall(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            installState = UpdateInstallState.Failed
            return
        }
        if (!AppUpdater.canInstallPackages(context)) {
            installState = UpdateInstallState.NeedsInstallPermission(apkFile)
            return
        }
        installState = UpdateInstallState.ReadyToInstall(apkFile)
        try {
            context.startActivity(AppUpdater.installIntent(context, apkFile))
        } catch (_: ActivityNotFoundException) {
            installState = UpdateInstallState.Failed
        } catch (_: SecurityException) {
            installState = UpdateInstallState.Failed
        }
    }

    fun openRelease(context: Context, update: AppUpdateResult.Available) {
        browserError = false
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
        } catch (_: ActivityNotFoundException) {
            browserError = true
        } catch (_: SecurityException) {
            browserError = true
        }
    }

    class Factory(private val version: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppUpdateViewModel::class.java))
            return AppUpdateViewModel(AppUpdateChecker(version)) as T
        }
    }
}

@Composable
fun AppUpdateNotice(viewModel: AppUpdateViewModel, context: Context, modifier: Modifier = Modifier) {
    val update = viewModel.result as? AppUpdateResult.Available ?: return
    if (viewModel.dismissed) return

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SectionCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_available, update.version), style = MaterialTheme.typography.titleMedium)

            when (val state = viewModel.installState) {
                UpdateInstallState.Idle -> {
                    val descRes = if (update.apkUrl != null) R.string.update_description else R.string.update_description_browser
                    Text(stringResource(descRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.startUpdate(context, update) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.update_action))
                        }
                        TextButton(onClick = viewModel::dismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.update_later))
                        }
                    }
                }
                is UpdateInstallState.Downloading -> {
                    val statusText = state.progress?.let {
                        stringResource(R.string.update_downloading_percent, (it * 100f).roundToInt().coerceIn(0, 100))
                    } ?: stringResource(R.string.update_downloading)
                    Text(statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.progress != null) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = viewModel::cancelDownload, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.update_cancel))
                        }
                    }
                }
                is UpdateInstallState.ReadyToInstall -> {
                    Text(stringResource(R.string.update_ready_to_install), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.startUpdate(context, update) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.update_install_action))
                        }
                        TextButton(onClick = viewModel::dismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.update_later))
                        }
                    }
                }
                is UpdateInstallState.NeedsInstallPermission -> {
                    Text(stringResource(R.string.update_permission_needed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.openInstallPermissionSettings(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.update_allow_install))
                        }
                        TextButton(onClick = viewModel::dismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.update_later))
                        }
                    }
                }
                UpdateInstallState.Failed -> {
                    Text(stringResource(R.string.update_download_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.startUpdate(context, update) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.update_retry))
                        }
                        TextButton(onClick = { viewModel.openRelease(context, update) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.update_open_browser))
                        }
                    }
                }
            }

            if (viewModel.browserError) {
                Text(stringResource(R.string.update_browser_error), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
