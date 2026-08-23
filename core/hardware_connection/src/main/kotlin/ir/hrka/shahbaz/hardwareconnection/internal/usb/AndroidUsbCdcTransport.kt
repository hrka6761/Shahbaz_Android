/** Android USB-host CDC-ACM bulk transport for the exact Shahbaz production device. */
package ir.hrka.shahbaz.hardwareconnection.internal.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import ir.hrka.shahbaz.hardwareconnection.hasExactShahbazBoardUsbIdentity
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidUsbCdcTransport(
    context: Context,
    private val listener: Listener,
    private val readTimeoutMillis: Int = 100,
    private val writeTimeoutMillis: Int = 1_000,
) : Closeable {
    internal interface Listener {
        fun onBytes(generation: Long, bytes: ByteArray)
        fun onTransportError(generation: Long, message: String, cause: Throwable?)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val running = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile private var generation = 0L
    @Volatile private var device: UsbDevice? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var communicationInterface: UsbInterface? = null
    @Volatile private var dataInterface: UsbInterface? = null
    @Volatile private var inputEndpoint: UsbEndpoint? = null
    @Volatile private var outputEndpoint: UsbEndpoint? = null
    @Volatile private var readerThread: Thread? = null

    fun matchingDevices(): List<UsbDevice> = usbManager.deviceList.values
        .filter { candidate ->
            hasExactShahbazBoardUsbIdentity(candidate.vendorId, candidate.productId)
        }
        .sortedBy { it.deviceId }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun isAttached(deviceId: Int): Boolean = usbManager.deviceList.values.any {
        it.deviceId == deviceId &&
            hasExactShahbazBoardUsbIdentity(it.vendorId, it.productId)
    }

    fun open(device: UsbDevice, generation: Long): OpenResult {
        close()
        if (
            !hasExactShahbazBoardUsbIdentity(device.vendorId, device.productId)
        ) {
            return OpenResult.Incompatible("USB VID/PID does not identify shahbaz_interface_board")
        }
        if (!usbManager.hasPermission(device)) return OpenResult.PermissionMissing
        val endpoints = findEndpoints(device)
            ?: return OpenResult.Incompatible("CDC-ACM bulk IN/OUT interfaces are missing")
        val opened = usbManager.openDevice(device) ?: return OpenResult.OpenFailed
        return try {
            if (!opened.claimInterface(endpoints.communication, true)) {
                opened.close()
                OpenResult.Incompatible("CDC communication interface could not be claimed")
            } else if (!opened.claimInterface(endpoints.data, true)) {
                opened.releaseInterface(endpoints.communication)
                opened.close()
                OpenResult.Incompatible("CDC data interface could not be claimed")
            } else {
                this.generation = generation
                this.device = device
                connection = opened
                communicationInterface = endpoints.communication
                dataInterface = endpoints.data
                inputEndpoint = endpoints.input
                outputEndpoint = endpoints.output
                configureCdc(opened, endpoints.communication)
                startReader(generation)
                OpenResult.Opened
            }
        } catch (error: RuntimeException) {
            runCatching { opened.close() }
            clearHandles()
            OpenResult.Failed(error)
        }
    }

    fun write(expectedGeneration: Long, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (expectedGeneration != generation || !running.get()) return false
        synchronized(writeLock) {
            val current = connection ?: return false
            val endpoint = outputEndpoint ?: return false
            var offset = 0
            while (offset < bytes.size) {
                val count = try {
                    current.bulkTransfer(
                        endpoint,
                        bytes,
                        offset,
                        bytes.size - offset,
                        writeTimeoutMillis,
                    )
                } catch (error: RuntimeException) {
                    listener.onTransportError(expectedGeneration, "USB write failed", error)
                    return false
                }
                if (count <= 0) {
                    listener.onTransportError(
                        expectedGeneration,
                        "USB write transferred $count/${bytes.size - offset} bytes",
                        null,
                    )
                    return false
                }
                offset += count
            }
        }
        return true
    }

    fun openedDeviceId(): Int? = device?.deviceId

    override fun close() {
        running.set(false)
        val thread = readerThread
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(readTimeoutMillis.toLong() + 200L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        synchronized(writeLock) {
            val current = connection
            val data = dataInterface
            val communication = communicationInterface
            if (current != null) {
                runCatching {
                    if (communication != null) {
                        setControlLineState(current, communication, CDC_CONTROL_LINE_IDLE)
                    }
                }
                runCatching { if (data != null) current.releaseInterface(data) }
                runCatching { if (communication != null) current.releaseInterface(communication) }
                runCatching { current.close() }
            }
            clearHandles()
        }
    }

    private fun startReader(generation: Long) {
        running.set(true)
        readerThread = Thread(
            {
                val buffer = ByteArray(2_048)
                while (running.get() && this.generation == generation) {
                    val current = connection ?: break
                    val endpoint = inputEndpoint ?: break
                    val count = try {
                        current.bulkTransfer(endpoint, buffer, buffer.size, readTimeoutMillis)
                    } catch (error: RuntimeException) {
                        if (running.get()) {
                            listener.onTransportError(generation, "USB read failed", error)
                        }
                        break
                    }
                    if (count > 0) listener.onBytes(generation, buffer.copyOf(count))
                    // Android reports a timeout as a negative count; detach is handled separately.
                }
            },
            "shahbaz-hardware-connection-rx",
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun configureCdc(connection: UsbDeviceConnection, communication: UsbInterface) {
        val lineCoding = byteArrayOf(
            0x00, 0xC2.toByte(), 0x01, 0x00, // 115200, informational for TinyUSB CDC.
            0x00, 0x00, 0x08, // one stop bit, no parity, 8 data bits.
        )
        val codingResult = connection.controlTransfer(
            0x21, 0x20, 0, communication.id, lineCoding, lineCoding.size, 1_000,
        )
        if (codingResult < 0) throw IllegalStateException("CDC SET_LINE_CODING failed")
        cdcOpenControlLineStates().forEach { state ->
            setControlLineState(connection, communication, state)
        }
    }

    private fun setControlLineState(
        connection: UsbDeviceConnection,
        communication: UsbInterface,
        state: Int,
    ) {
        val result = connection.controlTransfer(
            0x21, 0x22, state, communication.id, null, 0, 1_000,
        )
        if (result < 0) {
            throw IllegalStateException(
                "CDC SET_CONTROL_LINE_STATE 0x${state.toString(16).padStart(4, '0')} failed",
            )
        }
    }

    private fun clearHandles() {
        device = null
        connection = null
        communicationInterface = null
        dataInterface = null
        inputEndpoint = null
        outputEndpoint = null
        readerThread = null
    }

    private fun findEndpoints(device: UsbDevice): Endpoints? {
        var communication: UsbInterface? = null
        val dataCandidates = mutableListOf<UsbInterface>()
        repeat(device.interfaceCount) { index ->
            val usbInterface = device.getInterface(index)
            when (usbInterface.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> if (communication == null) communication = usbInterface
                UsbConstants.USB_CLASS_CDC_DATA -> dataCandidates += usbInterface
            }
        }
        for (data in dataCandidates) {
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            repeat(data.endpointCount) { index ->
                val endpoint = data.getEndpoint(index)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    when (endpoint.direction) {
                        UsbConstants.USB_DIR_IN -> if (input == null) input = endpoint
                        UsbConstants.USB_DIR_OUT -> if (output == null) output = endpoint
                    }
                }
            }
            val bulkInput = input
            val bulkOutput = output
            val control = communication
            if (control != null && bulkInput != null && bulkOutput != null) {
                return Endpoints(control, data, bulkInput, bulkOutput)
            }
        }
        return null
    }

    private data class Endpoints(
        val communication: UsbInterface,
        val data: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
    )

    sealed interface OpenResult {
        data object Opened : OpenResult
        data object PermissionMissing : OpenResult
        data object OpenFailed : OpenResult
        data class Incompatible(val message: String) : OpenResult
        data class Failed(val cause: Throwable) : OpenResult
    }
}
