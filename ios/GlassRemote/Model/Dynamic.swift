import Foundation
import ImageIO

/// The Dynamic Wallpaper setting's choice that shows each picture in turn.
let dynamicAll = "all"

/// Apple's dynamic desktop pictures as the TV has them (`GET /v1/dynamic`): each one's frames and the
/// sun's positions macOS chooses between, and where the TV works the sun out from.
struct DynamicManifest: Codable, Equatable, Sendable {
    struct Place: Codable, Equatable, Sendable {
        var latitude: Double
        var longitude: Double
    }

    struct Sun: Codable, Equatable, Sendable {
        /// Altitude, azimuth, and the frame for the sun there (several may share one).
        var a: Double
        var z: Double
        var i: Int
    }

    struct Wallpaper: Codable, Equatable, Identifiable, Sendable {
        var id: String
        var name: String
        var frames: Int
        var sun: [Sun]

        /// The frame for the sun at [at]: the one whose position is nearest it in the sky, as on the Mac
        /// and the TV (screensaver/DynamicWallpapers.kt).
        func frame(for at: SunPosition) -> Int {
            let a1 = at.altitude * .pi / 180
            var best = 0
            var nearest = -2.0
            for p in sun {
                let a2 = p.a * .pi / 180
                // The cosine of the angle between them: the bigger, the nearer.
                let closeness = sin(a1) * sin(a2) + cos(a1) * cos(a2) * cos((at.azimuth - p.z) * .pi / 180)
                if closeness > nearest {
                    nearest = closeness
                    best = p.i
                }
            }
            return min(max(best, 0), frames - 1)
        }
    }

    var wallpapers: [Wallpaper]
    var place: Place?

    /// Skopje, where the TV works the sun out from when it doesn't say.
    static let skopje = Place(latitude: 41.9981, longitude: 21.4254)

    func sun(at date: Date) -> SunPosition {
        let place = place ?? Self.skopje
        return SunPosition(at: date, latitude: place.latitude, longitude: place.longitude)
    }
}

/// Where the pictures' frames are kept, in the app's caches: `manifest.json` and `<id>/<frame>.jpg`
/// (the TV's 1200x1200 squares).
struct DynamicShelf: Sendable {
    let root = URL.cachesDirectory.appending(path: "Dynamic", directoryHint: .isDirectory)

    var manifestURL: URL { root.appending(path: "manifest.json") }

    func frameURL(_ id: String, _ frame: Int) -> URL { root.appending(path: "\(id)/\(frame).jpg") }

    func manifest() -> DynamicManifest? {
        guard let data = try? Data(contentsOf: manifestURL) else { return nil }
        return try? JSONDecoder().decode(DynamicManifest.self, from: data)
    }

    /// A frame decoded at no more than [pixels] on its side (a JPEG decodes small for less).
    func image(_ id: String, _ frame: Int, pixels: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(frameURL(id, frame) as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: pixels,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}
