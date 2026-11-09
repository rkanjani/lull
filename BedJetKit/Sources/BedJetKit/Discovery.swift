@preconcurrency import CoreBluetooth
import Foundation

/// A BedJet advertising nearby, which can be chosen. It only advertises while no phone is connected to it.
public struct NearbyBedJet: Identifiable, Equatable, Sendable {
    /// The Bluetooth identifier this phone knows the unit by. It never changes, unlike the name.
    public let id: UUID
    public var name: String
    /// Known from what the unit advertises for a BedJet 3; a V2 is only certain once it has connected.
    public var generation: Generation?
    /// Signal strength in dBm. Closer usually reads stronger, but walls and bodies get in the way.
    public var rssi: Int

    public init(id: UUID, name: String, generation: Generation?, rssi: Int) {
        self.id = id
        self.name = name
        self.generation = generation
        self.rssi = rssi
    }

    public enum Signal: String, Sendable { case strong = "Strong", fair = "Fair", weak = "Weak" }

    public var signal: Signal { rssi >= -60 ? .strong : rssi >= -75 ? .fair : .weak }
}

/// Lists the BedJets advertising nearby without connecting to any of them, so one can be picked.
@MainActor
public protocol BedJetDiscovery: AnyObject {
    var nearby: [NearbyBedJet] { get }
    /// `.searching` while it can look, otherwise why it can't.
    var state: LinkState { get }
    var onChange: (() -> Void)? { get set }
    func start()
    func stop()
}

#if !os(watchOS)
/// Finds real BedJets over Bluetooth.
@MainActor
public final class BLEDiscovery: NSObject, BedJetDiscovery {
    public private(set) var nearby: [NearbyBedJet] = [] { didSet { if nearby != oldValue { onChange?() } } }
    public private(set) var state: LinkState = .searching { didSet { if state != oldValue { onChange?() } } }
    public var onChange: (() -> Void)?

    private var central: CBCentralManager?
    private var wantsScan = false

    public override init() { super.init() }

    public func start() {
        wantsScan = true
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
        } else {
            scan()
        }
    }

    public func stop() {
        wantsScan = false
        central?.stopScan()
        nearby = []
    }

    private func scan() {
        guard wantsScan, let central, central.state == .poweredOn else { return }
        // Not filtered by service, because a V2 is known by its name. Duplicates keep the signal current.
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }
}

extension BLEDiscovery: CBCentralManagerDelegate {
    public nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn:
                state = .searching
                scan()
            case .unauthorized: state = .unauthorized
            case .poweredOff, .unsupported: state = .bluetoothOff
            default: break
            }
        }
    }

    public nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                           advertisementData: [String: Any], rssi RSSI: NSNumber) {
        MainActor.assumeIsolated {
            guard wantsScan, BLELink.isBedJet(peripheral, advertisement: advertisementData) else { return }
            let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? "BedJet"
            // 127 means the reading wasn't available; keep the last one.
            let rssi = RSSI.intValue == 127 ? nil : RSSI.intValue
            let found = NearbyBedJet(id: peripheral.identifier, name: name,
                                     generation: BLELink.generation(advertisement: advertisementData),
                                     rssi: rssi ?? nearby.first { $0.id == peripheral.identifier }?.rssi ?? -100)
            if let index = nearby.firstIndex(where: { $0.id == found.id }) {
                nearby[index] = found
            } else {
                nearby.append(found)
            }
        }
    }
}
#endif

/// Simulated BedJets nearby, for demo mode and tests. They are all there as soon as it starts.
@MainActor
public final class DemoDiscovery: BedJetDiscovery {
    public private(set) var nearby: [NearbyBedJet] = []
    public var state: LinkState = .searching { didSet { onChange?() } }
    public var onChange: (() -> Void)?
    /// What a scan finds. Changing it while scanning is like a unit turning on or off.
    public var units: [NearbyBedJet] { didSet { if isScanning { show() } } }

    private var isScanning = false

    /// Two share the default name, as real units often do, so the app has to tell them apart.
    public nonisolated static let simulated = [
        NearbyBedJet(id: UUID(uuidString: "D3A0B1E7-0000-4000-8000-00000000DE01")!, name: DemoLink.defaultName, generation: .v3, rssi: -52),
        NearbyBedJet(id: UUID(uuidString: "5F2C9A41-0000-4000-8000-00000000DE02")!, name: DemoLink.defaultName, generation: .v3, rssi: -68),
        NearbyBedJet(id: UUID(uuidString: "8B7E44C2-0000-4000-8000-00000000DE03")!, name: "BEDJET-GUEST", generation: .v3, rssi: -81),
    ]

    public init(units: [NearbyBedJet] = DemoDiscovery.simulated) {
        self.units = units
    }

    public func start() {
        isScanning = true
        show()
    }

    public func stop() {
        isScanning = false
        nearby = []
        onChange?()
    }

    private func show() {
        nearby = units
        onChange?()
    }
}
