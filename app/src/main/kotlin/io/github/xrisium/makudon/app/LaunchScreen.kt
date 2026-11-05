package io.github.xrisium.makudon.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R

/** Center the entire lockup in the edge-to-edge window, rather than its icon or safe area. */
@Composable
fun LaunchScreen(modifier: Modifier = Modifier) {
    val brand = stringResource(R.string.brand_name)
    val loading = stringResource(R.string.loading)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Matches the confirmed 412dp preview; shrink together for short landscape windows.
            val scale = minOf(1f, maxWidth.value / 280f, maxHeight.value / 260f)
            Column(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = brand
                    stateDescription = loading
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(30.dp * scale),
            ) {
                Icon(painterResource(R.drawable.makudon_symbol), null,
                    Modifier.width(166.dp * scale).aspectRatio(77f / 52f),
                    tint = MaterialTheme.colorScheme.onBackground)
                Icon(painterResource(R.drawable.makudon_wordmark), null,
                    Modifier.width(190.dp * scale).aspectRatio(258.304f / 47.136f),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}
