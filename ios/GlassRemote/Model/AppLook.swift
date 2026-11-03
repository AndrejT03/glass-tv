import Foundation
import Observation
import SwiftUI
import UIKit

/// The app's own background, behind the glass: one of Glass's wave wallpapers from the TV, the one the
/// TV has (when it is one of them), a soft colour field in the spirit of Apple Home's wallpapers (drawn
/// here, so sharp on any screen), pure black for OLED, or a picture of the household's own from the
/// iPhone. Kept on the phone, not on the TV.
@MainActor
@Observable
final class AppLook {
    /// Glass's wave wallpapers, as the TV's settings name them.
    static let wallpapers = ["dusk", "aurora", "tahoe", "sunrise", "graphite", "midnight"]
    static let likeTV = "tv"
    static let black = "black"

    /// A soft colour field: a 3×3 mesh, its middle points a little off centre so it flows.
    struct Blend: Identifiable {
        let id: String
        let colors: [Color]
    }

    static let blends: [Blend] = [
        Blend(id: "coral", colors: hex(["FFB199", "FF7A8A", "FF9FB8", "FF8A65", "FF5E7E", "F86CA7", "FFB56B", "FF7A6B", "D85A9C"])),
        Blend(id: "lavender", colors: hex(["C9B6FF", "A58BFF", "F3B7E4", "9C8CF5", "B39DFF", "E7A6D8", "7D6FE0", "A17FE8", "C58AD8"])),
        Blend(id: "ocean", colors: hex(["7FD8FF", "4FA8FF", "6FE3E1", "3D8BFF", "2FB4E8", "48D1C5", "2C5FD9", "1F86C9", "1FA3A8"])),
        Blend(id: "amber", colors: hex(["FFE08A", "FFC46B", "FFD6A0", "FFB347", "FF9A4D", "FFC07A", "FF8F5A", "F2724B", "E8875C"])),
        Blend(id: "meadow", colors: hex(["B8F2B0", "7FE0A3", "D2F5A3", "5CCB8A", "3FB88F", "8FD97A", "2E9E7A", "2B8C6E", "5AA85E"])),
        Blend(id: "night", colors: hex(["3B3F8F", "4C3A8C", "2B2F6B", "2E2A6E", "3D3480", "1F2A5E", "141838", "1C1A45", "0E1230"])),
    ]

    /// The colour of what is on and chosen across the app: iOS's blue unless another.
    struct Accent: Identifiable {
        let id: String
        let color: Color
    }

    static let accents: [Accent] = [
        Accent(id: "blue", color: Color(hex: "0A84FF")),
        Accent(id: "indigo", color: Color(hex: "5E5CE6")),
        Accent(id: "purple", color: Color(hex: "BF5AF2")),
        Accent(id: "pink", color: Color(hex: "FF375F")),
        Accent(id: "red", color: Color(hex: "FF453A")),
        Accent(id: "orange", color: Color(hex: "FF9F0A")),
        Accent(id: "yellow", color: Color(hex: "FFD60A")),
        Accent(id: "green", color: Color(hex: "30D158")),
        Accent(id: "teal", color: Color(hex: "40C8E0")),
        Accent(id: "graphite", color: Color(hex: "8E8E93")),
    ]

    private(set) var accentID: String

    var accent: Color { (Self.accents.first { $0.id == accentID } ?? Self.accents[0]).color }

    private(set) var choice: String
    /// Pictures the household added from the iPhone, by id, in the order they were added.
    private(set) var photos: [(id: String, image: UIImage)] = []

    @ObservationIgnored private let defaults = UserDefaults.standard

    init() {
        choice = defaults.string(forKey: Self.key) ?? "dusk"
        accentID = defaults.string(forKey: Self.accentKey) ?? "blue"
        photos = Self.loadPhotos()
        if Self.photoID(choice).map({ id in !photos.contains { $0.id == id } }) == true { choice = "dusk" }
    }

    func choose(_ id: String) {
        choice = id
        defaults.set(id, forKey: Self.key)
    }

