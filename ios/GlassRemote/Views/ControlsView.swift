import SwiftUI

/// The TV's control center and quick settings on the phone: glass tiles as on the TV, the light's
/// colours as glass drops.
struct ControlsView: View {
    @Environment(TVLink.self) private var link
    /// Turn Off or Turn On as asked, shown at once; the TV's own word takes over once its screen has
    /// gone off or on (or, without a word, after a while).
    @State private var poweringTo: Bool?

    var body: some View {
        NavigationStack {
            ScrollView {
                if let state = link.state {
                    let on = poweringTo ?? state.power.on ?? true
                    VStack(spacing: 14) {
                        GlassEffectContainer(spacing: 4) {
                            VStack(spacing: 14) {
                                HStack(spacing: 14) {
                                    PowerTile(state: state, on: on, poweringTo: $poweringTo)
                                    RestartTile(state: state).asleep(!on)
                                }
                                Group {
                                    SleepTile(state: state)
                                    VolumeTile(state: state)
                                    if state.picture.available {
                                        PictureTile(state: state)
                                    }
                                }
                                .asleep(!on)
                            }
                        }
                        LightTiles(state: state)
                        MenusRow().asleep(!on)
                        PerformanceTile()
                    }
                    .animation(.smooth(duration: 0.3), value: on)
                    .onChange(of: state.power.on) { _, now in
                        if now == poweringTo { poweringTo = nil }
                    }
                    .task(id: poweringTo) {
                        guard poweringTo != nil else { return }
                        try? await Task.sleep(for: .seconds(12))
                        poweringTo = nil
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 28)
                } else {
                    Waiting()
                }
            }
            .softEdges()
            .background { Backdrop() }
            // The TV's own name over its controls, as the household calls it (Settings renames it).
            .navigationTitle(link.state?.tv.name ?? link.saved?.name ?? link.strings.controls)
        }
    }
}

private extension View {
    /// While the TV sleeps, what only works with it awake fades back and takes no touches.
    func asleep(_ asleep: Bool) -> some View {
        opacity(asleep ? 0.4 : 1).disabled(asleep)
    }
}

/// Power or Restart: rare, so a low tile, its symbol on a coloured disc beside what it does, and a word
/// under it only where there is something to say (the TV off, or what it can't do). A new title
/// blurs into place, as one state into the next.
private struct ActionTile: View {
    let symbol: String
    let fill: Color
    let title: String
    let detail: String?

