package com.roman.zemzeme.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A banner that appears at the top of the screen showing update status.
 * 
 * Shows different UI based on update state:
 * - Downloading: progress bar with percentage
 * - Ready: "Install Update" button
 * - Installing: spinner
 * - Error: error message with retry option
 */
@Composable
fun UpdateBanner(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val updateManager = UpdateManager.getInstance(context)
    val updateState by updateManager.updateState.collectAsState()
    
    // Only show banner for relevant states
    val shouldShow = when (updateState) {
        is UpdateState.Downloading,
        is UpdateState.ReadyToInstall,
        is UpdateState.Installing,
        is UpdateState.PendingUserAction,
        is UpdateState.Error -> true
        else -> false
    }
    
    AnimatedVisibility(
        visible = shouldShow,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier
    ) {
        UpdateBannerContent(
            state = updateState,
            onInstall = { updateManager.installUpdate() },
            onDismiss = { updateManager.dismissUpdate() },
            onRetry = { updateManager.checkForUpdate() },
            onCancel = { updateManager.cancelDownload() }
        )
    }
}

@Composable
private fun UpdateBannerContent(
    state: UpdateState,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val backgroundColor = when (state) {
        is UpdateState.Error -> MaterialTheme.colorScheme.errorContainer
        is UpdateState.ReadyToInstall -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    val contentColor = when (state) {
        is UpdateState.Error -> MaterialTheme.colorScheme.onErrorContainer
        is UpdateState.ReadyToInstall -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        when (state) {
            is UpdateState.Downloading -> {
                DownloadingBanner(
                    progress = state.progress,
                    versionName = state.info.versionName,
                    contentColor = contentColor,
                    onCancel = onCancel
                )
            }
            
            is UpdateState.ReadyToInstall -> {
                ReadyToInstallBanner(
                    versionName = state.info.versionName,
                    contentColor = contentColor,
                    onInstall = onInstall,
                    onDismiss = onDismiss
                )
            }
            
            is UpdateState.Installing, is UpdateState.PendingUserAction -> {
                InstallingBanner(contentColor = contentColor)
            }
            
            is UpdateState.Error -> {
                ErrorBanner(
                    message = state.message,
                    contentColor = contentColor,
                    onRetry = onRetry,
                    onDismiss = onDismiss
                )
            }
            
            else -> {}
        }
    }
}

@Composable
private fun DownloadingBanner(
    progress: Float,
    versionName: String,
    contentColor: androidx.compose.ui.graphics.Color,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Downloading update $versionName",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = contentColor
            )
            Spacer(modifier = Modifier.weight(1f))
            if (progress >= 0) {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel download",
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (progress >= 0) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                strokeCap = StrokeCap.Round,
                trackColor = contentColor.copy(alpha = 0.2f),
                color = contentColor
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                strokeCap = StrokeCap.Round,
                trackColor = contentColor.copy(alpha = 0.2f),
                color = contentColor
            )
        }
    }
}

@Composable
private fun ReadyToInstallBanner(
    versionName: String,
    contentColor: androidx.compose.ui.graphics.Color,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.SystemUpdate,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Update ready",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
            Text(
                text = "Version $versionName downloaded",
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
        TextButton(onClick = onDismiss) {
            Text("Later", color = contentColor.copy(alpha = 0.7f))
        }
        Button(
            onClick = onInstall,
            colors = ButtonDefaults.buttonColors(
                containerColor = contentColor,
                contentColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text("Install")
        }
    }
}

@Composable
private fun InstallingBanner(
    contentColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = contentColor
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Installing update...",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = contentColor
        )
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    contentColor: androidx.compose.ui.graphics.Color,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Update failed",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f),
                maxLines = 1
            )
        }
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = contentColor.copy(alpha = 0.7f))
        }
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = contentColor,
                contentColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text("Retry")
        }
    }
}
