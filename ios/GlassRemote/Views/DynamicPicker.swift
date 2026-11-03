import SwiftUI

/// The Dynamic Wallpaper saver's picture: each of Apple's dynamic desktop pictures as it looks right
/// now (the frame for where the sun is), and all of them in turn.
struct DynamicPicker: View {
    @Environment(TVLink.self) private var link
    let choice: TVState.Choice
    @State private var pending: String?

    var body: some View {
        let store = link.dynamic
        let language = link.state?.tv.language ?? "mk"
        let chosen = pending ?? choice.value
        // Once a minute while it is on screen: the pictures follow the sun.
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let manifest = store.manifest
            let sun = (manifest ?? DynamicManifest(wallpapers: [])).sun(at: context.date)
            let part = DayPart(sun)
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    Disc(symbol: part.symbol, size: 40)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(choice.title).font(.headline)
                        Text(part.name(language)).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                ScrollView(.horizontal) {
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(choice.options) { option in
                            Button {
                                Haptics.select()
                                pending = option.id
                                Task {
                                    await link.setting(choice.key, option.id)
                                    if pending == option.id { pending = nil }
                                }
                            } label: {
                                Card(option: option, chosen: option.id == chosen, frames: frames(option.id, manifest, sun))
                            }
                            .buttonStyle(SpringButtonStyle(scale: 0.95))
                        }
                    }
                    .padding(.horizontal, 2)
                }
                .scrollIndicators(.hidden)
                .scrollClipDisabled()
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassEffect(.regular, in: .rect(cornerRadius: 28))
        .animation(.smooth, value: chosen)
    }

    /// What a card shows: the picture's frame for now; for all in turn, the first four pictures'.
    private func frames(_ id: String, _ manifest: DynamicManifest?, _ sun: SunPosition) -> [Frame] {
        let wallpapers = manifest?.wallpapers ?? []
        let shown = id == dynamicAll ? Array(wallpapers.prefix(4)) : wallpapers.filter { $0.id == id }
        return shown.map { Frame(id: $0.id, index: $0.frame(for: sun)) }
    }

    struct Frame: Hashable {
        var id: String
        var index: Int
    }
}

/// A picture as a rounded square with its name under it; the chosen one ringed in the accent.
private struct Card: View {
    @Environment(AppLook.self) private var look
    let option: TVState.Option<String>
    let chosen: Bool
    let frames: [DynamicPicker.Frame]

    private static let side: CGFloat = 104

    var body: some View {
        VStack(spacing: 7) {
            Group {
                if frames.count > 1 {
                    // All in turn: four pictures, two by two.
                    Grid(horizontalSpacing: 2, verticalSpacing: 2) {
                        GridRow { FrameImage(frame: frames[safe: 0], pixels: 160); FrameImage(frame: frames[safe: 1], pixels: 160) }
                        GridRow { FrameImage(frame: frames[safe: 2], pixels: 160); FrameImage(frame: frames[safe: 3], pixels: 160) }
                    }
                } else {
                    FrameImage(frame: frames.first, pixels: 320)
                }
            }
            .frame(width: Self.side, height: Self.side)
            .clipShape(.rect(cornerRadius: 20))
            .overlay {
                RoundedRectangle(cornerRadius: 20)
                    .strokeBorder(chosen ? look.accent : .white.opacity(0.18), lineWidth: chosen ? 3 : 1)
            }
            .overlay(alignment: .topTrailing) {
                if chosen {
                    Image(systemName: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(width: 22, height: 22)
                        .background(Circle().fill(look.accent))
                        .padding(6)
                        .transition(.scale.combined(with: .opacity))
                }
            }
            Text(option.label)
                .font(.caption.weight(chosen ? .semibold : .medium))
                .foregroundStyle(chosen ? .primary : .secondary)
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .frame(width: Self.side)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(option.label)
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }
}

/// One frame from the shelf, decoded small off the main thread; a soft placeholder until it is there.
private struct FrameImage: View {
    @Environment(TVLink.self) private var link
    let frame: DynamicPicker.Frame?
    let pixels: Int
    @State private var image: UIImage?

    var body: some View {
        Rectangle()
            .fill(.white.opacity(0.08))
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill().transition(.opacity)
                }
            }
            .clipped()
            .task(id: TaskKey(frame: frame, revision: link.dynamic.revision)) {
                guard let frame else { return }
                let decoded = await link.dynamic.image(frame.id, frame.index, pixels: pixels)
                withAnimation(.smooth(duration: 0.35)) { image = decoded }
            }
    }

    private struct TaskKey: Equatable {
        var frame: DynamicPicker.Frame?
        var revision: Int
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