    var body: some View {
        HStack(spacing: 10) {
            Disc(symbol: symbol, fill: fill, size: 36)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.headline)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                    .id(title)
                    .transition(.blurReplace)
                if let detail {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .transition(.opacity)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Power: Turn Off while the TV is awake (asked first), Turn On while it sleeps. Either shows at once;
/// the TV's word follows once its screen has gone off or on.
private struct PowerTile: View {
    @Environment(TVLink.self) private var link
    let state: TVState
    let on: Bool
    @Binding var poweringTo: Bool?
    @State private var asking = false

    var body: some View {
        let s = link.strings
        Button {
            if on { asking = true } else { power(on: true) }
        } label: {
            ActionTile(
                symbol: "power",
                fill: on ? .red : .green,
                title: on ? s.powerOff : s.powerOn,
                detail: !on ? s.off : state.power.canSleep ? nil : s.needsService
            )
        }
        .liquidButton(.rect(cornerRadius: 24), swell: 1.03)
        .confirmationDialog(s.powerOffConfirm, isPresented: $asking, titleVisibility: .visible) {
            Button(s.powerOff, role: .destructive) { power(on: false) }
            Button(s.cancel, role: .cancel) {}
        }
    }

    private func power(on: Bool) {
        Haptics.tap()
        poweringTo = on
        Task {
            link.notice = nil
            await link.send(on ? "/v1/power/on" : "/v1/power/off")
            // Refused (no accessibility service): back to what the TV is.
            if link.notice != nil { poweringTo = nil }
        }
    }
}

/// Restarts the TV: Glass presses Restart in the TV's own power menu, as the remote's held Power would.
private struct RestartTile: View {
    @Environment(TVLink.self) private var link
    let state: TVState
    @State private var asking = false

    var body: some View {
        let s = link.strings
        let can = state.power.canRestart ?? false
        Button { asking = true } label: {
            ActionTile(symbol: "arrow.clockwise", fill: .orange, title: s.restart, detail: can ? nil : s.needsService)
        }
        .liquidButton(.rect(cornerRadius: 24), swell: 1.03)
        .confirmationDialog(s.restartConfirm, isPresented: $asking, titleVisibility: .visible) {
            Button(s.restart, role: .destructive) {
                Task { await link.send("/v1/power/restart") }
            }
            Button(s.cancel, role: .cancel) {}
        }
    }
}

private struct SleepTile: View {
    @Environment(TVLink.self) private var link
    let state: TVState
    @State private var pending: Int?

    var body: some View {
        let s = link.strings
        let minutes = pending ?? state.sleep.minutes
        let on = minutes > 0
        Menu {
            Picker(s.sleepTimer, selection: Binding(get: { minutes }, set: { minutes in
                Haptics.select()
                pending = minutes
                Task {
                    await link.send("/v1/sleep", ["minutes": minutes])
                    if pending == minutes { pending = nil }
                }
            })) {
                ForEach(state.sleep.choices) { choice in
                    Text(choice.label).tag(choice.id)
                }
            }
        } label: {
            HStack(spacing: 12) {
                Disc(symbol: on ? "moon.zzz.fill" : "moon.zzz", fill: on ? .indigo : .white.opacity(0.16), size: 44)
                Text(s.sleepTimer).font(.headline).lineLimit(1).minimumScaleFactor(0.75)
                Spacer(minLength: 8)
                Group {
                    if pending == nil, on, let end = link.sleepEndsAt {
                        Text(timerInterval: Date.now...max(end, .now), countsDown: true)
                    } else if on {
                        Text(state.sleep.choices.first { $0.id == minutes }?.label ?? "")
                    } else {
                        Text(s.off)
                    }
                }
                .font(.subheadline.monospacedDigit().weight(.semibold))
                .foregroundStyle(.secondary)
                .fixedSize()
                Image(systemName: "chevron.up.chevron.down").font(.caption2.weight(.bold)).foregroundStyle(.secondary)
            }
            .padding(16)
            .frame(maxWidth: .infinity)
        }
        .liquidMenu(.rect(cornerRadius: 28), swell: 1.03)
    }
}

/// A choice on a glass tile (the picture preset, the sound mode): its value on a capsule that opens
/// the choices in a glass menu, and gives under the finger. A choice shows at once, while the TV
/// takes it (TCL's sound mode, a second); the TV's own answer replaces it, or puts back what was.
private struct TileMenu: View {
    let title: String
    let value: Int?
    let options: [TVState.Option<Int>]
    var fallback = "–"
    let pick: (Int) async -> Void
    @State private var pending: Int?

    var body: some View {
        let shown = pending ?? value
        Menu {
            Picker(title, selection: Binding(get: { shown ?? -1 }, set: { id in
                Haptics.select()
                pending = id
                Task {
                    await pick(id)
                    if pending == id { pending = nil }
                }
            })) {
                ForEach(options) { option in
                    Text(option.label).tag(option.id)
                }
            }
        } label: {
            HStack(spacing: 6) {
                Text(options.first { $0.id == shown }?.label ?? fallback).lineLimit(1)
                Image(systemName: "chevron.up.chevron.down").font(.caption2.weight(.bold))
            }
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(Capsule().fill(.white.opacity(0.14)))
            .contentShape(.capsule)
        }
        .menuStyle(.button)
        .buttonStyle(SpringButtonStyle(scale: 0.94))
    }
}

private struct VolumeTile: View {
    @Environment(TVLink.self) private var link
    let state: TVState
    @State private var level = 0.0
    @State private var dragging = false
    @State private var throttle = Throttle()

    var body: some View {
        let s = link.strings
        let muted = state.volume.muted
        Tile {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    Button {
                        Haptics.tap()
                        Task { await link.send("/v1/volume", ["mute": !muted]) }
                    } label: {
                        Disc(symbol: muted ? "speaker.slash.fill" : "speaker.wave.2.fill", fill: muted ? .white : .white.opacity(0.16), ink: muted ? .black : .white, size: 44)
                    }
                    .buttonStyle(SpringButtonStyle())
                    Text(s.volume).font(.headline)
                    Spacer(minLength: 8)
                    // TCL's sound mode, which Glass asks TCL's settings for.
                    if let sound = state.sound {
                        TileMenu(title: s.soundMode, value: sound.mode, options: sound.modes) { mode in
                            await link.send("/v1/sound", ["mode": mode])
                        }
                    }
                }
                HStack(spacing: 12) {
                    Image(systemName: "speaker.fill").foregroundStyle(.secondary)
                    Slider(value: $level, in: 0...100) { editing in
                        dragging = editing
                        if !editing { send(level, now: true) }
                    }
                    Image(systemName: "speaker.wave.3.fill").foregroundStyle(.secondary)
                    Text(muted ? "–" : "\(Int(level.rounded()))")
                        .font(.subheadline.monospacedDigit().weight(.semibold))
                        .contentTransition(.numericText())
                        .frame(minWidth: 30, alignment: .trailing)
                }
            }
        }
        .onAppear { level = Double(state.volume.percent) }
        .onChange(of: state.volume.percent) { _, percent in
            if !dragging { withAnimation(.snappy) { level = Double(percent) } }
        }
        .onChange(of: level) { _, value in
            if dragging { send(value, now: false) }
        }
    }

    private func send(_ value: Double, now: Bool) {
        let percent = Int(value.rounded())
        throttle.run { await link.send("/v1/volume", ["percent": percent]) }
        if now { Haptics.tap() }
    }
}

private struct PictureTile: View {
    @Environment(TVLink.self) private var link
    let state: TVState
    @State private var backlight = 0.0
    @State private var dragging = false
    @State private var throttle = Throttle()

