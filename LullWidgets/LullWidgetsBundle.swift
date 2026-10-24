import SwiftUI
import WidgetKit

@main
struct LullWidgetsBundle: WidgetBundle {
    var body: some Widget {
        TonightWidget()
        LastNightWidget()
        StartNightControl()
        RemoteControl()
        BedJetLiveActivity()
    }
}
