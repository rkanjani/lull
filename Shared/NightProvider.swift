import AppIntents
import BedJetKit
import SwiftUI
import WidgetKit

struct NightEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
}

/// Reads what the app last saved and lays out the night ahead of time: the BedJet keeps to its schedule by the
/// clock, so every phase change can be drawn without waking the app. Draws the BedJet the widget is set to.
struct NightProvider: AppIntentTimelineProvider {
    var preview = WidgetSnapshot.preview()

    func placeholder(in context: Context) -> NightEntry {
        NightEntry(date: Date(), snapshot: preview)
    }

    func snapshot(for configuration: BedJetConfiguration, in context: Context) async -> NightEntry {
        // The gallery shows the sample BedJets, which a real one picked for the widget isn't among.
        if context.isPreview { return NightEntry(date: Date(), snapshot: preview) }
        let snapshot = WidgetSnapshot.load() ?? WidgetSnapshot()
        return NightEntry(date: Date(), snapshot: snapshot.showing(configuration.bedJet?.id))
    }

    func timeline(for configuration: BedJetConfiguration, in context: Context) async -> Timeline<NightEntry> {
        let snapshot = (WidgetSnapshot.load() ?? WidgetSnapshot()).showing(configuration.bedJet?.id)
        let now = Date()
        let entries = snapshot.redraws(from: now).map { NightEntry(date: $0, snapshot: snapshot) }
        // The app redraws the widgets whenever what they show changes. Last night also moves on at midnight.
        let midnight = Calendar.current.date(byAdding: .day, value: 1, to: Calendar.current.startOfDay(for: now)) ?? now
        return Timeline(entries: entries, policy: .after(max(midnight, entries.last?.date ?? now)))
    }

    #if os(watchOS)
    /// Watch faces have no widget editor, so each BedJet is offered by name, as well as one that follows the BedJet the
    /// watch shows.
    func recommendations() -> [AppIntentRecommendation<BedJetConfiguration>] {
        let bedJets = WidgetSnapshot.load()?.bedJets ?? []
        let usual = AppIntentRecommendation(intent: BedJetConfiguration(), description: "Tonight")
        guard bedJets.count > 1 else { return [usual] }
        return [usual] + bedJets.map { bedJet in
            AppIntentRecommendation(intent: BedJetConfiguration(bedJet: BedJetEntity(bedJet)), description: "\(bedJet.name) · Tonight")
        }
    }
    #endif
}
