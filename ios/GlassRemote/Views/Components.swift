import SwiftUI
import UIKit

/// Behind every screen: the background chosen in Settings (Glass's dusk wallpaper, its icon's, unless
/// another), darkened where text goes, for the Liquid Glass over it to bend and pick up. A new choice
/// dissolves in.
struct Backdrop: View {
    @Environment(AppLook.self) private var look
    @Environment(TVLink.self) private var link

    var body: some View {
        let picture = look.picture(tvWallpaper: link.state?.setting("wallpaper")?.value)
        Color.black
            .overlay {
                Group {
                    switch picture {
                    case .wallpaper(let id):
                        Image("wallpaper-\(id)").resizable().scaledToFill()
                    case .blend(let id):
                        if let blend = AppLook.blends.first(where: { $0.id == id }) { BlendView(blend: blend) }
                    case .photo(let id):
                        if let image = look.image(ofPhoto: id) { Image(uiImage: image).resizable().scaledToFill() }
                    case .black:
                        // Pure black, for OLED: no picture, no shade.
                        Color.clear
                    }
                }
                .id(picture)
                .transition(.opacity)
            }
            .overlay {
                if picture != .black { Shade() }
            }
            .animation(.smooth(duration: 0.6), value: picture)
            .clipped()
            // Only to look at: a picture wider than the screen would otherwise take touches beside the content.
            .allowsHitTesting(false)
            .ignoresSafeArea()
    }
}

/// Darker where the titles are, so text over any picture reads.
private struct Shade: View {
    var body: some View {
        LinearGradient(
            stops: [
                .init(color: .black.opacity(0.62), location: 0),
                .init(color: .black.opacity(0.28), location: 0.5),
                .init(color: .black.opacity(0.18), location: 1),
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }
}

/// A Liquid Glass tile, as the control center's on the TV. What is on it stays flat and sharp: no
/// glass on glass.
struct Tile<Content: View>: View {
    var tint: Color? = nil
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassEffect(.regular.tint(tint), in: .rect(cornerRadius: 28))
    }
}

/// A Liquid Glass button that answers the finger as iOS 26's own glass does: the glass reacts where it
/// is touched, swells a little and lights up the moment it is pressed, and settles back when let go,
/// with only the glass's own slight give (a tap carries no momentum to bounce with). With Reduce
/// Motion it only lights up.
struct LiquidGlassButtonStyle<S: Shape>: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let shape: S
    var tint: Color? = nil
    var swell: CGFloat = 1.04

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed
        configuration.label
            .contentShape(shape)
            .glassEffect(.regular.tint(tint).interactive(), in: shape)
            .overlay {
                shape.fill(.white.opacity(pressed ? 0.13 : 0)).allowsHitTesting(false)
            }
            .scaleEffect(pressed && !reduceMotion ? swell : 1)
            .animation(pressed ? .spring(duration: 0.18) : .spring(duration: 0.4, bounce: 0.2), value: pressed)
    }
}

/// For what sits on a glass tile (no glass on glass) or carries its own glass: it gives a little
/// under the finger and settles back. With Reduce Motion it dims instead.
struct SpringButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var scale: CGFloat = 0.92

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed
        configuration.label
            .scaleEffect(pressed && !reduceMotion ? scale : 1)
            .opacity(pressed && reduceMotion ? 0.7 : 1)
            .animation(pressed ? .spring(duration: 0.16) : .spring(duration: 0.4, bounce: 0.2), value: pressed)
    }
}

extension View {
    /// The Liquid Glass button style, in [shape].
    func liquidButton<S: Shape>(_ shape: S, tint: Color? = nil, swell: CGFloat = 1.04) -> some View {
        buttonStyle(LiquidGlassButtonStyle(shape: shape, tint: tint, swell: swell))
    }

    /// A menu whose button is Liquid Glass (its choices open in iOS 26's glass menu).
    func liquidMenu<S: Shape>(_ shape: S, swell: CGFloat = 1.04) -> some View {
        menuStyle(.button).buttonStyle(LiquidGlassButtonStyle(shape: shape, swell: swell))
    }

    /// Progressive blur where content scrolls under the bars, instead of a hard edge.
    func softEdges() -> some View {
        scrollEdgeEffectStyle(.soft, for: .all)
            .toolbarBackgroundVisibility(.hidden, for: .navigationBar)
    }
}

/// A coloured disc for a tile's symbol, as on iOS's Control Center.
struct Disc: View {
    let symbol: String
    var fill: Color = .white.opacity(0.16)
    var ink: Color = .white
    var size: CGFloat = 48

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(ink)
            .frame(width: size, height: size)
            .background(Circle().fill(fill))
    }
}

struct SectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.title3.bold())
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 12)
            .padding(.horizontal, 6)
    }
}

/// One of the TV's settings as a glass capsule that opens its choices (a glass menu, on iOS 26).
struct ChoiceMenu: View {
    @Environment(TVLink.self) private var link
    let choice: TVState.Choice
    @State private var pending: String?

