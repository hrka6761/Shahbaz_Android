/**
 * Coordinates Android location, compass, connectivity, reverse-geocoding, and flight-setup state
 * for the Shahbaz map feature.
 *
 * The ViewModel in this file converts platform callbacks into the immutable [MapUiState] observed
 * by the map UI and releases every registered callback when the feature leaves the foreground or
 * the ViewModel is cleared.
 */
package ir.hrka.shahbaz.feature.map

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import ir.hrka.shahbaz.core.domain.haversineDistanceMeters
import ir.hrka.shahbaz.core.location.HeadingProvider
import ir.hrka.shahbaz.core.model.GeoCoordinate
import ir.hrka.shahbaz.feature.map.impl.R
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Owns map-feature state and coordinates the Android services that supply that state.
 *
 * Call [onForeground] and [onBackground] from the hosting activity's corresponding lifecycle
 * callbacks. Permission results are forwarded through [onPermissionResult], while destination
 * and takeoff-altitude actions are handled by [setDestination], [clearDestination],
 * [advanceToTakeoffAltitude], [returnToDestinationSelection], [updateTakeoffAltitude], and
 * [confirmTakeoffAltitude]. Consumers observe [uiState].
 *
 * @param application Application used to obtain process-scoped Android services and resources.
 */
class MapViewModel(application: Application) : AndroidViewModel(application) {
    /** Process-scoped context used to avoid retaining an activity instance. */
    private val appContext = application.applicationContext

    /** Google Play services client that supplies precise device location updates. */
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    /** Android service used to observe whether device location providers are enabled. */
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** Android service used to determine validated network connectivity. */
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Locale-aware Android geocoder used to derive optional place names from coordinates. */
    private val geocoder = Geocoder(appContext, Locale.getDefault())

    /** Rotation-vector-backed provider that emits display-corrected compass headings. */
    private val headingProvider = HeadingProvider(appContext)

    /** Mutable backing flow for the read-only state exposed through [uiState]. */
    private val _uiState = MutableStateFlow(
        MapUiState(
            locationStatus = if (hasLocationPermission()) {
                if (hasFineLocationPermission()) {
                    LocationStatus.LOCATING
                } else {
                    LocationStatus.PRECISE_PERMISSION_REQUIRED
                }
            } else {
                LocationStatus.PERMISSION_REQUIRED
            },
            isOnline = hasValidatedInternet(),
            hasPrecisePermission = hasFineLocationPermission(),
        )
    )

    /** Immutable stream of state consumed by the map UI. */
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /** Whether the host is currently in the foreground and may consume platform updates. */
    private var isForeground = false

    /** Whether continuous fused-location updates are currently registered. */
    private var locationUpdatesStarted = false

    /** Whether [networkCallback] was successfully registered. */
    private var networkCallbackRegistered = false

    /** Whether [locationStateReceiver] was successfully registered. */
    private var locationReceiverRegistered = false

    /** Cancellation source for the outstanding one-shot current-location request. */
    private var currentLocationCancellation: CancellationTokenSource? = null

    /** Job that marks an unresolved initial location request as unavailable. */
    private var locationTimeoutJob: Job? = null

    /** Job that invalidates an origin after location availability remains stale. */
    private var locationStaleJob: Job? = null

    /** Active reverse-geocoding job for the origin point. */
    private var originGeocodeJob: Job? = null

    /** Coordinate against which the active origin reverse-geocoding job was started. */
    private var originGeocodeAnchor: GeoCoordinate? = null

    /** Active reverse-geocoding job for the selected destination. */
    private var destinationGeocodeJob: Job? = null

    /** Elapsed-realtime timestamp at which the most recent location was accepted. */
    private var lastLocationElapsedRealtimeMillis = 0L

