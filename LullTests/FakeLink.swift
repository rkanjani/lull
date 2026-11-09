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
    private(set) var sent: [Data] = []
    private(set) var stops = 0
    /// How many times another phone was asked to hand the BedJet over.
    private(set) var requests = 0
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
        if heldElsewhere {
            onStateChange?(.yielded)
            return
        }
        device.answers = answers
        device.start()
    }

    func stop() {
        stops += 1
        device.stop()
    }

    func requestControl() {
        requests += 1
        heldElsewhere = false
        start()
    }

    func send(_ command: Data) async throws {
        if holdsSends { await withCheckedContinuation { held.append($0) } }
        guard state == .ready else { throw BedJetError.notConnected }
        sent.append(command)
        try await device.send(command)
    }

    func query(_ request: UInt8) async throws -> Data {
        guard state == .ready else { throw BedJetError.notConnected }
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