    var body: some View {
        let s = link.strings
        Tile {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    Disc(symbol: "tv.inset.filled", size: 44)
                    Text(s.picture).font(.headline)
                    Spacer(minLength: 8)
                    // A preset not in the list is an HDR picture's, which TCL's own menu handles.
                    TileMenu(title: s.picture, value: state.picture.mode, options: state.picture.modes, fallback: "HDR") { mode in
                        await link.send("/v1/picture", ["mode": mode])
                    }
                }
                if state.picture.backlight != nil {
                    HStack(spacing: 12) {
                        Image(systemName: "sun.min.fill").foregroundStyle(.secondary)
                        Slider(value: $backlight, in: 0...100) { editing in
                            dragging = editing
                            if !editing { send(backlight) }
                        }
                        Image(systemName: "sun.max.fill").foregroundStyle(.secondary)
                        Text("\(Int(backlight.rounded()))")
                            .font(.subheadline.monospacedDigit().weight(.semibold))
                            .contentTransition(.numericText())
                            .frame(minWidth: 30, alignment: .trailing)
                    }
                    .accessibilityLabel(s.brightness)
                }
            }
        }
        .onAppear { backlight = Double(state.picture.backlight ?? 0) }
        .onChange(of: state.picture.backlight) { _, value in
            if !dragging, let value { withAnimation(.snappy) { backlight = Double(value) } }
        }
        .onChange(of: backlight) { _, value in
            if dragging { send(value) }
        }
    }

    private func send(_ value: Double) {
        let percent = Int(value.rounded())
        throttle.run { await link.send("/v1/picture", ["backlight": percent]) }
    }
}

