/** Hosts the Shahbaz Compose application and bridges Android settings and permission contracts. */
package ir.hrka.shahbaz

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.hrka.shahbaz.core.designsystem.ShahbazTheme
import ir.hrka.shahbaz.feature.dashboard.DashboardPhoneSensors
import ir.hrka.shahbaz.feature.dashboard.DashboardScreen
import ir.hrka.shahbaz.feature.dashboard.DashboardViewModel
import ir.hrka.shahbaz.feature.dashboard.PhoneReading
import ir.hrka.compass.CompassFailureCode
import ir.hrka.compass.CompassUnavailableReason
import ir.hrka.shahbaz.feature.map.CompassSensorStatus
import ir.hrka.shahbaz.feature.map.LocationStatus
import ir.hrka.shahbaz.feature.map.MapScreen
import ir.hrka.shahbaz.feature.map.MapUiState
import ir.hrka.shahbaz.feature.map.MapViewModel

/** Thin application-shell activity that renders the map feature and owns system navigation. */
class MainActivity : ComponentActivity() {
    /** Activity-scoped state holder for the map feature. */
    private val mapViewModel: MapViewModel by viewModels()

    /** Activity-scoped state holder for board health, telemetry, and dashboard-only motion. */
    private val dashboardViewModel: DashboardViewModel by viewModels()

    /** Tracks whether Activity lifecycle currently permits dashboard sensor acquisition. */
    private var hostStarted = false

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
                val mapState by mapViewModel.uiState.collectAsStateWithLifecycle()
                val dashboardState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
                val flightPlan = mapState.confirmedFlightPlan

                LaunchedEffect(mapState) {
                    dashboardViewModel.updatePhoneSensors(
                        sensors = mapState.toDashboardPhoneSensors(),
                        isOnline = mapState.isOnline,
                    )
                }
                LaunchedEffect(flightPlan) {
                    if (flightPlan == null) {
                        dashboardViewModel.clearFlightPlan()
                    } else {
                        dashboardViewModel.setFlightPlan(flightPlan)
                        if (hostStarted) dashboardViewModel.onHostForeground()
                    }
                }

                BackHandler(enabled = flightPlan != null) {
                    dashboardViewModel.clearFlightPlan()
                    mapViewModel.clearConfirmedFlightPlan()
                }

                if (flightPlan == null) {
                    MapScreen(
                        state = mapState,
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
                } else {
                    DashboardScreen(
                        state = dashboardState,
                        onRequestUsbPermission = dashboardViewModel::requestUsbPermission,
                        onRetryBoardConnection = dashboardViewModel::retryBoardConnection,
                    )
                }
            }
        }
    }

    /** Notifies the map feature that foreground-only work may start. */
    override fun onStart() {
        super.onStart()
        hostStarted = true
        mapViewModel.onForeground()
        if (mapViewModel.uiState.value.confirmedFlightPlan != null) {
            dashboardViewModel.onHostForeground()
        }
    }

    /** Stops foreground-only feature work before the activity leaves the visible lifecycle. */
    override fun onStop() {
        hostStarted = false
        dashboardViewModel.onHostBackground()
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

/** Maps the existing phone-only sensor pipeline into dashboard state with explicit provenance. */
internal fun MapUiState.toDashboardPhoneSensors(): DashboardPhoneSensors {
    val position = when (locationStatus) {
        LocationStatus.READY -> origin?.coordinate?.let { PhoneReading.Available(it) }
            ?: PhoneReading.AwaitingFirstSample
        LocationStatus.LOCATING -> PhoneReading.AwaitingFirstSample
        LocationStatus.PERMISSION_REQUIRED -> PhoneReading.Unavailable("Location permission required")
        LocationStatus.PRECISE_PERMISSION_REQUIRED ->
            PhoneReading.Unavailable("Precise location permission required")
        LocationStatus.LOCATION_DISABLED -> PhoneReading.Unavailable("Phone location is disabled")
        LocationStatus.UNAVAILABLE -> PhoneReading.Unavailable("Phone location is unavailable")
    }
    val orientation = when (val value = compassStatus) {
        CompassSensorStatus.Inactive -> PhoneReading.Inactive
        CompassSensorStatus.Starting -> PhoneReading.AwaitingFirstSample
        is CompassSensorStatus.AwaitingFirstSample -> PhoneReading.AwaitingFirstSample
        is CompassSensorStatus.Active -> compassReading?.let { PhoneReading.Available(it) }
            ?: PhoneReading.AwaitingFirstSample
        is CompassSensorStatus.NoResponse -> PhoneReading.NoResponse(
            lastValue = compassReading,
            reason = "Phone compass did not respond",
        )
        is CompassSensorStatus.Stale -> PhoneReading.Stale(compassReading)
        is CompassSensorStatus.Unavailable -> when (value.reason) {
            CompassUnavailableReason.NO_SUPPORTED_SENSOR -> PhoneReading.NotPresent(
                "No supported phone orientation sensor is present"
            )
            CompassUnavailableReason.SENSOR_SERVICE_UNAVAILABLE -> PhoneReading.Unavailable(
                "Android sensor service is unavailable"
            )
        }
        is CompassSensorStatus.Failed -> when (value.failure.code) {
            CompassFailureCode.SENSOR_UNAVAILABLE -> PhoneReading.NotPresent(
                "Phone orientation sensor is unavailable"
            )
            CompassFailureCode.REGISTRATION_FAILED -> PhoneReading.Failed(
                "Phone orientation sensor registration failed"
            )
            CompassFailureCode.INVALID_SENSOR_DATA -> PhoneReading.Invalid(
                lastValue = compassReading,
                reason = "Phone orientation sensor returned invalid data",
            )
        }
    }
    return DashboardPhoneSensors(position = position, orientation = orientation)
}
