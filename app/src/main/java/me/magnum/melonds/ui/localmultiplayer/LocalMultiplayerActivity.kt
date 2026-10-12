package me.magnum.melonds.ui.localmultiplayer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import me.magnum.melonds.ui.localmultiplayer.ui.LocalMultiplayerScreen
import me.magnum.melonds.ui.theme.MelonTheme

@AndroidEntryPoint
class LocalMultiplayerActivity : AppCompatActivity() {

    private val viewModel by viewModels<LocalMultiplayerViewModel>()
    private val permissionsGranted = mutableStateOf(false)

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionsGranted.value = result.values.all { it }
        if (permissionsGranted.value) {
            viewModel.onStart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        setContent {
            MelonTheme {
                LocalMultiplayerScreen(
                    viewModel = viewModel,
                    permissionsGranted = permissionsGranted.value,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                    onBackClick = { onSupportNavigateUp() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        permissionsGranted.value = requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (permissionsGranted.value) {
            viewModel.onStart()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onStop()
    }
}
