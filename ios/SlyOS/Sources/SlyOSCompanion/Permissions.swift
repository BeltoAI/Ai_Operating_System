import Foundation
import Observation
import CoreLocation
import EventKit
import Contacts
import AVFoundation
import Photos
import Speech
import UserNotifications
import UIKit

/// Every capability SlyOS can ask for, what it is actually for, and whether it has been granted.
///
/// Two rules this screen exists to enforce:
///
/// * **Ask at the point of need.** App Review rejects apps that demand everything on first launch
///   (Guideline 5.1.1), and users refuse permissions they can't see a reason for. Each row explains
///   what the capability buys before it asks.
/// * **Never claim a capability that isn't granted.** On Android the app told its owner it had done
///   things it could not do. A denied permission has to read as denied everywhere.
@Observable
final class Permissions: NSObject {

    static let shared = Permissions()

    enum Capability: String, CaseIterable, Identifiable {
        case contacts, calendar, reminders, location, camera, microphone, speech, photos, notifications

        var id: String { rawValue }

        var title: String {
            switch self {
            case .contacts: "Contacts"
            case .calendar: "Calendar"
            case .reminders: "Reminders"
            case .location: "Location"
            case .camera: "Camera"
            case .microphone: "Microphone"
            case .speech: "Speech"
            case .photos: "Photos"
            case .notifications: "Notifications"
            }
        }

        /// What it buys — written for the owner, not for a compliance form.
        var reason: String {
            switch self {
            case .contacts: "Find people by name and know how to reach them"
            case .calendar: "Answer what's on, and schedule things for you"
            case .reminders: "Create and read reminders you ask for"
            case .location: "Answer where you are, and share it when you ask"
            case .camera: "Look mode — read receipts, documents and whatever you point it at"
            case .microphone: "Talk to SlyOS instead of typing"
            case .speech: "Turn what you say into text on-device"
            case .photos: "Save documents it makes, and read ones you pick"
            case .notifications: "Tell you when something needs you"
            }
        }

        var symbol: String {
            switch self {
            case .contacts: "person.crop.circle.fill"
            case .calendar: "calendar"
            case .reminders: "checklist"
            case .location: "location.fill"
            case .camera: "camera.fill"
            case .microphone: "mic.fill"
            case .speech: "waveform"
            case .photos: "photo.fill"
            case .notifications: "bell.fill"
            }
        }
    }

    enum State: Equatable {
        case notAsked, granted, limited, denied

        var label: String {
            switch self {
            case .notAsked: "Not asked"
            case .granted: "On"
            case .limited: "Limited"
            case .denied: "Off"
            }
        }
    }

    private(set) var states: [Capability: State] = [:]

    func state(_ c: Capability) -> State { states[c] ?? .notAsked }

    private let locationManager = CLLocationManager()
    private var locationContinuation: CheckedContinuation<Void, Never>?

    private override init() {
        super.init()
        locationManager.delegate = self
        refresh()
    }

    /// Read current status without prompting for anything.
    func refresh() {
        states[.contacts] = map(CNContactStore.authorizationStatus(for: .contacts))
        states[.calendar] = mapEvent(EKEventStore.authorizationStatus(for: .event))
        states[.reminders] = mapEvent(EKEventStore.authorizationStatus(for: .reminder))
        states[.camera] = map(AVCaptureDevice.authorizationStatus(for: .video))
        states[.microphone] = map(AVCaptureDevice.authorizationStatus(for: .audio))
        states[.speech] = mapSpeech(SFSpeechRecognizer.authorizationStatus())
        states[.photos] = mapPhotos(PHPhotoLibrary.authorizationStatus(for: .readWrite))
        states[.location] = mapLocation(locationManager.authorizationStatus)

        Task {
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            await MainActor.run {
                states[.notifications] = switch settings.authorizationStatus {
                case .authorized, .provisional, .ephemeral: .granted
                case .denied: .denied
                default: .notAsked
                }
            }
        }
    }

    /// Ask for one capability. Already-denied permissions can only be changed in Settings, so this
    /// opens Settings rather than silently doing nothing — a button that appears dead is worse than
    /// one that hands you off.
    @MainActor
    func request(_ c: Capability) async {
        if state(c) == .denied { openSettings(); return }

        switch c {
        case .contacts:
            _ = try? await CNContactStore().requestAccess(for: .contacts)
        case .calendar:
            _ = try? await EKEventStore().requestFullAccessToEvents()
        case .reminders:
            _ = try? await EKEventStore().requestFullAccessToReminders()
        case .camera:
            _ = await AVCaptureDevice.requestAccess(for: .video)
        case .microphone:
            _ = await AVCaptureDevice.requestAccess(for: .audio)
        case .speech:
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                SFSpeechRecognizer.requestAuthorization { _ in cont.resume() }
            }
        case .photos:
            _ = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        case .notifications:
            _ = try? await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound, .badge])
        case .location:
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                locationContinuation = cont
                locationManager.requestWhenInUseAuthorization()
            }
        }
        refresh()
    }

    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    /// How many are on — a one-glance answer to "is this thing set up?".
    var grantedCount: Int {
        Capability.allCases.filter { state($0) == .granted || state($0) == .limited }.count
    }

    // MARK: - Status mapping

    private func map(_ s: CNAuthorizationStatus) -> State {
        switch s {
        case .authorized: .granted
        case .limited: .limited
        case .denied, .restricted: .denied
        default: .notAsked
        }
    }

    private func map(_ s: AVAuthorizationStatus) -> State {
        switch s {
        case .authorized: .granted
        case .denied, .restricted: .denied
        default: .notAsked
        }
    }

    private func mapEvent(_ s: EKAuthorizationStatus) -> State {
        switch s {
        case .fullAccess: .granted
        case .writeOnly: .limited
        case .denied, .restricted: .denied
        default: .notAsked
        }
    }

    private func mapSpeech(_ s: SFSpeechRecognizerAuthorizationStatus) -> State {
        switch s {
        case .authorized: .granted
        case .denied, .restricted: .denied
        default: .notAsked
        }
    }

    private func mapPhotos(_ s: PHAuthorizationStatus) -> State {
        switch s {
        case .authorized: .granted
        case .limited: .limited
        case .denied, .restricted: .denied
        default: .notAsked
        }
    }

    private func mapLocation(_ s: CLAuthorizationStatus) -> State {
        switch s {
        case .authorizedAlways, .authorizedWhenInUse: .granted
        case .denied, .restricted: .denied
        default: .notAsked
        }
    }
}

extension Permissions: CLLocationManagerDelegate {
    /// Location has no async request API — the answer arrives here, which is what resumes the
    /// continuation the request is waiting on.
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        states[.location] = mapLocation(manager.authorizationStatus)
        locationContinuation?.resume()
        locationContinuation = nil
    }
}
