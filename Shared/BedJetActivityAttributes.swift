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

        /// When what's shown may stop being true unless Lull updates it: the soonest of its BedJets', but for a BedJet
        /// that ends before another. There is only one stale date for the whole, and reaching it hides every night's
        /// phase, so one side's air running out would hide the other side's phase for the rest of the night. A BedJet's
        /// end only takes its countdown to 0:00, which it does by itself, and Lull takes it out when it next hears.
        func staleDate(at now: Date) -> Date? {
            let lastEnd = sides.map(\.status.endsAt).max()
            return sides.compactMap { side in
                let date = side.status.staleDate(at: now)
                return date < side.status.endsAt || side.status.endsAt == lastEnd ? date : nil
            }.min()
        }

        /// The most ActivityKit takes: content any larger fails to start or update the Live Activity, with nothing to
        /// show for it.
        static let maxBytes = 4096

        var size: Int { (try? JSONEncoder().encode(self).count) ?? .max }

        /// This, made to fit ActivityKit. Two of the longest nights fit with room to spare, so this is only a safeguard:
        /// the last BedJet is left out until it fits, rather than the Live Activity silently not changing.
        func fitted(within maxBytes: Int = maxBytes) -> ContentState? {
            var fitted = self
            while fitted.size > maxBytes {
                guard fitted.sides.count > 1 else { return nil }
                fitted.sides.removeLast()
            }
            return fitted
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
