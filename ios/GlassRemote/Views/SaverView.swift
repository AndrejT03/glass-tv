import SwiftUI

/// The screen savers: start one, and while it is on, the previous or next shot or stop, as Left,
/// Right and any other button do on the remote. Start Now's glass flows into those three buttons.
struct SaverView: View {
    @Environment(TVLink.self) private var link
    @Environment(AppLook.self) private var look
    @Namespace private var glass

    var body: some View {
        let s = link.strings
        NavigationStack {
            ScrollView {
                if let state = link.state {
                    VStack(spacing: 14) {
                        // Closer than the buttons are apart, so they stay round at rest and only flow while they change.
                        GlassEffectContainer(spacing: 12) {
                            if state.saver.showing {
                                VStack(spacing: 12) {
                                    Text(s.saverShowing)
                                        .font(.subheadline.weight(.medium))
                                        .foregroundStyle(.secondary)
                                    HStack(spacing: 18) {
                                        RoundButton(symbol: "backward.fill", label: s.previous) { saver("previous") }
                                            .glassEffectID("previous", in: glass)
                                        RoundButton(symbol: "stop.fill", label: s.stop, big: true) { saver("stop") }
                                            .glassEffectID("main", in: glass)
                                        RoundButton(symbol: "forward.fill", label: s.next) { saver("next") }
                                            .glassEffectID("next", in: glass)
                                    }
                                }
                                .frame(maxWidth: .infinity)
                            } else {
                                Button { saver("start") } label: {
                                    Label(s.startNow, systemImage: "play.fill")
                                        .font(.headline)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 17)
                                }
                                .liquidButton(.capsule, tint: look.accent.opacity(0.85), swell: 1.03)
                                .glassEffectID("main", in: glass)
                            }
                        }
                        .animation(.smooth(duration: 0.45), value: state.saver.showing)
                        .padding(.vertical, 6)

                        if let choice = state.setting("saver") {
                            GlassEffectContainer(spacing: 4) {
                                VStack(spacing: 10) {
                                    ForEach(choice.options) { option in
                                        SaverRow(option: option, chosen: option.id == choice.value) {
                                            Haptics.select()
                                            Task { await link.setting("saver", option.id) }
                                        }
                                    }
                                }
                            }
                        }
                        if let choice = state.setting("dynamicWallpaper") { DynamicPicker(choice: choice) }
                        GlassEffectContainer(spacing: 4) {
                            VStack(spacing: 10) {
                                if let choice = state.setting("idleMinutes") { ChoiceTile(choice: choice, symbol: "timer") }
                                if let choice = state.setting("saverClock") { ChoiceTile(choice: choice, symbol: "clock.fill") }
                                if let choice = state.setting("aerialQuality") { ChoiceTile(choice: choice, symbol: "4k.tv.fill") }
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 28)
                } else {
                    Waiting()
                }
            }
            .softEdges()
            .background { Backdrop() }
            .navigationTitle(s.screenSaver)
        }
    }

    private func saver(_ action: String) {
        Haptics.tap()
        Task { await link.send("/v1/saver", ["action": action]) }
    }
}

private struct RoundButton: View {
    let symbol: String
    let label: String
    var big = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: big ? 26 : 20, weight: .semibold))
                .frame(width: big ? 78 : 60, height: big ? 78 : 60)
        }
        .liquidButton(.circle, swell: 1.12)
        .accessibilityLabel(label)
    }
}

/// A screen saver as a glass capsule; the chosen one white, as the TV's focus.
private struct SaverRow: View {
    @Environment(AppLook.self) private var look
    let option: TVState.Option<String>
    let chosen: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Disc(symbol: symbol, fill: .white.opacity(chosen ? 0.24 : 0.16), size: 44)
                Text(option.label).font(.headline)
                Spacer()
                if chosen {
                    Image(systemName: "checkmark").font(.headline)
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
        }
        .liquidButton(.capsule, tint: chosen ? look.accent : nil, swell: 1.03)
        .animation(.smooth, value: chosen)
    }

    private var symbol: String {
        switch option.id {
        case "aerial": "globe.europe.africa.fill"
        case "landscapes": "mountain.2.fill"
        case "portraits": "rectangle.split.3x1.fill"
        case "dynamic": "sun.horizon.fill"
        case "liquid": "water.waves"
        case "standby": "clock.fill"
        default: "photo.on.rectangle"
        }
    }
}
