// The watch apps reach the BedJet through the phone, and watchOS can't advertise a hand-over request
// (`CBPeripheralManager`), so Bluetooth is left out there.
#if !os(watchOS)
@preconcurrency import CoreBluetooth
import CryptoKit
import Foundation

/// CoreBluetooth transport for a BedJet 3 or V2. A link made for a unit only ever connects to that unit, so
/// several can run side by side, one per BedJet, and none silently swaps in another BedJet. Without a unit
/// (`bedjetctl`) it takes the first one found.
///
/// The BedJet 3 has its own GATT service. The V2 sends its serial protocol (`CodecV2`) through a
/// Microchip "transparent UART" service, which other gadgets use too, so a V2 is recognised by its name.
///
/// The BedJet accepts a single connection and stops advertising while it has one, so a second phone
/// cannot even see it. Two phones share it by handing over: the phone that wants the BedJet briefly
/// advertises a request, and the phone holding it disconnects when it sees that request.
@MainActor
public final class BLELink: NSObject, BedJetLink {
    public private(set) var state: LinkState = .searching {
        didSet {
            guard state != oldValue else { return }
            if state == .ready { stopRequesting() }
            onStateChange?(state)
        }
    }
    public private(set) var generation: Generation = .v3
    public private(set) var lastStatus: DeviceStatus?
    public var onStateChange: ((LinkState) -> Void)?
    public var onStatus: ((DeviceStatus) -> Void)?
    /// Return false to postpone a hand-over, for example while a schedule is being saved.
    public var canYield: () -> Bool = { true }
    public private(set) var lastConnection: ConnectionTiming?
    /// The unit this link belongs to: the one it was made for, or the first found by a link made without one.
    public private(set) var unit: UUID?

    private static func uuid(_ short: String) -> CBUUID { CBUUID(string: "0000\(short)-BED0-0080-AA55-4265644A6574") }
    private static let service = uuid("1000")
    private static let statusUUID = uuid("2000")
    private static let nameUUID = uuid("2001")
    private static let commandUUID = uuid("2004")
    private static let replyUUID = uuid("2006")
    private static let uartService = CBUUID(string: "49535343-FE7D-4AE5-8FA9-9FAFD205E455")
    private static let uartNotifyUUID = CBUUID(string: "49535343-1E4D-4BD9-BA61-23C647249616")
    private static let uartWriteUUID = CBUUID(string: "49535343-8841-43F4-A8D4-ECBE34729BB3")
    /// Where versions that knew a single BedJet remembered it.
    private static let legacyUnitKey = "BedJetKit.peripheral"
    private static let legacyNameKey = "BedJetKit.deviceName"

    /// How long a phone that handed over stays out of the way before it quietly waits for the BedJet again.
    private static let courtesy: TimeInterval = 10
    /// How long a request is advertised. Shorter than `courtesy`, so one request is answered once.
    private static let requestDuration: TimeInterval = 8
    /// How long a known unit gets to accept a connection before Lull scans for it instead.
    /// Longer than `requestDuration`, so another phone has time to hand the unit over.
    private static let connectPatience: TimeInterval = 12

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var command: CBCharacteristic?
    private var reply: CBCharacteristic?
    private var wantsConnection = false
    private var yieldedUntil = Date.distantPast
    private var requestGeneration = 0
    private var askedAt: Date?
    private var linkUpAfter: TimeInterval?
    private var radioOnAfter: TimeInterval = 0
    private var servicesAfter: TimeInterval = 0
    private var foundByScan = false
    private var skipsRememberedUnit = false
    private var connectGeneration = 0

    private var statusCharacteristic: CBCharacteristic?
    private var lastHead: Data?
    private var lastRemainderRead = Date.distantPast
    private var lastExtra: DeviceStatus.Extra?
    private var tag: UInt8 = 0
    private var queue: Task<Void, Never>?
    private var pendingWrite: CheckedContinuation<Void, Error>?
    private var pendingRead: CheckedContinuation<Data, Error>?
    /// A V2 answers through the same notifications that carry its status.
    private var pendingReply: (query: UInt8, continuation: CheckedContinuation<Data, Error>)?
    private var uartBuffer = Data()
    private var operation = 0

    /// The unit found by an earlier version of Lull, for carrying over to a link made with `init(unit:)`.
    public static func legacyUnit(in defaults: UserDefaults = .standard) -> (unit: UUID, name: String?)? {
        guard let unit = defaults.string(forKey: legacyUnitKey).flatMap(UUID.init) else { return nil }
        return (unit, defaults.string(forKey: legacyNameKey))
    }

