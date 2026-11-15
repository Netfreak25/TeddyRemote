package de.teddycloud.teddyremote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import de.teddycloud.teddyremote.service.TeddyRemoteService
import de.teddycloud.teddyremote.ui.MainViewModel
import de.teddycloud.teddyremote.ui.TeddyRemoteApp

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as TeddyRemoteApplication).container)
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lifecycle = (application as TeddyRemoteApplication).connectionLifecycle
        lifecycle.opened()
        lifecycleScope.launch {
            lifecycle.closed.collect { closed -> if (closed) finishAndRemoveTask() }
        }
        enableEdgeToEdge()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            TeddyRemoteApp(viewModel = viewModel)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.updateConfirmation.collect { confirmation ->
                    if (confirmation != null) {
                        viewModel.updateConfirmationShown()
                        try { startActivity(confirmation) }
                        catch (_: Exception) { viewModel.updateConfirmationFailed() }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshUpdatePermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as TeddyRemoteApplication).connectionLifecycle.opened()
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        viewModel.focusBox(intent?.getStringExtra(TeddyRemoteService.EXTRA_BOX_ID))
    }
}
