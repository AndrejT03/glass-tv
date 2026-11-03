import Foundation

/// The app group the app and its widgets share: which TV, and what the app last heard from it.
///
/// Its name is read from the entitlements this very binary is signed with, as signing tools may
/// give the group another name to fit the certificate (Sideloadly, AltStore, a free Apple ID): the app
/// and the widgets each read their own signature, so they agree on whatever name it got. Then the
/// provisioning profile inside the bundle, AltStore's `ALTAppGroups`, and the name it was built with.
enum AppGroup {
    static let built = "group.com.andrej.glassremote"

    static let id: String = signed().first(where: { $0.contains("glassremote") }) ?? signed().first ?? built

    /// Nil when the app was signed without an app group: the widgets then can't know the TV.
    static var defaults: UserDefaults? { available ? UserDefaults(suiteName: id) : nil }

    static var available: Bool { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) != nil }

    private static func signed() -> [String] {
        if let groups = signature, !groups.isEmpty { return groups }
        if let groups = profile, !groups.isEmpty { return groups }
        return altGroups
    }

    private static let groupsKey = "com.apple.security.application-groups"

    /// From the entitlements blob of the executable's code signature (what the system enforces).
    private static let signature: [String]? = {
        guard let url = Bundle.main.executableURL, let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        func read(_ offset: UInt64, _ count: Int) -> Data? {
            try? handle.seek(toOffset: offset)
            return try? handle.read(upToCount: count)
        }
        func u32(_ d: Data, _ at: Int, big: Bool) -> UInt32 {
            guard at + 4 <= d.count else { return 0 }
            let v = d.subdata(in: d.startIndex + at ..< d.startIndex + at + 4).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }
            return big ? UInt32(bigEndian: v) : UInt32(littleEndian: v)
        }
        guard var header = read(0, 4096) else { return nil }
        var slice: UInt64 = 0
        // A fat binary: the arm64 slice.
        if u32(header, 0, big: true) == 0xCAFE_BABE {
            let count = Int(u32(header, 4, big: true))
            for k in 0 ..< count where u32(header, 8 + k * 20, big: true) == 0x0100_000C {
                slice = UInt64(u32(header, 8 + k * 20 + 8, big: true))
            }
            guard slice > 0, let thin = read(slice, 4096) else { return nil }
            header = thin
        }
        guard u32(header, 0, big: false) == 0xFEED_FACF, let commands = read(slice, 32 + Int(u32(header, 20, big: false))) else { return nil }
        var at = 32
        for _ in 0 ..< Int(u32(header, 16, big: false)) {
            let cmd = u32(commands, at, big: false), size = Int(u32(commands, at + 4, big: false))
            guard size > 0 else { return nil }
            // LC_CODE_SIGNATURE: a superblob of big-endian blobs; the entitlements one is type 5.
            if cmd == 0x1D, let blob = read(slice + UInt64(u32(commands, at + 8, big: false)), Int(u32(commands, at + 12, big: false))),
               u32(blob, 0, big: true) == 0xFADE_0CC0 {
                for k in 0 ..< Int(u32(blob, 8, big: true)) where u32(blob, 12 + k * 8, big: true) == 5 {
                    let start = Int(u32(blob, 16 + k * 8, big: true))
                    guard u32(blob, start, big: true) == 0xFADE_7171 else { continue }
                    let length = Int(u32(blob, start + 4, big: true))
                    guard start + length <= blob.count else { return nil }
                    let xml = blob.subdata(in: blob.startIndex + start + 8 ..< blob.startIndex + start + length)
                    let plist = try? PropertyListSerialization.propertyList(from: xml, format: nil) as? [String: Any]
                    return plist?[groupsKey] as? [String]
                }
            }
            at += size
        }
        return nil
    }()

    /// From embedded.mobileprovision, whose plist sits in plain text inside its CMS wrapper.
    private static let profile: [String]? = {
        guard let data = try? Data(contentsOf: Bundle.main.bundleURL.appending(path: "embedded.mobileprovision")),
              let start = data.range(of: Data("<?xml".utf8)), let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound ..< data.endIndex),
              let plist = try? PropertyListSerialization.propertyList(from: data.subdata(in: start.lowerBound ..< end.upperBound), format: nil) as? [String: Any]
        else { return nil }
        return (plist["Entitlements"] as? [String: Any])?[groupsKey] as? [String]
    }()

    /// AltStore's note of the names it gave, in the app's Info.plist (the widgets are inside the app:
    /// Glass.app/PlugIns/…appex).
    private static var altGroups: [String] {
        var bundle = Bundle.main
        if bundle.bundleURL.pathExtension == "appex",
           let app = Bundle(url: bundle.bundleURL.deletingLastPathComponent().deletingLastPathComponent()) {
            bundle = app
        }
        return bundle.object(forInfoDictionaryKey: "ALTAppGroups") as? [String] ?? []
    }
}

/// The widgets' kind, for the app to ask WidgetKit to draw them again.
let controlsWidgetKind = "QuickControls"

/// What the widgets show of the TV: as the app last heard it, or as the TV answered a widget's button.
struct TVSnapshot: Codable, Equatable, Sendable {
    var name: String
    var language: String
    /// Awake (its screen on); asleep, Glass still answers and can wake it.
    var on: Bool
    var volume: Int
    var muted: Bool
    var lightOn: Bool
    /// The light's colour on screen, RRGGBB.
    var lightSwatch: String?
    var saverShowing: Bool
}

/// What the app leaves for the widgets: the TV to reach (its token is in the app group's keychain
/// group), the TV as last heard, and the app's accent.
enum WidgetFacts {
    struct TV: Codable, Equatable, Sendable {
        var id: String
        var host: String
        var port: Int
    }

    private static var defaults: UserDefaults? { AppGroup.defaults }

    static var tv: TV? {
        get { defaults?.data(forKey: "tv").flatMap { try? JSONDecoder().decode(TV.self, from: $0) } }
        set { defaults?.set(newValue.flatMap { try? JSONEncoder().encode($0) }, forKey: "tv") }
    }

    static var snapshot: TVSnapshot? {
        get { defaults?.data(forKey: "snapshot").flatMap { try? JSONDecoder().decode(TVSnapshot.self, from: $0) } }
        set { defaults?.set(newValue.flatMap { try? JSONEncoder().encode($0) }, forKey: "snapshot") }
    }

    /// RRGGBB.
    static var accent: String? {
        get { defaults?.string(forKey: "accent") }
        set { defaults?.set(newValue, forKey: "accent") }
    }

    /// Why the widgets can't reach the TV now (no Wi-Fi, another network, no answer); nil when they can.
    static var problem: TVReach? {
        get { defaults?.string(forKey: "problem").flatMap(TVReach.init(rawValue:)).flatMap { $0 == .ok ? nil : $0 } }
        set { defaults?.set(newValue?.rawValue, forKey: "problem") }
    }

    /// Debug builds: a network the app was started to pretend it is on (`-glassReach offWifi`), so
    /// the simulator, always on the Mac's network, can show the widgets' warning too.
    static var pretendReach: TVReach? {
        get { defaults?.string(forKey: "pretendReach").flatMap(TVReach.init(rawValue:)) }
        set { defaults?.set(newValue?.rawValue, forKey: "pretendReach") }
    }

    /// The app group the widgets found, noted when WidgetKit last asked them for a timeline: the app's
    /// Settings tell from it whether the widgets reach what the app leaves them.
    static var widgetsGroup: String? {
        get { defaults?.string(forKey: "widgetsGroup") }
        set { defaults?.set(newValue, forKey: "widgetsGroup") }
    }
}
