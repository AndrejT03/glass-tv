import Foundation
import Network
import Observation

/// The TVs running Glass on the home network, as Bonjour finds them (`_glass._tcp`, which Glass
/// advertises with its id, so a TV is known again at a new address).
@MainActor
@Observable
final class Discovery {
    /// One TV, however many times Bonjour sees it (on each network interface, or an old announcement).
    struct TV: Identifiable, Hashable {
        var id: String
        var name: String
        var endpoints: [NWEndpoint]
    }

    struct Address: Equatable, Sendable {
        var host: String
        var port: Int
    }

    private(set) var tvs: [TV] = []
    @ObservationIgnored private var browser: NWBrowser?

    var isBrowsing: Bool { browser != nil }

    func start() {
        guard browser == nil else { return }
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_glass._tcp", domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let found = Self.tvs(in: results)
            MainActor.assumeIsolated { self?.tvs = found }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    func stop() {
        browser?.cancel()
        browser = nil
        tvs = []
    }

    private nonisolated static func tvs(in results: Set<NWBrowser.Result>) -> [TV] {
        var byID: [String: TV] = [:]
        for result in results {
            guard case let .service(name, _, _, _) = result.endpoint else { continue }
            var id = name
            if case let .bonjour(txt) = result.metadata, let tvID = txt["id"] { id = tvID }
            var tv = byID[id] ?? TV(id: id, name: name, endpoints: [])
            // The same name each time, whichever announcement comes first.
            if name.localizedStandardCompare(tv.name) == .orderedAscending { tv.name = name }
            tv.endpoints.append(result.endpoint)
            byID[id] = tv
        }
        return byID.values.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    /// The first of a TV's announcements that leads somewhere.
    static func resolve(_ tv: TV) async throws -> Address {
        for endpoint in tv.endpoints {
            if let address = try? await resolve(endpoint) { return address }
        }
        throw GlassAPI.Failure.badAnswer
    }

    /// The address behind a TV Bonjour found: IPv4, for a plain http:// URL.
    static func resolve(_ endpoint: NWEndpoint) async throws -> Address {
        let parameters = NWParameters.tcp
        if let ip = parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }
        let connection = NWConnection(to: endpoint, using: parameters)
        defer { connection.cancel() }
        let resolver = Resolver()
        connection.stateUpdateHandler = { [weak connection] state in
            let address = connection?.currentPath?.remoteEndpoint.flatMap(Self.address)
            MainActor.assumeIsolated { resolver.changed(state, address) }
        }
        connection.start(queue: .main)
        return try await resolver.address()
    }

    private nonisolated static func address(_ endpoint: NWEndpoint) -> Address? {
        guard case let .hostPort(host, port) = endpoint else { return nil }
        let text: String = switch host {
        case .ipv4(let ip): "\(ip)".components(separatedBy: "%")[0]
        case .ipv6(let ip): "[\("\(ip)".components(separatedBy: "%")[0])]"
        case .name(let name, _): name
        @unknown default: "\(host)"
        }
        return Address(host: text, port: Int(port.rawValue))
    }

    /// Waits for a connection to say where it went: ready (its address), failed, or three seconds.
    @MainActor
    private final class Resolver {
        private var continuation: CheckedContinuation<Address, Error>?
        private var result: Result<Address, Error>?

        func changed(_ state: NWConnection.State, _ address: Address?) {
            switch state {
            case .ready: finish(address.map { .success($0) } ?? .failure(GlassAPI.Failure.badAnswer))
            case .failed(let error), .waiting(let error): finish(.failure(error))
            default: break
            }
        }

        func address() async throws -> Address {
            if let result { return try result.get() }
            return try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                Task {
                    try? await Task.sleep(for: .seconds(3))
                    self.finish(.failure(GlassAPI.Failure.badAnswer))
                }
            }
        }

        private func finish(_ outcome: Result<Address, Error>) {
            guard result == nil else { return }
            result = outcome
            continuation?.resume(with: outcome)
            continuation = nil
        }
    }
}
