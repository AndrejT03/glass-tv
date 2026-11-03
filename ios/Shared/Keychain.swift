import Foundation
import Security

/// Each TV's pairing token, in the keychain under the TV's id: in the app group's keychain group,
/// so the widgets' Start button can reach the TV too.
enum Keychain {
    private static let service = "com.andrej.glassremote.token"

    static func token(for id: String) -> String? {
        var query = item(id)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &found) == errSecSuccess, let data = found as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// False when the keychain wouldn't keep it (an app without its signature's entitlements).
    static func save(_ token: String, for id: String) -> Bool {
        SecItemDelete(item(id) as CFDictionary)
        var entry = item(id)
        entry[kSecValueData as String] = Data(token.utf8)
        // Readable once the phone has been unlocked after starting, for the widgets.
        entry[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        var shared = entry
        shared[kSecAttrAccessGroup as String] = AppGroup.id
        // Without the app group (a signature without it), in the app's own keychain group.
        return SecItemAdd(shared as CFDictionary, nil) == errSecSuccess || SecItemAdd(entry as CFDictionary, nil) == errSecSuccess
    }

    /// A token kept before the widgets, moved into the app group's keychain group.
    static func share(for id: String) {
        guard let token = token(for: id) else { return }
        var query = item(id)
        query[kSecAttrAccessGroup as String] = AppGroup.id
        query[kSecReturnAttributes as String] = true
        if SecItemCopyMatching(query as CFDictionary, nil) != errSecSuccess { _ = save(token, for: id) }
    }

    static func delete(for id: String) {
        SecItemDelete(item(id) as CFDictionary)
    }

    private static func item(_ id: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: id,
        ]
    }
}
