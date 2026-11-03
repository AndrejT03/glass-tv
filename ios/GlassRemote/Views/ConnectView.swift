import SwiftUI

/// Finding the TV: the ones Bonjour sees on the home network, or an address typed in.
struct ConnectView: View {
    @Environment(TVLink.self) private var link
    @State private var target: PairingTarget?
    @State private var busy: String?
    @State private var problem: String?
    @State private var typing = false

    var body: some View {
        let s = link.strings
        NavigationStack {
            ScrollView {
                VStack(spacing: 28) {
                    VStack(spacing: 14) {
                        Image(systemName: "tv")
                            .font(.system(size: 42, weight: .light))
                            .frame(width: 100, height: 100)
                            .glassEffect(.regular, in: .circle)
                            .symbolEffect(.pulse, isActive: link.discovery.tvs.isEmpty)
                        Text(s.connectTitle)
                            .font(.largeTitle.bold())
                            .multilineTextAlignment(.center)
                        Text(s.connectHint)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, 36)

                    GlassEffectContainer(spacing: 4) {
                        VStack(spacing: 12) {
                            ForEach(link.discovery.tvs) { tv in
                                Button { connect(tv) } label: {
                                    TVRow(name: tv.name, busy: busy == tv.id)
                                }
                                .liquidButton(.capsule, swell: 1.03)
                                .transition(.blurReplace)
                            }
                        }
                    }
                    .animation(.smooth, value: link.discovery.tvs)

                    if link.discovery.tvs.isEmpty {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text(s.searching).foregroundStyle(.secondary)
                        }
                    }
                    if let problem {
                        Text(problem).font(.footnote).foregroundStyle(.red)
                    }
                    Button(s.enterAddress) { typing = true }
                        .buttonStyle(.glass)
                        .controlSize(.large)
                }
                .padding(20)
            }
            .softEdges()
            .background { Backdrop() }
            .navigationDestination(item: $target) { PairView(target: $0) }
        }
        .onAppear { link.discovery.start() }
        .onDisappear { link.discovery.stop() }
        .sheet(isPresented: $typing) {
            AddressSheet { host, port in connect(host: host, port: port) }
        }
    }

    private func connect(_ tv: Discovery.TV) {
        busy = tv.id
        problem = nil
        Task {
            do {
                target = try await link.startPairing(with: tv)
            } catch {
                problem = link.strings.cantReach
            }
            busy = nil
        }
    }

    private func connect(host: String, port: Int) {
        busy = host
        problem = nil
        Task {
            do {
                target = try await link.startPairing(host: host, port: port)
            } catch {
                problem = link.strings.cantReach
            }
            busy = nil
        }
    }
}

private struct TVRow: View {
    let name: String
    let busy: Bool