    /** Receives continuous fused-location results and availability changes. */
    private val locationCallback = object : LocationCallback() {
        /**
         * Accepts the latest location contained in a fused-location result.
         *
         * @param result Result delivered by Google Play services.
         */
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::acceptLocation)
        }

        /**
         * Cancels or schedules stale-location handling when provider availability changes.
         *
         * @param availability Current fused-location availability reported by Google Play services.
         */
        override fun onLocationAvailability(availability: LocationAvailability) {
            if (availability.isLocationAvailable) {
                locationStaleJob?.cancel()
            } else {
                scheduleLocationStaleCheck()
            }
        }
    }

    /** Receives system broadcasts when the user changes device location-provider settings. */
    private val locationStateReceiver = object : BroadcastReceiver() {
        /**
         * Re-evaluates permission and provider state after a location settings broadcast.
         *
         * @param context Broadcast delivery context; ignored because [appContext] is retained.
         * @param intent Delivered system intent; its action is already constrained at registration.
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshDeviceState()
        }
    }

    /** Receives active-network changes and refreshes the validated-connectivity flag. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        /**
         * Refreshes connectivity when a network becomes available.
         *
         * @param network Newly available network.
         */
        override fun onAvailable(network: Network) = refreshConnectivity()

        /**
         * Refreshes connectivity when a network is lost.
         *
         * @param network Network that is no longer available.
         */
        override fun onLost(network: Network) = refreshConnectivity()

        /**
         * Refreshes connectivity when validation or internet capabilities change.
         *
         * @param network Network whose capabilities changed.
         * @param networkCapabilities Newly reported capabilities for [network].
         */
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = refreshConnectivity()
    }

    /** Registers the process-scoped connectivity callback released by [onCleared]. */
    init {
        registerNetworkCallback()
    }

    /**
     * Starts foreground-only heading and location work and reconciles current device state.
     *
     * A retained origin older than [MAX_RETAINED_LOCATION_AGE_MILLIS] is discarded before new
     * updates begin so the UI never presents an outdated point as current.
     */
    fun onForeground() {
        isForeground = true
        registerLocationStateReceiver()
        val retainedOriginIsStale = _uiState.value.origin != null &&
            SystemClock.elapsedRealtime() - lastLocationElapsedRealtimeMillis >
            MAX_RETAINED_LOCATION_AGE_MILLIS
        if (retainedOriginIsStale) {
            _uiState.update { it.copy(origin = null, locationStatus = LocationStatus.LOCATING) }
        }
        _uiState.update { it.copy(headingDegrees = null) }
        if (headingProvider.isAvailable) {
            headingProvider.start { heading ->
                _uiState.update { it.copy(headingDegrees = heading) }
            }
        }
        refreshDeviceState()
    }

    /** Stops foreground-only platform work and clears the transient compass heading. */
    fun onBackground() {
        isForeground = false
        stopLocationUpdates()
        headingProvider.stop()
        unregisterLocationStateReceiver()
        _uiState.update { it.copy(headingDegrees = null) }
    }

    /** Re-evaluates feature state after Android returns a location permission result. */
    fun onPermissionResult() {
        _uiState.update { it.copy(hasPrecisePermission = hasFineLocationPermission()) }
        refreshDeviceState()
    }

    /** Restarts location acquisition using the latest permission and provider state. */
    fun retryLocation() {
        stopLocationUpdates()
        refreshDeviceState()
    }

    /**
     * Records a user-selected destination and starts optional reverse geocoding when online.
     *
     * @param coordinate Validated destination coordinate selected on the map or entered manually.
     */
    fun setDestination(coordinate: GeoCoordinate) {
        val fallbackName = appContext.getString(R.string.selected_destination)
        _uiState.update {
            it.copy(
                destination = PlacePoint(coordinate, fallbackName),
                isTakeoffAltitudeConfirmed = false,
            )
        }
        resolveDestinationName(coordinate)
    }

    /** Clears the selected destination and cancels its outstanding reverse-geocoding request. */
    fun clearDestination() {
        destinationGeocodeJob?.cancel()
        _uiState.update {
            it.copy(
                destination = null,
                flightSetupStep = FlightSetupStep.DESTINATION,
                takeoffAltitudeInput = "",
                isTakeoffAltitudeConfirmed = false,
            )
        }
    }

    /** Advances from route selection to takeoff-altitude entry when a destination exists. */
    fun advanceToTakeoffAltitude() {
        _uiState.update { state -> state.advanceToTakeoffAltitude() }
    }

    /** Returns to destination selection without discarding the selected route or altitude draft. */
    fun returnToDestinationSelection() {
        _uiState.update { state -> state.returnToDestinationSelection() }
    }

    /**
     * Stores the latest takeoff-altitude draft and clears any earlier confirmation.
     *
     * @param input User-entered altitude text expressed in meters.
     */
    fun updateTakeoffAltitude(input: String) {
        _uiState.update { state -> state.updateTakeoffAltitude(input) }
    }

    /** Confirms the current valid altitude while the takeoff-altitude step is active. */
    fun confirmTakeoffAltitude() {
        _uiState.update { state -> state.confirmTakeoffAltitude() }
    }

    /**
     * Reconciles foreground, permission, precision, and provider state before acquiring location.
     */
    private fun refreshDeviceState() {
        if (!isForeground) return

        val hasPermission = hasLocationPermission()
        val hasFinePermission = hasFineLocationPermission()
        _uiState.update { it.copy(hasPrecisePermission = hasFinePermission) }

        when {
            !hasPermission -> {
                stopLocationUpdates()
                _uiState.update {
                    it.copy(origin = null, locationStatus = LocationStatus.PERMISSION_REQUIRED)
                }
            }

            !hasFinePermission -> {
                stopLocationUpdates()
                _uiState.update {
                    it.copy(
                        origin = null,
                        locationStatus = LocationStatus.PRECISE_PERMISSION_REQUIRED,
                    )
                }
            }

            !locationManager.isLocationEnabled -> {
                stopLocationUpdates()
                _uiState.update {
                    it.copy(origin = null, locationStatus = LocationStatus.LOCATION_DISABLED)
                }
            }

            else -> startLocationUpdates()
        }
    }

    /**
     * Registers high-accuracy continuous location updates and requests an immediate location fix.
     *
     * Registration is skipped when updates are already active or fine permission is absent. A
     * synchronous permission failure is converted into [LocationStatus.PERMISSION_REQUIRED].
     */
    private fun startLocationUpdates() {
        if (locationUpdatesStarted) return
        if (!hasFineLocationPermission()) return

        if (_uiState.value.origin == null) {
            _uiState.update { it.copy(locationStatus = LocationStatus.LOCATING) }
        } else {
            _uiState.update { it.copy(locationStatus = LocationStatus.READY) }
        }

        val updateRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL_MILLIS,
        )
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MILLIS)
            .setWaitForAccurateLocation(true)
            .build()

        try {
            locationUpdatesStarted = true
            fusedLocationClient.requestLocationUpdates(
                updateRequest,
                locationCallback,
                Looper.getMainLooper(),
            ).addOnFailureListener { error ->
                locationUpdatesStarted = false
                handleLocationFailure(error, clearExistingOrigin = true)
            }
            requestImmediateLocation()
            startLocationTimeout()
        } catch (_: SecurityException) {
            locationUpdatesStarted = false
            _uiState.update { it.copy(locationStatus = LocationStatus.PERMISSION_REQUIRED) }
        }
    }

    /**
     * Starts a cancellable one-shot request so an origin can be obtained before periodic updates.
     */
    private fun requestImmediateLocation() {
        currentLocationCancellation?.cancel()
        val cancellation = CancellationTokenSource()
        currentLocationCancellation = cancellation
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(MAX_CACHED_LOCATION_AGE_MILLIS)
            .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MILLIS)
            .build()

        try {
            fusedLocationClient.getCurrentLocation(request, cancellation.token)
                .addOnSuccessListener { location -> location?.let(::acceptLocation) }
                .addOnFailureListener { error ->
                    handleLocationFailure(error, clearExistingOrigin = false)
                }
        } catch (_: SecurityException) {
            _uiState.update { it.copy(locationStatus = LocationStatus.PERMISSION_REQUIRED) }
        }
    }

    /**
     * Schedules the deadline that marks initial location acquisition as unavailable.
     *
     * No deadline is retained when an origin already exists.
     */
    private fun startLocationTimeout() {
        locationTimeoutJob?.cancel()
        if (_uiState.value.origin != null) return
        locationTimeoutJob = viewModelScope.launch {
            delay(CURRENT_LOCATION_TIMEOUT_MILLIS)
            if (_uiState.value.origin == null) {
                _uiState.update { it.copy(locationStatus = LocationStatus.UNAVAILABLE) }
            }
        }
    }

    /**
     * Validates and publishes a platform location as the current origin.
     *
     * Accepted locations reset timeout and staleness tracking and may trigger reverse geocoding.
     * Invalid, non-finite, non-foreground, or insufficiently permitted locations are ignored.
     *
     * @param location Android location candidate received from fused location services.
     */
    private fun acceptLocation(location: Location) {
        if (!isForeground || !hasFineLocationPermission()) return
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        val coordinate = runCatching {
            GeoCoordinate(location.latitude, location.longitude)
        }.getOrNull() ?: return

        locationTimeoutJob?.cancel()
        locationStaleJob?.cancel()
        lastLocationElapsedRealtimeMillis = SystemClock.elapsedRealtime()
        val existing = _uiState.value.origin
        val shouldRefreshName = existing == null ||
            !existing.hasResolvedName ||
            haversineDistanceMeters(existing.coordinate, coordinate) >= ADDRESS_REFRESH_DISTANCE_METERS
        val keepExistingName = existing != null &&
            haversineDistanceMeters(existing.coordinate, coordinate) < ADDRESS_REFRESH_DISTANCE_METERS
        val fallbackName = appContext.getString(R.string.current_location)

        _uiState.update {
            it.copy(
                locationStatus = LocationStatus.READY,
                origin = PlacePoint(
                    coordinate = coordinate,
                    name = if (keepExistingName) existing.name else fallbackName,
                    hasResolvedName = keepExistingName && existing.hasResolvedName,
                ),
                hasPrecisePermission = hasFineLocationPermission(),
            )
        }

        if (shouldRefreshName) resolveOriginName(coordinate)
    }

    /**
     * Converts a failed fused-location request into unavailable UI state when still relevant.
     *
     * @param error Platform failure retained for diagnostic API shape but intentionally not shown.
     * @param clearExistingOrigin Whether a previously accepted origin must also be discarded.
     */
    private fun handleLocationFailure(
        @Suppress("UNUSED_PARAMETER") error: Exception,
        clearExistingOrigin: Boolean,
    ) {
        if (!isForeground) return
        if (clearExistingOrigin || _uiState.value.origin == null && !locationUpdatesStarted) {
            _uiState.update {
                it.copy(
                    origin = if (clearExistingOrigin) null else it.origin,
                    locationStatus = LocationStatus.UNAVAILABLE,
                )
            }
        }
    }

    /**
     * Schedules a delayed check that removes an origin when location availability remains stale.
     */
    private fun scheduleLocationStaleCheck() {
        locationStaleJob?.cancel()
        locationStaleJob = viewModelScope.launch {
            delay(LOCATION_STALE_TIMEOUT_MILLIS)
            if (!isForeground) return@launch
            val isStale = lastLocationElapsedRealtimeMillis == 0L ||
                SystemClock.elapsedRealtime() - lastLocationElapsedRealtimeMillis >=
                LOCATION_STALE_TIMEOUT_MILLIS
            if (isStale) {
                val status = if (locationManager.isLocationEnabled) {
                    LocationStatus.UNAVAILABLE
                } else {
                    LocationStatus.LOCATION_DISABLED
                }
                _uiState.update { it.copy(origin = null, locationStatus = status) }
            }
        }
    }

    /**
     * Cancels one-shot and delayed location work and unregisters continuous location updates.
     */
    private fun stopLocationUpdates() {
        locationTimeoutJob?.cancel()
        locationStaleJob?.cancel()
        currentLocationCancellation?.cancel()
        currentLocationCancellation = null
        if (locationUpdatesStarted) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationUpdatesStarted = false
        }
    }

    /**
     * Resolves a display name for the origin unless an equivalent request is already active.
     *
     * Results are applied only while the current origin remains within
     * [ADDRESS_REFRESH_DISTANCE_METERS] of the requested coordinate.
     *
     * @param coordinate Origin coordinate for which to request a human-readable name.
     */
    private fun resolveOriginName(coordinate: GeoCoordinate) {
        if (!_uiState.value.isOnline) return
        val activeAnchor = originGeocodeAnchor
        if (
            originGeocodeJob?.isActive == true &&
            activeAnchor != null &&
            haversineDistanceMeters(activeAnchor, coordinate) < ADDRESS_REFRESH_DISTANCE_METERS
        ) {
            return
        }
        originGeocodeJob?.cancel()
        originGeocodeAnchor = coordinate
        originGeocodeJob = viewModelScope.launch {
            val name = reverseGeocode(coordinate)
                ?: appContext.getString(R.string.address_unavailable)
            _uiState.update { state ->
                val current = state.origin
                if (current != null &&
                    haversineDistanceMeters(current.coordinate, coordinate) < ADDRESS_REFRESH_DISTANCE_METERS
                ) {
                    state.copy(origin = current.copy(name = name, hasResolvedName = true))
                } else {
                    state
                }
            }
            originGeocodeAnchor = null
        }
    }

    /**
     * Resolves a display name for the current destination when validated internet is available.
     *
     * Results are applied only if the destination still exactly matches [coordinate].
     *
     * @param coordinate Destination coordinate for which to request a human-readable name.
     */
    private fun resolveDestinationName(coordinate: GeoCoordinate) {
        if (!_uiState.value.isOnline) return
        destinationGeocodeJob?.cancel()
        destinationGeocodeJob = viewModelScope.launch {
            val name = reverseGeocode(coordinate)
                ?: appContext.getString(R.string.address_unavailable)
            _uiState.update { state ->
                val current = state.destination
                if (current?.coordinate == coordinate) {
                    state.copy(destination = current.copy(name = name, hasResolvedName = true))
                } else {
                    state
                }
            }
        }
    }

    /**
     * Reverse geocodes a coordinate using the API-appropriate Android geocoder implementation.
     *
     * Ordinary platform failures are converted to `null`; coroutine cancellation is propagated.
     *
     * @param coordinate Coordinate whose first available address should be resolved.
     * @return A bounded display name, or `null` when geocoding is absent or unsuccessful.
     * @throws CancellationException when the calling coroutine is cancelled.
     */
    private suspend fun reverseGeocode(coordinate: GeoCoordinate): String? {
        val address = try {
            if (!Geocoder.isPresent()) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                reverseGeocodeAsync(coordinate)
            } else {
                reverseGeocodeBlocking(coordinate)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
        return address?.displayName()
    }

    /**
     * Uses the asynchronous Android 13+ geocoder API to retrieve the first matching address.
     *
     * @param coordinate Coordinate to reverse geocode.
     * @return The first reported address, or `null` when the geocoder reports no result or error.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun reverseGeocodeAsync(coordinate: GeoCoordinate): Address? =
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(
                coordinate.latitude,
                coordinate.longitude,
                1,
                object : Geocoder.GeocodeListener {
                    /**
                     * Resumes the suspended request with the first returned address.
                     *
                     * @param addresses Addresses returned by the platform geocoder.
                     */
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }

                    /**
                     * Resumes the suspended request without an address after a geocoder error.
                     *
                     * @param errorMessage Optional platform error detail; not exposed to the UI.
                     */
                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }

    /**
     * Runs the legacy blocking geocoder API on the IO dispatcher.
     *
     * @param coordinate Coordinate to reverse geocode.
     * @return The first matching address, or `null` when no address is returned.
     */
    @Suppress("DEPRECATION")
    private suspend fun reverseGeocodeBlocking(coordinate: GeoCoordinate): Address? =
        withContext(Dispatchers.IO) {
            geocoder.getFromLocation(coordinate.latitude, coordinate.longitude, 1)?.firstOrNull()
        }

    /**
     * Builds a compact human-readable name from an Android address.
     *
     * The full address line is preferred; otherwise distinct locality components are joined. The
     * result is bounded by [MAX_ADDRESS_LENGTH].
     *
     * @receiver Address returned by Android reverse geocoding.
     * @return A non-blank display name, or `null` when no usable component is present.
     */
    private fun Address.displayName(): String? {
        val fullAddress = getAddressLine(0)?.trim()?.takeIf(String::isNotEmpty)
        if (fullAddress != null) return fullAddress.take(MAX_ADDRESS_LENGTH)

        val parts = listOfNotNull(featureName, subLocality, locality, adminArea, countryName)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        return parts.takeIf(List<String>::isNotEmpty)
            ?.joinToString(", ")
            ?.take(MAX_ADDRESS_LENGTH)
    }

    /** Registers [networkCallback] and records whether registration succeeded. */
    private fun registerNetworkCallback() {
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (_: RuntimeException) {
            networkCallbackRegistered = false
        }
    }

    /**
     * Registers [locationStateReceiver] for provider and location-mode changes once per foreground
     * session.
     */
    private fun registerLocationStateReceiver() {
        if (locationReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                locationStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            locationReceiverRegistered = true
        }
    }

    /** Unregisters [locationStateReceiver] when it was previously registered. */
    private fun unregisterLocationStateReceiver() {
        if (!locationReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(locationStateReceiver) }
        locationReceiverRegistered = false
    }

    /**
     * Publishes current validated-connectivity state and retries unresolved names after reconnection.
     */
    private fun refreshConnectivity() {
        viewModelScope.launch {
            val online = hasValidatedInternet()
            val wasOnline = _uiState.value.isOnline
            _uiState.update { it.copy(isOnline = online) }
            if (!wasOnline && online) {
                _uiState.value.origin
                    ?.takeUnless(PlacePoint::hasResolvedName)
                    ?.let { resolveOriginName(it.coordinate) }
                _uiState.value.destination
                    ?.takeUnless(PlacePoint::hasResolvedName)
                    ?.let { resolveDestinationName(it.coordinate) }
            }
        }
    }

    /**
     * Determines whether the active network provides validated internet access.
     *
     * @return `true` only when Android reports both internet and validation capabilities.
     */
    private fun hasValidatedInternet(): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork
        ) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Determines whether either Android location permission is granted.
     *
     * @return `true` when fine or coarse location permission is granted.
     */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Determines whether precise Android location permission is granted.
     *
     * @return `true` when fine location permission is granted.
     */
    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /** Releases all location, heading, broadcast, network, and coroutine work owned by this model. */
    override fun onCleared() {
        stopLocationUpdates()
        headingProvider.stop()
        unregisterLocationStateReceiver()
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        }
        super.onCleared()
    }

    /** Timing, distance, and output bounds used by map state orchestration. */
    private companion object {
        /** Desired interval between high-accuracy continuous location updates. */
        const val LOCATION_UPDATE_INTERVAL_MILLIS = 5_000L

        /** Fastest interval at which continuous location updates may be delivered. */
        const val LOCATION_FASTEST_INTERVAL_MILLIS = 2_000L

        /** Maximum duration of a one-shot current-location request. */
        const val CURRENT_LOCATION_TIMEOUT_MILLIS = 20_000L

        /** Maximum age accepted for a cached location returned by a one-shot request. */
        const val MAX_CACHED_LOCATION_AGE_MILLIS = 10_000L

        /** Maximum age for retaining an origin while the app is backgrounded. */
        const val MAX_RETAINED_LOCATION_AGE_MILLIS = 15_000L

        /** Duration without location availability after which the origin is considered stale. */
        const val LOCATION_STALE_TIMEOUT_MILLIS = 20_000L

        /** Minimum origin movement that warrants requesting a new reverse-geocoded name. */
        const val ADDRESS_REFRESH_DISTANCE_METERS = 100.0

        /** Maximum number of characters exposed for a reverse-geocoded display name. */
        const val MAX_ADDRESS_LENGTH = 120
    }
}
