import Foundation

/// A schedule that is running on the BedJet right now.
struct ActiveRun: Codable, Hashable {
    var schedule: Schedule
    var startedAt: Date

    var endsAt: Date { startedAt.addingTimeInterval(TimeInterval(schedule.totalMinutes * 60)) }

    func progress(at date: Date) -> Double {
        min(max(date.timeIntervalSince(startedAt) / max(endsAt.timeIntervalSince(startedAt), 1), 0), 1)
    }

    func phase(at date: Date) -> (phase: Phase, index: Int, minutesLeft: Int)? {
        var elapsed = Int(date.timeIntervalSince(startedAt) / 60)
        for (index, phase) in schedule.phases.enumerated() {
            if elapsed < phase.minutes { return (phase, index, phase.minutes - elapsed) }
            elapsed -= phase.minutes
        }
        return nil
    }

    /// When each phase ends, the last of them being the end of the night.
    var phaseEnds: [Date] {
        var end = startedAt
        return schedule.phases.map { phase in
            end = end.addingTimeInterval(TimeInterval(phase.minutes * 60))
            return end
        }
    }
}
