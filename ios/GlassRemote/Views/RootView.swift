import SwiftUI

/// Finding and pairing a TV, or controlling the one paired; the TV is followed while the app is in front.
struct RootView: View {
    @Environment(TVLink.self) private var link
    @Environment(AppLook.self) private var look
    @Environment(\.scenePhase) private var phase

    var body: some View {
        Group {
            if link.saved == nil {
                ConnectView()
            } else {
                MainView()
            }
        }
        // The widgets' buttons in the app's accent.
        .onChange(of: look.accentID, initial: true) { link.widgets.accent(look.accent.hex) }
        .onChange(of: phase, initial: true) { _, phase in
            switch phase {
            case .active: link.activate()
            case .background: link.deactivate()
            default: break
            }
        }
    }
}

/// The tabs, on iOS 26's Liquid Glass tab bar.
struct MainView: View {
    @Environment(TVLink.self) private var link
    @State private var tab = "controls"

    var body: some View {
        let s = link.strings
        TabView(selection: $tab) {
            Tab(s.controls, systemImage: "switch.2", value: "controls") { ControlsView() }
            Tab(s.inputs, systemImage: "tv", value: "inputs") { InputsView() }
            Tab(s.screenSaver, systemImage: "photo.on.rectangle", value: "saver") { SaverView() }
            Tab(s.settings, systemImage: "gearshape", value: "settings") { SettingsView() }
        }
        .overlay(alignment: .top) { StatusBanner() }
        .onOpenURL { url in
            // From the widgets: glassremote://controls (or another tab's name).
            if url.scheme == "glassremote", let host = url.host(), ["controls", "inputs", "saver", "settings"].contains(host) { tab = host }
        }
    }
}
