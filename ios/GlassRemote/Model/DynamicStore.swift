import Foundation
import Observation
import UIKit

/// Apple's dynamic desktop pictures for the Dynamic Wallpaper picker, brought from the TV once (77
/// frames, about 14 MB) into the app's caches, and again only when the TV's set of pictures changes.
@MainActor
@Observable
final class DynamicStore {
    private(set) var manifest: DynamicManifest?
    /// Bumped as frames arrive, for the picker's pictures to look again.
    private(set) var revision = 0

    @ObservationIgnored let shelf = DynamicShelf()
    @ObservationIgnored private var syncing = false
    /// Every frame of [manifest] is on the shelf.
    @ObservationIgnored private var complete = false
    @ObservationIgnored private let images = NSCache<NSString, UIImage>()

    init() {
        manifest = shelf.manifest()
        complete = manifest.map { m in missing(m).isEmpty } ?? false
    }

    /// After each state from the TV: the pictures, when the TV's differ from those here.
    func update(_ state: TVState, api: GlassAPI?) {
        guard let choice = state.setting("dynamicWallpaper") else { return }
        let onTV = Set(choice.options.map(\.id)).subtracting([dynamicAll])
        let here = Set(manifest?.wallpapers.map(\.id) ?? [])
        if let api, !syncing, onTV != here || !complete {
            syncing = true
            Task { await sync(api) }
        }
    }

    /// A frame for the picker, decoded at [pixels] off the main thread, and kept.
    func image(_ id: String, _ frame: Int, pixels: Int) async -> UIImage? {
        let key = "\(id)/\(frame)/\(pixels)/\(revision)" as NSString
        if let kept = images.object(forKey: key) { return kept }
        let shelf = self.shelf
        let decoded = await Task.detached(priority: .userInitiated) { shelf.image(id, frame, pixels: pixels).map { UIImage(cgImage: $0) } }.value
        if let decoded { images.setObject(decoded, forKey: key) }
        return decoded
    }

    private func sync(_ api: GlassAPI) async {
        defer { syncing = false }
        guard let data = try? await api.dynamic(), let fresh = try? JSONDecoder().decode(DynamicManifest.self, from: data) else { return }
        let shelf = self.shelf
        let files = FileManager.default
        // A picture the TV now has otherwise (pushed again) is brought again.
        for old in manifest?.wallpapers ?? [] where fresh.wallpapers.first(where: { $0.id == old.id }) != old {
            try? files.removeItem(at: shelf.root.appending(path: old.id))
        }
        let wanted = missing(fresh)
        // Four at a time: the TV's little server answers one request per connection.
        await withTaskGroup(of: Void.self) { group in
            var queue = wanted[...]
            func next() {
                guard let (id, frame) = queue.popFirst() else { return }
                group.addTask { await Self.bring(id, frame, from: api, to: shelf) }
            }
            for _ in 0..<4 { next() }
            while await group.next() != nil { next() }
        }
        // The manifest last: every frame it names is here by now.
        try? files.createDirectory(at: shelf.root, withIntermediateDirectories: true)
        try? data.write(to: shelf.manifestURL, options: .atomic)
        manifest = fresh
        complete = missing(fresh).isEmpty
        revision += 1
    }

    private nonisolated static func bring(_ id: String, _ frame: Int, from api: GlassAPI, to shelf: DynamicShelf) async {
        guard let jpeg = try? await api.dynamicFrame(id, frame) else { return }
        let url = shelf.frameURL(id, frame)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? jpeg.write(to: url, options: .atomic)
    }

    private func missing(_ manifest: DynamicManifest) -> [(String, Int)] {
        manifest.wallpapers.flatMap { w in (0..<w.frames).map { (w.id, $0) } }
            .filter { !FileManager.default.fileExists(atPath: shelf.frameURL($0.0, $0.1).path) }
    }
}
