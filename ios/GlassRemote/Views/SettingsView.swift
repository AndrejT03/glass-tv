import PhotosUI
import SwiftUI

/// The launcher's settings, the TV's own screens to open (as the round buttons of its control center
/// and quick settings), and the TV this phone is paired with.
struct SettingsView: View {
    @Environment(TVLink.self) private var link
    @State private var forgetting = false
    @State private var renaming = false
    @State private var newName = ""

    var body: some View {
        let s = link.strings
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    if let state = link.state {
                        SectionTitle(text: s.look)
                        GlassEffectContainer(spacing: 4) {
                            VStack(spacing: 10) {
                                if let choice = state.setting("homeStyle") { ChoiceTile(choice: choice, symbol: "rectangle.3.group.fill") }
                                if let choice = state.setting("wallpaper") { ChoiceTile(choice: choice, symbol: "photo.fill") }
                                if let choice = state.setting("clockStyle") { ChoiceTile(choice: choice, symbol: "clock.fill") }
                            }
                        }
                        SectionTitle(text: s.general)
                        GlassEffectContainer(spacing: 4) {
                            VStack(spacing: 10) {
                                if let sounds = state.setting("sounds") {
                                    HStack(spacing: 12) {
                                        Disc(symbol: "speaker.wave.2.fill", size: 40)
                                        Text(sounds.title).font(.headline)
                                        Spacer()
                                        Toggle(sounds.title, isOn: Binding(get: { sounds.value == "on" }, set: { on in
                                            Haptics.select()
                                            Task { await link.setting("sounds", on ? "on" : "off") }
                                        }))
                                        .labelsHidden()
                                    }
                                    .padding(12)
                                    .glassEffect(.regular, in: .rect(cornerRadius: 26))
                                }
                                if let choice = state.setting("language") { ChoiceTile(choice: choice, symbol: "character.bubble.fill") }
                            }
                        }
                        SectionTitle(text: s.appBackground)
                        BackdropPicker()
                        SectionTitle(text: s.accent)
                        AccentPicker()
                        SectionTitle(text: s.openOnTV)
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: 18) {
                            ForEach(screens(s), id: \.id) { screen in
                                Button {
                                    Haptics.tap()
                                    Task { await link.send("/v1/open", ["screen": screen.id]) }
                                } label: {
                                    VStack(spacing: 8) {
                                        Image(systemName: screen.symbol)
                                            .font(.title3.weight(.semibold))
                                            .frame(width: 60, height: 60)
                                            .glassEffect(.regular.interactive(), in: .circle)
                                        Text(screen.label)
                                            .font(.caption2.weight(.medium))
                                            .foregroundStyle(.secondary)
                                            .multilineTextAlignment(.center)
                                            .lineLimit(2, reservesSpace: true)
                                    }
                                }
                                .buttonStyle(SpringButtonStyle(scale: 1.1))
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    SectionTitle(text: s.tv)
                    VStack(alignment: .leading, spacing: 12) {
                        if let tv = link.saved {
                            Button {
                                newName = link.state?.tv.name ?? tv.name
                                renaming = true
                            } label: {
                                LabeledContent(s.name) {
                                    HStack(spacing: 6) {
                                        Text(link.state?.tv.name ?? tv.name)
                                        Image(systemName: "pencil").font(.caption.weight(.semibold))
                                    }
                                    .foregroundStyle(.tint)
                                }
                                .contentShape(.rect)
                            }
                            .buttonStyle(SpringButtonStyle(scale: 0.97))
                            .disabled(link.state == nil)
                            LabeledContent(s.address, value: "\(tv.host):\(tv.port)")
                            WidgetsStatus()
                        }
                        Button(s.forget, role: .destructive) { forgetting = true }
                            .buttonStyle(.glass)
                            .tint(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .font(.subheadline)
                    .padding(16)
                    .glassEffect(.regular, in: .rect(cornerRadius: 26))
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 28)
            }
            .softEdges()
            .background { Backdrop() }
            .navigationTitle(s.settings)
            .alert(s.renameTV, isPresented: $renaming) {
                TextField(link.state?.tv.systemName ?? "", text: $newName)
                Button(s.save) { Task { await link.send("/v1/name", ["name": newName]) } }
                Button(s.cancel, role: .cancel) {}
            } message: {
                Text(s.tvNameHint)
            }
            .confirmationDialog(s.forget, isPresented: $forgetting, titleVisibility: .visible) {
                Button(s.forget, role: .destructive) { Task { await link.forget() } }
                Button(s.cancel, role: .cancel) {}
            } message: {
                Text(s.forgetConfirm)
            }
        }
    }

    private struct Screen {
        let id: String
        let symbol: String
        let label: String
    }

    /// The TV's own screens, as Glass opens them from its control center and quick settings.
    private func screens(_ s: Strings) -> [Screen] {
        [
            Screen(id: "settings", symbol: "gearshape.fill", label: s.androidSettings),
            Screen(id: "wifi", symbol: "wifi", label: s.wifi),
            Screen(id: "bluetooth", symbol: "dot.radiowaves.left.and.right", label: s.bluetooth),
            Screen(id: "picture", symbol: "tv.inset.filled", label: s.pictureSettings),
            Screen(id: "speakers", symbol: "hifispeaker.fill", label: s.speakers),
            Screen(id: "gameMaster", symbol: "gamecontroller.fill", label: s.gameMaster),
            Screen(id: "cec", symbol: "cable.connector", label: s.cec),
        ]
    }
}

/// The app's own background, each choice a little phone-shaped picture on a glass bezel: the TV's
/// wallpaper, pure black and the household's own pictures (a long press removes one), then Glass's
/// wave wallpapers, then soft colour fields in the spirit of Apple Home's.
private struct BackdropPicker: View {
    @Environment(AppLook.self) private var look
    @Environment(TVLink.self) private var link
    @State private var item: PhotosPickerItem?
    @State private var picking = false