/// The light behind the TV: a tile to turn it on or off, and its colours as glass drops below it,
/// the household's own colour on the colour wheel at the end.
private struct LightTiles: View {
    @Environment(TVLink.self) private var link
    let state: TVState
    @State private var custom: Color
    @State private var throttle = Throttle(every: .milliseconds(200))
    @State private var askDefault = false
    @State private var pendingOn: Bool?

    init(state: TVState) {
        self.state = state
        _custom = State(initialValue: Color(hex: state.light.custom ?? "FFFFFF"))
    }

    var body: some View {
        let s = link.strings
        let light = state.light
        let colour = light.colors.indices.contains(light.color) ? light.colors[light.color] : nil
        let on = pendingOn ?? light.on
        VStack(spacing: 12) {
            Tile {
                HStack(spacing: 12) {
                    Disc(
                        symbol: on ? "lightbulb.fill" : "lightbulb",
                        fill: on ? Color(hex: colour?.swatch ?? "FFFFFF") : .white.opacity(0.16),
                        ink: on ? .black : .white,
                        size: 44
                    )
                    .animation(.smooth(duration: 0.25), value: on)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.light).font(.headline)
                        Text(status(light, on: on, colour: colour?.label)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    Toggle(s.light, isOn: Binding(get: { on }, set: { on in
                        Haptics.select()
                        pendingOn = on
                        Task {
                            await link.send("/v1/light", ["on": on])
                            if pendingOn == on { pendingOn = nil }
                        }
                    }))
                    .labelsHidden()
                }
            }
            ScrollViewReader { drops in
                ScrollView(.horizontal, showsIndicators: false) {
                    // Drops 10 pt apart that run together a little only as the chosen one swells.
                    GlassEffectContainer(spacing: 8) {
                        HStack(spacing: 10) {
                            ForEach(Array(light.colors.enumerated()), id: \.offset) { index, choice in
                                let chosen = index == light.color
                                Button {
                                    Haptics.select()
                                    Task { await link.send("/v1/light", ["color": index]) }
                                } label: {
                                    Color.clear
                                        .frame(width: chosen ? 50 : 42, height: chosen ? 50 : 42)
                                        .overlay {
                                            if chosen {
                                                Image(systemName: "checkmark").font(.subheadline.weight(.bold)).foregroundStyle(.black.opacity(0.7))
                                            }
                                        }
                                }
                                .liquidButton(.circle, tint: Color(hex: choice.swatch), swell: 1.15)
                                .accessibilityLabel(choice.label)
                                .id(index)
                            }
                            ColorPicker(s.light, selection: $custom, supportsOpacity: false)
                                .labelsHidden()
                                .frame(width: 42, height: 42)
                                .glassEffect(.regular.interactive(), in: .circle)
                        }
                        .padding(.horizontal, 6)
                        .padding(.vertical, 6)
                    }
                }
                .scrollClipDisabled()
                .animation(.snappy(duration: 0.3), value: light.color)
                // The chosen colour in view, also when it is far along the row.
                .onAppear { drops.scrollTo(light.color, anchor: .center) }
            }
            if let index = light.customIndex, index == light.color, let rgb = light.custom {
                Button { askDefault = true } label: {
                    Text(s.setDefault).font(.subheadline.weight(.semibold)).padding(.horizontal, 16).padding(.vertical, 10)
                }
                    .liquidButton(.capsule)
                    .confirmationDialog(s.setDefaultConfirm, isPresented: $askDefault, titleVisibility: .visible) {
                        Button(s.setDefault) { Task { await link.send("/v1/light", ["default": rgb]) } }
                        Button(s.cancel, role: .cancel) {}
                    }
            }
        }
        .disabled(!light.permitted)
        .onChange(of: custom) { _, picked in
            let rgb = picked.hex
            throttle.run { await link.send("/v1/light", ["rgb": rgb]) }
        }
    }

