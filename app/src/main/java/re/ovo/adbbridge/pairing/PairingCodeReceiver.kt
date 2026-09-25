package re.ovo.adbbridge.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.RemoteInput

class PairingCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingService.KEY_CODE)
            ?.toString()
            ?: return

        val service = Intent(context, PairingService::class.java).putExtra(PairingService.EXTRA_CODE, code)
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
