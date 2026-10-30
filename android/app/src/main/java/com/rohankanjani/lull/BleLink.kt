package com.rohankanjani.lull

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.rohankanjani.lull.bedjetkit.BedJetError
import com.rohankanjani.lull.bedjetkit.BedJetLink
import com.rohankanjani.lull.bedjetkit.Codec
import com.rohankanjani.lull.bedjetkit.CodecV2
import com.rohankanjani.lull.bedjetkit.ConnectionTiming
import com.rohankanjani.lull.bedjetkit.DeviceStatus
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.takes
import com.rohankanjani.lull.bedjetkit.Handoff
import com.rohankanjani.lull.bedjetkit.LinkState
import com.rohankanjani.lull.bedjetkit.deviceName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Android Bluetooth transport for a BedJet 3 or V2. A link made for a unit only ever connects to that unit, so
 * several can run side by side, one per BedJet, and none silently swaps in another BedJet. Without a unit it
 * takes the first one found.
 * Everything runs on the main thread; Bluetooth callbacks are posted there.
 *
 * The BedJet 3 has its own GATT service. The V2 sends its serial protocol ([CodecV2]) through a
 * Microchip "transparent UART" service, which other gadgets use too, so a V2 is recognised by its name.
 *
 * The BedJet accepts a single connection and stops advertising while it has one, so a second phone
 * cannot even see it. Two phones share it by handing over: the phone that wants the BedJet briefly
 * advertises a request, and the phone holding it disconnects when it sees that request. Requests use
 * the same UUIDs as the iOS app, so iPhones and Android phones hand over to each other.
 */
