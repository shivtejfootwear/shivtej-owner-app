@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.staff

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Returns a function that runs an action once the CAMERA permission is granted. */
@Composable
fun rememberWithCamera(): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pending?.invoke()
        else android.widget.Toast.makeText(ctx, "Scan के लिए कैमरा की अनुमति चाहिए", android.widget.Toast.LENGTH_SHORT).show()
        pending = null
    }
    return { action ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) action()
        else { pending = action; launcher.launch(Manifest.permission.CAMERA) }
    }
}

/** Full-screen live camera scanner (CameraX + ML Kit). Calls onResult once with the decoded text. */
@Composable
fun ScannerDialog(title: String = "बारकोड स्कैन करें", onResult: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                CameraPreview(onResult)
                // aiming frame
                Box(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.72f).aspectRatio(1.4f)
                        .clip(RoundedCornerShape(16.dp)).border(2.dp, Color.White, RoundedCornerShape(16.dp))
                )
                Row(
                    Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Text(
                    "प्रोडक्ट के बारकोड पर कैमरा रखें",
                    color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
                )
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val handled = remember { AtomicBoolean(false) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_QR_CODE, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF, Barcode.FORMAT_CODABAR
            ).build()
        )
    }
    val latestOnCode by rememberUpdatedState(onCode)

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        val main = Handler(Looper.getMainLooper())
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || handled.get()) {
                    proxy.close()
                } else {
                    val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(img)
                        .addOnSuccessListener { list ->
                            val v = list.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                            if (v != null && handled.compareAndSet(false, true)) main.post { latestOnCode(v) }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            try { future.get().unbindAll() } catch (_: Exception) { }
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
