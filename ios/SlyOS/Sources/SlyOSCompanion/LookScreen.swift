import SwiftUI
import AVFoundation
import Vision

/// Look — the live camera, matching Compose `LookScreen`.
///
/// Not a document scanner. It is a viewfinder you point at something and ask about: Back, Flip and
/// Auto at the top, corner brackets framing the subject, mode pills, and Identify.
///
/// One deliberate split, and it is also what makes the app useful before anyone has an API key:
/// **reading text is on-device and always works**; *identifying* what a thing is needs a model that
/// can see. Scan receipt and Scan doc therefore work with no key at all.
struct LookScreen: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SlySettings.self) private var settings

    @State private var camera = CameraSession()
    @State private var mode: Mode = .identify
    @State private var result: Result?
    @State private var working = false
    @State private var failure: String?

    enum Mode: String, CaseIterable, Identifiable {
        case identify = "Identify", who = "Who's this", receipt = "Scan receipt", doc = "Scan doc"
        var id: String { rawValue }

        /// Only the ones that ask *what is this* need a model that can see. The rest are Vision
        /// text recognition, which runs on the phone.
        var needsVision: Bool { self == .identify || self == .who }

        var question: String {
            switch self {
            case .identify:
                "What is this? Give it a short title on the first line, then two or three sentences "
                + "of what it actually is and why it matters. No preamble."
            case .who:
                "Who or what is the person, brand or logo here? Short title first line, then two or "
                + "three sentences. If you can't tell, say so plainly."
            case .receipt, .doc:
                ""
            }
        }
    }

    struct Result {
        var title: String
        var body: String
        var savedToBrain: Bool
    }

    var body: some View {
        let p = Palette(dark: true)   // the viewfinder is always dark; the world behind it isn't ours
        ZStack {
            CameraPreview(session: camera.session).ignoresSafeArea()
            CornerBrackets().ignoresSafeArea().allowsHitTesting(false)

            VStack(spacing: 0) {
                topBar(p)
                Spacer()
                if let result { resultCard(result, p) }
                else if let failure { message(failure, p) }
                modePills(p)
                bottomBar(p)
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 14)
        }
        .background(.black)
        .environment(\.palette, p)
        .task { await camera.start() }
        .onDisappear { camera.stop() }
        .statusBarHidden(true)
    }

    // MARK: - Chrome

    private func topBar(_ p: Palette) -> some View {
        HStack {
            pill("Back", p) { dismiss() }
            Spacer()
            pill("Flip", p) { camera.flip() }
            pill(camera.torchOn ? "Torch" : "Auto", p) { camera.toggleTorch() }
        }
        .padding(.top, 8)
    }

    private func modePills(_ p: Palette) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(Mode.allCases) { m in
                    Button { mode = m; result = nil; failure = nil } label: {
                        Text(m.rawValue)
                            .font(.system(size: T.body))
                            .foregroundStyle(mode == m ? .white : .white.opacity(0.75))
                            .padding(.horizontal, 18).padding(.vertical, 11)
                            .background(Capsule().fill(mode == m ? p.accent : .black.opacity(0.55)))
                    }
                }
            }
        }
        .padding(.bottom, 10)
    }

    private func bottomBar(_ p: Palette) -> some View {
        HStack(spacing: 10) {
            VStack(spacing: 4) {
                OrangeDot()
                Text("tap to talk").font(.system(size: T.small)).foregroundStyle(.white)
            }
            .padding(.horizontal, 18).padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 16).fill(.black.opacity(0.55)))

            Button(action: capture) {
                Group {
                    if working { SlyWaiting("looking", orbit: 22) }
                    else {
                        Text(mode == .identify ? "Identify" : mode.rawValue)
                            .font(.system(size: T.prompt - 4)).foregroundStyle(.white)
                    }
                }
                .frame(maxWidth: .infinity).padding(.vertical, 16)
                .background(RoundedRectangle(cornerRadius: 16).fill(p.accent))
            }
            .disabled(working)
        }
    }

    private func pill(_ label: String, _ p: Palette, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: T.body)).foregroundStyle(.white)
                .padding(.horizontal, 20).padding(.vertical, 11)
                .background(Capsule().fill(.black.opacity(0.55)))
        }
    }

    private func message(_ text: String, _ p: Palette) -> some View {
        Text(text)
            .font(.system(size: T.small)).foregroundStyle(.white)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 20).fill(.black.opacity(0.7)))
            .padding(.bottom, 10)
    }

    private func resultCard(_ r: Result, _ p: Palette) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(r.title)
                .font(.system(size: 30)).foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
            ScrollView {
                Text(r.body)
                    .font(.system(size: T.body)).foregroundStyle(.white.opacity(0.9))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 150)

            HStack(spacing: 10) {
                Button { search(r.title) } label: {
                    Text("Search")
                        .font(.system(size: T.body)).foregroundStyle(.white)
                        .padding(.horizontal, 26).padding(.vertical, 13)
                        .background(Capsule().fill(.white.opacity(0.22)))
                }
                if r.savedToBrain {
                    Text("saved to memory")
                        .font(.system(size: T.caption)).foregroundStyle(.white.opacity(0.7))
                }
                Spacer()
                Button { result = nil } label: {
                    Image(systemName: "xmark").font(.system(size: 15))
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(RoundedRectangle(cornerRadius: 20).fill(.black.opacity(0.72)))
        .padding(.bottom, 10)
    }

    // MARK: - Work

    private func capture() {
        working = true; failure = nil; result = nil
        Task {
            guard let jpeg = await camera.capture() else {
                failure = "Couldn't take the photo."; working = false; return
            }
            do {
                if mode.needsVision {
                    // Resized first — the raw frame is past every provider's per-image limit.
                    let sized = LookMode.prepareForVision(jpeg)
                    let answer = try await AgentClient.look(at: sized, question: mode.question)
                    let lines = answer.split(separator: "\n", maxSplits: 1)
                    let title = lines.first.map(String.init) ?? "Look"
                    let body = lines.count > 1 ? String(lines[1]).trimmingCharacters(in: .whitespacesAndNewlines) : ""
                    SlyStore.shared.insert(kind: "doc", title: title, body: answer, source: "Look")
                    result = Result(title: title, body: body, savedToBrain: true)
                } else {
                    // On-device text recognition — no key, no network, nothing leaves the phone.
                    guard let image = UIImage(data: jpeg) else { throw AgentClient.ClientError.malformedImage }
                    let text = await LookMode.text(in: image)
                    guard !text.isEmpty else {
                        failure = "No text I could read in that."; working = false; return
                    }
                    let title = text.split(separator: "\n").first.map(String.init) ?? mode.rawValue
                    SlyStore.shared.insert(kind: "doc", title: title, body: text, source: "Look")
                    result = Result(title: title, body: text, savedToBrain: true)
                }
            } catch {
                failure = error.localizedDescription
            }
            working = false
        }
    }

    private func search(_ term: String) {
        let q = term.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        if let url = URL(string: "https://www.google.com/search?q=\(q)") {
            UIApplication.shared.open(url)
        }
    }
}

