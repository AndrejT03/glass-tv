import Foundation
import Network
import Observation
import UIKit

/// A TV found or typed in, on its way to being paired.
struct PairingTarget: Hashable, Identifiable {
    var id: String
    var name: String
    var host: String
    var port: Int
}

/// The TV this iPhone controls: where it is (kept across launches), its token (in the keychain), its
/// state as Glass reports it (followed live while the app is open), and what the app asks of it.
@MainActor
@Observable
final class TVLink {
    struct Saved: Codable, Equatable {
        var id: String
        var name: String
        var host: String
        var port: Int
    }

    enum Status: Equatable { case connecting, live, unreachable }

    private(set) var saved: Saved?
    private(set) var state: TVState?
    private(set) var status: Status = .connecting
    /// When the TV's sleep timer runs out, worked out as its state arrives.
    private(set) var sleepEndsAt: Date?
    /// A short note after the TV refused something (Power Off without its accessibility service…).
    var notice: String?
    /// Whether the phone is where it can reach the TV: on its Wi-Fi. Off any Wi-Fi the app stops trying
    /// until the network changes; on another one it tries now and then.
    private(set) var reach: TVReach = .ok

    /// Why the TV can't be reached, as the app says it: the network's verdict, unless the TV answers
    /// anyway (a home network of several parts).
    var cantReach: TVReach? { status == .live || reach == .ok ? nil : reach }
    /// Bumped when something was asked of the TV while the phone can't reach it, for the banner to nudge.
    private(set) var reachNudge = 0

    let discovery = Discovery()
    let arts = ArtStore()
    let dynamic = DynamicStore()
    let widgets = WidgetBridge()

    @ObservationIgnored private var following: Task<Void, Never>?
    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private let paths = NWPathMonitor()
    @ObservationIgnored private var path: NWPath?
    @ObservationIgnored private var active = false

    init() {
        if let data = defaults.data(forKey: Keys.saved) {
            saved = try? JSONDecoder().decode(Saved.self, from: data)
        }
        // A token kept before the widgets, for their buttons too.
        if let saved { Keychain.share(for: saved.id) }
        #if DEBUG
        WidgetFacts.pretendReach = defaults.string(forKey: "glassReach").flatMap(TVReach.init(rawValue:))
        #endif
        paths.pathUpdateHandler = { [weak self] path in
            MainActor.assumeIsolated { self?.networkChanged(path) }
        }
        paths.start(queue: .main)
    }

    /// The phone joined or left a network: whether it can reach the TV now. Back on the TV's Wi-Fi, it
    /// follows the TV again at once instead of at its next retry.
    private func networkChanged(_ path: NWPath) {
        self.path = path
        let before = reach
        updateReach()
        if reach == .ok, before != .ok, active {
            following?.cancel()
            following = nil
            activate()
        }
    }

    private func updateReach() {
        var now: TVReach = .ok
        if let saved, let path { now = TVReach.of(host: saved.host, path: path) }
        #if DEBUG
        if let pretend = WidgetFacts.pretendReach { now = pretend }
        #endif
        guard now != reach else { return }
        reach = now
        widgets.reach(now)
    }

    /// The app's words: in the TV's language once it is known.
    var strings: Strings {
        if let language = state?.tv.language { return .of(language) }
        if let language = defaults.string(forKey: Keys.language) { return .of(language) }
        return .of(Locale.preferredLanguages.contains { $0.hasPrefix("mk") } ? "mk" : "en")
    }

    private var api: GlassAPI? {
        guard let saved, let base = GlassAPI.base(host: saved.host, port: saved.port) else { return nil }
        return GlassAPI(base: base, token: Keychain.token(for: saved.id))
    }

    // MARK: Pairing

    /// A TV Bonjour found: where it is, then a code on its screen.
    func startPairing(with tv: Discovery.TV) async throws -> PairingTarget {
        let address = try await Discovery.resolve(tv)
        let target = PairingTarget(id: tv.id, name: tv.name, host: address.host, port: address.port)
        try await requestCode(target)
        return target
    }

    /// A TV by the address typed in.
    func startPairing(host: String, port: Int) async throws -> PairingTarget {
        guard let base = GlassAPI.base(host: host, port: port) else { throw GlassAPI.Failure.badAnswer }
        let info = try await GlassAPI(base: base).info()
        let target = PairingTarget(id: info.id, name: info.name, host: host, port: port)
        try await requestCode(target)
        return target
    }

    func requestCode(_ target: PairingTarget) async throws {
        try await api(for: target)?.pair()
    }

    /// The code from the TV's screen: the TV is this app's from now on.
    func confirm(_ target: PairingTarget, code: String) async throws {
        guard let api = api(for: target) else { throw GlassAPI.Failure.badAnswer }
        let token = try await api.confirm(code: code, device: UIDevice.current.name)
        guard Keychain.save(token, for: target.id) else { throw GlassAPI.Failure.keychain }
        save(Saved(id: target.id, name: target.name, host: target.host, port: target.port))
        activate()
    }

    func cancel(_ target: PairingTarget) async {
        await api(for: target)?.cancelPairing()
    }

    /// Unpairs from the TV, and forgets it here.
    func forget() async {
        await api?.unpair()
        forgetHere()
    }