    private func status(_ light: TVState.Light, on: Bool, colour: String?) -> String {
        let s = link.strings
        if !light.permitted { return s.needsBluetooth }
        switch light.link {
        case "unreachable": return s.stripAway
        case "connecting": return s.connecting
        default: return on ? (colour ?? s.on) : s.off
        }
    }
}

/// Glass's own menus, on the TV over whatever is on: its control center, its inputs menu and its quick
/// settings, opened (and closed again) as the remote's buttons do.
private struct MenusRow: View {
    @Environment(TVLink.self) private var link

    var body: some View {
        let s = link.strings
        GlassEffectContainer(spacing: 4) {
            HStack(spacing: 10) {
                item(symbol: "switch.2", label: s.controlCenter, screen: "controlCenter")
                item(symbol: "cable.connector.horizontal", label: s.inputs, screen: "inputs")
                item(symbol: "slider.horizontal.3", label: s.quickSettings, screen: "quickSettings")
            }
        }
    }

    private func item(symbol: String, label: String, screen: String) -> some View {
        Button {
            Haptics.tap()
            Task { await link.send("/v1/open", ["screen": screen]) }
        } label: {
            VStack(spacing: 7) {
                Image(systemName: symbol)
                    .font(.title3.weight(.semibold))
                    .frame(height: 26)
                Text(label)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .padding(.vertical, 14)
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity)
        }
        .liquidButton(.rect(cornerRadius: 22), swell: 1.03)
    }
}

/// The TV's performance, as its control center's tile: how fast the internet answers, its memory and
/// what the network brings in, read every two seconds while the tile is on screen.
private struct PerformanceTile: View {
    @Environment(TVLink.self) private var link
    @State private var stats: TVStats?

    var body: some View {
        let s = link.strings
        Tile {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    Disc(symbol: "gauge.with.dots.needle.67percent", size: 44)
                    Text(s.performance).font(.headline)
                }
                HStack(alignment: .top) {
                    Ring(title: s.internet, value: ping, fraction: pingFraction, tint: pingTint)
                    Ring(title: s.memory, value: memory, fraction: stats?.ram ?? 0, tint: .white)
                    Ring(title: s.network, value: download, fraction: min((stats?.downMbps ?? 0) / 100, 1), tint: .cyan)
                }
            }
        }
        .task {
            while !Task.isCancelled {
                if let next = await link.stats() {
                    withAnimation(.smooth) { stats = next }
                }
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    private var ping: String {
        guard let ms = stats?.pingMs else { return "–" }
        return ms < 0 ? link.strings.noAnswer : "\(ms) ms"
    }

    private var pingFraction: Double {
        guard let ms = stats?.pingMs else { return 0 }
        return ms < 0 ? 1 : max(0.06, min(Double(ms) / 200, 1))
    }

    // As the TV's ring: green under 60 ms, yellow under 150, red above or without an answer.
    private var pingTint: Color {
        guard let ms = stats?.pingMs else { return .white }
        if ms < 0 || ms >= 150 { return .red }
        return ms < 60 ? .green : .yellow
    }

    private var memory: String {
        guard let stats else { return "–" }
        return String(format: "%.1f / %.1f GB", stats.ramUsedGb, stats.ramTotalGb)
    }

    private var download: String {
        guard let down = stats?.downMbps else { return "–" }
        return String(format: down < 10 ? "%.1f Mb/s" : "%.0f Mb/s", down)
    }
}

private struct Ring: View {
    let title: String
    let value: String
    let fraction: Double
    let tint: Color

    var body: some View {
        VStack(spacing: 10) {
            ZStack {
                Circle().stroke(.white.opacity(0.12), lineWidth: 7)
                Circle()
                    .trim(from: 0, to: max(0.001, min(fraction, 1)))
                    .stroke(tint, style: StrokeStyle(lineWidth: 7, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            .frame(width: 64, height: 64)
            VStack(spacing: 2) {
                Text(value)
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .contentTransition(.numericText())
                Text(title).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
    }
}
