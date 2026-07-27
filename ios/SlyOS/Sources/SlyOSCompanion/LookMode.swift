import SwiftUI
import VisionKit
import Vision
import CoreLocation
import Observation

/// Look mode — what the camera and photo permissions are *for*.
///
/// Reads whatever you point it at, on-device, and puts the text into the brain. Receipts, letters,
/// business cards, a whiteboard. Nothing is uploaded: `VNRecognizeTextRequest` runs locally, which
/// is the only reason it is honest to say your documents stay on your phone.
enum LookMode {

    /// Shrink a photo to something a vision model will actually accept.
    ///
    /// A full iPhone frame is 3–5MB, and base64 inflates it by a third — comfortably past
    /// Anthropic's 5MB per-image limit, which is why Look failed on a key that answered text fine.
    /// 1568px on the long edge is Anthropic's own recommended maximum; beyond it they downscale
    /// anyway, so sending more costs upload time and buys nothing.
    static func prepareForVision(_ data: Data, maxEdge: CGFloat = 1568) -> Data {
        guard let image = UIImage(data: data) else { return data }
        let longest = max(image.size.width, image.size.height)
        guard longest > maxEdge else {
            return image.jpegData(compressionQuality: 0.8) ?? data
        }
        let scale = maxEdge / longest
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: size)
        let shrunk = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        return shrunk.jpegData(compressionQuality: 0.8) ?? data
    }


    /// Pull text out of an image. Accurate rather than fast — a misread total on a receipt is worse
    /// than a slow scan.
    static func text(in image: UIImage) async -> String {
        guard let cg = image.cgImage else { return "" }

        return await withCheckedContinuation { continuation in
            let request = VNRecognizeTextRequest { request, _ in
                let lines = (request.results as? [VNRecognizedTextObservation] ?? [])
                    .compactMap { $0.topCandidates(1).first?.string }
                continuation.resume(returning: lines.joined(separator: "\n"))
            }
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true

            let handler = VNImageRequestHandler(cgImage: cg, options: [:])
            DispatchQueue.global(qos: .userInitiated).async {
                do { try handler.perform([request]) }
                catch { continuation.resume(returning: "") }
            }
        }
    }

    /// Read an image and commit what it says to the brain.
    @discardableResult
    static func capture(_ image: UIImage, source: String = "Look") async -> String {
        let read = await text(in: image)
        guard !read.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return "" }

        // First line makes a serviceable title — on a receipt it is the merchant, on a letter the
        // sender, which is what someone would search for later.
        let title = read.split(separator: "\n").first.map(String.init) ?? "Scan"
        SlyStore.shared.insert(kind: "doc", title: title, body: read, source: source)
        return read
    }
}

/// The document scanner, wrapped for SwiftUI. Handles the edge detection and perspective correction
/// that a raw camera feed does not.
struct DocumentScanner: UIViewControllerRepresentable {
    let onScan: ([UIImage]) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let vc = VNDocumentCameraViewController()
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: VNDocumentCameraViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let parent: DocumentScanner
        init(_ parent: DocumentScanner) { self.parent = parent }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController,
                                          didFinishWith scan: VNDocumentCameraScan) {
            let pages = (0..<scan.pageCount).map { scan.imageOfPage(at: $0) }
            parent.onScan(pages)
            parent.dismiss()
        }

        func documentCameraViewControllerDidCancel(_ c: VNDocumentCameraViewController) {
            parent.dismiss()
        }

        func documentCameraViewController(_ c: VNDocumentCameraViewController,
                                          didFailWithError error: Error) {
            parent.dismiss()
        }
    }
}

/// Where you are — what the location permission is *for*.
///
/// Used two ways, both of which the owner asks for explicitly: answering questions that depend on
/// where you are, and sharing your location in something you send. There is no background tracking
/// and no Always authorization, because SlyOS has no reason to know where you are when it is shut.
@Observable
final class LocationProvider: NSObject, CLLocationManagerDelegate {

    static let shared = LocationProvider()

    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<CLLocation?, Never>?

    private(set) var placeName: String?

    private override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// A one-shot fix. Returns nil rather than throwing when permission is refused — a missing
    /// location should quietly drop out of the context, not fail the whole question.
    func current() async -> CLLocation? {
        let permissions = Permissions.shared
        if permissions.state(.location) == .notAsked { await permissions.request(.location) }
        guard permissions.state(.location) == .granted else { return nil }

        return await withCheckedContinuation { cont in
            continuation = cont
            manager.requestLocation()
        }
    }

    /// A human-readable place, for context lines and for sharing.
    func describe() async -> String? {
        guard let location = await current() else { return nil }
        let places = try? await CLGeocoder().reverseGeocodeLocation(location)
        guard let p = places?.first else { return nil }
        let name = [p.locality, p.administrativeArea, p.country]
            .compactMap { $0 }.joined(separator: ", ")
        placeName = name.isEmpty ? nil : name
        return placeName
    }

    func locationManager(_ m: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        continuation?.resume(returning: locations.last)
        continuation = nil
    }

    func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        continuation?.resume(returning: nil)
        continuation = nil
    }
}