    var body: some View {
        let shown = pending ?? choice.value
        Menu {
            Picker(choice.title, selection: Binding(get: { shown }, set: { value in
                Haptics.select()
                pending = value
                Task {
                    await link.setting(choice.key, value)
                    if pending == value { pending = nil }
                }
            })) {
                ForEach(choice.options) { option in
                    Text(option.label).tag(option.id)
                }
            }
        } label: {
            HStack(spacing: 6) {
                Text(choice.options.first { $0.id == shown }?.label ?? choice.label).lineLimit(1)
                Image(systemName: "chevron.up.chevron.down").font(.caption2.weight(.bold))
            }
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
        }
        .liquidMenu(.capsule)
    }
}

/// A setting on a glass tile: its symbol and name, its value as a glass menu.
struct ChoiceTile: View {
    let choice: TVState.Choice
    let symbol: String

    var body: some View {
        HStack(spacing: 12) {
            Disc(symbol: symbol, size: 40)
            Text(choice.title).font(.headline).lineLimit(2)
            Spacer(minLength: 8)
            ChoiceMenu(choice: choice)
        }
        .padding(12)
        .glassEffect(.regular, in: .rect(cornerRadius: 26))
    }
}

/// Until the TV's state has come: on its way, or, off the TV's Wi-Fi, what to do about it.
struct Waiting: View {
    @Environment(TVLink.self) private var link

    var body: some View {
        let s = link.strings
        Group {
            if let reason = link.cantReach, reason != .noAnswer {
                VStack(spacing: 14) {
                    Image(systemName: "wifi.exclamationmark")
                        .font(.system(size: 30, weight: .semibold))
                        .symbolEffect(.bounce, value: link.reachNudge)
                        .frame(width: 72, height: 72)
                        .glassEffect(.regular.tint(.orange.opacity(0.35)), in: .circle)
                    VStack(spacing: 6) {
                        Text(reason == .offWifi ? s.wifiOff : s.wifiOther).font(.title3.bold())
                        Text(s.wifiHint)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 26)
                .frame(maxWidth: .infinity)
                .glassEffect(.regular, in: .rect(cornerRadius: 30))
                .padding(.horizontal, 16)
                .padding(.top, 48)
                .transition(.opacity.combined(with: .scale(scale: 0.96)))
            } else {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(s.reconnecting).font(.subheadline.weight(.medium))
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
                .glassEffect(.regular, in: .capsule)
                .frame(maxWidth: .infinity)
                .padding(.top, 80)
                .transition(.opacity)
            }
        }
        .animation(.smooth, value: link.cantReach)
    }
}

/// Over the top of the screen: the TV out of reach, or what it couldn't do, on a glass capsule.
struct StatusBanner: View {
    @Environment(TVLink.self) private var link

    var body: some View {
        VStack {
            if let reason = link.cantReach, reason != .noAnswer, link.state != nil {
                // Off the TV's Wi-Fi: it stays until the phone is back on it, nudging when a control is tried.
                HStack(spacing: 10) {
                    Image(systemName: "wifi.exclamationmark")
                        .symbolEffect(.bounce, value: link.reachNudge)
                    Text(link.strings.wifiBanner)
                }
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 18)
                .padding(.vertical, 11)
                .glassEffect(.regular.tint(.orange.opacity(0.45)), in: .capsule)
                .transition(.move(edge: .top).combined(with: .opacity))
            } else if link.status == .unreachable, link.state != nil {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(link.strings.reconnecting)
                }
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 18)
                .padding(.vertical, 11)
                .glassEffect(.regular, in: .capsule)
                .transition(.move(edge: .top).combined(with: .opacity))
            } else if let notice = link.notice {
                Text(notice)
                    .font(.subheadline.weight(.medium))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 11)
                    .glassEffect(.regular.tint(.orange.opacity(0.45)), in: .capsule)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task(id: notice) {
                        try? await Task.sleep(for: .seconds(3.5))
                        link.notice = nil
                    }
            }
        }
        .padding(.horizontal, 20)
        .animation(.smooth, value: link.status)
        .animation(.smooth, value: link.cantReach)
        .animation(.smooth, value: link.notice)
    }
}

/// What the finger feels: a light tap for an action, the selection tick for a choice, as iOS's own
/// pickers and switches give.
@MainActor
enum Haptics {
    static func tap() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    static func select() {
        UISelectionFeedbackGenerator().selectionChanged()
    }
}

extension Color {
    /// From the TV's RRGGBB.
    init(hex: String) {
        let value = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0xFFFFFF
        self.init(
            .sRGB,
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }

    /// As the TV takes it, RRGGBB (a wide-gamut colour clamped to sRGB).
    var hex: String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(self).getRed(&r, green: &g, blue: &b, alpha: &a)
        func byte(_ x: CGFloat) -> Int { Int((min(max(x, 0), 1) * 255).rounded()) }
        return String(format: "%02X%02X%02X", byte(r), byte(g), byte(b))
    }
}
