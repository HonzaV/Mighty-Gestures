package cz.mightybities.mightygestures.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import cz.mightybities.mightygestures.R
import cz.mightybities.mightygestures.ui.theme.MightyGesturesTheme
import cz.mightybities.mightygestures.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(HomeScreenTags.TITLE),
            )
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .padding(top = MaterialTheme.spacing.small)
                        .testTag(HomeScreenTags.SUBTITLE),
            )
        }
    }
}

// Pseudo-locales need isPseudoLocalesEnabled in the debug build type: en-XA = long accented text, ar-XB = RTL.
@PreviewLightDark
@PreviewFontScale
@Preview(name = "Long text (en-XA)", locale = "en-rXA")
@Preview(name = "RTL (ar-XB)", locale = "ar-rXB")
@Composable
private fun HomeScreenPreview() {
    MightyGesturesTheme {
        HomeScreen()
    }
}

object HomeScreenTags {
    const val TITLE = "home_title"
    const val SUBTITLE = "home_subtitle"
}
