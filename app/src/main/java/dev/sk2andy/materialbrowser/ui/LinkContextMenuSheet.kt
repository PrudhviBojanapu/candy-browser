package dev.sk2andy.materialbrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sk2andy.materialbrowser.R
import dev.sk2andy.materialbrowser.browser.actions.WebContentTarget
import dev.sk2andy.materialbrowser.ui.theme.browserChromeColor
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkContextMenuSheet(
    target: WebContentTarget?,
    onOpenInForeground: (String) -> Unit,
    onOpenInBackground: (String) -> Unit,
    onOpenInPrivate: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    onShareLink: (String) -> Unit,
    onDownloadLink: () -> Unit,
    onDownloadImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (target == null) return
    val view = LocalView.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val linkUrl = target.linkUrl
    val imageUrl = target.imageUrl
    val displayHost = linkUrl?.let { url ->
        try {
            URI(url).host ?: url
        } catch (_: Throwable) {
            url
        }
    } ?: imageUrl?.let { url ->
        try {
            URI(url).host ?: url
        } catch (_: Throwable) {
            url
        }
    } ?: stringResource(R.string.content_actions_title)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = browserChromeColor(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // Header: Domain & URL
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = displayHost,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (linkUrl != null && linkUrl != displayHost) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = linkUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp,
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Link Actions
            if (linkUrl != null) {
                LinkContextItem(
                    icon = Icons.Outlined.OpenInNew,
                    label = stringResource(R.string.action_open_in_new_tab_and_switch),
                    onClick = {
                        view.performConfirmHaptic()
                        onDismiss()
                        onOpenInForeground(linkUrl)
                    },
                )

                LinkContextItem(
                    icon = Icons.Outlined.OpenInBrowser,
                    label = stringResource(R.string.action_open_link_background_tab),
                    onClick = {
                        view.performConfirmHaptic()
                        onDismiss()
                        onOpenInBackground(linkUrl)
                    },
                )

                LinkContextItem(
                    icon = Icons.Outlined.Security,
                    label = stringResource(R.string.action_open_link_in_private_tab),
                    onClick = {
                        view.performConfirmHaptic()
                        onDismiss()
                        onOpenInPrivate(linkUrl)
                    },
                )

                LinkContextItem(
                    icon = Icons.Outlined.ContentCopy,
                    label = stringResource(R.string.external_link_preview_copy_link),
                    onClick = {
                        view.performConfirmHaptic()
                        onDismiss()
                        onCopyLink(linkUrl)
                    },
                )

                LinkContextItem(
                    icon = Icons.Outlined.Share,
                    label = stringResource(R.string.action_share),
                    onClick = {
                        view.performConfirmHaptic()
                        onDismiss()
                        onShareLink(linkUrl)
                    },
                )

                if (target.canDownloadLink) {
                    LinkContextItem(
                        icon = Icons.Outlined.Download,
                        label = stringResource(R.string.action_download_link),
                        onClick = {
                            view.performConfirmHaptic()
                            onDismiss()
                            onDownloadLink()
                        },
                    )
                }
            }

            // Image Actions
            if (imageUrl != null) {
                LinkContextItem(
                    icon = Icons.Outlined.Image,
                    label = stringResource(R.string.action_download_image),
                    onClick = {
                        view.performConfirmHaptic()
                        onDismiss()
                        onDownloadImage()
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LinkContextItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
