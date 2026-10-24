// Live Activities are iPhone only, and other targets build the Shared folder too, such as a watch app.
#if canImport(ActivityKit)
import ActivityKit
import BedJetKit
import Foundation

/// Lull's Live Activity. The Dynamic Island shows only one of an app's Live Activities, so with a BedJet on each side of
/// the bed one Live Activity holds them both, rather than one each with only one ever in the island.
struct BedJetActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// Each BedJet running from this phone, in the order Lull lists them.
        var sides: [Side]
        var useFahrenheit: Bool

        func differsEnough(from shown: ContentState) -> Bool {
            sides.map(\.id) != shown.sides.map(\.id) || useFahrenheit != shown.useFahrenheit
                || zip(sides, shown.sides).contains { $0.differsEnough(from: $1) }
        }

        /// When what's shown may stop being true unless Lull updates it: the soonest of its BedJets'.
        func staleDate(at now: Date) -> Date? {
            sides.map { $0.status.staleDate(at: now) }.min()
        }

        /// What a phase is set to, such as "75° · Fan 40%".
        func detail(of phase: Phase, on side: Side) -> String {
            phase.setting(on: side.generation, fahrenheit: useFahrenheit)
        }
    }

    /// One BedJet in the Live Activity.
    struct Side: Codable, Hashable, Identifiable {
        /// The BedJet's own ID, so a tap opens Lull on it.
        var id: UUID
        var status: LiveStatus
        /// What the BedJet is called on screen, which can change while it runs.
        var name: String
        var generation: Generation

        func differsEnough(from shown: Side) -> Bool {
            status.differsEnough(from: shown.status) || name != shown.name || generation != shown.generation
        }
    }

    /// The most BedJets one Live Activity shows. The Dynamic Island has room for one on each side of the camera.
    static let maxSides = 2
}

#endif