@SuppressLint("MissingPermission") // Every Bluetooth call is behind hasPermissions().
class BleLink(
    context: Context,
    /** The BedJet to connect to. Without one, the first BedJet found is used. */
    unit: String? = null,
) : BedJetLink {
    private val context = context.applicationContext

    /** The unit this link belongs to (its Bluetooth address): the one it was made for, or the first found by a link made without one. */
    var unit: String? = unit
        private set

    override var state: LinkState = LinkState.SEARCHING
        private set(value) {
            if (field == value) return
            field = value
            if (value == LinkState.READY) stopRequesting()
            onStateChange?.invoke(value)
        }
    override var generation: Generation = Generation.V3
        private set
    override var lastStatus: DeviceStatus? = null
        private set
    override var onStateChange: ((LinkState) -> Unit)? = null
    override var onStatus: ((DeviceStatus) -> Unit)? = null
    /** Return false to postpone a hand-over, for example while a schedule is being saved. */
    var canYield: () -> Boolean = { true }
    var lastConnection: ConnectionTiming? = null
        private set

    companion object {
        /** Where the link keeps what it knows about units. */
        fun preferences(context: Context): SharedPreferences = context.getSharedPreferences("BedJetKit", Context.MODE_PRIVATE)

        /** The unit found by an earlier version of Lull, with its name, for carrying over to a link made with a unit. */
        fun legacyUnit(prefs: SharedPreferences): Pair<String, String?>? {
            val unit = prefs.getString(LEGACY_UNIT_KEY, null) ?: return null
            return unit to prefs.getString(LEGACY_NAME_KEY, null)
        }

        /** Drops what was kept to ask another phone for this unit. The unit itself is left as it is. */
        fun forget(context: Context, unit: String) {
            preferences(context).edit().remove(nameKey(unit)).apply()
        }

        fun isBedJet(result: ScanResult): Boolean {
            val record = result.scanRecord
            if (record?.serviceUuids?.contains(ParcelUuid(SERVICE)) == true) return true
            val name = record?.deviceName ?: result.device.name ?: ""
            return name.uppercase().startsWith("BEDJET")
        }

        /** What can be told from an advertisement. A V2 is recognised by its name, which proves less. */
        fun generation(result: ScanResult): Generation? {
            val services = result.scanRecord?.serviceUuids.orEmpty()
            if (ParcelUuid(SERVICE) in services) return Generation.V3
            if (ParcelUuid(UART_SERVICE) in services) return Generation.V2
            return null
        }

        private fun uuid(short: String): UUID = UUID.fromString("0000$short-BED0-0080-AA55-4265644A6574")
        private val SERVICE: UUID = uuid("1000")
        private val STATUS: UUID = uuid("2000")
        private val NAME: UUID = uuid("2001")
        private val WIFI_NETWORK: UUID = uuid("2002")
        private val WIFI_PASSWORD: UUID = uuid("2003")
        private val COMMAND: UUID = uuid("2004")
        private val REPLY: UUID = uuid("2006")
        private val UART_SERVICE: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
        private val UART_NOTIFY: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
        private val UART_WRITE: UUID = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3")
        private val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Where versions that knew a single BedJet remembered it. */
        private const val LEGACY_UNIT_KEY = "peripheral"
        private const val LEGACY_NAME_KEY = "deviceName"
        private fun nameKey(unit: String) = "$LEGACY_NAME_KEY.$unit"

        /** How long a phone that handed over stays out of the way before it quietly waits for the BedJet again. */
        private const val COURTESY_MS = 10_000L
        /** How long a request is advertised. Shorter than the courtesy, so one request is answered once. */
        private const val REQUEST_DURATION_MS = 8_000L
        /**
         * How long a known unit gets to accept a connection before Lull scans for it instead.
         * Longer than the request, so another phone has time to hand the unit over.
         */
        private const val CONNECT_PATIENCE_MS = 12_000L
        private const val OPERATION_TIMEOUT_MS = 5_000L
        /** Status an operation finishes with when the connection went away before it completed. */
        private const val LINK_GONE = -1
    }

    private val prefs = preferences(context)
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private var reply: BluetoothGattCharacteristic? = null
    /** Written only. It says it can be read, but a BedJet 3 never answers, which stalls every request after it. */
    private var wifiNetwork: BluetoothGattCharacteristic? = null
    private var wifiPassword: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var wantsConnection = false
    private var yieldedUntil = 0L
    private var requestGeneration = 0
    private var advertising: AdvertiseCallback? = null
    private var scanning: ScanCallback? = null
    private var radioReceiver: BroadcastReceiver? = null
    private var skipsRememberedUnit = false
    private var connectGeneration = 0
    private var askedAt: Long? = null
    private var linkUpAfter: Double? = null
    private var radioOnAfter = 0.0
    private var servicesAfter = 0.0
    private var foundByScan = false

    private var lastHead: ByteArray? = null
    private var lastRemainderRead = 0L
    private var lastExtra: DeviceStatus.Extra? = null
    private var tag = 0
    private val serial = Mutex()
    /** A V2 answers through the same notifications that carry its status. */
    private class PendingReply(val query: Int, val reply: CompletableDeferred<ByteArray>)
    private var pendingReply: PendingReply? = null
    private var uartBuffer = ByteArray(0)

    // Android runs one GATT operation at a time, so everything sent to the unit waits its turn here.
    private class Operation(val begin: (BluetoothGatt) -> Boolean, val finish: (ByteArray?, Int) -> Unit)

    private val operations = ArrayDeque<Operation>()
    private var current: Operation? = null

    init {
        unit?.let(::adoptLegacyName)
    }

    override fun start() {
        requestControl()
    }

    /** Connects whenever the BedJet is free, without asking another phone to hand it over. */
    fun listen() {
        wantsConnection = true
        watchRadio()
        if (radioUsable()) connectIfPossible()
    }

    override fun stop() {
        wantsConnection = false
        askedAt = null
        stopRequesting()
        stopScan()
        yieldedUntil = 0
        skipsRememberedUnit = false
        stopWatchingRadio()
        val gatt = gatt
        if (gatt != null && state == LinkState.READY) {
            gatt.disconnect()
        } else {
            // Nothing is connected, so no disconnect will arrive to tidy up.
            gatt?.close()
            this.gatt = null
            if (state == LinkState.CONNECTING || state == LinkState.YIELDED) state = LinkState.SEARCHING
        }
    }

    override fun requestControl() {
        wantsConnection = true
        watchRadio()
        if (state == LinkState.YIELDED) {
            yieldedUntil = 0
            // A phone that handed over waits quietly for the BedJet; asking for it again goes straight to it.
            gatt?.close()
            gatt = null
            state = LinkState.SEARCHING
        }
        if (state != LinkState.READY && askedAt == null) {
            askedAt = SystemClock.elapsedRealtime()
            radioOnAfter = 0.0
        }
        if (!radioUsable()) return
        connectIfPossible()
        if (state == LinkState.READY) return
        val request = handoffUuid ?: return
        // Ask straight away rather than waiting to see whether the BedJet answers: if it is free
        // nobody is listening, and if another phone has it every moment of waiting is wasted.
        requestGeneration += 1
        val generation = requestGeneration
        advertise(request)
        main.postDelayed({ if (requestGeneration == generation) stopRequesting() }, REQUEST_DURATION_MS)
    }

    override suspend fun send(command: ByteArray) {
        serial.withLock { write(command) }
    }

    override suspend fun writeWiFi(network: ByteArray, password: ByteArray) {
        serial.withLock {
            if (state != LinkState.READY) throw BedJetError.NotConnected
            val networkCharacteristic = wifiNetwork ?: throw BedJetError.InvalidWiFi(BedJetError.NO_WIFI_SETUP)
            val passwordCharacteristic = wifiPassword ?: throw BedJetError.InvalidWiFi(BedJetError.NO_WIFI_SETUP)
            write(network, networkCharacteristic)
            write(password, passwordCharacteristic)
        }
    }

    override suspend fun query(request: Int): ByteArray {
        if (generation == Generation.V2) {
            return serial.withLock {
                // A request the unit missed is simply asked again.
                try { exchange(request) } catch (_: BedJetError.Timeout) { exchange(request) }
            }
        }
        return queryV3(request)
    }

    private suspend fun queryV3(request: Int): ByteArray = serial.withLock {
        // The reply characteristic keeps its previous contents until the unit has answered,
        // so retry until the echoed request and tag match.
        repeat(4) { attempt ->
            tag = if (tag == 255) 1 else tag + 1
            write(Codec.query(request, tag))
            if (attempt > 0) delay(150)
            val data = read()
            if (data.size >= 2 && data[0].toInt() and 0xff == request && data[1].toInt() and 0xff == tag) {
                return@withLock data.copyOfRange(2, data.size)
            }
        }
        throw BedJetError.BadResponse
    }

    // Radio and permission

    private fun hasPermissions(): Boolean = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Reports why Bluetooth can't be used right now, and returns whether it can. */
    private fun radioUsable(): Boolean {
        val adapter = adapter
        return when {
            adapter == null -> { state = LinkState.BLUETOOTH_OFF; false }
            !hasPermissions() -> { state = LinkState.UNAUTHORIZED; false }
            !adapter.isEnabled -> { state = LinkState.BLUETOOTH_OFF; false }
            else -> {
                if (state == LinkState.BLUETOOTH_OFF || state == LinkState.UNAUTHORIZED) state = LinkState.SEARCHING
                true
            }
        }
    }

    private fun watchRadio() {
        if (radioReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        radioOnAfter = askedAt?.let { seconds(it) } ?: 0.0
                        if (wantsConnection && radioUsable()) connectIfPossible()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        gatt?.close()
                        dropConnection(reconnect = false)
                        state = LinkState.BLUETOOTH_OFF
                    }
                }
            }
        }
        radioReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun stopWatchingRadio() {
        val receiver = radioReceiver ?: return
        radioReceiver = null
        context.unregisterReceiver(receiver)
    }

    // Connection

    private fun connectIfPossible() {
        if (!wantsConnection || gatt != null || !radioUsable()) return
        if (state == LinkState.YIELDED && SystemClock.elapsedRealtime() < yieldedUntil) return
        val adapter = adapter ?: return
        val remembered = unit
        if (!skipsRememberedUnit && remembered != null && BluetoothAdapter.checkBluetoothAddress(remembered)) {
            // Going straight to the unit this phone already knows is several times quicker than scanning for it.
            foundByScan = false
            connect(adapter.getRemoteDevice(remembered))
        } else {
            if (state != LinkState.YIELDED) state = LinkState.SEARCHING
            foundByScan = true
            // Not filtered by service, because a V2 is known by its name rather than by what it advertises.
            startScan(filter = null, forRequests = false)
        }
    }

    private fun connect(device: BluetoothDevice) {
        stopScan()
        // A phone that handed over keeps saying so while it waits; the connection completes
        // whenever the BedJet becomes free again.
        val waits = state == LinkState.YIELDED
        if (state != LinkState.YIELDED) state = LinkState.CONNECTING
        @Suppress("DEPRECATION") // The replacement needs Android 17; this works on every version Lull supports.
        val target = device.connectGatt(context, waits, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = target
        expectAnswer(target)
    }

    /**
     * A unit that was reset or moved away may never answer that attempt. Give it a while, then scan
     * for it instead.
     */
    private fun expectAnswer(target: BluetoothGatt) {
        connectGeneration += 1
        val generation = connectGeneration
        main.postDelayed({
            if (connectGeneration != generation || !wantsConnection || gatt !== target) return@postDelayed
            // A phone that handed over is meant to wait.
            if (state != LinkState.CONNECTING && state != LinkState.SEARCHING) return@postDelayed
            target.close()
            gatt = null
            skipsRememberedUnit = true
            foundByScan = true
            state = LinkState.SEARCHING
            startScan(filter = null, forRequests = false)
        }, CONNECT_PATIENCE_MS)
    }

    private fun dropConnection(reconnect: Boolean = true) {
        gatt = null
        command = null
        reply = null
        wifiNetwork = null
        wifiPassword = null
        statusCharacteristic = null
        lastHead = null
        lastExtra = null
        lastStatus = null
        uartBuffer = ByteArray(0)
        pendingReply?.reply?.completeExceptionally(BedJetError.NotConnected)
        pendingReply = null
        current?.finish?.invoke(null, LINK_GONE)
        current = null
        while (operations.isNotEmpty()) operations.removeFirst().finish(null, LINK_GONE)
        stopScan()
        if (state != LinkState.YIELDED && state != LinkState.BLUETOOTH_OFF) state = LinkState.SEARCHING
        // A short pause stops a unit that keeps refusing from turning this into a busy loop.
        if (reconnect) main.postDelayed({ connectIfPossible() }, 500)
    }

    // Hand-over between phones

    private val handoffUuid: UUID?
        get() = unit?.let { prefs.getString(nameKey(it), null) }?.let(Handoff::uuid)

    /**
     * The name is needed to ask for the BedJet before it has connected, so it carries over from
     * versions that remembered one BedJet.
     */
    private fun adoptLegacyName(unit: String) {
        if (prefs.getString(nameKey(unit), null) != null) return
        if (prefs.getString(LEGACY_UNIT_KEY, null) != unit) return
        val name = prefs.getString(LEGACY_NAME_KEY, null) ?: return
        prefs.edit().putString(nameKey(unit), name).apply()
    }

    /** The unit's own name identifies it to the other phone during a hand-over. */
    private fun rememberName(name: String) {
        val unit = unit ?: return
        prefs.edit().putString(nameKey(unit), name).apply()
        watchForRequests()
    }

    private fun advertise(request: UUID) {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        advertising?.let { advertiser.stopAdvertising(it) }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(request)).setIncludeDeviceName(false).build()
        val callback = object : AdvertiseCallback() {}
        advertising = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    private fun stopRequesting() {
        requestGeneration += 1
        val callback = advertising ?: return
        advertising = null
        if (hasPermissions()) adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
    }

    /** While holding the BedJet, listen for another phone asking for it. */
    private fun watchForRequests() {
        val request = handoffUuid ?: return
        if (state != LinkState.READY) return
        startScan(ScanFilter.Builder().setServiceUuid(ParcelUuid(request)).build(), forRequests = true)
    }

    private fun startScan(filter: ScanFilter?, forRequests: Boolean) {
        stopScan()
        val scanner = adapter?.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post {
                    if (scanning !== this) return@post
                    if (forRequests) {
                        // The only thing scanned for while connected is another phone asking for this BedJet.
                        if (state == LinkState.READY) handOver()
                    } else if (gatt == null && isBedJet(result) && takes(result.device.address, boundTo = unit)) {
                        connect(result.device)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                // Android limits how often an app may start scanning; try again shortly rather than sit searching.
                main.postDelayed({
                    if (scanning !== this) return@postDelayed
                    scanning = null
                    if (forRequests) watchForRequests() else connectIfPossible()
                }, 5_000)
            }
        }
        scanning = callback
        val mode = if (forRequests) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY
        scanner.startScan(filter?.let(::listOf), ScanSettings.Builder().setScanMode(mode).build(), callback)
    }

    private fun stopScan() {
        val callback = scanning ?: return
        scanning = null
        if (hasPermissions() && adapter?.isEnabled == true) adapter.bluetoothLeScanner?.stopScan(callback)
    }

    private fun handOver(attempt: Int = 0) {
        val gatt = gatt ?: return
        if (state != LinkState.READY) return
        if (!canYield() || current != null || operations.isNotEmpty()) {
            // Finish what is in flight first. If that takes too long, keep the BedJet and listen again.
            if (attempt >= 15) return watchForRequests()
            stopScan()
            main.postDelayed({ handOver(attempt + 1) }, 300)
            return
        }
        stopScan()
        yieldedUntil = SystemClock.elapsedRealtime() + COURTESY_MS
        state = LinkState.YIELDED
        gatt.disconnect()
        main.postDelayed({ connectIfPossible() }, COURTESY_MS)
    }

    // Operations

    private fun enqueue(operation: Operation) {
        operations.addLast(operation)
        runNext()
    }

    private fun runNext() {
        if (current != null) return
        val gatt = gatt
        val next = operations.removeFirstOrNull() ?: return
        if (gatt == null) return next.finish(null, LINK_GONE)
        current = next
        if (!next.begin(gatt)) {
            current = null
            next.finish(null, BluetoothGatt.GATT_FAILURE)
            runNext()
        }
    }

    /** Called from the Bluetooth callbacks when the operation in flight completes. */
    private fun complete(value: ByteArray?, status: Int) {
        val operation = current ?: return
        current = null
        operation.finish(value, status)
        runNext()
    }

    /** Writes to the command characteristic unless told another. */
    private suspend fun write(data: ByteArray, target: BluetoothGattCharacteristic? = null) {
        val characteristic = target ?: command
        if (state != LinkState.READY || characteristic == null) throw BedJetError.NotConnected
        val done = CompletableDeferred<Int>()
        val operation = Operation(begin = { it.write(characteristic, data) }, finish = { _, status -> done.complete(status) })
        enqueue(operation)
        val status = withTimeoutOrNull(OPERATION_TIMEOUT_MS) { done.await() } ?: run { abandon(operation); throw BedJetError.Timeout }
        if (status != BluetoothGatt.GATT_SUCCESS) throw BedJetError.NotConnected
    }

    private suspend fun exchange(request: Int): ByteArray {
        if (state != LinkState.READY || command == null) throw BedJetError.NotConnected
        // Listen before writing, since the reply can arrive before the write is acknowledged.
        val pending = PendingReply(request, CompletableDeferred())
        pendingReply = pending
        try {
            write(CodecV2.request(request))
            return withTimeoutOrNull(OPERATION_TIMEOUT_MS) { pending.reply.await() } ?: throw BedJetError.Timeout
        } finally {
            if (pendingReply === pending) pendingReply = null
        }
    }

    private suspend fun read(): ByteArray {
        val characteristic = reply
        if (state != LinkState.READY || characteristic == null) throw BedJetError.NotConnected
        val done = CompletableDeferred<Pair<ByteArray?, Int>>()
        val operation = Operation(begin = { it.readCharacteristic(characteristic) }, finish = { value, status -> done.complete(value to status) })
        enqueue(operation)
        val (value, status) = withTimeoutOrNull(OPERATION_TIMEOUT_MS) { done.await() } ?: run { abandon(operation); throw BedJetError.Timeout }
        if (status == LINK_GONE) throw BedJetError.NotConnected
        return value ?: throw BedJetError.BadResponse
    }

    /** An operation the unit never answered stops blocking the ones behind it, and is never sent late. */
    private fun abandon(operation: Operation) {
        if (current === operation) {
            current = null
            runNext()
        } else {
            operations.remove(operation)
        }
    }

    private fun BluetoothGatt.write(characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        // Writes are acknowledged, unless a characteristic only takes them without.
        val properties = characteristic.properties
        val type = if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            writeCharacteristic(characteristic, data, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            characteristic.writeType = type
            @Suppress("DEPRECATION")
            writeCharacteristic(characteristic)
        }
    }

    private fun BluetoothGatt.subscribe(characteristic: BluetoothGattCharacteristic): Boolean {
        setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG) ?: return false
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            writeDescriptor(descriptor, enable) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = enable
            @Suppress("DEPRECATION")
            writeDescriptor(descriptor)
        }
    }

    /**
     * Notifications carry the live readings. When the link's packet size is too small for the whole
     * status, the unit flags the notification as partial and serves the remainder (program progress,
     * LED and beep settings) to a read of the same characteristic.
     */
    private fun receiveStatus(chunk: ByteArray) {
        if (!Codec.isStatusStart(chunk)) {
            val head = lastHead ?: return
            Codec.parseStatus(head + chunk)?.extra?.let { lastExtra = it }
            return
        }
        var status = Codec.parseStatus(chunk) ?: return
        if (status.extra != null) {
            lastExtra = status.extra
        } else {
            status = status.copy(extra = lastExtra)
            lastHead = chunk
            val characteristic = statusCharacteristic
            if (characteristic != null && SystemClock.elapsedRealtime() - lastRemainderRead > 2000) {
                lastRemainderRead = SystemClock.elapsedRealtime()
                enqueue(Operation(begin = { it.readCharacteristic(characteristic) }, finish = { value, _ ->
                    if (value != null) receiveStatus(value)
                }))
            }
        }
        lastStatus = status
        onStatus?.invoke(status)
    }

    /** A V2 streams packets that notifications may split, and answers requests among its status updates. */
    private fun receiveUart(chunk: ByteArray) {
        val split = CodecV2.takePackets(uartBuffer + chunk)
        uartBuffer = split.rest
        for (packet in split.packets) {
            CodecV2.parseStatus(packet)?.let { status ->
                lastStatus = status
                onStatus?.invoke(status)
            }
            val name = CodecV2.reply(CodecV2.Query.DEVICE_NAME, packet)?.let(CodecV2::parseName)
            if (!name.isNullOrEmpty()) rememberName(name)
            val pending = pendingReply ?: continue
            val payload = CodecV2.reply(pending.query, packet) ?: continue
            pendingReply = null
            pending.reply.complete(payload)
        }
    }

    private fun seconds(since: Long): Double = (SystemClock.elapsedRealtime() - since) / 1000.0

    private fun discovered(gatt: BluetoothGatt) {
        servicesAfter = askedAt?.let { seconds(it) } ?: 0.0
        val bedJet = gatt.getService(SERVICE)
        val uart = gatt.getService(UART_SERVICE)
        if (bedJet != null) {
            generation = Generation.V3
            command = bedJet.getCharacteristic(COMMAND)
            reply = bedJet.getCharacteristic(REPLY)
            wifiNetwork = bedJet.getCharacteristic(WIFI_NETWORK)
            wifiPassword = bedJet.getCharacteristic(WIFI_PASSWORD)
            statusCharacteristic = bedJet.getCharacteristic(STATUS)
        } else if (uart != null) {
            generation = Generation.V2
            command = uart.getCharacteristic(UART_WRITE)
            statusCharacteristic = uart.getCharacteristic(UART_NOTIFY)
        } else {
            gatt.disconnect()
            return
        }
        if (command == null || (reply == null && generation == Generation.V3)) {
            gatt.disconnect()
            return
        }
        statusCharacteristic?.let { status -> enqueue(Operation(begin = { it.subscribe(status) }, finish = { _, _ -> })) }
        bedJet?.getCharacteristic(NAME)?.let { name ->
            enqueue(Operation(begin = { it.readCharacteristic(name) }, finish = { value, _ ->
                if (value != null && value.isNotEmpty()) rememberName(Codec.parseText(value))
            }))
        }
        // Only connections somebody asked for are timed, not quiet reconnects after a hand-over.
        lastConnection = askedAt?.let { asked ->
            linkUpAfter?.let { ConnectionTiming(radioOnAfter, it, servicesAfter, seconds(asked), knownUnit = !foundByScan) }
        }
        askedAt = null
        linkUpAfter = null
        state = LinkState.READY
        watchForRequests()
        if (generation == Generation.V2) {
            // The official app asks for the unit's details first thing, and the name is needed for hand-overs.
            scope.launch {
                runCatching { query(CodecV2.Query.INFO) }
                runCatching { deviceName() }
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (gatt !== this@BleLink.gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectGeneration += 1
                    skipsRememberedUnit = false
                    // Qualified: inside this initializer, plain `unit` is the constructor's parameter.
                    if (this@BleLink.unit == null) {
                        this@BleLink.unit = gatt.device.address
                        adoptLegacyName(gatt.device.address)
                    }
                    linkUpAfter = askedAt?.let { seconds(it) }
                    // A larger packet fits the whole status in one notification; the unit copes either way.
                    if (!gatt.requestMtu(64)) gatt.discoverServices()
                } else {
                    gatt.close()
                    dropConnection(reconnect = wantsConnection)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (gatt === this@BleLink.gatt) gatt.discoverServices() }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post { if (gatt === this@BleLink.gatt) discovered(gatt) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post { if (gatt === this@BleLink.gatt) complete(null, status) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post { if (gatt === this@BleLink.gatt) complete(null, status) }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val copy = value.copyOf()
            main.post { if (gatt === this@BleLink.gatt) complete(if (status == BluetoothGatt.GATT_SUCCESS) copy else null, status) }
        }

        @Deprecated("Used before Android 13")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            when (characteristic.uuid) {
                STATUS -> main.post { if (gatt === this@BleLink.gatt) receiveStatus(copy) }
                UART_NOTIFY -> main.post { if (gatt === this@BleLink.gatt) receiveUart(copy) }
            }
        }

        @Deprecated("Used before Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value: ByteArray? = characteristic.value
            if (value != null) onCharacteristicChanged(gatt, characteristic, value)
        }
    }
}
