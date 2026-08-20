/** Tests the application composition boundary between setup sensors and dashboard sensors. */
package ir.hrka.shahbaz

import ir.hrka.compass.CalibrationStatus
import ir.hrka.compass.CompassAccuracy
import ir.hrka.compass.CompassAccuracyLevel
import ir.hrka.compass.CompassFailure
import ir.hrka.compass.CompassFailureCode
import ir.hrka.compass.CompassReading
import ir.hrka.compass.CompassSensorSource
import ir.hrka.compass.CompassUnavailableReason
import ir.hrka.shahbaz.core.model.GeoCoordinate
import ir.hrka.shahbaz.feature.dashboard.PhoneReading
import ir.hrka.shahbaz.feature.map.CompassSensorStatus
import ir.hrka.shahbaz.feature.map.LocationStatus
import ir.hrka.shahbaz.feature.map.MapUiState
import ir.hrka.shahbaz.feature.map.PlacePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM verification that phone values keep explicit phone-only provenance and availability. */
class DashboardPhoneMappingTest {
    @Test
    fun `ready location maps to an available dashboard position`() {
        val coordinate = GeoCoordinate(35.7, 51.4)
        val result = MapUiState(
            locationStatus = LocationStatus.READY,
            origin = PlacePoint(coordinate, "Current location"),
        ).toDashboardPhoneSensors()

        assertEquals(
            coordinate,
            (result.position as PhoneReading.Available<GeoCoordinate>).value,
        )
        assertTrue(result.orientation is PhoneReading.Inactive)
    }

    @Test
    fun `missing permission remains unavailable instead of becoming zero data`() {
        val result = MapUiState(
            locationStatus = LocationStatus.PERMISSION_REQUIRED,
        ).toDashboardPhoneSensors()

        assertTrue(result.position is PhoneReading.Unavailable)
    }

    @Test
    fun `stale compass status never maps a retained reading as live`() {
        val result = MapUiState(
            compassReading = Reading,
            compassStatus = CompassSensorStatus.Stale(Reading.sensorSource),
        ).toDashboardPhoneSensors()

        assertTrue(result.orientation is PhoneReading.Stale)
        assertEquals(
            Reading,
            (result.orientation as PhoneReading.Stale<CompassReading>).lastValue,
        )
    }

    @Test
    fun `silent and absent compass sources remain distinct`() {
        val silent = MapUiState(
            compassStatus = CompassSensorStatus.NoResponse(
                CompassSensorSource.ROTATION_VECTOR
            ),
        ).toDashboardPhoneSensors()
        val absent = MapUiState(
            compassStatus = CompassSensorStatus.Unavailable(
                CompassUnavailableReason.NO_SUPPORTED_SENSOR
            ),
        ).toDashboardPhoneSensors()

        assertTrue(silent.orientation is PhoneReading.NoResponse)
        assertTrue(absent.orientation is PhoneReading.NotPresent)
    }

    @Test
    fun `invalid samples and registration failures remain distinct`() {
        val invalid = MapUiState(
            compassReading = Reading,
            compassStatus = CompassSensorStatus.Failed(
                CompassFailure(CompassFailureCode.INVALID_SENSOR_DATA)
            ),
        ).toDashboardPhoneSensors()
        val registrationFailure = MapUiState(
            compassStatus = CompassSensorStatus.Failed(
                CompassFailure(CompassFailureCode.REGISTRATION_FAILED)
            ),
        ).toDashboardPhoneSensors()

        assertTrue(invalid.orientation is PhoneReading.Invalid)
        assertEquals(
            Reading,
            (invalid.orientation as PhoneReading.Invalid<CompassReading>).lastValue,
        )
        assertTrue(registrationFailure.orientation is PhoneReading.Failed)
    }

    private companion object {
        val Reading = CompassReading(
            magneticAzimuthDegrees = 42f,
            trueAzimuthDegrees = null,
            declinationDegrees = null,
            pitchDegrees = 3f,
            rollDegrees = -2f,
            accuracy = CompassAccuracy(
                level = CompassAccuracyLevel.HIGH,
                estimatedErrorDegrees = null,
                calibrationStatus = CalibrationStatus.NOT_REQUIRED,
            ),
            sensorSource = CompassSensorSource.ROTATION_VECTOR,
            timestampNanos = 1L,
        )
    }
}
