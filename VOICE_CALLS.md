# SlyOS — AI Call Handling (on-device)

The goal: **incoming calls handled by your AI, from your brain, in your voice.** This documents what ships
today on a stock, unrooted Android phone, the one hard platform wall, and the roadmap to true live voice.

## What ships today (on-device, no server, no key)

**AI Call Screening** — a `CallScreeningService` (`SlyCallScreeningService`). When enabled
(Settings → AI call screening; you grant the system "call-screening app" role once):

- **People in your contacts ring through untouched.**
- **Unknown callers are handled by your AI.** With "Text back" on, SlyOS declines the call and sends the
  caller a short reply written from your brain, in your voice ("Hi, this is Emil's assistant — he can't take
  calls right now; text me what you need and I'll make sure he sees it."). The call is still logged so you
  see who called, and the exchange is written into the brain.
- **A heads-up notification** lets you **answer with the AI on speaker** in one tap — this opens SlyOS's
  voice loop (the same working `SpeechRecognizer → brain → ElevenLabs cloned voice / device TTS` pipeline
  used in Converse). Put the call on speaker and your AI converses with the caller in your cloned voice.

This is genuinely "my AI takes my calls," and it needs nothing but the phone.

## The one hard wall (why full auto-answer isn't possible on stock Android)

You cannot capture or inject the **live two-way audio of a call** from a third-party app:

- **Cellular:** since Android 10 the `VOICE_CALL` audio stream is restricted to system/carrier apps.
  `CallScreeningService` can screen, allow, silence or decline — it is *not* handed the live audio.
- **WhatsApp / VoIP:** end-to-end encrypted; no API; Accessibility and `MediaRecorder` cannot tap the
  other party's audio.

So a fully seamless "AI picks up and talks, hands-free, in the background" is **not achievable on an
unrooted stock phone** — regardless of the TTS/STT stack. The **speaker loop** above is the honest
on-device workaround (the phone's mic hears the caller off the speaker, and the AI's speech plays out the
speaker to the caller). Quality is limited by that acoustic path.

## Voice stack

- **Speaking:** on-device `TextToSpeech` (free, offline) by default; if the user has added an **ElevenLabs**
  key + a cloned voice, replies play in their **cloned voice** (already wired in Converse and reused here).
- **Listening:** on-device `SpeechRecognizer`.
- **Brain:** the same `BrainContext` + `AgentLoop` every SlyOS surface uses — so the AI answers as *you*.

## Roadmap to true, seamless voice (beyond stock limits)

1. **Chatterbox (Resemble AI, MIT) + Vosk on-device** — swap the TTS/STT for sub-200ms zero-shot cloning
   and offline STT. This upgrades *quality/latency* of the speaker-loop and the SlyOS Phone; it does **not**
   remove the call-audio wall on stock Android. Integration is native (`.so` + model files) — a build-time
   addition, staged as its own phase.
2. **SlyOS Phone (our OS)** — as the system dialer/OS we control the telephony audio route, so the AI can
   answer fully hands-free with no speaker loop. This is where Chatterbox voice cloning becomes seamless.
3. **Server-forwarding number (real live answering, any phone)** — a Twilio/Telnyx number + conditional
   call-forwarding runs the `STT → brain → TTS(cloned)` loop in the cloud and answers callers in your voice
   with low latency. This is how "Natural AI Phone"-style products actually do live answering.

## The free on-device stack (Vosk + Chatterbox) — exact integration

The voice loop is built around swappable STT/TTS so the free open-source models drop in without touching the
call/brain plumbing. What's in-tree today: the **voice-clone training flow** (`VoiceSampleStore` +
`VoiceSetupDialog` — record a 20 s sample, stored app-private, never uploaded) and the working
`SpeechRecognizer → brain → TextToSpeech/ElevenLabs` loop. The two native pieces below need on-device
build+test iterations (they can't be compiled/validated off-device), so they're documented as ready-to-add:

### STT — Vosk (free, offline, ~50 MB model)
1. `build.gradle.kts` (app): `implementation("com.alphacephei:vosk-android:0.3.47")`
2. Ship/download a model (e.g. `vosk-model-small-en-us-0.15`) into app storage on first run.
3. Wrapper: open a `Model(dir)`, create a `Recognizer(model, 16000f)`, feed it PCM from an `AudioRecord`
   on `AudioSource.MIC` (in speaker mode the mic hears the caller), and emit `recognizer.result` /
   `partialResult`. This replaces `SpeechRecognizer` in the loop — same callback shape, zero cloud.

### TTS / cloning — Chatterbox (free, on-device, in your voice)
Chatterbox is a PyTorch model; there is no Android library, so on-device use is a **model-conversion**
project, not a drop-in:
1. Convert the Chatterbox checkpoint to a mobile runtime — **ExecuTorch** (PyTorch's on-device target) or
   **ONNX Runtime Mobile**. NeuTTS Air (0.5B) or Qwen3-TTS are lighter alternatives worth benchmarking on
   the S25 first.
2. Bundle the converted model + the user's `VoiceSampleStore.sampleFile` as the zero-shot reference.
3. Implement a `TtsEngine.speak(text): audio` backed by the runtime; feed the audio to `AudioTrack`
   (playing out the speaker so the caller hears it). Slot it in where the loop currently calls
   `TextToSpeech`/ElevenLabs — the loop code doesn't change.
Until that native runtime is wired, the loop uses free device `TextToSpeech` (not cloned) with ElevenLabs as
the optional cloud-cloned fallback. The `VoiceSampleStore` sample is already captured and waiting for it.

## Files
- `SlyCallScreeningService.kt` — the screening service + brain text-back + answer-on-speaker notification.
- `tools/CallHandling.kt` — request/hold the call-screening role.
- `MemoryStore.aiCallHandling / callTextBack` — the toggles (Settings).
- Manifest: `SlyCallScreeningService` with `BIND_SCREENING_SERVICE` + `android.telecom.CallScreeningService`.
- `tools/VoiceSampleStore.kt` — stores the on-device clone sample + the read-aloud training script.
- `screens/VoiceSetupDialog.kt` — "Set up my voice": record / play back / delete the sample (Settings).
