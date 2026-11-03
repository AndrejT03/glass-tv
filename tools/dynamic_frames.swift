// Splits one of Apple's dynamic desktop pictures (a HEIC with several images and the sun's position
// for each in its XMP, apple_desktop:solar) into the pictures Glass shows: for the TV, each image cut
// to 16:9 at 1920x1080 in sRGB (the TV draws its apps at 1080p); for the iPhone app's widgets, a
// square (the whole picture: Apple's are 6016x6016) at 1200x1200 in Display P3. Prints the sun's
// positions as JSON.
// Usage: swift tools/dynamic_frames.swift <picture.heic> <out dir>   (writes tv/N.jpg, phone/N.jpg)
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
guard args.count == 3 else {
    FileHandle.standardError.write("usage: dynamic_frames.swift <picture.heic> <out dir>\n".data(using: .utf8)!)
    exit(2)
}
let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: args[1]) as CFURL, nil)!
let out = URL(fileURLWithPath: args[2])

/// The sun's positions from the XMP: each with its image, and which images are light and dark.
func solar() -> [String: Any] {
    guard let meta = CGImageSourceCopyMetadataAtIndex(source, 0, nil),
          let tag = CGImageMetadataCopyTagWithPath(meta, nil, "apple_desktop:solar" as CFString),
          let base64 = CGImageMetadataTagCopyValue(tag) as? String,
          let data = Data(base64Encoded: base64),
          let plist = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
    else { fatalError("no apple_desktop:solar in \(args[1])") }
    let sun = (plist["si"] as? [[String: Any]] ?? []).map { entry in
        ["a": (entry["a"] as? NSNumber)?.doubleValue ?? 0, "z": (entry["z"] as? NSNumber)?.doubleValue ?? 0, "i": (entry["i"] as? NSNumber)?.intValue ?? 0]
    }
    let appearance = plist["ap"] as? [String: Any] ?? [:]
    return ["sun": sun, "light": (appearance["l"] as? NSNumber)?.intValue ?? 0, "dark": (appearance["d"] as? NSNumber)?.intValue ?? 0]
}

/// The image filling [width]x[height] (cut around its middle, as macOS fills a screen), in [space].
func render(_ image: CGImage, width: Int, height: Int, space: CGColorSpace) -> CGImage {
    let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    context.interpolationQuality = .high
    let scale = max(CGFloat(width) / CGFloat(image.width), CGFloat(height) / CGFloat(image.height))
    let w = CGFloat(image.width) * scale
    let h = CGFloat(image.height) * scale
    context.draw(image, in: CGRect(x: (CGFloat(width) - w) / 2, y: (CGFloat(height) - h) / 2, width: w, height: h))
    return context.makeImage()!
}

func write(_ image: CGImage, _ url: URL, quality: Double) {
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
    guard CGImageDestinationFinalize(destination) else { fatalError("can't write \(url.path)") }
}

let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
let p3 = CGColorSpace(name: CGColorSpace.displayP3)!
let count = CGImageSourceGetCount(source)
for i in 0..<count {
    autoreleasepool {
        let image = CGImageSourceCreateImageAtIndex(source, i, nil)!
        write(render(image, width: 1920, height: 1080, space: srgb), out.appending(path: "tv/\(i).jpg"), quality: 0.9)
        write(render(image, width: 1200, height: 1200, space: p3), out.appending(path: "phone/\(i).jpg"), quality: 0.82)
    }
}
var info = solar()
info["frames"] = count
let json = try! JSONSerialization.data(withJSONObject: info, options: [.sortedKeys])
print(String(data: json, encoding: .utf8)!)
