package com.a11yland.hissi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.a11yland.hissi.R

// accessibility.cloud requires visible attribution of its data — and the
// platform is Sozialhelden's, so the footer names them too. One Text with
// two links so the line wraps as a whole on narrow widths.
@Composable
fun AttributionFooter(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.data_by))
                append(" ")
                withLink(
                    LinkAnnotation.Url(url = AttributionLinks.ACCESSIBILITY_CLOUD, styles = linkStyles) {
                        uriHandler.openUri(AttributionLinks.ACCESSIBILITY_CLOUD)
                    },
                ) {
                    append("accessibility.cloud")
                }
                append(stringResource(R.string.data_project_of))
                append(" ")
                withLink(
                    LinkAnnotation.Url(url = AttributionLinks.SOZIALHELDEN, styles = linkStyles) {
                        uriHandler.openUri(AttributionLinks.SOZIALHELDEN)
                    },
                ) {
                    append("Sozialhelden e.V.")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
