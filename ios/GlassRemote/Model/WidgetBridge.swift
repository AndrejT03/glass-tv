import Foundation
import WidgetKit

/// Keeps the quick-control widgets up to date from the app, cheaply: what they show is written to the
/// app group as the TV's state comes, and WidgetKit is asked to draw them again only when something
/// they show has changed, once things settle (a volume slider sends many states).
@MainActor
final class WidgetBridge {
    private var reload: Task<Void, Never>?

    func update(_ state: TVState, tv: TVLink.Saved) {
        let facts = WidgetFacts.TV(id: tv.id, host: tv.host, port: tv.port)
        if WidgetFacts.tv != facts {
            WidgetFacts.tv = facts
            scheduleReload()
        }
        let snapshot = TVSnapshot(state)
        if WidgetFacts.snapshot != snapshot {
            WidgetFacts.snapshot = snapshot
            scheduleReload()
        }
        // The TV answers: whatever kept the widgets from it is over.
        if WidgetFacts.problem != nil {
            WidgetFacts.problem = nil
            scheduleReload()
        }
    }

    /// The phone's network changed: the widgets say so (or stop saying so) without waiting for a press.
    func reach(_ reach: TVReach) {
        let problem = WidgetFacts.problem
        if reach == .offWifi || reach == .otherNetwork {
            guard problem != reach else { return }
            WidgetFacts.problem = reach
        } else {
            guard problem == .offWifi || problem == .otherNetwork else { return }
            WidgetFacts.problem = nil
        }
        scheduleReload()
    }

    func accent(_ hex: String) {
        guard WidgetFacts.accent != hex else { return }
        WidgetFacts.accent = hex
        scheduleReload()
    }

    /// The TV was forgotten: the widgets have nothing to reach.
    func forget() {
        WidgetFacts.tv = nil
        WidgetFacts.snapshot = nil
        scheduleReload()
    }

    private func scheduleReload() {
        reload?.cancel()
        reload = Task {
            try? await Task.sleep(for: .seconds(1.5))
            guard !Task.isCancelled else { return }
            WidgetCenter.shared.reloadTimelines(ofKind: controlsWidgetKind)
        }
    }
}

extension TVSnapshot {
    init(_ state: TVState) {
        let light = state.light
        self.init(
            name: state.tv.name,
            language: state.tv.language,
            on: state.power.on ?? true,
            volume: state.volume.percent,
            muted: state.volume.muted,
            lightOn: light.on,
            lightSwatch: light.colors.indices.contains(light.color) ? light.colors[light.color].swatch : nil,
            saverShowing: state.saver.showing
        )
    }
}