/// The orange corner brackets that frame the subject.
private struct CornerBrackets: View {
    var body: some View {
        GeometryReader { geo in
            let inset: CGFloat = 26
            let len: CGFloat = 34
            let rect = CGRect(x: inset, y: geo.size.height * 0.13,
                              width: geo.size.width - inset * 2,
                              height: geo.size.height * 0.52)
            Path { p in
                for (x, y, dx, dy) in [
                    (rect.minX, rect.minY, 1.0, 1.0), (rect.maxX, rect.minY, -1.0, 1.0),
                    (rect.minX, rect.maxY, 1.0, -1.0), (rect.maxX, rect.maxY, -1.0, -1.0)
                ] {
                    p.move(to: CGPoint(x: x + len * dx, y: y))
                    p.addLine(to: CGPoint(x: x, y: y))
                    p.addLine(to: CGPoint(x: x, y: y + len * dy))
                }
            }
            .stroke(T.accent.opacity(0.85), lineWidth: 2.5)
        }
    }
}

/// The live preview layer.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.layer.session = session
        view.layer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        override var layer: AVCaptureVideoPreviewLayer { super.layer as! AVCaptureVideoPreviewLayer }
    }
}

/// Camera plumbing, kept away from the view.
@Observable
final class CameraSession {
    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private var position: AVCaptureDevice.Position = .back
    private(set) var torchOn = false
    private var delegate: PhotoDelegate?

    @MainActor
    func start() async {
        guard Permissions.shared.state(.camera) != .granted else { return await configure() }
        await Permissions.shared.request(.camera)
        await configure()
    }

    @MainActor
    private func configure() async {
        guard Permissions.shared.state(.camera) == .granted, !session.isRunning else { return }
        session.beginConfiguration()
        session.sessionPreset = .photo
        session.inputs.forEach { session.removeInput($0) }
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position),
           let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) {
            session.addInput(input)
        }
        if session.canAddOutput(output) { session.addOutput(output) }
        session.commitConfiguration()
        // Starting blocks; keeping it off the main actor is what stops the tap freezing the UI.
        Task.detached { [session] in session.startRunning() }
    }

    func stop() {
        guard session.isRunning else { return }
        Task.detached { [session] in session.stopRunning() }
    }

    @MainActor
    func flip() {
        position = position == .back ? .front : .back
        Task { await configure() }
    }

    func toggleTorch() {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { return }
        try? device.lockForConfiguration()
        torchOn.toggle()
        device.torchMode = torchOn ? .on : .off
        device.unlockForConfiguration()
    }

    /// One frame, as JPEG.
    func capture() async -> Data? {
        await withCheckedContinuation { continuation in
            let delegate = PhotoDelegate { continuation.resume(returning: $0) }
            self.delegate = delegate   // the system holds this weakly; losing it drops the callback
            output.capturePhoto(with: AVCapturePhotoSettings(), delegate: delegate)
        }
    }

    private final class PhotoDelegate: NSObject, AVCapturePhotoCaptureDelegate {
        let done: (Data?) -> Void
        init(done: @escaping (Data?) -> Void) { self.done = done }

        func photoOutput(_ output: AVCapturePhotoOutput,
                         didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
            done(photo.fileDataRepresentation())
        }
    }
}
