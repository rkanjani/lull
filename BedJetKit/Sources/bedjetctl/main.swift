import BedJetKit
import Foundation

// Read-only check against a real BedJet: prints its status and saved programs, changes nothing.
@MainActor
func run() {
    let link = BLELink()
    var printed = false
    link.onStatus = { status in
        guard !printed, status.extra != nil else { return }
        printed = true
        print("status:", status)
    }
    link.onStateChange = { state in
        print("link:", state)
        guard state == .ready else { return }
        Task { @MainActor in
            do {
                print("name:", try await link.deviceName())
                print("firmware:", try await link.firmwareVersion())
                for (slot, program) in try await link.storedPrograms().enumerated() {
                    print("slot \(slot + 1):", program.map { "\($0.name)" } ?? "empty")
                    for step in program?.steps ?? [] {
                        let length = step.endsAtClockTime ? String(format: "until %ld:%02ld", step.hours, step.minutes) : "for \(step.hours)h\(step.minutes)m"
                        print("   \(step.mode) fan \((step.fanStep + 1) * 5)% \(Double(step.temperature) / 2)C \(length)")
                    }
                }
            } catch {
                print("error:", error)
            }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            link.stop()
            exit(0)
        }
    }
    link.start()
}

Task { @MainActor in run() }
DispatchQueue.main.asyncAfter(deadline: .now() + 40) { print("timed out"); exit(1) }
RunLoop.main.run()
