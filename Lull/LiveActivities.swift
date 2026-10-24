import ActivityKit
import Foundation

/// Keeps Lull's Live Activity up while a BedJet is running, so the night or the Remote's timer can be seen in the
/// Dynamic Island and on the Lock Screen. It holds every running BedJet, since the Dynamic Island shows only one of an
/// app's Live Activities. Lull can't run while it's closed, so it is drawn to carry on by the clock; it is brought up to
/// date whenever Lull hears from a BedJet.
@MainActor
final class LiveActivities {
    typealias State = BedJetActivityAttributes.ContentState

    enum Wanted {
        case show(BedJetActivityAttributes.Side)
        /// Lull hasn't heard from the BedJet since launching, so whatever is up for it stays until it does or runs out.
        case keep
    }

    /// What to do with the Live Activity that is already up or was swiped away.
    enum Step: Equatable {
        /// Still right, or swiped away with nothing new to show: left as it is.
        case leave
        case update
        /// Swiped away, and there's something new: a new one is started.
        case replace
        case end
    }

    /// Brings the Live Activity in line with the BedJets, in the order Lull lists them. A BedJet left out, being off or
    /// no longer known, is taken out of it, and it ends once none is left.
    func show(_ wanted: [(id: UUID, wanted: Wanted)], useFahrenheit: Bool, at now: Date = Date()) {
        let activities = Activity<BedJetActivityAttributes>.activities
        // The one up, or else the last one swiped away, which stays down until there's something new.
        let current = activities.first(where: Self.isUp) ?? activities.first
        let target = Self.state(for: wanted, useFahrenheit: useFahrenheit, shown: current?.content.state, at: now)
        // Any more are left over from an earlier start, and go.
        for extra in activities where extra.id != current?.id && Self.isUp(extra) {
            Task { await extra.end(nil, dismissalPolicy: .immediate) }
        }
        if let current {
            let isUp = Self.isUp(current)
            switch Self.step(for: target, shown: current.content, isUp: isUp, at: now) {
            case .leave:
                return
            case .update:
                if let target { Task { await current.update(content(target, at: now)) } }
                return
            case .replace:
                break
            case .end:
                if isUp { Task { await current.end(nil, dismissalPolicy: .immediate) } }
                return
            }
        }
        // Turned off in Settings: nothing new is started, rather than asking ActivityKit with every report.
        guard let target, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        // Too many already up: the app carries on without one.
        _ = try? Activity.request(attributes: BedJetActivityAttributes(), content: content(target, at: now))
    }

    /// What the Live Activity should show: each BedJet wanted, up to as many as fit, or nil when none is running.
    /// A BedJet Lull hasn't heard from yet keeps what was shown for it, until it runs out.
    static func state(for wanted: [(id: UUID, wanted: Wanted)], useFahrenheit: Bool, shown: State?, at now: Date) -> State? {
        let sides = wanted.compactMap { id, wanted -> BedJetActivityAttributes.Side? in
            switch wanted {
            case let .show(side): side
            case .keep: shown?.sides.first { $0.id == id && $0.status.endsAt > now }
            }
        }
        guard !sides.isEmpty else { return nil }
        return State(sides: Array(sides.prefix(BedJetActivityAttributes.maxSides)), useFahrenheit: useFahrenheit)
    }

    /// What to do with the Live Activity, given what it should show now.
    static func step(for target: State?, shown: ActivityContent<State>, isUp: Bool, at now: Date) -> Step {
        guard let target else { return .end }
        let isNew = target.differsEnough(from: shown.state)
        // Swiped away: left down until there is something new to show.
        guard isUp else { return isNew ? .replace : .leave }
        let isStale = shown.staleDate.map { $0 <= now } ?? false
        return isNew || isStale ? .update : .leave
    }

    private static func isUp(_ activity: Activity<BedJetActivityAttributes>) -> Bool {
        activity.activityState == .active || activity.activityState == .stale
    }

    private func content(_ state: State, at now: Date) -> ActivityContent<State> {
        ActivityContent(state: state, staleDate: state.staleDate(at: now))
    }
}
