/** Hosts the Shahbaz Compose application and bridges Android settings and permission contracts. */
package ir.hrka.shahbaz

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shahbaz.flightblackbox.FbbEventRef
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FlightBlackBox
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
import ir.hrka.shahbaz.feature.reportdetails.ReportDetailsScreen
import ir.hrka.shahbaz.feature.reports.ReportsScreen
import ir.hrka.shahbaz.feature.settings.SettingsScreen

/** Thin application-shell activity that renders the map feature and owns system navigation. */
class MainActivity : ComponentActivity() {
    /** Activity-scoped state holder for the map feature. */
    private val mapViewModel: MapViewModel by viewModels()

    /** Activity-scoped state holder for board health, telemetry, and dashboard-only motion. */
    private val dashboardViewModel: DashboardViewModel by viewModels()

    /** Tracks whether Activity lifecycle currently permits dashboard sensor acquisition. */
    private var hostStarted = false

    /** Root lifecycle event for app-shell causal chains owned by this Activity instance. */
    private var activityCreateEvent: FbbEventRef? = null

    /** Most recent Android location permission request event awaiting a result callback. */
    private var locationPermissionRequestEvent: FbbEventRef? = null

    /** Runtime-permission contract requesting coarse and precise location together. */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val result = FlightBlackBox.record(
            type = FbbEventType.SYSTEM,
            description = "Android location permission result received",
            cause = locationPermissionRequestEvent,
            metadata = mapOf(
                "fineGranted" to (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true),
                "coarseGranted" to (grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true),
            ),
            persistence = FbbPersistence.IMPORTANT,
        )
        val call = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "MapViewModel.onPermissionResult()",
            cause = result,
        )
        mapViewModel.onPermissionResult()
        FlightBlackBox.record(
            type = FbbEventType.RETURN,
            description = "MapViewModel.onPermissionResult() completed",
            cause = call,
        )
    }

    /**
     * Creates the edge-to-edge Compose hierarchy and connects feature callbacks to Android APIs.
     *
     * @param savedInstanceState previously saved activity state when the system recreates it.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        activityCreateEvent = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "MainActivity.onCreate()",
            cause = FlightBlackBox.processStartEvent(),
            metadata = mapOf("hasSavedState" to (savedInstanceState != null)),
            persistence = FbbPersistence.IMPORTANT,
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShahbazTheme {
                var appRoute by rememberSaveable { mutableStateOf(AppRoute.FLIGHT.name) }
                var reportDetailsSessionId by rememberSaveable { mutableStateOf<String?>(null) }
                val mapState by mapViewModel.uiState.collectAsStateWithLifecycle()
                val dashboardState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
                val flightPlan = mapState.confirmedFlightPlan

                if (appRoute == AppRoute.FLIGHT.name) {
                    LifecycleStartEffect(mapViewModel) {
                        val foreground = FlightBlackBox.record(
                            type = FbbEventType.CALL,
                            description = "Flight screen started -> MapViewModel.onForeground()",
                            parent = activityCreateEvent,
                        )
                        mapViewModel.onForeground()
                        FlightBlackBox.record(
                            type = FbbEventType.RETURN,
                            description = "MapViewModel.onForeground() completed",
                            cause = foreground,
                        )

                        onStopOrDispose {
                            val background = FlightBlackBox.record(
                                type = FbbEventType.CALL,
                                description = "Flight screen stopped -> MapViewModel.onBackground()",
                                parent = activityCreateEvent,
                            )
                            mapViewModel.onBackground()
                            FlightBlackBox.record(
                                type = FbbEventType.RETURN,
                                description = "MapViewModel.onBackground() completed",
                                cause = background,
                            )
                        }
                    }
                }

                fun openSettings(mapLifecycleOwner: NavigationLifecycleOwner) {
                    val request = FlightBlackBox.record(
                        type = FbbEventType.USER,
                        description = "App shell Settings button clicked",
                        parent = activityCreateEvent,
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    FlightBlackBox.record(
                        type = FbbEventType.LIFECYCLE,
                        description = "MapScreen lifecycle destroyed before SettingsScreen",
                        cause = request,
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    mapLifecycleOwner.destroy()
                    appRoute = AppRoute.SETTINGS.name
                    FlightBlackBox.record(
                        type = FbbEventType.NAV,
                        description = "Flight shell -> SettingsScreen",
                        cause = request,
                    )
                }

                fun closeSettings() {
                    val request = FlightBlackBox.record(
                        type = FbbEventType.USER,
                        description = "SettingsScreen back requested",
                        parent = activityCreateEvent,
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    appRoute = AppRoute.FLIGHT.name
                    FlightBlackBox.record(
                        type = FbbEventType.NAV,
                        description = "SettingsScreen -> Flight shell",
                        cause = request,
                    )
                }

                fun openReports() {
                    val request = FlightBlackBox.record(
                        type = FbbEventType.USER,
                        description = "SettingsScreen.ManageReports clicked",
                        parent = activityCreateEvent,
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    appRoute = AppRoute.REPORTS.name
                    FlightBlackBox.record(
                        type = FbbEventType.NAV,
                        description = "SettingsScreen -> ReportsScreen",
                        cause = request,
                    )
                }

                fun closeReports() {
                    val request = FlightBlackBox.record(
                        type = FbbEventType.USER,
                        description = "ReportsScreen back requested",
                        parent = activityCreateEvent,
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    reportDetailsSessionId = null
                    appRoute = AppRoute.SETTINGS.name
                    FlightBlackBox.record(
                        type = FbbEventType.NAV,
                        description = "ReportsScreen -> SettingsScreen",
                        cause = request,
                    )
                }

                fun openReportDetails(sessionId: String) {
                    val request = FlightBlackBox.record(
                        type = FbbEventType.USER,
                        description = "ReportsScreen report item opened",
                        parent = activityCreateEvent,
                        metadata = mapOf("sessionId" to sessionId),
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    reportDetailsSessionId = sessionId
                    appRoute = AppRoute.REPORT_DETAILS.name
                    FlightBlackBox.record(
                        type = FbbEventType.NAV,
                        description = "ReportsScreen -> ReportDetailsScreen",
                        cause = request,
                    )
                }

                fun closeReportDetails() {
                    val sessionId = reportDetailsSessionId
                    val request = FlightBlackBox.record(
                        type = FbbEventType.USER,
                        description = "ReportDetailsScreen back requested",
                        parent = activityCreateEvent,
                        metadata = mapOf("sessionId" to sessionId),
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    reportDetailsSessionId = null
                    appRoute = AppRoute.REPORTS.name
                    FlightBlackBox.record(
                        type = FbbEventType.NAV,
                        description = "ReportDetailsScreen -> ReportsScreen",
                        cause = request,
                    )
                }

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
                        val dashboardNav = FlightBlackBox.record(
                            type = FbbEventType.NAV,
                            description = "MapScreen -> DashboardScreen",
                            parent = activityCreateEvent,
                            metadata = mapOf("appRoute" to appRoute),
                            persistence = FbbPersistence.IMPORTANT,
                        )
                        dashboardViewModel.setFlightPlan(flightPlan, cause = dashboardNav)
                        if (hostStarted) dashboardViewModel.onHostForeground()
                    }
                }

                BackHandler(enabled = appRoute == AppRoute.SETTINGS.name) {
                    closeSettings()
                }

                Box(Modifier.fillMaxSize()) {
                    if (appRoute == AppRoute.SETTINGS.name) {
                        SettingsScreen(
                            onBack = ::closeSettings,
                            onOpenReports = ::openReports,
                        )
                    } else if (appRoute == AppRoute.REPORTS.name) {
                        ReportsScreen(
                            onBack = ::closeReports,
                            onOpenReportDetails = ::openReportDetails,
                        )
                    } else if (appRoute == AppRoute.REPORT_DETAILS.name && reportDetailsSessionId != null) {
                        ReportDetailsScreen(
                            sessionId = requireNotNull(reportDetailsSessionId),
                            onBack = ::closeReportDetails,
                        )
                    } else {
                        if (flightPlan == null) {
                            val mapLifecycleOwner = rememberNavigationLifecycleOwner()
                            CompositionLocalProvider(
                                LocalLifecycleOwner provides mapLifecycleOwner,
                            ) {
                                MapScreen(
                                    state = mapState,
                                    onDestinationSelected = mapViewModel::setDestination,
                                    onClearDestination = mapViewModel::clearDestination,
                                    onAdvanceToTakeoffAltitude = mapViewModel::advanceToTakeoffAltitude,
                                    onReturnToDestinationSelection =
                                        mapViewModel::returnToDestinationSelection,
                                    onTakeoffAltitudeChanged = mapViewModel::updateTakeoffAltitude,
                                    onConfirmTakeoffAltitude = {
                                        mapViewModel.confirmTakeoffAltitude()
                                        if (mapViewModel.uiState.value.confirmedFlightPlan != null) {
                                            FlightBlackBox.record(
                                                type = FbbEventType.LIFECYCLE,
                                                description = "MapScreen lifecycle destroyed before DashboardScreen",
                                                parent = activityCreateEvent,
                                                persistence = FbbPersistence.IMPORTANT,
                                            )
                                            mapLifecycleOwner.destroy()
                                        }
                                    },
                                    onRequestPermission = ::requestLocationPermission,
                                    onOpenAppSettings = ::openAppSettings,
                                    onOpenLocationSettings = ::openLocationSettings,
                                    onRetryLocation = mapViewModel::retryLocation,
                                )
                            }
                            FloatingActionButton(
                                onClick = { openSettings(mapLifecycleOwner) },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .systemBarsPadding()
                                    .padding(end = 16.dp, bottom = 88.dp),
                            ) {
                                Icon(Icons.Rounded.Settings, contentDescription = "Open settings")
                            }
                        } else {
                            val dashboardLifecycleOwner = rememberNavigationLifecycleOwner()
                            BackHandler {
                                val back = FlightBlackBox.record(
                                    type = FbbEventType.USER,
                                    description = "System back pressed on DashboardScreen",
                                    parent = activityCreateEvent,
                                    persistence = FbbPersistence.IMPORTANT,
                                )
                                FlightBlackBox.record(
                                    type = FbbEventType.LIFECYCLE,
                                    description = "DashboardScreen lifecycle destroyed before MapScreen",
                                    cause = back,
                                    persistence = FbbPersistence.IMPORTANT,
                                )
                                dashboardLifecycleOwner.destroy()
                                dashboardViewModel.clearFlightPlan()
                                mapViewModel.clearConfirmedFlightPlan()
                                FlightBlackBox.record(
                                    type = FbbEventType.NAV,
                                    description = "DashboardScreen -> MapScreen",
                                    cause = back,
                                )
                            }
                            CompositionLocalProvider(
                                LocalLifecycleOwner provides dashboardLifecycleOwner,
                            ) {
                                DashboardScreen(
                                    state = dashboardState,
                                    onRequestUsbPermission = dashboardViewModel::requestUsbPermission,
                                    onRetryBoardConnection = dashboardViewModel::retryBoardConnection,
                                )
                            }
                        }
                    }
                }
            }
        }
        FlightBlackBox.record(
            type = FbbEventType.UI,
            description = "MainActivity Compose content installed",
            cause = activityCreateEvent,
        )
    }

    /** Notifies dashboard hardware work that the host may start. */
    override fun onStart() {
        val start = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "MainActivity.onStart()",
            cause = activityCreateEvent,
            persistence = FbbPersistence.IMPORTANT,
        )
        super.onStart()
        hostStarted = true
        FlightBlackBox.record(
            type = FbbEventType.STATE,
            description = "hostStarted: false -> true",
            cause = start,
        )
        if (mapViewModel.uiState.value.confirmedFlightPlan != null) {
            val decision = FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "confirmedFlightPlan present -> dashboard host foreground",
                cause = start,
            )
            val dashboardForeground = FlightBlackBox.record(
                type = FbbEventType.CALL,
                description = "DashboardViewModel.onHostForeground()",
                cause = decision,
            )
            dashboardViewModel.onHostForeground()
            FlightBlackBox.record(
                type = FbbEventType.RETURN,
                description = "DashboardViewModel.onHostForeground() completed",
                cause = dashboardForeground,
            )
        }
    }

    /** Reconciles a USB grant after Android's permission activity returns to this host. */
    override fun onResume() {
        val resume = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "MainActivity.onResume()",
            cause = activityCreateEvent,
        )
        super.onResume()
        if (mapViewModel.uiState.value.confirmedFlightPlan != null) {
            val decision = FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "confirmedFlightPlan present -> refresh dashboard host",
                cause = resume,
            )
            val dashboardResume = FlightBlackBox.record(
                type = FbbEventType.CALL,
                description = "DashboardViewModel.onHostResume()",
                cause = decision,
            )
            dashboardViewModel.onHostResume()
            FlightBlackBox.record(
                type = FbbEventType.RETURN,
                description = "DashboardViewModel.onHostResume() completed",
                cause = dashboardResume,
            )
        }
    }

    /** Stops foreground-only feature work before the activity leaves the visible lifecycle. */
    override fun onStop() {
        val stop = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "MainActivity.onStop()",
            cause = activityCreateEvent,
            persistence = FbbPersistence.IMPORTANT,
        )
        hostStarted = false
        FlightBlackBox.record(
            type = FbbEventType.STATE,
            description = "hostStarted: true -> false",
            cause = stop,
        )
        val dashboardBackground = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "DashboardViewModel.onHostBackground()",
            cause = stop,
        )
        dashboardViewModel.onHostBackground()
        FlightBlackBox.record(
            type = FbbEventType.RETURN,
            description = "DashboardViewModel.onHostBackground() completed",
            cause = dashboardBackground,
        )
        super.onStop()
    }

    /** Launches the runtime request for coarse and precise location permissions. */
    private fun requestLocationPermission() {
        val request = FlightBlackBox.record(
            type = FbbEventType.USER,
            description = "MapScreen.LocationPermission.Request clicked",
            persistence = FbbPersistence.IMPORTANT,
        )
        locationPermissionRequestEvent = request
        FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "ActivityResultLauncher.launch(location permissions)",
            cause = request,
        )
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    /** Opens this application's system settings page for permission correction. */
    private fun openAppSettings() {
        val request = FlightBlackBox.record(
            type = FbbEventType.USER,
            description = "MapScreen.OpenAppSettings clicked",
            persistence = FbbPersistence.IMPORTANT,
        )
        startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        )
        FlightBlackBox.record(
            type = FbbEventType.SYSTEM,
            description = "Android app details settings opened",
            cause = request,
            metadata = mapOf("action" to AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS),
            persistence = FbbPersistence.IMPORTANT,
        )
    }

    /** Opens device location settings so the user can enable location services. */
    private fun openLocationSettings() {
        val request = FlightBlackBox.record(
            type = FbbEventType.USER,
            description = "MapScreen.OpenLocationSettings clicked",
            persistence = FbbPersistence.IMPORTANT,
        )
        startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS))
        FlightBlackBox.record(
            type = FbbEventType.SYSTEM,
            description = "Android location settings opened",
            cause = request,
            metadata = mapOf("action" to AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS),
            persistence = FbbPersistence.IMPORTANT,
        )
    }
}

/**
 * Documents the AppRoute type and the role it plays in this module.
 */
private enum class AppRoute {
    FLIGHT,
    SETTINGS,
    REPORTS,
    REPORT_DETAILS,
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
