import SwiftUI

/// The watch's pages, as the iPhone's tabs: Tonight, the Remote and Last Night.
enum WatchPage: Hashable {
    case tonight, remote, lastNight

    init(_ tab: LullTab) {
        switch tab {
        case .schedule: self = .tonight
        case .remote: self = .remote
        case .history: self = .lastNight
        }
    }
}

struct WatchRootView: View {
    @Environment(WatchModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @State private var page = WatchPage.tonight
    @State private var choosing = false

    var body: some View {
        NavigationStack {
            TabView(selection: $page) {
                TonightPage().tag(WatchPage.tonight)
                RemotePage().tag(WatchPage.remote)
                LastNightPage().tag(WatchPage.lastNight)
            }
            .tabViewStyle(.verticalPage)
            .toolbar {
                if model.hasSeveralBedJets {
                    ToolbarItem(placement: .topBarLeading) {
                        Button {
                            choosing = true
                        } label: {
                            // Named, so it's clear which side of the bed is shown. Drawn here: the app's white tint
                            // would fill the system's button, hiding what it says.
                            Text(model.bedJet?.name ?? "BedJet")
                                .scaledFont(13, .semibold)
                                .foregroundStyle(.white)
                                .lineLimit(1)
                                .padding(.horizontal, 10)
                                .frame(minHeight: 32)
                                .frame(maxWidth: 96)
                                .background(Theme.raised, in: Capsule())
                                .overlay(Capsule().strokeBorder(Theme.hairline))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("BedJet, \(model.bedJet?.name ?? "none")")
                        .accessibilityHint("Switches BedJet")
                    }
                }
            }
            .sheet(isPresented: $choosing) {
                NavigationStack {
                    BedJetPicker()
                        .toolbar {
                            // Drawn here: the system's close button takes the app's white tint as its fill, hiding its ✕.
                            ToolbarItem(placement: .cancellationAction) {
                                Button {
                                    choosing = false
                                } label: {
                                    Image(systemName: "xmark")
                                        .scaledFont(14, .semibold)
                                        .foregroundStyle(.white)
                                        .frame(width: 32, height: 32)
                                        .background(Theme.raised, in: Circle())
                                        .overlay(Circle().strokeBorder(Theme.hairline))
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Close")
                            }
                        }
                }
            }
        }
        .background(Theme.background)
        // Complications open the page they show, on the BedJet they show. One showing the BedJet already shown leaves the
        // watch following the iPhone, if it was.
        .onOpenURL { url in
            if let id = LullTab.bedJet(in: url), id != model.bedJet?.id, model.snapshot.bedJets.contains(where: { $0.id == id }) {
                model.pickedID = id
            }
            if let tab = LullTab(url: url) { page = WatchPage(tab) }
        }
        .onAppear {
            AppCommands.handler = { command in
                if case let .show(tab) = command { page = WatchPage(tab) }
                if case .startNight = command { page = .tonight }
                model.handle(command)
            }
            model.refreshIfStale()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.refreshIfStale() }
        }
        .alert("Something went wrong", isPresented: .init(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
    }
}

/// Which BedJet the watch shows, by name, when Lull knows more than one.
struct BedJetPicker: View {
    @Environment(WatchModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List(model.snapshot.bedJets) { bedJet in
            Button {
                model.pickedID = bedJet.id
                dismiss()
            } label: {
                HStack {
                    Text(bedJet.name)
                    Spacer()
                    if bedJet.id == model.bedJet?.id {
                        Image(systemName: "checkmark").foregroundStyle(Theme.secondary)
                    }
                }
            }
            .accessibilityAddTraits(bedJet.id == model.bedJet?.id ? .isSelected : [])
        }
        .navigationTitle("BedJets")
    }
}
