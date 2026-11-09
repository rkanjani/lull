import BedJetKit
import Foundation

/// A BedJet for tests. It speaks like the simulated one, but connects only when allowed, records every command,
/// can hold a command in flight, and keeps its state between connections like a real unit. It can be held by another
/// phone, and then only connects when asked for.
@MainActor
final class FakeLink: BedJetLink {
    var device: DemoLink { didSet { attach() } }
    var generation: Generation = .v3
    var answers = true
    /// Another phone has the BedJet: it connects only once asked for, as a hand-over request does.
    var heldElsewhere = false
    var holdsSends = false
    /// Off, the unit takes commands but its reports never arrive, as when Lull lets go of it first.
    var reports = true
    /// On, letting go takes a moment, as over Bluetooth: the link still says it's ready until the connection drops, but
    /// nothing sent on it arrives, and asking for the unit meanwhile connects once it has dropped.
    var dropsSlowly = false
    private var isDropping = false
    private var connectsAfterDrop = false
    private(set) var sent: [Data] = []
    private(set) var stops = 0
    /// How often Lull asked for the unit, which over Bluetooth asks another phone to hand it over too.
    private(set) var starts = 0
    /// How many times another phone was asked to hand the BedJet over.
    private(set) var requests = 0
    /// How often Lull connected only if the unit was free.
    private(set) var listens = 0
    private var held: [CheckedContinuation<Void, Never>] = []
    var isHolding: Bool { !held.isEmpty }
    var heldCount: Int { held.count }

    var onStateChange: ((LinkState) -> Void)?
    var onStatus: ((DeviceStatus) -> Void)?
    var state: LinkState { heldElsewhere ? .yielded : device.state }
    var lastStatus: DeviceStatus? { device.lastStatus }

    init(name: String) {
        device = DemoLink(name: name)
        attach()
    }

    private func attach() {
        device.onStateChange = { [weak self] in self?.onStateChange?($0) }
        device.onStatus = { [weak self] in if self?.reports == true { self?.onStatus?($0) } }
    }

    func start() {
        starts += 1
        connect()
    }

    func listen() {
        listens += 1
        connect()
    }

    private func connect() {
        if heldElsewhere {
            onStateChange?(.yielded)
            return
        }
        if isDropping {
            connectsAfterDrop = true
            return
        }
        device.answers = answers
        device.start()
    }

    func stop() {
        stops += 1
        connectsAfterDrop = false
        guard dropsSlowly, state == .ready else { return device.stop() }
        isDropping = true
        Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            isDropping = false
            device.stop()
            if connectsAfterDrop { connect() }
        }
    }

    func writeWiFi(network: Data, password: Data) async throws {
        try await device.writeWiFi(network: network, password: password)
    }

    func requestControl() {
        requests += 1
        heldElsewhere = false
        connect()
    }

    func send(_ command: Data) async throws {
        if holdsSends { await withCheckedContinuation { held.append($0) } }
        guard state == .ready, !isDropping else { throw BedJetError.notConnected }
        sent.append(command)
        try await device.send(command)
    }

    func query(_ request: UInt8) async throws -> Data {
        guard state == .ready, !isDropping else { throw BedJetError.notConnected }
        return try await device.query(request)
    }

    func release() {
        holdsSends = false
        let waiting = held
        held = []
        for continuation in waiting { continuation.resume() }
    }

    /// Lets only the oldest command in flight through; the rest stay held.
    func releaseFirst() {
        guard !held.isEmpty else { return }
        held.removeFirst().resume()
    }

    /// A status report, however late it arrives.
    func report(_ status: DeviceStatus) { onStatus?(status) }
}
