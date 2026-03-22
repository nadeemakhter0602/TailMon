// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.AppTheme

@Composable
fun IntroView(onContinue: () -> Unit) {
  Column(
      modifier = Modifier.fillMaxHeight().fillMaxWidth().verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier =
                Modifier.size(72.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(Color(0xFF1F1E1E)))
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            modifier = Modifier.padding(start = 40.dp, end = 40.dp, bottom = 40.dp),
            text = stringResource(R.string.welcome1),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center)

        Button(onClick = onContinue) {
          Text(
              text = stringResource(id = R.string.getStarted),
              fontSize = MaterialTheme.typography.titleMedium.fontSize)
        }
        Spacer(modifier = Modifier.height(40.dp))

        Box(
            modifier = Modifier.fillMaxHeight().padding(start = 20.dp, end = 20.dp, bottom = 40.dp),
            contentAlignment = Alignment.BottomCenter) {
              Text(
                  text = stringResource(R.string.welcome2),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center)
            }
      }
}

@Composable
@Preview
fun IntroViewPreview() {
  AppTheme { Surface { IntroView({}) } }
}
