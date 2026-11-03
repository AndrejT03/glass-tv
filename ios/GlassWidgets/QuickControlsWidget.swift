import AppIntents
import SwiftUI
import WidgetKit

/// Quick controls for the TV on the Home Screen, as tiles in the manner of Control Center (their symbols
/// alone, as the household asked; the words are for VoiceOver): small (power, mute, the screen saver and the light) and
/// medium (power and mute beside the volume, which shows its level between − and +; the screen saver,
/// the light, the inputs and the last app), under the TV's name. Off the TV's Wi-Fi the name gives way to
/// what to do about it, and the tiles dim (a press still tries, and clears it if the TV answers).
///
/// Light on the battery: the widget never asks the TV anything by itself. It shows the TV as the app
/// last heard it (or as the TV answered a button), drawn again when the app or a button says something
/// changed; a button sends one request, only when pressed. Once an hour WidgetKit asks it again, and it
/// looks at which network the phone is on (no traffic), so it says when the phone has left the TV's Wi-Fi.
struct QuickControlsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: controlsWidgetKind, provider: ControlsProvider()) { entry in
            ControlsView(entry: entry)
        }
        .configurationDisplayName(ControlsWords.current.name)
        .description(ControlsWords.current.description)
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct ControlsEntry: TimelineEntry {
    var date: Date
    /// Nil until the app has paired with a TV and heard from it.
    var tv: TVSnapshot?
    var accent: Color
    /// Why the TV can't be reached now, if it can't.
    var problem: TVReach?

    static var now: ControlsEntry {
        ControlsEntry(
            date: .now,
            tv: WidgetFacts.tv == nil ? nil : WidgetFacts.snapshot,
            accent: WidgetFacts.accent.map(Color.init(hex:)) ?? .blue,
            problem: WidgetFacts.problem
        )
    }
}

struct ControlsProvider: TimelineProvider {
    func placeholder(in context: Context) -> ControlsEntry {
        var entry = ControlsEntry.now
        if entry.tv == nil {
            entry.tv = TVSnapshot(name: "TV", language: "mk", on: true, volume: 30, muted: false, lightOn: true, lightSwatch: nil, saverShowing: false)
            entry.problem = nil
        }
        return entry
    }

    func getSnapshot(in context: Context, completion: @escaping (ControlsEntry) -> Void) {
        completion(context.isPreview && ControlsEntry.now.tv == nil ? placeholder(in: context) : .now)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ControlsEntry>) -> Void) {
        // WidgetKit calls it once and waits for it.
        nonisolated(unsafe) let completion = completion
        Task {
            if WidgetFacts.widgetsGroup != AppGroup.id { WidgetFacts.widgetsGroup = AppGroup.id }
            // Which network the phone is on now: off the TV's Wi-Fi, or back on it.
            if let tv = WidgetFacts.tv {
                let reach = await TVReach.forWidgets(tv)
                let problem = WidgetFacts.problem
                if reach == .offWifi || reach == .otherNetwork {
                    if problem != reach { WidgetFacts.problem = reach }
                } else if problem == .offWifi || problem == .otherNetwork {
                    WidgetFacts.problem = nil
                }
            }
            // Nothing else changes by itself: the app and the buttons ask WidgetKit for a new one.
            completion(Timeline(entries: [.now], policy: .after(.now + 60 * 60)))
        }
    }
}

struct ControlsView: View {
    @Environment(\.widgetFamily) private var family
    let entry: ControlsEntry

    var body: some View {
        Group {
            if let tv = entry.tv {
                let words = ControlsWords.of(tv.language)
                let medium = family == .systemMedium
                VStack(spacing: 8) {
                    Header(tv: tv, problem: entry.problem, words: words, medium: medium)
                    // Equal halves, each of equal tiles, so every column lines up (a Grid sized the
                    // columns by their words).
                    VStack(spacing: 8) {
                        if medium {
                            HStack(spacing: 8) {
                                HStack(spacing: 8) {
                                    Tile(control: .power, tv: tv, accent: entry.accent, words: words)
                                    Tile(control: .mute, tv: tv, accent: entry.accent, words: words)
                                }
                                VolumeTile(tv: tv, words: words)
                            }
                            HStack(spacing: 8) {
                                HStack(spacing: 8) {
                                    Tile(control: .saver, tv: tv, accent: entry.accent, words: words)
                                    Tile(control: .light, tv: tv, accent: entry.accent, words: words)
                                }
                                HStack(spacing: 8) {
                                    Tile(control: .inputs, tv: tv, accent: entry.accent, words: words)
                                    Tile(control: .lastApp, tv: tv, accent: entry.accent, words: words)
                                }
                            }
                        } else {
                            HStack(spacing: 8) {
                                Tile(control: .power, tv: tv, accent: entry.accent, words: words)
                                Tile(control: .mute, tv: tv, accent: entry.accent, words: words)
                            }
                            HStack(spacing: 8) {
                                Tile(control: .saver, tv: tv, accent: entry.accent, words: words)
                                Tile(control: .light, tv: tv, accent: entry.accent, words: words)
                            }
                        }
                    }
                    // Out of reach: still there to press (a press tries, and clears it if the TV answers).
                    .opacity(entry.problem == nil ? 1 : 0.45)
                }
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "tv").font(.title2.weight(.semibold))
                    Text(ControlsWords.current.pair)
                        .font(.caption.weight(.semibold))
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .foregroundStyle(.white)
        .containerBackground(for: .widget) { Background(accent: entry.accent) }
        .widgetURL(URL(string: "glassremote://controls"))
    }
}

/// A deep, cool field with a faint glow of the app's accent in the top corner, as light on glass.
private struct Background: View {
    let accent: Color

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.15, green: 0.16, blue: 0.25), Color(red: 0.04, green: 0.04, blue: 0.08)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            RadialGradient(colors: [accent.opacity(0.28), .clear], center: .topTrailing, startRadius: 0, endRadius: 190)
        }
    }
}

