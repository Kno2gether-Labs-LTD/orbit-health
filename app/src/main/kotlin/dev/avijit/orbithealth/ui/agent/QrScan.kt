package dev.avijit.orbithealth.ui.agent

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** What the scanner shows under its viewfinder. */
const val ScanPrompt = "Scan the pairing code on your Orbit Server"

/**
 * Returns a "scan" action: asks for the camera only now (the first time the user taps Scan), then opens the ZXing
 * scanner for one QR code. [onResult] gets the code's text, or null when the scan was cancelled; [onCameraDenied]
 * runs when the user refuses the camera. Nothing is decoded off the phone.
 */
@Composable
fun rememberQrScanner(onResult: (String?) -> Unit, onCameraDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val denied by rememberUpdatedState(onCameraDenied)
    val options = remember {
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(ScanPrompt)
            .setBeepEnabled(false)
            .setOrientationLocked(false)
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> result(r.contents) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanner.launch(options) else denied()
    }
    return remember(context) {
        {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                scanner.launch(options)
            } else {
                permission.launch(Manifest.permission.CAMERA)
            }
        }
    }
}
