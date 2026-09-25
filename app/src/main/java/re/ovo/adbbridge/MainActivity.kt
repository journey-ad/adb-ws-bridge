package re.ovo.adbbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import re.ovo.adbbridge.bridge.BridgeService
import re.ovo.adbbridge.ui.AppContent

class MainActivity : ComponentActivity() {

    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleAction(intent)
        setContent {
            AppContent()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    private fun handleAction(intent: Intent?) {
        when (intent?.action) {
            BridgeService.ACTION_START -> startForegroundService(
                Intent(this, BridgeService::class.java)
            )

            BridgeService.ACTION_STOP -> startService(
                Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_STOP)
            )
        }
    }
}
