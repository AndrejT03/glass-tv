import AppIntents
import Foundation

/// A quick control on the widgets, each one of Glass's actions (the toggles are the TV's own, as only
/// the TV knows for sure what is on).
enum TVControl: String, AppEnum {
    case power, volumeDown, volumeUp, mute, saver, light, inputs, lastApp

    static var typeDisplayRepresentation: TypeDisplayRepresentation { "TV Control" }

    static var caseDisplayRepresentations: [TVControl: DisplayRepresentation] {
        [
            .power: "Power", .volumeDown: "Volume Down", .volumeUp: "Volume Up", .mute: "Mute",
            .saver: "Screen Saver", .light: "Light", .inputs: "Inputs", .lastApp: "Last App",
        ]
    }

    /// The request to Glass (`remote/RemoteApi.kt`): the volume 5% a press.
    var request: (path: String, body: [String: Any]) {
        switch self {
        case .power: ("/v1/power/toggle", [:])
        case .volumeDown: ("/v1/volume", ["step": -5])
        case .volumeUp: ("/v1/volume", ["step": 5])
        case .mute: ("/v1/volume", ["toggleMute": true])
        case .saver: ("/v1/saver", ["action": "toggle"])
        case .light: ("/v1/light", ["toggle": true])
        case .inputs: ("/v1/open", ["screen": "inputs"])
        case .lastApp: ("/v1/apps/last", [:])
        }
    }
}

/// A widget's button: one request to the TV, with the app's token, only when it is pressed. The TV
/// answers with its state, which the widget then shows (WidgetKit draws it again after the press).
struct ControlTV: AppIntent {
    static let title: LocalizedStringResource = "Control the TV"
    static let isDiscoverable = false

    @Parameter(title: "Control")
    var control: TVControl

    init() {
        control = .power
    }

    init(_ control: TVControl) {
        self.control = control
    }

    func perform() async throws -> some IntentResult {
        guard let tv = WidgetFacts.tv, let token = Keychain.token(for: tv.id),
              let url = URL(string: "http://\(tv.host):\(tv.port)\(control.request.path)") else { return .result() }
        // Off any Wi-Fi nothing would get there: the widget says so instead of waiting for a timeout.
        let reach = await TVReach.forWidgets(tv)
        if reach == .offWifi {
            WidgetFacts.problem = .offWifi
            return .result()
        }
        var request = URLRequest(url: url, timeoutInterval: 6)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: control.request.body)
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 6
        guard let (data, response) = try? await URLSession(configuration: config).data(for: request) else {
            // No answer: on another Wi-Fi that says why; on the TV's, the TV didn't answer.
            WidgetFacts.problem = reach == .otherNetwork ? .otherNetwork : .noAnswer
            return .result()
        }
        WidgetFacts.problem = nil
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              var now = try? JSONDecoder().decode(Answer.self, from: data).snapshot else { return .result() }
        // The TV answers before it has gone to sleep or put the saver up: those flip as asked.
        if let before = WidgetFacts.snapshot {
            if control == .power { now.on = !before.on }
            if control == .saver { now.saverShowing = !before.saverShowing }
        }
        WidgetFacts.snapshot = now
        return .result()
    }
}

/// Just what the widgets show of the state the TV answers with.
private struct Answer: Decodable {
    struct TV: Decodable {
        var name: String
        var language: String
    }

    struct Power: Decodable {
        var on: Bool?
    }

    struct Volume: Decodable {
        var percent: Int
        var muted: Bool
    }

    struct Light: Decodable {
        struct Colour: Decodable {
            var swatch: String
        }

        var on: Bool
        var color: Int
        var colors: [Colour]
    }

    struct Saver: Decodable {
        var showing: Bool
    }

    var tv: TV
    var power: Power
    var volume: Volume
    var light: Light
    var saver: Saver

    var snapshot: TVSnapshot {
        TVSnapshot(
            name: tv.name,
            language: tv.language,
            on: power.on ?? true,
            volume: volume.percent,
            muted: volume.muted,
            lightOn: light.on,
            lightSwatch: light.colors.indices.contains(light.color) ? light.colors[light.color].swatch : nil,
            saverShowing: saver.showing
        )
    }
}

extension TVReach {
    /// The phone's network against the TV's address, asked once (no traffic).
    static func forWidgets(_ tv: WidgetFacts.TV) async -> TVReach {
        #if DEBUG
        if let pretend = WidgetFacts.pretendReach { return pretend }
        #endif
        return of(host: tv.host, path: await currentPath())
    }
}
