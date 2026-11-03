import Foundation

/// Glass's server on the TV (`remote/RemoteServer.kt`, port 7676): JSON over HTTP on the home
/// network, each request with the token pairing gave. Every POST answers with the TV's state after it.
struct GlassAPI {
    let base: URL
    var token: String? = nil

    enum Failure: Error, Equatable {
        /// The TV doesn't know this phone (any more).
        case unpaired
        case wrongCode
        case codeOver
        /// The TV couldn't do it: its reason, as Glass names it ("needs-service", "no-saver", …).
        case refused(String)
        case badAnswer
        /// The phone couldn't keep the token pairing gave.
        case keychain
    }

    static let port = 7676

    static func base(host: String, port: Int) -> URL? { URL(string: "http://\(host):\(port)") }

    func info() async throws -> TVInfo { try decode(await send("GET", "/v1/info")) }

    func pair() async throws { _ = try await send("POST", "/v1/pair") }

    func cancelPairing() async { _ = try? await send("POST", "/v1/pair/cancel") }

    /// The code from the TV's screen, for the token kept from then on.
    func confirm(code: String, device: String) async throws -> String {
        struct Answer: Decodable { var token: String }
        let answer: Answer = try decode(await send("POST", "/v1/pair/confirm", ["code": code, "device": device]))
        return answer.token
    }

    func unpair() async { _ = try? await send("POST", "/v1/unpair") }

    func state() async throws -> TVState { try decode(await send("GET", "/v1/state")) }

    func stats() async throws -> TVStats { try decode(await send("GET", "/v1/stats")) }

    func act(_ path: String, _ body: [String: Any]) async throws -> TVState { try decode(await send("POST", path, body)) }

    func art(_ key: String) async throws -> Data {
        var components = URLComponents(url: base.appending(path: "/v1/apps/art"), resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "key", value: key)]
        guard let url = components?.url else { throw Failure.badAnswer }
        return try await send("GET", url: url)
    }

    /// Apple's dynamic desktop pictures on the TV (`dynamic.json`, as push_dynamic.sh made it): as it comes.
    func dynamic() async throws -> Data { try await send("GET", "/v1/dynamic") }

    /// One frame of a dynamic desktop picture, the TV's 1200x1200 square of it.
    func dynamicFrame(_ id: String, _ frame: Int) async throws -> Data {
        var components = URLComponents(url: base.appending(path: "/v1/dynamic/frame"), resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "id", value: id), URLQueryItem(name: "frame", value: String(frame))]
        guard let url = components?.url else { throw Failure.badAnswer }
        return try await send("GET", url: url)
    }

    /// The TV's state now, then again whenever it changes, until the connection ends.
    func events() -> AsyncThrowingStream<TVState, Error> {
        let request = eventsRequest()
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, response) = try await Self.streams.bytes(for: request)
                    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                    if status == 401 { throw Failure.unpaired }
                    guard status == 200 else { throw Failure.refused("status-\(status)") }
                    for try await line in bytes.lines where line.hasPrefix("data: ") {
                        continuation.yield(try JSONDecoder().decode(TVState.self, from: Data(line.dropFirst(6).utf8)))
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func eventsRequest() -> URLRequest {
        var request = URLRequest(url: base.appending(path: "/v1/events"))
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        // The TV sends a comment every 15 s at the least, so a quiet 45 s means it has gone.
        request.timeoutInterval = 45
        return request
    }

    private func send(_ method: String, _ path: String, _ body: [String: Any]? = nil) async throws -> Data {
        try await send(method, url: base.appending(path: path), body: body)
    }

    private func send(_ method: String, url: URL, body: [String: Any]? = nil) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = method
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await Self.session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 200 { return data }
        let reason = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String ?? "status-\(status)"
        switch (status, reason) {
        case (401, "wrong-code"): throw Failure.wrongCode
        case (401, _): throw Failure.unpaired
        case (410, _): throw Failure.codeOver
        default: throw Failure.refused(reason)
        }
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw Failure.badAnswer
        }
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 6
        return URLSession(configuration: config)
    }()

    private static let streams: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 45
        config.timeoutIntervalForResource = 24 * 60 * 60
        return URLSession(configuration: config)
    }()
}
