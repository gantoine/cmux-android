package com.gantoine.muxer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun DictateButton(dictating: Boolean, onClick: () -> Unit) {
    if (dictating) {
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(Icons.Filled.Mic, contentDescription = "Listening")
        }
    } else {
        IconButton(onClick = onClick) { Icon(Icons.Filled.Mic, contentDescription = "Dictate a message") }
    }
}
