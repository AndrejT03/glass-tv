import SwiftUI

/// Glass on the iPhone: the Glass launcher's remote and settings, over the home network.
@main
struct GlassRemoteApp: App {
    @State private var link = TVLink()
    @State private var look = AppLook()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(link)
                .environment(look)
                .tint(look.accent)
                .preferredColorScheme(.dark)
        }
    }
}