    var body: some View {
        HStack(spacing: 14) {
            Disc(symbol: "tv.fill", size: 44)
            Text(name).font(.headline).lineLimit(1)
            Spacer()
            if busy {
                ProgressView()
            } else {
                Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}

/// The TV's address, typed in, on a glass sheet.
private struct AddressSheet: View {
    @Environment(TVLink.self) private var link
    @Environment(\.dismiss) private var dismiss
    @State private var address = ""
    @FocusState private var focused: Bool
    let go: (String, Int) -> Void

    var body: some View {
        let s = link.strings
        VStack(alignment: .leading, spacing: 16) {
            Text(s.enterAddress).font(.title2.bold())
            Text(s.addressHint).font(.callout).foregroundStyle(.secondary)
            TextField(s.addressPlaceholder, text: $address)
                .keyboardType(.numbersAndPunctuation)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focused)
                .submitLabel(.go)
                .onSubmit(submit)
                .padding(.horizontal, 18)
                .padding(.vertical, 14)
                .glassEffect(.regular, in: .capsule)
            HStack(spacing: 12) {
                Spacer()
                Button(s.cancel) { dismiss() }
                    .buttonStyle(.glass)
                    .controlSize(.large)
                Button(s.connect, action: submit)
                    .buttonStyle(.glassProminent)
                    .controlSize(.large)
                    .disabled(address.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(24)
        .presentationDetents([.height(300)])
        .onAppear { focused = true }
    }

    private func submit() {
        let parts = address.trimmingCharacters(in: .whitespaces).split(separator: ":", maxSplits: 1)
        guard let host = parts.first.map(String.init), !host.isEmpty else { return }
        let port = parts.count > 1 ? Int(parts[1]) ?? GlassAPI.port : GlassAPI.port
        dismiss()
        go(host, port)
    }
}

/// The four digits the TV shows, in glass boxes like its own.
struct PairView: View {
    let target: PairingTarget
    @Environment(TVLink.self) private var link
    @State private var code = ""
    @State private var message: String?
    @State private var over = false
    @State private var checking = false
    @State private var shakes = 0
    @FocusState private var typing: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let s = link.strings
        VStack(spacing: 28) {
            VStack(spacing: 10) {
                Text(s.pairTitle).font(.largeTitle.bold())
                Text(String(format: s.pairHint, target.name))
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            ZStack {
                TextField("", text: $code)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .focused($typing)
                    .frame(width: 1, height: 1)
                    .opacity(0.01)
                GlassEffectContainer(spacing: 2) {
                    HStack(spacing: 12) {
                        ForEach(0..<4, id: \.self) { i in
                            DigitBox(digit: digit(i), current: typing && !checking && i == min(code.count, 3))
                        }
                    }
                }
            }
            .contentShape(.rect)
            .onTapGesture { typing = true }
            .keyframeAnimator(initialValue: 0.0, trigger: shakes) { content, x in
                content.offset(x: x)
            } keyframes: { _ in
                KeyframeTrack {
                    LinearKeyframe(-14, duration: 0.06)
                    LinearKeyframe(12, duration: 0.08)
                    LinearKeyframe(-7, duration: 0.07)
                    LinearKeyframe(3, duration: 0.06)
                    LinearKeyframe(0, duration: 0.05)
                }
            }
            if checking {
                ProgressView()
            }
            if let message {
                Text(message).font(.callout.weight(.medium)).foregroundStyle(.red)
            }
            if over {
                Button(s.newCode, action: newCode)
                    .buttonStyle(.glassProminent)
                    .controlSize(.large)
            }
            Spacer()
        }
        .padding(24)
        .padding(.top, 20)
        .frame(maxWidth: .infinity)
        .background { Backdrop() }
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { typing = true }
        .onChange(of: code) { _, new in
            let digits = String(new.filter(\.isNumber).prefix(4))
            if digits != new {
                code = digits
            } else if digits.count == 4 {
                check(digits)
            }
        }
        .onDisappear {
            // Gone back without pairing: the TV takes its code away.
            if link.saved?.id != target.id { Task { await link.cancel(target) } }
        }
    }

    private func digit(_ i: Int) -> String {
        i < code.count ? String(code[code.index(code.startIndex, offsetBy: i)]) : ""
    }

    private func check(_ entered: String) {
        checking = true
        message = nil
        Task {
            do {
                try await link.confirm(target, code: entered)
            } catch GlassAPI.Failure.wrongCode {
                message = link.strings.wrongCode
                if !reduceMotion { shakes += 1 }
                code = ""
            } catch GlassAPI.Failure.codeOver {
                message = link.strings.codeOver
                over = true
                code = ""
            } catch GlassAPI.Failure.keychain {
                message = link.strings.noKeychain
                code = ""
            } catch {
                message = link.strings.cantReach
                code = ""
            }
            checking = false
        }
    }

    private func newCode() {
        over = false
        message = nil
        Task {
            try? await link.requestCode(target)
            typing = true
        }
    }
}

private struct DigitBox: View {
    let digit: String
    let current: Bool

    var body: some View {
        Text(digit)
            .font(.system(size: 40, weight: .semibold, design: .rounded))
            .contentTransition(.numericText())
            .frame(width: 64, height: 80)
            .glassEffect(current ? .regular.tint(.white.opacity(0.22)) : .regular, in: .rect(cornerRadius: 20))
            .overlay {
                RoundedRectangle(cornerRadius: 20)
                    .strokeBorder(.white.opacity(current ? 0.75 : 0), lineWidth: 1.5)
            }
            .animation(.smooth(duration: 0.2), value: current)
            .animation(.snappy, value: digit)
    }
}
