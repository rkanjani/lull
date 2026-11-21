import BedJetKit
import SwiftUI

/// The capsule button for a page's main action, sized for the wrist: the iPhone's `PrimaryButtonStyle`, smaller.
struct WatchButtonStyle: ButtonStyle {
    var prominent = true
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaledFont(16, .semibold)
            .foregroundStyle(prominent ? Color.black : Color.white)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(prominent ? Color.white : Theme.raised, in: Capsule())
            .overlay(Capsule().strokeBorder(prominent ? Color.clear : Theme.hairline))
            .opacity(isEnabled ? 1 : 0.4)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.snappy(duration: 0.2), value: configuration.isPressed)
    }
}

/// A page's main action, which waits for the iPhone once tapped: "Start Night", then "Starting…".
struct ErrandButton: View {
    let title: String
    let busyTitle: String
    var prominent = true
    /// Off when there's nothing to send, such as the Remote before anything on it has changed.
    var isEnabled = true
    let command: WatchCommand
    @Environment(WatchModel.self) private var model

    var body: some View {
        let isBusy = model.isSending(command)
        Button {
            Task { await model.send(command) }
        } label: {
            HStack(spacing: 6) {
                if isBusy { ProgressView().frame(width: 18, height: 18) }
                Text(isBusy ? busyTitle : title)
            }
        }
        .buttonStyle(WatchButtonStyle(prominent: prominent))
        .disabled(!isEnabled || model.pending != nil || model.unavailable != nil)
        .accessibilityLabel(isBusy ? busyTitle : title)
    }
}

/// Why the controls are off, under them.
struct Footnote: View {
    let text: String

    var body: some View {
        Text(text)
            .scaledFont(12)
            .foregroundStyle(Theme.tertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A title and a line under it, for a page with nothing to show yet.
struct EmptyMessage: View {
    let symbol: String
    let title: String
    let message: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: symbol)
                .scaledFont(20, .semibold)
                .foregroundStyle(Theme.secondary)
            Text(title).scaledFont(16, .semibold)
            Text(message)
                .scaledFont(13)
                .foregroundStyle(Theme.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// A setting the Digital Crown turns once it's tapped, with − and + for a finger. It reads as one adjustable control to
/// VoiceOver, as the iPhone's sliders do.
struct CrownRow: View {
    let title: String
    let valueText: String
    /// What VoiceOver reads for the value, when the shown text abbreviates it.
    var spokenValue: String?
    @Binding var value: Double
    let range: ClosedRange<Double>
    var step: Double = 1
    var tint: Color = .white
    /// Which setting has the crown. Shared by the page's rows, so tapping one takes the crown from another.
    let focus: FocusState<RemoteSetting?>.Binding
    let setting: RemoteSetting

    private var isFocused: Bool { focus.wrappedValue == setting }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).scaledFont(12, .medium).foregroundStyle(Theme.secondary)
            HStack(spacing: 4) {
                nudge("minus", by: -step)
                Text(valueText)
                    .scaledFont(18, .semibold)
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(maxWidth: .infinity)
                    .contentTransition(.numericText())
                nudge("plus", by: step)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Theme.raised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
            .strokeBorder(isFocused ? tint : Theme.hairline, lineWidth: isFocused ? 2 : 1))
        .focusable()
        .focused(focus, equals: setting)
        // A tap anywhere but − and + hands this row the crown.
        .onTapGesture { focus.wrappedValue = setting }
        .digitalCrownRotation($value, from: range.lowerBound, through: range.upperBound, by: step, sensitivity: .low,
                              isContinuous: false, isHapticFeedbackEnabled: true)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(spokenValue ?? valueText)
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: change(by: step)
            case .decrement: change(by: -step)
            @unknown default: break
            }
        }
    }

    private func nudge(_ symbol: String, by amount: Double) -> some View {
        Button {
            change(by: amount)
        } label: {
            Image(systemName: symbol).scaledFont(13, .bold).frame(width: 28, height: 28)
        }
        .buttonStyle(.plain)
        .foregroundStyle(tint)
        .disabled(amount < 0 ? value <= range.lowerBound : value >= range.upperBound)
    }

    private func change(by amount: Double) {
        value = min(max(value + amount, range.lowerBound), range.upperBound)
    }
}