    private func forgetHere() {
        deactivate()
        if let saved { Keychain.delete(for: saved.id) }
        save(nil)
        widgets.forget()
        state = nil
        status = .connecting
    }

    private func api(for target: PairingTarget) -> GlassAPI? {
        GlassAPI.base(host: target.host, port: target.port).map { GlassAPI(base: $0) }
    }

    private func save(_ tv: Saved?) {
        saved = tv
        if let tv, let data = try? JSONEncoder().encode(tv) {
            defaults.set(data, forKey: Keys.saved)
        } else {
            defaults.removeObject(forKey: Keys.saved)
        }
        updateReach()
    }

    // MARK: Following the TV

    /// The app is in front: follow the TV's state until it goes.
    func activate() {
        active = true
        guard saved != nil, following == nil else { return }
        following = Task { await follow() }
    }

    func deactivate() {
        active = false
        following?.cancel()
        following = nil
    }

    private func follow() async {
        var failures = 0
        while !Task.isCancelled, let api = self.api {
            // Off any Wi-Fi nothing can reach the TV: no retries until the network changes.
            guard reach != .offWifi else {
                status = .unreachable
                following = nil
                return
            }
            do {
                if state == nil { status = .connecting }
                for try await next in api.events() {
                    apply(next)
                    status = .live
                    failures = 0
                }
            } catch GlassAPI.Failure.unpaired {
                // The TV no longer knows this phone.
                forgetHere()
                return
            } catch {}
            guard !Task.isCancelled else { return }
            failures += 1
            status = .unreachable
            // The TV may have a new address from the router: Bonjour knows it by its id.
            if failures >= 2 { await rediscover() }
            // On another Wi-Fi, now and then only (in case the network is the TV's after all).
            try? await Task.sleep(for: .seconds(reach == .otherNetwork ? 30 : min(failures * 2, 8)))
        }
    }

    private func rediscover() async {
        guard let saved else { return }
        let browsing = discovery.isBrowsing
        discovery.start()
        defer { if !browsing { discovery.stop() } }
        for _ in 0..<10 {
            if let tv = discovery.tvs.first(where: { $0.id == saved.id }) {
                if let address = try? await Discovery.resolve(tv), address.host != saved.host || address.port != saved.port {
                    var moved = saved
                    moved.host = address.host
                    moved.port = address.port
                    save(moved)
                }
                return
            }
            try? await Task.sleep(for: .milliseconds(400))
        }
    }

    private func apply(_ next: TVState) {
        state = next
        sleepEndsAt = next.sleep.minutes > 0 ? Date(timeIntervalSinceNow: Double(next.sleep.endsInMs) / 1000) : nil
        defaults.set(next.tv.language, forKey: Keys.language)
        if let saved, saved.name != next.tv.name {
            var renamed = saved
            renamed.name = next.tv.name
            save(renamed)
        }
        dynamic.update(next, api: api)
        if let saved { widgets.update(next, tv: saved) }
    }

    // MARK: Asking the TV

    /// One of Glass's actions (a POST); the TV's state after it comes back with the answer.
    func send(_ path: String, _ body: [String: Any] = [:]) async {
        guard let api else { return }
        guard cantReach == nil else {
            // Nothing would get there: say so where the banner is, at once.
            reachNudge += 1
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
            return
        }
        do {
            apply(try await api.act(path, body))
        } catch GlassAPI.Failure.refused(let reason) {
            notice = strings.refusal(reason)
        } catch GlassAPI.Failure.unpaired {
            forgetHere()
        } catch is CancellationError {
            // A newer value of the same slider took its place.
        } catch let error as URLError where error.code == .cancelled {
        } catch {
            status = .unreachable
        }
    }

    func setting(_ key: String, _ value: String) async {
        await send("/v1/settings", ["key": key, "value": value])
    }

    func stats() async -> TVStats? { try? await api?.stats() }

    func loadArt(_ key: String) async { await arts.load(key, from: api) }

    private enum Keys {
        static let saved = "tv"
        static let language = "language"
    }
}

/// The apps' tiles from the TV (its sharp tile art), each loaded once.
@MainActor
@Observable
final class ArtStore {
    private(set) var images: [String: UIImage] = [:]
    @ObservationIgnored private var loading: Set<String> = []

    func load(_ key: String, from api: GlassAPI?) async {
        guard images[key] == nil, !loading.contains(key), let api else { return }
        loading.insert(key)
        defer { loading.remove(key) }
        if let data = try? await api.art(key), let image = UIImage(data: data) {
            images[key] = image
        }
    }
}

/// Sends a slider's value as it moves: the latest one, at most every [every], so the TV follows the
/// finger without a request for every point of it.
@MainActor
final class Throttle {
    private let every: Duration
    private var pending: Task<Void, Never>?
    private var last = ContinuousClock.now - .seconds(10)

    init(every: Duration = .milliseconds(120)) {
        self.every = every
    }

    func run(_ action: @escaping @MainActor () async -> Void) {
        pending?.cancel()
        let wait = every - (ContinuousClock.now - last)
        pending = Task {
            if wait > .zero {
                try? await Task.sleep(for: wait)
                guard !Task.isCancelled else { return }
            }
            last = .now
            await action()
        }
    }
}
