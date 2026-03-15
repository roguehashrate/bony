package social.bony

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import social.bony.account.signer.AmberSignerBridge
import social.bony.ui.BonyNavHost
import social.bony.ui.theme.BonyTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var amberBridge: AmberSignerBridge

    private val amberLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        amberBridge.onResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Forward pending Amber sign requests to the system intent launcher
        lifecycleScope.launch {
            amberBridge.pendingRequest.collect { request ->
                request?.let { amberLauncher.launch(it.intent) }
            }
        }

        setContent {
            BonyTheme {
                BonyNavHost()
            }
        }
    }
}
