import CoreLocation
import NetworkExtension
import Observation

/// The Wi-Fi network this iPhone is on, to offer it to a BedJet. An iPhone can't list the networks around it, and
/// seeing its own needs location access, which Lull asks for only on the Wi-Fi screen and uses for nothing else.
@MainActor
@Observable
final class WiFiNetworks: NSObject, CLLocationManagerDelegate {
    enum Access { case unknown, allowed, approximate, denied }

    private(set) var current: String?
    private(set) var access: Access = .unknown
    @ObservationIgnored private let location = CLLocationManager()
    private let isDemo: Bool

    /// Demo BedJets get a pretend network, so the screen can be explored without location access.
    init(isDemo: Bool) {
        self.isDemo = isDemo
        super.init()
        if isDemo {
            current = "Home"
            access = .allowed
        }
    }

    /// Asks for location the first time, then looks up the network once it's allowed.
    func look() {
        guard !isDemo else { return }
        location.delegate = self
        switch location.authorizationStatus {
        case .notDetermined:
            access = .unknown
            location.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            // The network name counts as precise location.
            guard location.accuracyAuthorization == .fullAccuracy else {
                access = .approximate
                return
            }
            access = .allowed
            NEHotspotNetwork.fetchCurrent { network in
                let name = network?.ssid
                Task { @MainActor in self.current = name }
            }
        default:
            access = .denied
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in if self.access != .allowed { self.look() } }
    }
}