/// The TV's name and how it is (asleep, its volume); off the TV's Wi-Fi, what to do about it instead.
private struct Header: View {
    let tv: TVSnapshot
    let problem: TVReach?
    let words: ControlsWords
    let medium: Bool

    var body: some View {
        HStack(spacing: 5) {
            if let problem {
                Image(systemName: problem == .noAnswer ? "exclamationmark.triangle.fill" : "wifi.exclamationmark")
                    .font(.system(size: 11, weight: .bold))
                Text(words.problem(problem, long: medium))
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                Spacer(minLength: 0)
            } else {
                Image(systemName: "tv.fill")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(.white.opacity(0.6))
                Text(tv.name)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                Spacer(minLength: 4)
                Group {
                    if !tv.on {
                        Image(systemName: "moon.fill")
                    } else if !medium {
                        // The small one has no volume tile: its level here.
                        HStack(spacing: 2) {
                            Image(systemName: tv.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                            if !tv.muted { Text("\(tv.volume)%").monospacedDigit() }
                        }
                    }
                }
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.white.opacity(0.6))
            }
        }
        .foregroundStyle(problem == nil ? .white : Color(red: 1, green: 0.74, blue: 0.36))
        .invalidatableContent()
        .frame(height: 16)
    }
}

/// A control as a round Control Center button: its symbol in a circle. Lit in the accent (the light in
/// its own colour) while what it stands for is on.
private struct Tile: View {
    @Environment(\.widgetRenderingMode) private var mode
    let control: TVControl
    let tv: TVSnapshot
    let accent: Color
    let words: ControlsWords

    var body: some View {
        // On the tinted and clear Home Screens everything takes one colour and only its opacity tells
        // things apart: a lit tile is brighter, its symbol in the tint.
        let tinted = mode == .accented
        let ink: Color = !tinted && lit && control == .light && lightIsPale ? .black : .white
        Button(intent: ControlTV(control)) {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .semibold))
                .widgetAccentable(tinted && lit)
                .foregroundStyle(ink)
                .opacity(tinted && !lit ? 0.7 : 1)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .aspectRatio(1, contentMode: .fit)
                .background {
                    Circle()
                        .fill(tinted ? .white.opacity(lit ? 0.32 : 0.1) : lit ? fill : .white.opacity(0.12))
                        .overlay { Circle().strokeBorder(.white.opacity(lit ? 0.18 : 0.1), lineWidth: 0.5) }
                }
                .contentShape(.circle)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(words.label(control))
    }

    private var lit: Bool {
        switch control {
        case .power: tv.on
        case .mute: tv.muted
        case .saver: tv.saverShowing
        case .light: tv.lightOn
        default: false
        }
    }

    private var fill: Color {
        if control == .light, let swatch = tv.lightSwatch { return Color(hex: swatch) }
        return accent
    }

    /// A light colour (white, a pale yellow) wants dark ink on it.
    private var lightIsPale: Bool {
        guard let swatch = tv.lightSwatch, let value = UInt32(swatch, radix: 16) else { return false }
        let r = Double((value >> 16) & 0xFF), g = Double((value >> 8) & 0xFF), b = Double(value & 0xFF)
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.72
    }

    private var symbol: String {
        switch control {
        case .power: "power"
        case .volumeDown: "minus"
        case .volumeUp: "plus"
        case .mute: tv.muted ? "speaker.slash.fill" : "speaker.fill"
        case .saver: "photo.on.rectangle"
        case .light: tv.lightOn ? "lightbulb.fill" : "lightbulb"
        case .inputs: "tv"
        case .lastApp: "arrow.uturn.backward"
        }
    }
}

