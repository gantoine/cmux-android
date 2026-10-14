package com.gantoine.cmux.ui

import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.gantoine.cmux.PairLink

@Composable
fun QrScanner(onDismiss: () -> Unit, onScanned: (LaunchTarget.Pair) -> Unit) {
    BackHandler(onBack = onDismiss)
    var rejected by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    val currentOnScanned by rememberUpdatedState(onScanned)
    Box(Modifier.fillMaxSize()) {
        CameraPreview(onCode = { text ->
            val link = PairLink.parse(text)
            when {
                scanned -> Unit
                link == null -> rejected = true
                else -> {
                    scanned = true
                    currentOnScanned(link)
                }
            }
        })
        Box(
            Modifier
                .align(Alignment.Center)
                .size(260.dp)
                .border(3.dp, Color.White, RoundedCornerShape(28.dp)),
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            IconButton(onClick = onDismiss, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
        Text(
            if (rejected) "That isn't a cmux pairing code." else "Point at the QR code from cmux-relay pair",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 48.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val controller = remember { LifecycleCameraController(context) }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    DisposableEffect(lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(scanner), CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED, executor) { result ->
                result.getValue(scanner)?.firstNotNullOfOrNull { it.rawValue }?.let(currentOnCode)
            },
        )
        controller.initializationFuture.addListener({
            if (!controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) && controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                controller.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            }
        }, executor)
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            scanner.close()
        }
    }
    AndroidView(
        factory = { PreviewView(it).apply { this.controller = controller } },
        modifier = Modifier.fillMaxSize(),
    )
}