    var body: some View {
        let s = link.strings
        let tv = AppLook.tv(link.state?.setting("wallpaper")?.value)
        VStack(alignment: .leading, spacing: 10) {
            row {
                Thumb(chosen: look.choice == AppLook.likeTV, label: s.likeTV, badge: "tv") {
                    Image("wallpaper-\(tv)").resizable().scaledToFill()
                } action: {
                    look.choose(AppLook.likeTV)
                }
                Thumb(chosen: look.choice == AppLook.black, label: s.black) {
                    Color.black
                } action: {
                    look.choose(AppLook.black)
                }
                ForEach(look.photos, id: \.id) { photo in
                    Thumb(chosen: look.choice == AppLook.photo(photo.id), label: s.yourPhoto) {
                        Image(uiImage: photo.image).resizable().scaledToFill()
                    } action: {
                        look.choose(AppLook.photo(photo.id))
                    }
                    .contextMenu {
                        Button(s.remove, systemImage: "trash", role: .destructive) { look.removePhoto(photo.id) }
                    }
                }
                Thumb(chosen: false, label: s.choosePhoto) {
                    Image(systemName: "photo.badge.plus")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } action: {
                    picking = true
                }
            }
            caption("Glass")
            row {
                ForEach(AppLook.wallpapers, id: \.self) { id in
                    Thumb(chosen: look.choice == id, label: id.capitalized) {
                        Image("wallpaper-\(id)").resizable().scaledToFill()
                    } action: {
                        look.choose(id)
                    }
                }
            }
            caption(s.gradients)
            row {
                ForEach(AppLook.blends) { blend in
                    Thumb(chosen: look.choice == blend.id, label: s.blendNames[blend.id] ?? blend.id) {
                        BlendView(blend: blend)
                    } action: {
                        look.choose(blend.id)
                    }
                }
            }
        }
        .photosPicker(isPresented: $picking, selection: $item, matching: .images)
        .onChange(of: item) { _, picked in
            guard let picked else { return }
            Task {
                if let data = try? await picked.loadTransferable(type: Data.self) {
                    await look.addPhoto(data)
                }
                item = nil
            }
        }
    }