/// The volume as a capsule, its level between the round buttons that change it: − and + at its ends.
private struct VolumeTile: View {
    @Environment(\.widgetRenderingMode) private var mode
    let tv: TVSnapshot
    let words: ControlsWords

    var body: some View {
        let level = tv.muted || !tv.on ? 0 : Double(min(max(tv.volume, 0), 100)) / 100
        // − and + as circles as tall as the capsule, inset like the round buttons beside it.
        GeometryReader { geometry in
            let side = max(0, min(geometry.size.height, geometry.size.width / 3) - 10)
            content(level: level, side: side)
                .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .opacity(tv.on ? 1 : 0.5)
        .background {
            Capsule()
                .fill(.white.opacity(0.08))
                .overlay { Capsule().strokeBorder(.white.opacity(0.1), lineWidth: 0.5) }
        }
    }

    private func content(level: Double, side: CGFloat) -> some View {
        HStack(spacing: 0) {
            step(.volumeDown, "minus", side: side)
            VStack(spacing: 7) {
                HStack(spacing: 3) {
                    Image(systemName: tv.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .font(.system(size: 10, weight: .semibold))
                    Text(tv.muted ? words.muted : "\(tv.volume)%")
                        .font(.system(size: 13, weight: .semibold))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                .invalidatableContent()
                Capsule()
                    .fill(.white.opacity(0.22))
                    .frame(height: 4)
                    .overlay(alignment: .leading) {
                        GeometryReader { geometry in
                            Capsule()
                                .fill(.white)
                                .frame(width: max(4, geometry.size.width * level))
                                .widgetAccentable()
                        }
                    }
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 6)
            step(.volumeUp, "plus", side: side)
        }
        .padding(.horizontal, 5)
    }

    private func step(_ control: TVControl, _ symbol: String, side: CGFloat) -> some View {
        Button(intent: ControlTV(control)) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .bold))
                .frame(width: side, height: side)
                .background { Circle().fill(.white.opacity(mode == .accented ? 0.12 : 0.14)) }
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(words.label(control))
    }
}

/// The widgets' words, in the TV's language, as the app's.
struct ControlsWords {
    let name: String
    let description: String
    let pair: String
    let muted: String
    /// For VoiceOver: the tiles show their symbols alone.
    let labels: [TVControl: String]
    /// Off the TV's Wi-Fi, without any, the TV not answering: short (small) and long (medium).
    let problems: [TVReach: (short: String, long: String)]

    func label(_ control: TVControl) -> String { labels[control] ?? control.rawValue }

    func problem(_ problem: TVReach, long: Bool) -> String {
        guard let words = problems[problem] else { return "" }
        return long ? words.long : words.short
    }

    static var current: ControlsWords { of(WidgetFacts.snapshot?.language ?? "mk") }

    static func of(_ language: String) -> ControlsWords { language == "en" ? .en : .mk }

    static let mk = ControlsWords(
        name: "Брзи контроли",
        description: "Напојување, звук, screen saver и светлото на ТВ-то, од почетниот екран.",
        pair: "Отвори го Glass и поврзи го ТВ-то",
        muted: "Без звук",
        labels: [.power: "Напојување", .volumeDown: "Потивко", .volumeUp: "Погласно", .mute: "Без звук",
                 .saver: "Screen saver", .light: "Светло", .inputs: "Влезови", .lastApp: "Последна апликација"],
        problems: [
            .offWifi: ("Нема Wi-Fi", "Поврзи се на Wi-Fi-то на ТВ-то"),
            .otherNetwork: ("Друга Wi-Fi мрежа", "Ова не е Wi-Fi-то на ТВ-то"),
            .noAnswer: ("ТВ-то не се јавува", "ТВ-то не се јавува"),
        ]
    )

    static let en = ControlsWords(
        name: "Quick Controls",
        description: "The TV's power, sound, screen saver and light, from the Home Screen.",
        pair: "Open Glass and connect the TV",
        muted: "Muted",
        labels: [.power: "Power", .volumeDown: "Volume Down", .volumeUp: "Volume Up", .mute: "Mute",
                 .saver: "Screen Saver", .light: "Light", .inputs: "Inputs", .lastApp: "Last App"],
        problems: [
            .offWifi: ("No Wi-Fi", "Join the TV's Wi-Fi to control it"),
            .otherNetwork: ("Other Wi-Fi", "This isn't the TV's Wi-Fi"),
            .noAnswer: ("TV not answering", "The TV isn't answering"),
        ]
    )
}

extension Color {
    /// From the TV's RRGGBB.
    init(hex: String) {
        let value = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0xFFFFFF
        self.init(.sRGB, red: Double((value >> 16) & 0xFF) / 255, green: Double((value >> 8) & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
    }
}

@main
struct GlassWidgets: WidgetBundle {
    var body: some Widget {
        QuickControlsWidget()
    }
}
