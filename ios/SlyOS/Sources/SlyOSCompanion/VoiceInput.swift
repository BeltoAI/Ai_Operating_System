import Foundation
import Speech
import AVFoundation
import Observation

/// Talking to SlyOS instead of typing — what the microphone and speech permissions are *for*.
///
/// Recognition is forced on-device wherever the language supports it. That is not a nicety: this
/// app's whole pitch is that your life stays on your phone, and the default recogniser ships your
/// audio to Apple's servers.
@Observable
final class VoiceInput {

    static let shared = VoiceInput()

    private(set) var isListening = false
    private(set) var transcript = ""
    private(set) var error: String?

    private let recognizer = SFSpeechRecognizer(locale: Locale.current)
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private let engine = AVAudioEngine()

    var isAvailable: Bool { recognizer?.isAvailable ?? false }

    /// Begin listening. Asks for microphone and speech access at the point of need.
    @MainActor
    func start() async {
        guard !isListening else { return }
        error = nil
        transcript = ""

        let permissions = Permissions.shared
        if permissions.state(.microphone) != .granted { await permissions.request(.microphone) }
        if permissions.state(.speech) != .granted { await permissions.request(.speech) }

        guard permissions.state(.microphone) == .granted else {
            error = "SlyOS needs the microphone to hear you."; return
        }
        guard permissions.state(.speech) == .granted else {
            error = "SlyOS needs speech recognition to understand you."; return
        }
        guard let recognizer, recognizer.isAvailable else {
            error = "Speech recognition isn't available right now."; return
        }

        do {
            let session = AVAudioSession.sharedInstance()
            // `.duckOthers` so music dips rather than stops — being talked over is not a reason to
            // kill whatever the owner was listening to.
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            // Keep the audio on the device when the language model allows it.
            request.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
            self.request = request

            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
                request.append(buffer)
            }

            engine.prepare()
            try engine.start()
            isListening = true

            task = recognizer.recognitionTask(with: request) { [weak self] result, err in
                guard let self else { return }
                if let result {
                    Task { @MainActor in self.transcript = result.bestTranscription.formattedString }
                }
                if err != nil || (result?.isFinal ?? false) {
                    Task { @MainActor in self.stop() }
                }
            }
        } catch {
            self.error = error.localizedDescription
            stop()
        }
    }

    /// Stop listening and release the audio session, so other apps get sound back immediately.
    @MainActor
    func stop() {
        guard isListening || engine.isRunning else { return }
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil
        isListening = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