    func chooseAccent(_ id: String) {
        accentID = id
        defaults.set(id, forKey: Self.accentKey)
    }

    /// A picture from the library: kept at the size a phone screen needs, and chosen.
    func addPhoto(_ data: Data) async {
        guard let image = UIImage(data: data) else { return }
        let fitted = await Self.fitted(image)
        let id = UUID().uuidString
        if let jpeg = fitted.jpegData(compressionQuality: 0.88) {
            try? FileManager.default.createDirectory(at: Self.folder, withIntermediateDirectories: true)
            try? jpeg.write(to: Self.folder.appending(path: "\(id).jpg"), options: .atomic)
        }
        photos.append((id, fitted))
        choose(Self.photo(id))
    }

    func removePhoto(_ id: String) {
        try? FileManager.default.removeItem(at: Self.folder.appending(path: "\(id).jpg"))
        photos.removeAll { $0.id == id }
        if choice == Self.photo(id) { choose("dusk") }
    }

    /// What to draw for [choice], with the TV's wallpaper setting for "like the TV".
    enum Picture: Hashable {
        case wallpaper(String)
        case blend(String)
        case photo(String)
        case black
    }

    func picture(tvWallpaper: String?) -> Picture {
        if choice == Self.black { return .black }
        if choice == Self.likeTV { return .wallpaper(Self.tv(tvWallpaper)) }
        if Self.blends.contains(where: { $0.id == choice }) { return .blend(choice) }
        if let id = Self.photoID(choice), photos.contains(where: { $0.id == id }) { return .photo(id) }
        return .wallpaper(Self.wallpapers.contains(choice) ? choice : "dusk")
    }

    func image(ofPhoto id: String) -> UIImage? { photos.first { $0.id == id }?.image }

    /// The TV's wallpaper setting as one of the app's: its Apple Aerial has no still here, so dusk.
    static func tv(_ setting: String?) -> String {
        setting.flatMap { wallpapers.contains($0) ? $0 : nil } ?? "dusk"
    }

    static func photo(_ id: String) -> String { "photo:\(id)" }

    private static func photoID(_ choice: String) -> String? {
        choice.hasPrefix("photo:") ? String(choice.dropFirst(6)) : nil
    }

    private static func loadPhotos() -> [(id: String, image: UIImage)] {
        let manager = FileManager.default
        // The single photo of the first version becomes the first of the household's pictures.
        let old = URL.applicationSupportDirectory.appending(path: "backdrop.jpg")
        if manager.fileExists(atPath: old.path()) {
            try? manager.createDirectory(at: folder, withIntermediateDirectories: true)
            try? manager.moveItem(at: old, to: folder.appending(path: "first.jpg"))
            UserDefaults.standard.set(photo("first"), forKey: key)
        }
        let files = (try? manager.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.creationDateKey])) ?? []
        return files
            .filter { $0.pathExtension == "jpg" }
            .sorted { created($0) < created($1) }
            .compactMap { url in UIImage(contentsOfFile: url.path()).map { (url.deletingPathExtension().lastPathComponent, $0) } }
    }

    private static func created(_ url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
    }

    private static func fitted(_ image: UIImage) async -> UIImage {
        let longest: CGFloat = 2_000
        let scale = min(1, longest / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        return await image.byPreparingThumbnail(ofSize: size) ?? image
    }

    private static func hex(_ values: [String]) -> [Color] { values.map(Color.init(hex:)) }

    private static let key = "backdrop"
    private static let accentKey = "accent"

    private static var folder: URL { URL.applicationSupportDirectory.appending(path: "backdrops") }
}

/// A soft colour field ([AppLook.Blend]) drawn as a mesh gradient.
struct BlendView: View {
    let blend: AppLook.Blend

    var body: some View {
        MeshGradient(
            width: 3,
            height: 3,
            points: [
                [0, 0], [0.5, 0], [1, 0],
                [0, 0.48], [0.58, 0.42], [1, 0.55],
                [0, 1], [0.45, 1], [1, 1],
            ],
            colors: blend.colors
        )
    }
}
