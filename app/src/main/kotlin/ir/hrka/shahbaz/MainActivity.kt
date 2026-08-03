/** Hosts the Shahbaz Compose application and bridges Android settings and permission contracts. */
package ir.hrka.shahbaz

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.hrka.shahbaz.core.designsystem.ShahbazTheme
import ir.hrka.shahbaz.feature.map.MapScreen
import ir.hrka.shahbaz.feature.map.MapViewModel

/** Thin application-shell activity that renders the map feature and owns system navigation. */
class MainActivity : ComponentActivity() {
    /** Activity-scoped state holder for the map feature. */
    private val mapViewModel: MapViewModel by viewModels()

    /** Runtime-permission contract requesting coarse and precise location together. */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        mapViewModel.onPermissionResult()
    }

    /**
     * Creates the edge-to-edge Compose hierarchy and connects feature callbacks to Android APIs.
     *
     * @param savedInstanceState previously saved activity state when the system recreates it.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShahbazTheme {
                val state by mapViewModel.uiState.collectAsStateWithLifecycle()
                MapScreen(
                    state = state,
                    onDestinationSelected = mapViewModel::setDestination,
                    onClearDestination = mapViewModel::clearDestination,
                    onAdvanceToTakeoffAltitude = mapViewModel::advanceToTakeoffAltitude,
                    onReturnToDestinationSelection = mapViewModel::returnToDestinationSelection,
                    onTakeoffAltitudeChanged = mapViewModel::updateTakeoffAltitude,
                    onConfirmTakeoffAltitude = mapViewModel::confirmTakeoffAltitude,
                    onRequestPermission = ::requestLocationPermission,
                    onOpenAppSettings = ::openAppSettings,
                    onOpenLocationSettings = ::openLocationSettings,
                    onRetryLocation = mapViewModel::retryLocation,
                )
            }
        }
    }

    /** Notifies the map feature that foreground-only work may start. */
    override fun onStart() {
        super.onStart()
        mapViewModel.onForeground()
    }

    /** Stops foreground-only feature work before the activity leaves the visible lifecycle. */
    override fun onStop() {
        mapViewModel.onBackground()
        super.onStop()
    }

    /** Launches the runtime request for coarse and precise location permissions. */
    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    /** Opens this application's system settings page for permission correction. */
    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        )
    }

    /** Opens device location settings so the user can enable location services. */
    private fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }
}