    private func row<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 12) { content() }
                .padding(.horizontal, 4)
                .padding(.vertical, 6)
        }
    }

    private func caption(_ text: String) -> some View {
        Text(text)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 6)
    }
}

private struct Thumb<Picture: View>: View {
    let chosen: Bool
    let label: String
    var badge: String? = nil
    @ViewBuilder let picture: Picture
    let action: () -> Void

    var body: some View {
        Button {
            Haptics.select()
            action()
        } label: {
            ThumbFace(chosen: chosen, label: label, badge: badge) { picture }
        }
        .buttonStyle(SpringButtonStyle(scale: 1.06))
    }
}

/// A phone-shaped picture on a glass bezel, in the accent when chosen, its name under it.
private struct ThumbFace<Picture: View>: View {
    @Environment(AppLook.self) private var look
    let chosen: Bool
    let label: String
    var badge: String? = nil
    @ViewBuilder let picture: Picture

    var body: some View {
        VStack(spacing: 8) {
            picture
                .frame(width: 66, height: 112)
                .clipShape(.rect(cornerRadius: 14))
                .overlay(alignment: .bottomTrailing) {
                    if let badge {
                        Image(systemName: badge)
                            .font(.caption2.weight(.bold))
                            .padding(5)
                            .background(.black.opacity(0.4), in: .circle)
                            .padding(5)
                    }
                }
                .overlay {
                    if chosen {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.title2)
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, look.accent)
                    }
                }
                .padding(4)
                .glassEffect(chosen ? .regular.tint(look.accent).interactive() : .regular.interactive(), in: .rect(cornerRadius: 18))
                .animation(.smooth, value: chosen)
            Text(label)
                .font(.caption2.weight(.medium))
                .foregroundStyle(chosen ? .primary : .secondary)
                .lineLimit(1)
                .frame(width: 80)
        }
    }
}

/// The app's accent: what is on and chosen across it (the tab bar, switches, sliders, chosen rows), as
/// glass drops of iOS's own colours.
private struct AccentPicker: View {
    @Environment(AppLook.self) private var look
    @Environment(TVLink.self) private var link

    var body: some View {
        let s = link.strings
        ScrollView(.horizontal, showsIndicators: false) {
            GlassEffectContainer(spacing: 8) {
                HStack(alignment: .top, spacing: 14) {
                    ForEach(AppLook.accents) { accent in
                        let chosen = look.accentID == accent.id
                        VStack(spacing: 8) {
                            Button {
                                Haptics.select()
                                withAnimation(.smooth) { look.chooseAccent(accent.id) }
                            } label: {
                                Color.clear
                                    .frame(width: 46, height: 46)
                                    .overlay {
                                        if chosen { Image(systemName: "checkmark").font(.subheadline.weight(.bold)).foregroundStyle(.white) }
                                    }
                            }
                            .liquidButton(.circle, tint: accent.color, swell: 1.15)
                            Text(s.accentNames[accent.id] ?? accent.id)
                                .font(.caption2.weight(.medium))
                                .foregroundStyle(chosen ? .primary : .secondary)
                                .lineLimit(1)
                        }
                        .frame(width: 66)
                    }
                }
                .padding(.horizontal, 4)
                .padding(.vertical, 6)
            }
        }
    }
}

/// Whether the widgets reach what the app leaves them: the app group, under the name the signature gave
/// it, and the widgets having found the same one.
private struct WidgetsStatus: View {
    @Environment(TVLink.self) private var link

    var body: some View {
        let s = link.strings
        let available = AppGroup.available
        let heard = available && WidgetFacts.widgetsGroup == AppGroup.id
        VStack(alignment: .leading, spacing: 4) {
            LabeledContent(s.widgets) {
                Label(available ? (heard ? s.widgetsReady : s.widgetsWaiting) : s.widgetsNoGroup,
                      systemImage: heard ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(heard ? .green : .orange)
            }
            if !heard {
                Text(available ? s.widgetsWaitingHint : s.widgetsNoGroupHint)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(AppGroup.id)
                .font(.caption2.monospaced())
                .foregroundStyle(.tertiary)
        }
    }
}