    /// Whether a unit found by scanning is the one a link is for. Another BedJet is never taken in its place,
    /// even when the right one is away: it could be a partner's.
    public static func takes(_ found: UUID, boundTo unit: UUID?) -> Bool {
        unit.map { $0 == found } ?? true
    }

    /// - Parameter unit: The BedJet to connect to. Without one, the first BedJet found is used.
    public init(unit: UUID? = nil) {
        self.unit = unit
        super.init()
        if let unit { Self.adoptLegacyName(for: unit) }
    }

    public func start() {
        requestControl()
    }

    /// Connects whenever the BedJet is free, without asking another phone to hand it over.
    public func listen() {
        wantsConnection = true
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
        } else {
            connectIfPossible()
        }
    }

    public func stop() {
        wantsConnection = false
        askedAt = nil
        stopRequesting()
        central?.stopScan()
        yieldedUntil = .distantPast
        skipsRememberedUnit = false
        if let peripheral { central?.cancelPeripheralConnection(peripheral) }
        if state != .ready {
            // Nothing is connected, so no disconnect will arrive to tidy up.
            peripheral = nil
            state = .searching
        }
    }

    public func requestControl() {
        wantsConnection = true
        let wasYielded = state == .yielded
        if wasYielded {
            yieldedUntil = .distantPast
            state = .searching
        }
        if state != .ready, askedAt == nil {
            askedAt = Date()
            radioOnAfter = 0
        }
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
        } else {
            // A phone that handed over may have been waiting on the unit for a long time. Now somebody is waiting too.
            if wasYielded, let peripheral { expectAnswer(from: peripheral) }
            connectIfPossible()
        }
        guard state != .ready, let request = handoffUUID else { return }
        // Ask straight away rather than waiting to see whether the BedJet answers: if it is free
        // nobody is listening, and if another phone has it every moment of waiting is wasted.
        requestGeneration += 1
        let generation = requestGeneration
        advertise(request)
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.requestDuration * 1_000_000_000))
            if let self, self.requestGeneration == generation { self.stopRequesting() }
        }
    }

    /// Drops what was kept to ask another phone for this unit. The unit itself is left as it is.
    public static func forget(_ unit: UUID) {
        UserDefaults.standard.removeObject(forKey: nameKey(of: unit))
    }

    public func send(_ data: Data) async throws {
        try await serially { try await self.write(data) }
    }

    public func query(_ request: UInt8) async throws -> Data {
        if generation == .v2 {
            return try await serially {
                // A request the unit missed is simply asked again.
                do { return try await self.exchange(request) } catch BedJetError.timeout { return try await self.exchange(request) }
            }
        }
        return try await serially {
            // The reply characteristic keeps its previous contents until the unit has answered,
            // so retry until the echoed request and tag match.
            for attempt in 0..<4 {
                self.tag = self.tag == 255 ? 1 : self.tag + 1
                try await self.write(Codec.query(request, tag: self.tag))
                if attempt > 0 { try await Task.sleep(nanoseconds: 150_000_000) }
                let data = try await self.read()
                if data.count >= 2, data[data.startIndex] == request, data[data.startIndex + 1] == self.tag {
                    return data.dropFirst(2)
                }
            }
            throw BedJetError.badResponse
        }
    }

    // MARK: Connection

    private func connectIfPossible() {
        guard wantsConnection, let central, central.state == .poweredOn, peripheral == nil else { return }
        if state == .yielded, Date() < yieldedUntil { return }
        if !skipsRememberedUnit, let unit, let known = central.retrievePeripherals(withIdentifiers: [unit]).first {
            // Going straight to the unit this phone already knows is several times quicker than scanning for it.
            foundByScan = false
            connect(known)
        } else {
            if state != .yielded { state = .searching }
            foundByScan = true
            // Not filtered by service, because a V2 is known by its name rather than by what it advertises.
            central.scanForPeripherals(withServices: nil)
        }
    }

    private func connect(_ target: CBPeripheral) {
        central?.stopScan()
        peripheral = target
        target.delegate = self
        // A phone that handed over keeps saying so while it waits; the connection completes
        // whenever the BedJet becomes free again.
        if state != .yielded { state = .connecting }
        central?.connect(target)
        expectAnswer(from: target)
    }

    /// CoreBluetooth waits for a unit forever, and one that was reset or moved away may never answer
    /// that attempt. Give it a while, then scan for it instead.
    private func expectAnswer(from target: CBPeripheral) {
        connectGeneration += 1
        let generation = connectGeneration
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.connectPatience * 1_000_000_000))
            guard let self, self.connectGeneration == generation, self.wantsConnection, self.peripheral === target,
                  // A phone that handed over is meant to wait.
                  self.state == .connecting || self.state == .searching, let central = self.central else { return }
            central.cancelPeripheralConnection(target)
            self.peripheral = nil
            self.skipsRememberedUnit = true
            self.foundByScan = true
            self.state = .searching
            // Unfiltered, like the first scan, so a V2 can be found by its name.
            central.scanForPeripherals(withServices: nil)
        }
    }

    private func dropConnection() {
        peripheral = nil
        command = nil
        reply = nil
        statusCharacteristic = nil
        lastHead = nil
        lastExtra = nil
        lastStatus = nil
        uartBuffer.removeAll()
        pendingReply?.continuation.resume(throwing: BedJetError.notConnected)
        pendingReply = nil
        pendingWrite?.resume(throwing: BedJetError.notConnected)
        pendingWrite = nil
        pendingRead?.resume(throwing: BedJetError.notConnected)
        pendingRead = nil
        central?.stopScan()
        if state != .yielded { state = .searching }
        connectIfPossible()
    }

    // MARK: Hand-over between phones

    /// Requests are specific to one BedJet, so a neighbour's phone looking for theirs is ignored.
    private var handoffUUID: CBUUID? {
        unit.flatMap { UserDefaults.standard.string(forKey: Self.nameKey(of: $0)) }.map(Self.handoffUUID(forDeviceNamed:))
    }

    private static func nameKey(of unit: UUID) -> String { "\(legacyNameKey).\(unit.uuidString)" }

    /// The name is needed to ask for the BedJet before it has connected, so it carries over from
    /// versions that remembered one BedJet.
    private static func adoptLegacyName(for unit: UUID) {
        guard UserDefaults.standard.string(forKey: nameKey(of: unit)) == nil, let legacy = legacyUnit(),
              legacy.unit == unit, let name = legacy.name else { return }
        UserDefaults.standard.set(name, forKey: nameKey(of: unit))
    }

    /// The unit's own name identifies it to the other phone during a hand-over.
    private func rememberName(_ name: String) {
        guard let unit else { return }
        UserDefaults.standard.set(name, forKey: Self.nameKey(of: unit))
        watchForRequests()
    }

    static func handoffUUID(forDeviceNamed name: String) -> CBUUID {
        var bytes = Array(SHA256.hash(data: Data("lull.handoff.\(name)".utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0f) | 0x50
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        return CBUUID(data: Data(bytes))
    }

    private func advertise(_ request: CBUUID) {
        HandoffRequests.shared.set(request, for: self)
    }

    private func stopRequesting() {
        requestGeneration += 1
        HandoffRequests.shared.set(nil, for: self)
    }

    /// While holding the BedJet, listen for another phone asking for it.
    private func watchForRequests() {
        guard state == .ready, let central, let request = handoffUUID else { return }
        central.stopScan()
        central.scanForPeripherals(withServices: [request])
    }

    private func handOver(attempt: Int = 0) {
        guard state == .ready, let peripheral else { return }
        guard canYield(), pendingWrite == nil, pendingRead == nil else {
            // Finish what is in flight first. If that takes too long, keep the BedJet and listen again.
            guard attempt < 15 else { return watchForRequests() }
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 300_000_000)
                self?.handOver(attempt: attempt + 1)
            }
            return
        }
        yieldedUntil = Date().addingTimeInterval(Self.courtesy)
        state = .yielded
        central?.cancelPeripheralConnection(peripheral)
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.courtesy * 1_000_000_000))
            self?.connectIfPossible()
        }
    }

    // MARK: Operations

    /// The unit handles one request at a time, so everything goes through a single queue.
    private func serially<T>(_ work: @escaping @MainActor () async throws -> T) async throws -> T {
        let previous = queue
        let task = Task { @MainActor () throws -> T in
            await previous?.value
            return try await work()
        }
        queue = Task { _ = try? await task.value }
        return try await task.value
    }

    private func write(_ data: Data) async throws {
        guard state == .ready, let peripheral, let command else { throw BedJetError.notConnected }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            pendingWrite = continuation
            peripheral.writeValue(data, for: command, type: .withResponse)
            expire { if let pending = $0.pendingWrite { $0.pendingWrite = nil; pending.resume(throwing: BedJetError.timeout) } }
        }
    }

    private func read() async throws -> Data {
        guard state == .ready, let peripheral, let reply else { throw BedJetError.notConnected }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            pendingRead = continuation
            peripheral.readValue(for: reply)
            expire { if let pending = $0.pendingRead { $0.pendingRead = nil; pending.resume(throwing: BedJetError.timeout) } }
        }
    }

    private func exchange(_ request: UInt8) async throws -> Data {
        guard state == .ready, let peripheral, let command else { throw BedJetError.notConnected }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            // Listen before writing, since the reply can arrive before the write is acknowledged.
            pendingReply = (request, continuation)
            peripheral.writeValue(CodecV2.request(request), for: command, type: .withResponse)
            expire { if let pending = $0.pendingReply { $0.pendingReply = nil; pending.continuation.resume(throwing: BedJetError.timeout) } }
        }
    }

    private func expire(_ fail: @escaping @MainActor (BLELink) -> Void) {
        operation += 1
        let current = operation
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard let self, self.operation == current else { return }
            fail(self)
        }
    }

    /// Notifications carry the live readings. When the link's packet size is too small for the whole
    /// status, the unit flags the notification as partial and serves the remainder (program progress,
    /// LED and beep settings) to a read of the same characteristic.
    private func receiveStatus(_ chunk: Data) {
        guard Codec.isStatusStart(chunk) else {
            if let head = lastHead, let extra = Codec.parseStatus(head + chunk)?.extra { lastExtra = extra }
            return
        }
        guard var status = Codec.parseStatus(chunk) else { return }
        defer { lastStatus = status; onStatus?(status) }
        if let extra = status.extra {
            lastExtra = extra
        } else {
            status.extra = lastExtra
            lastHead = chunk
            if let peripheral, let statusCharacteristic, Date().timeIntervalSince(lastRemainderRead) > 2 {
                lastRemainderRead = Date()
                peripheral.readValue(for: statusCharacteristic)
            }
        }
    }

    /// A V2 streams packets that notifications may split, and answers requests among its status updates.
    private func receiveUART(_ chunk: Data) {
        uartBuffer.append(chunk)
        for packet in CodecV2.takePackets(from: &uartBuffer) {
            if let status = CodecV2.parseStatus(packet) {
                lastStatus = status
                onStatus?(status)
            }
            if let name = CodecV2.reply(to: CodecV2.Query.deviceName, in: packet).map(CodecV2.parseName), !name.isEmpty {
                rememberName(name)
            }
            if let pending = pendingReply, let payload = CodecV2.reply(to: pending.query, in: packet) {
                pendingReply = nil
                pending.continuation.resume(returning: payload)
            }
        }
    }

    static func isBedJet(_ peripheral: CBPeripheral, advertisement: [String: Any]) -> Bool {
        let services = advertisement[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        if services.contains(service) { return true }
        let name = advertisement[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? ""
        return name.uppercased().hasPrefix("BEDJET")
    }

    /// What can be told from an advertisement. A V2 is recognised by its name, which proves less.
    static func generation(advertisement: [String: Any]) -> Generation? {
        let services = advertisement[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        if services.contains(service) { return .v3 }
        if services.contains(uartService) { return .v2 }
        return nil
    }
}

extension BLELink: CBCentralManagerDelegate, CBPeripheralDelegate {
    public nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn:
                radioOnAfter = askedAt.map { Date().timeIntervalSince($0) } ?? 0
                connectIfPossible()
            case .unauthorized: state = .unauthorized
            case .poweredOff, .unsupported: state = .bluetoothOff
            default: break
            }
        }
    }

    public nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                           advertisementData: [String: Any], rssi RSSI: NSNumber) {
        MainActor.assumeIsolated {
            if state == .ready {
                // The only thing scanned for while connected is another phone asking for this BedJet.
                let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
                if let request = handoffUUID, services.contains(request) { handOver() }
                return
            }
            guard self.peripheral == nil, Self.isBedJet(peripheral, advertisement: advertisementData),
                  Self.takes(peripheral.identifier, boundTo: unit) else { return }
            connect(peripheral)
        }
    }

    public nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated {
            connectGeneration += 1
            skipsRememberedUnit = false
            if unit == nil {
                unit = peripheral.identifier
                Self.adoptLegacyName(for: peripheral.identifier)
            }
            linkUpAfter = askedAt.map { Date().timeIntervalSince($0) }
            peripheral.discoverServices([Self.service, Self.uartService])
        }
    }

    public nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            // A unit given up on may still report back; that attempt is already over.
            guard peripheral === self.peripheral else { return }
            dropConnection()
        }
    }

    public nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            guard peripheral === self.peripheral else { return }
            dropConnection()
        }
    }

    public nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            let services = peripheral.services ?? []
            servicesAfter = askedAt.map { Date().timeIntervalSince($0) } ?? 0
            if let service = services.first(where: { $0.uuid == Self.service }) {
                generation = .v3
                peripheral.discoverCharacteristics([Self.statusUUID, Self.nameUUID, Self.commandUUID, Self.replyUUID], for: service)
            } else if let service = services.first(where: { $0.uuid == Self.uartService }) {
                generation = .v2
                peripheral.discoverCharacteristics([Self.uartNotifyUUID, Self.uartWriteUUID], for: service)
            } else {
                central?.cancelPeripheralConnection(peripheral)
            }
        }
    }

    public nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            for characteristic in service.characteristics ?? [] {
                switch characteristic.uuid {
                case Self.statusUUID:
                    statusCharacteristic = characteristic
                    peripheral.setNotifyValue(true, for: characteristic)
                case Self.nameUUID: peripheral.readValue(for: characteristic)
                case Self.commandUUID, Self.uartWriteUUID: command = characteristic
                case Self.replyUUID: reply = characteristic
                case Self.uartNotifyUUID:
                    statusCharacteristic = characteristic
                    peripheral.setNotifyValue(true, for: characteristic)
                default: break
                }
            }
            guard command != nil, reply != nil || generation == .v2 else {
                central?.cancelPeripheralConnection(peripheral)
                return
            }
            // Only connections somebody asked for are timed, not quiet reconnects after a hand-over.
            lastConnection = askedAt.flatMap { askedAt in
                linkUpAfter.map {
                    ConnectionTiming(radioOn: radioOnAfter, linkUp: $0, services: servicesAfter,
                                     usable: Date().timeIntervalSince(askedAt), knownUnit: !foundByScan)
                }
            }
            askedAt = nil
            linkUpAfter = nil
            state = .ready
            watchForRequests()
            if generation == .v2 {
                // The official app asks for the unit's details first thing, and the name is needed for hand-overs.
                Task { @MainActor [weak self] in
                    _ = try? await self?.query(CodecV2.Query.info)
                    _ = try? await self?.deviceName()
                }
            }
        }
    }

    public nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            if let error, let pending = pendingReply {
                pendingReply = nil
                pending.continuation.resume(throwing: error)
            }
            guard let pending = pendingWrite else { return }
            pendingWrite = nil
            if let error { pending.resume(throwing: error) } else { pending.resume() }
        }
    }

    public nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            if characteristic.uuid == Self.statusUUID {
                if let value = characteristic.value { receiveStatus(value) }
            } else if characteristic.uuid == Self.uartNotifyUUID {
                if let value = characteristic.value { receiveUART(value) }
            } else if characteristic.uuid == Self.nameUUID {
                guard let value = characteristic.value, !value.isEmpty else { return }
                rememberName(Codec.parseText(value))
            } else if characteristic.uuid == Self.replyUUID, let pending = pendingRead {
                pendingRead = nil
                if let value = characteristic.value { pending.resume(returning: value) } else { pending.resume(throwing: error ?? BedJetError.badResponse) }
            }
        }
    }
}

/// The phone advertises one set of services at a time, so every link's request for its BedJet goes out together.
@MainActor
private final class HandoffRequests: NSObject, CBPeripheralManagerDelegate {
    static let shared = HandoffRequests()

    private var manager: CBPeripheralManager?
    private var requests: [ObjectIdentifier: CBUUID] = [:]

    func set(_ request: CBUUID?, for link: BLELink) {
        requests[ObjectIdentifier(link)] = request
        if manager == nil, request != nil { manager = CBPeripheralManager(delegate: self, queue: .main) }
        advertise()
    }

    private func advertise() {
        guard let manager, manager.state == .poweredOn else { return }
        manager.stopAdvertising()
        let services = Array(Set(requests.values))
        if !services.isEmpty { manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: services]) }
    }

    nonisolated func peripheralManagerDidUpdateState(_ manager: CBPeripheralManager) {
        MainActor.assumeIsolated { advertise() }
    }
}
#endif
