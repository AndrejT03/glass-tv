import Foundation

/// What Glass on the TV says of itself: `GET /v1/state`, and each event of `/v1/events`. Lists the TV
/// defines (picture presets, the light's colours, the settings' choices) carry their names in the
/// TV's language, so the app shows the TV's own words for them.
struct TVState: Decodable, Equatable, Sendable {
    struct TV: Decodable, Equatable, Sendable {
        var id: String
        /// The household's name for it, or the system's.
        var name: String
        /// The TV's own name, which a blank name gives back (missing from an older Glass).
        var systemName: String?
        var language: String
    }

    struct Power: Decodable, Equatable, Sendable {
        /// The TV is awake, its screen on; asleep, Glass still answers and can wake it. Missing from a
        /// Glass older than Turn On.
        var on: Bool?
        /// Glass's accessibility service is on: only it may put the TV to sleep, or restart it.
        var canSleep: Bool
        /// Missing from a Glass older than the restart.
        var canRestart: Bool?
    }

    /// TCL's sound mode (Standard, Music, Voice…): by TCL's number for it.
    struct Sound: Decodable, Equatable, Sendable {
        var mode: Int
        var modes: [Option<Int>]
    }

    struct Volume: Decodable, Equatable, Sendable {
        var percent: Int
        var muted: Bool
    }

    struct Option<ID: Decodable & Hashable & Sendable>: Decodable, Hashable, Identifiable, Sendable {
        var id: ID
        var label: String
    }

    struct Picture: Decodable, Equatable, Sendable {
        /// TCL's picture service is there (it isn't on other TVs).
        var available: Bool
        /// The preset, a TCL mode number; one not in [modes] is an HDR picture's.
        var mode: Int?
        var backlight: Int?
        var modes: [Option<Int>]
    }

    struct Sleep: Decodable, Equatable, Sendable {
        var minutes: Int
        var endsInMs: Int
        var choices: [Option<Int>]
    }

    struct Light: Decodable, Equatable, Sendable {
        struct Colour: Decodable, Hashable, Sendable {
            var label: String
            /// How it looks on screen, RRGGBB.
            var swatch: String
        }

        var on: Bool
        /// Index in [colors]: 0 is the strip's own, the last one the household's own once there is one.
        var color: Int
        /// The Bluetooth link to the strip: idle, connecting, ready, unreachable or nopermission.
        var link: String
        var permitted: Bool
        var colors: [Colour]
        /// The household's own colour, RRGGBB, set on this app's colour wheel.
        var custom: String?

        var customIndex: Int? { custom == nil ? nil : colors.count - 1 }
    }

    struct Input: Decodable, Equatable, Identifiable, Sendable {
        var id: String
        /// What the household calls it, the device on it, or the port.
        var name: String
        /// The port, when [name] is something else.
        var port: String?
        /// tuner, hdmi, composite or other.
        var kind: String
        var connected: Bool
    }

    struct App: Decodable, Equatable, Identifiable, Sendable {
        var key: String
        var label: String
        var package: String

        var id: String { key }
    }

    struct Saver: Decodable, Equatable, Sendable {
        var showing: Bool
    }

    /// A setting, as the TV's own settings list has it: one of its options.
    struct Choice: Decodable, Equatable, Identifiable, Sendable {
        var key: String
        var title: String
        var value: String
        var options: [Option<String>]

        var id: String { key }
        var label: String { options.first { $0.id == value }?.label ?? value }
    }

    var tv: TV
    var power: Power
    var volume: Volume
    var picture: Picture
    var sleep: Sleep
    var light: Light
    var inputs: [Input]
    /// The input on screen, while the TV shows one.
    var currentInput: String?
    /// The TV's apps, in the household's order.
    var apps: [App]
    var recent: [String]
    var lastApp: String?
    /// The app in front (a package), nil on the home screen.
    var front: String?
    /// Where the TV has TCL's sound modes (not on other TVs).
    var sound: Sound?
    var saver: Saver
    var settings: [Choice]

    func setting(_ key: String) -> Choice? { settings.first { $0.key == key } }
}

/// The TV's performance (`GET /v1/stats`), as the control center's tile on the TV shows it.
struct TVStats: Decodable, Equatable, Sendable {
    /// How long the internet takes to answer; -1 when it didn't.
    var pingMs: Int
    var ram: Double
    var ramUsedGb: Double
    var ramTotalGb: Double
    var downMbps: Double?
}

/// Which TV answers (`GET /v1/info`), before pairing.
struct TVInfo: Decodable, Sendable {
    var app: String
    var api: Int
    var id: String
    var name: String
    var paired: Bool
}
