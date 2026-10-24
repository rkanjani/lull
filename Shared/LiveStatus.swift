import BedJetKit
import Foundation

/// What a running BedJet is doing, as shown outside Lull.
/// Lull lets go of the BedJet whenever it leaves the screen, so this has to stay true by the clock alone: a night
/// plays out from when it started, and air from the Remote turns off when its timer runs out.
enum LiveStatus: Codable, Hashable {
    /// A schedule started from this phone.
    case night(ActiveRun)
    /// Air started from the Remote. The temperature is nil for modes this BedJet doesn't let you set it for.
    case air(mode: Mode, temperature: Int?, fanPercent: Int, endsAt: Date)

    /// What to show for a BedJet, or nil when it is off. A schedule another phone started isn't shown: its phases
    /// aren't known here, and its timer only counts down the step it is on.
    /// - Parameter reportedAt: When the BedJet sent `status`. The last report is kept while Lull is away from the
    ///   BedJet, so its timer counts from then, not from now.
    static func of(run: ActiveRun?, status: DeviceStatus?, reportedAt: Date? = nil, generation: Generation,
                   at now: Date = Date()) -> LiveStatus? {
        if let run, run.endsAt > now { return .night(run) }
        // Whether a schedule is running arrives in the second half of the report; until it does, it can't be told apart.
        guard let status, status.isRunning, status.remainingSeconds > 0, let extra = status.extra, extra.programStep == 0 else {
            return nil
        }
        let endsAt = (reportedAt ?? now).addingTimeInterval(TimeInterval(status.remainingSeconds))
        guard endsAt > now else { return nil }
        return .air(mode: status.mode,
                    temperature: generation.adjustsTemperature(status.mode) ? status.targetTemperature : nil,
                    fanPercent: status.fanPercent,
                    endsAt: endsAt)
    }

    var endsAt: Date {
        switch self {
        case let .night(run): run.endsAt
        case let .air(_, _, _, endsAt): endsAt
        }
    }

    /// When what's shown may stop being true unless Lull updates it: for a night, the end of the phase it is in,
    /// after which the phase is hidden and only the night's own progress, which is always right, stays.
    func staleDate(at now: Date) -> Date {
        switch self {
        case let .night(run): run.phaseEnds.first { $0 > now } ?? run.endsAt
        case let .air(_, _, _, endsAt): endsAt
        }
    }

    /// Whether a new report is worth redrawing for. The BedJet reports every second and its timer drifts against
    /// the phone's clock, so a shut-off time within a minute of the one shown is left alone.
    func differsEnough(from shown: LiveStatus) -> Bool {
        switch (self, shown) {
        case let (.night(run), .night(shownRun)):
            return run != shownRun
        case let (.air(mode, temperature, fan, endsAt), .air(shownMode, shownTemperature, shownFan, shownEndsAt)):
            return mode != shownMode || temperature != shownTemperature || fan != shownFan
                || abs(endsAt.timeIntervalSince(shownEndsAt)) > 60
        default:
            return true
        }
    }
}
