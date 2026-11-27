package io.github.stevenzektyramadhan.isle.ui

import io.github.stevenzektyramadhan.isle.R
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.stevenzektyramadhan.isle.update.AppUpdateChecker
import io.github.stevenzektyramadhan.isle.update.AppUpdateResult
import kotlinx.coroutines.launch

class AppUpdateViewModel(private val checker: AppUpdateChecker) : ViewModel() {
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

    init { check(false) }

    fun check(isManual: Boolean) {
        if (checking) {
            if (isManual) manual = true
            return
        }
        checking = true
        manual = isManual
        dismissed = false
        browserError = false
        result = null
        viewModelScope.launch {
            try { result = checker.check() } finally { checking = false }
        }
    }

    fun dismiss() { dismissed = true }

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
    SectionCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.update_available, update.version), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.update_description))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.openRelease(context, update) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_action)) }
                TextButton(onClick = viewModel::dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_later)) }
            }
            if (viewModel.browserError) {
                Text(stringResource(R.string.update_browser_error), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
