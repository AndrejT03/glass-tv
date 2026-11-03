import Darwin
import Foundation
import Network
import os

/// Whether the phone can reach the TV from where it is: the TV is on the home network, so the phone has
/// to be on its Wi-Fi. Told apart without asking the TV anything (no traffic, nothing for the battery).
enum TVReach: String, Codable, Sendable {
    case ok
    /// No Wi-Fi (or Ethernet) at all: mobile data, or nothing.
    case offWifi
    /// A Wi-Fi, but not the TV's: the phone's address isn't in the TV's network.
    case otherNetwork
    /// On the TV's network, and the TV didn't answer (asleep without its network standby, or away).
    case noAnswer

    /// The phone's network against the TV at [host], as [path] has it.
    static func of(host: String, path: NWPath) -> TVReach {
        guard let tv = ipv4(host) else { return .ok }
        // The emulator, through adb on this Mac.
        if tv & 0xFF == 127 { return .ok }
        let local = path.availableInterfaces.filter { $0.type == .wifi || $0.type == .wiredEthernet }
        guard path.status == .satisfied, !local.isEmpty, path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet) else {
            return .offWifi
        }
        // Only a private address says which network it is on (a public one could be reached from anywhere).
        guard isPrivate(tv) else { return .ok }
        let mine = addresses(on: Set(local.map(\.name)))
        guard !mine.isEmpty else { return .ok }
        return mine.contains { $0.address & $0.mask == tv & $0.mask } ? .ok : .otherNetwork
    }

    /// The network now, asked once (for the widgets, which don't keep a monitor running).
    static func currentPath() async -> NWPath {
        await withCheckedContinuation { continuation in
            let monitor = NWPathMonitor()
            let once = OSAllocatedUnfairLock(initialState: false)
            monitor.pathUpdateHandler = { path in
                guard once.withLock({ done in defer { done = true }; return !done }) else { return }
                monitor.cancel()
                continuation.resume(returning: path)
            }
            monitor.start(queue: .global(qos: .userInitiated))
        }
    }

    /// In network byte order, as `in_addr` keeps it.
    private static func ipv4(_ host: String) -> UInt32? {
        var address = in_addr()
        return inet_pton(AF_INET, host, &address) == 1 ? address.s_addr : nil
    }

    private static func isPrivate(_ address: UInt32) -> Bool {
        let a = address & 0xFF, b = (address >> 8) & 0xFF
        return a == 10 || (a == 172 && (16...31).contains(b)) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    private static func addresses(on names: Set<String>) -> [(address: UInt32, mask: UInt32)] {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [] }
        defer { freeifaddrs(list) }
        var found: [(UInt32, UInt32)] = []
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = entry.pointee
            guard let addr = ifa.ifa_addr, let mask = ifa.ifa_netmask, addr.pointee.sa_family == UInt8(AF_INET),
                  names.contains(String(cString: ifa.ifa_name)) else { continue }
            let a = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            let m = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            found.append((a, m))
        }
        return found
    }
}
