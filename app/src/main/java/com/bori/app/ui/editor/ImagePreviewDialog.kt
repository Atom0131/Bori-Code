package com.bori.app.ui.editor

import com.bori.theme.util.BoriAlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shows a picture from the project (one the user added for the agents) instead of opening it as text. */
@Composable
fun ImagePreviewDialog(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val file = remember(path) { File(path) }
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { decodeSampled(file, MAX_SIDE) }
        failed = bitmap == null
    }

    BoriAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                    val image = bitmap
                    when {
                        image != null -> Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = file.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                        )
                        failed -> Text("Can't show this picture")
                        else -> CircularProgressIndicator()
                    }
                }
                Text(
                    Formatter.formatShortFileSize(context, file.length()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private const val MAX_SIDE = 2048

/** Decodes [file] at a power-of-two scale so neither side is much over [maxSide]; null if it isn't a picture. */
private fun decodeSampled(file: File, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
