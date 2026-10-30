package com.shivtej.owner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Download tray with an arrow going into it (the chosen icon), drawn directly so it works on every version. */
@Composable
fun DownloadTrayGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val k = size.minDimension / 24f
        fun pt(x: Float, y: Float) = Offset(x * k, y * k)
        val w = 2.2f * k
        drawLine(color, pt(12f, 4f), pt(12f, 14f), strokeWidth = w, cap = StrokeCap.Round)
        drawPath(Path().apply { moveTo(8f * k, 10.5f * k); lineTo(12f * k, 14.5f * k); lineTo(16f * k, 10.5f * k) },
            color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(Path().apply { moveTo(4f * k, 14f * k); lineTo(4f * k, 19f * k); lineTo(20f * k, 19f * k); lineTo(20f * k, 14f * k) },
            color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Dashboard button: a green tick when every product photo is on the phone; otherwise the download button
 * with the number missing. Pressing it shows the state and downloads the rest.
 */
@Composable
fun PhotoDownloadButton(app: App) {
    val ctx = LocalContext.current
    val st by PhotoCache.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val allSaved = st.total == 0 || st.missing == 0

    IconButton(onClick = { open = true }) {
        if (allSaved) {
            Icon(Icons.Filled.CheckCircle, "सभी फ़ोटो सेव हैं", tint = Color(0xFF66BB6A))
        } else {
            Box(contentAlignment = Alignment.TopEnd) {
                DownloadTrayGlyph(Modifier.size(24.dp))
                Text(if (st.missing > 99) "99+" else "${st.missing}", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFFC62828),
                    modifier = Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(horizontal = 3.dp))
            }
        }
    }

    if (open) AlertDialog(
        onDismissRequest = { if (!st.downloading) open = false },
        title = { Text(if (allSaved) "सभी फ़ोटो फ़ोन में सेव हैं" else "${st.missing} फ़ोटो फ़ोन में नहीं हैं") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    st.downloading -> {
                        LinearProgressIndicator(
                            progress = if (st.total == 0) 0f else st.saved.toFloat() / st.total,
                            modifier = Modifier.fillMaxWidth())
                        Text("डाउनलोड हो रहा है ${st.saved} / ${st.total}")
                    }
                    allSaved -> Text("${st.saved} फ़ोटो सेव हैं। नई फ़ोटो अपने-आप आती रहेंगी।")
                    else -> Text("${st.saved} फ़ोटो सेव हैं, बाकी ${st.missing} डाउनलोड करें।")
                }
            }
        },
        confirmButton = {
            if (!allSaved && !st.downloading) {
                TextButton(onClick = {
                    app.scope.launch { PhotoCache.syncAll(ctx, app.repo.products.first()) }
                }) { Text("सारी फ़ोटो डाउनलोड करें") }
            } else {
                TextButton(onClick = { open = false }) { Text("बंद करें") }
            }
        }
    )
}
