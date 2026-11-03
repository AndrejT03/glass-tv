import SwiftUI

/// The TV's inputs (as its inputs menu lists them, under the household's names) and its apps.
struct InputsView: View {
    @Environment(TVLink.self) private var link
    @State private var renaming: TVState.Input?
    @State private var newName = ""

    var body: some View {
        let s = link.strings
        NavigationStack {
            ScrollView {
                if let state = link.state {
                    VStack(spacing: 14) {
                        GlassEffectContainer(spacing: 4) {
                            VStack(spacing: 10) {
                                ForEach(state.inputs) { input in
                                    InputRow(input: input, current: input.id == state.currentInput) {
                                        Haptics.select()
                                        Task { await link.send("/v1/inputs/watch", ["id": input.id]) }
                                    }
                                    .contextMenu {
                                        Button(s.rename, systemImage: "pencil") {
                                            newName = input.port == nil ? "" : input.name
                                            renaming = input
                                        }
                                        if input.port != nil {
                                            Button(s.resetName, systemImage: "arrow.uturn.backward") {
                                                Task { await link.send("/v1/inputs/rename", ["id": input.id, "name": ""]) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if let key = state.lastApp, let app = state.apps.first(where: { $0.key == key }) {
                            SectionTitle(text: s.lastApp)
                            LastAppRow(app: app) {
                                Haptics.tap()
                                Task { await link.send("/v1/apps/last") }
                            }
                        }
                        SectionTitle(text: s.apps)
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], spacing: 16) {
                            ForEach(state.apps) { app in
                                AppTile(app: app) {
                                    Haptics.tap()
                                    Task { await link.send("/v1/apps/launch", ["key": app.key]) }
                                }
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
            .navigationTitle(s.inputs)
            .alert(s.rename, isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField(renaming?.port ?? renaming?.name ?? "", text: $newName)
                Button(s.save) {
                    if let input = renaming {
                        Task { await link.send("/v1/inputs/rename", ["id": input.id, "name": newName]) }
                    }
                }
                Button(s.cancel, role: .cancel) {}
            } message: {
                Text(s.renameHint)
            }
        }
    }
}

/// An input as a glass capsule; the one on screen white, as the TV's focus platter.
private struct InputRow: View {
    @Environment(TVLink.self) private var link
    @Environment(AppLook.self) private var look
    let input: TVState.Input
    let current: Bool
    let action: () -> Void

    var body: some View {
        let s = link.strings
        Button(action: action) {
            HStack(spacing: 14) {
                Disc(symbol: symbol, fill: .white.opacity(current ? 0.24 : 0.16), size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(input.name).font(.headline).lineLimit(1)
                    if let subtitle = subtitle(s) {
                        Text(subtitle).font(.caption).foregroundStyle(current ? .white.opacity(0.8) : .secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                if current {
                    Text(s.onScreen).font(.caption.weight(.bold)).foregroundStyle(.white.opacity(0.85))
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .opacity(input.connected || current ? 1 : 0.55)
        }
        .liquidButton(.capsule, tint: current ? look.accent : nil, swell: 1.03)
        .animation(.smooth, value: current)
    }

    private var symbol: String {
        switch input.kind {
        case "tuner": "antenna.radiowaves.left.and.right"
        case "hdmi": "cable.connector.horizontal"
        case "composite": "cable.coaxial"
        default: "rectangle.connected.to.line.below"
        }
    }

    private func subtitle(_ s: Strings) -> String? {
        if input.connected { return input.port }
        return [input.port, s.nothingOnIt].compactMap { $0 }.joined(separator: " · ")
    }
}

private struct LastAppRow: View {
    let app: TVState.App
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Art(app: app)
                    .frame(width: 100, height: 60)
                    .clipShape(.rect(cornerRadius: 12))
                Text(app.label).font(.headline)
                Spacer()
                Image(systemName: "arrow.uturn.backward.circle.fill").font(.title2).foregroundStyle(.secondary)
            }
            .padding(8)
            .padding(.trailing, 8)
        }
        .liquidButton(.rect(cornerRadius: 20), swell: 1.03)
    }
}

/// An app's tile from the TV, on a glass bezel like the TV's dock.
private struct AppTile: View {
    let app: TVState.App
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Art(app: app)
                    .aspectRatio(5 / 3, contentMode: .fit)
                    .clipShape(.rect(cornerRadius: 13))
                    .padding(5)
                    .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 18))
                Text(app.label)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .contentShape(.rect)
        }
        // Lifts toward the finger, as a focused tile does on the TV.
        .buttonStyle(SpringButtonStyle(scale: 1.06))
    }
}

private struct Art: View {
    @Environment(TVLink.self) private var link
    let app: TVState.App

    var body: some View {
        ZStack {
            Rectangle().fill(.white.opacity(0.08))
            if let image = link.arts.images[app.key] {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .transition(.opacity)
            } else {
                Text(app.label)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(6)
            }
        }
        .animation(.smooth, value: link.arts.images[app.key] != nil)
        .task(id: app.key) { await link.loadArt(app.key) }
    }
}
