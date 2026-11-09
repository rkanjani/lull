import BedJetKit
import SwiftUI

enum Theme {
    static let background = Color.black
    static let surface = Color(red: 0.055, green: 0.059, blue: 0.071)
    static let raised = Color(red: 0.098, green: 0.102, blue: 0.118)
    static let hairline = Color.white.opacity(0.08)
    static let secondary = Color.white.opacity(0.55)
    /// The faintest text that still reads at 4.5:1 against the background and surfaces.
    static let tertiary = Color.white.opacity(0.48)

    static let cool = Color(red: 0.30, green: 0.64, blue: 1.0)
    static let deepCool = Color(red: 0.17, green: 0.36, blue: 1.0)
    static let warm = Color(red: 1.0, green: 0.54, blue: 0.24)
    static let hot = Color(red: 1.0, green: 0.30, blue: 0.18)
    static let neutral = Color(red: 0.56, green: 0.56, blue: 0.60)
}

extension Mode {
    var tint: Color {
        switch self {
        case .cool: return Theme.cool
        case .heat: return Theme.warm
        case .turbo: return Theme.hot
        case .extendedHeat: return Color(red: 1.0, green: 0.70, blue: 0.25)
        case .dry: return Color(red: 0.35, green: 0.84, blue: 0.77)
        case .standby, .wait: return Theme.neutral
        }
    }
}

/// Small all-caps label used above groups of content.
struct Eyebrow: View {
    let text: String
    var color = Theme.secondary

    init(_ text: String, color: Color = Theme.secondary) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text.uppercased())
            .scaledFont(11, .semibold)
            .tracking(1.6)
            .foregroundStyle(color)
            // Read as written rather than in capitals, which VoiceOver can spell out letter by letter.
            .accessibilityLabel(text)
    }
}

struct Card<Content: View>: View {
    var padding: CGFloat = 20
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(Theme.hairline))
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    var prominent = true
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaledFont(17, .semibold)
            .foregroundStyle(prominent ? Color.black : Color.white)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(prominent ? Color.white : Theme.raised, in: Capsule())
            .overlay(Capsule().strokeBorder(prominent ? Color.clear : Theme.hairline))
            .opacity(isEnabled ? 1 : 0.4)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.snappy(duration: 0.2), value: configuration.isPressed)
    }
}

/// A slider with a tinted track that snaps to whole steps and shows its value inline.
struct LabeledSlider: View {
    let title: String
    let valueText: String
    /// What VoiceOver reads for the value, when the shown text abbreviates it.
    var spokenValue: String?
    @Binding var value: Double
    let range: ClosedRange<Double>
    var step: Double = 1
    var tint: Color = .white
    /// Set when the slider swaps places with a row of that height, so its name stays where it was.
    var headerMinHeight: CGFloat?
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // Placed like the row it swaps with, rather than on the value's baseline, so the name lines up with it exactly:
            // centred beside a time, or at the top above one at the largest text sizes.
            ValueHeader(title: title, value: valueText,
                        alignment: headerMinHeight == nil ? .firstTextBaseline : typeSize.isAccessibilitySize ? .top : .center)
                .frame(minHeight: headerMinHeight, alignment: .leading)
            Slider(value: $value, in: range, step: step)
                .tint(tint)
                .sensoryFeedback(.selection, trigger: value)
        }
        // One VoiceOver element for the title, value and track together, so touching any of them
        // finds the slider, and swiping up or down adjusts it.
        .accessibilityRepresentation {
            Slider(value: $value, in: range, step: step)
                .accessibilityLabel(title)
                .accessibilityValue(spokenValue ?? valueText)
        }
    }
}

/// How tall a row with a time picker or a button is, at least, so rows that swap one for another keep their labels in place.
let timeRowHeight: CGFloat = 44

/// A minute of the day as the `Date` a time picker edits: today, at that time.
func timeOfDayBinding(get: @escaping () -> Int, set: @escaping (Int) -> Void) -> Binding<Date> {
    Binding {
        let minute = get()
        return Calendar.current.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: Date()) ?? Date()
    } set: { date in
        set(Format.minuteOfDay(date))
    }
}

/// A setting's name with its value at the end of the line, stacked instead when large text won't fit on one.
struct ValueHeader: View {
    let title: String
    let value: String
    var alignment: VerticalAlignment = .firstTextBaseline

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: alignment) {
                name
                Spacer()
                reading
            }
            VStack(alignment: .leading, spacing: 2) {
                name
                reading
            }
        }
    }

    private var name: some View {
        Text(title).scaledFont(15, .medium).foregroundStyle(Theme.secondary)
    }

    private var reading: some View {
        Text(value)
            .scaledFont(20, .semibold)
            .monospacedDigit()
            .contentTransition(.numericText())
    }
}

extension View {
    /// The design's point sizes, grown or shrunk with the person's text size setting.
    func scaledFont(_ size: CGFloat, _ weight: Font.Weight = .regular) -> some View {
        modifier(ScaledFont(size: size, weight: weight))
    }
}

private struct ScaledFont: ViewModifier {
    @ScaledMetric private var size: CGFloat
    let weight: Font.Weight

    init(size: CGFloat, weight: Font.Weight) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: Self.style(for: size))
        self.weight = weight
    }

    func body(content: Content) -> some View {
        content.font(.system(size: size, weight: weight))
    }

    /// The text style nearest the design size, so each size scales at the rate the system uses for it.
    private static func style(for size: CGFloat) -> Font.TextStyle {
        switch size {
        case ..<12: return .caption2
        case ..<13: return .caption
        case ..<15: return .footnote
        case ..<16: return .subheadline
        case ..<17: return .callout
        case ..<19: return .body
        case ..<22: return .title3
        case ..<26: return .title2
        case ..<30: return .title
        default: return .largeTitle
        }
    }
}
