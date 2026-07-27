import Foundation

/// Minimal Keychain wrapper for the OAuth tokens.
///
/// `kSecAttrAccessibleAfterFirstUnlock` rather than the default: a background refresh of mail or
/// calendar has to work while the phone is still locked, which the stricter classes forbid.
struct Keychain {
    let service: String

    /// Shared with the extension. Without an access group each process gets its own keychain
    /// partition, and the extension would find no API key however many the app had stored.
    static let accessGroup: String? = nil   // set alongside DEVELOPMENT_TEAM: "TEAMID.com.belto.slyos"

    private func query(_ account: String) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        if let group = Self.accessGroup { q[kSecAttrAccessGroup as String] = group }
        return q
    }

    func set(_ value: String, for account: String) {
        var q = query(account)
        SecItemDelete(q as CFDictionary)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(q as CFDictionary, nil)
    }

    func string(for account: String) -> String? {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func remove(_ account: String) {
        SecItemDelete(query(account) as CFDictionary)
    }
}
