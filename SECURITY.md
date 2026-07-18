# Security Policy

SlyOS is early-stage software that handles sensitive personal data (messages, contacts, calendar, photos, and API keys). We take security seriously and appreciate responsible disclosure.

## Reporting a vulnerability

Please report security issues **privately** — do not open a public GitHub issue.

- Email: **support@belto.world**
- Include: a description, reproduction steps, affected version/commit, and impact.
- We aim to acknowledge within 5 business days.

## Scope & honest caveats

- The **prompt-injection filter** and the **outbound safety filter** are first-party and have **not been independently audited.** Autonomous features (auto-reply, tap-send, overnight missions) should be treated as beta and kept under review.
- Keys are stored on-device and are excluded from Android backup, but a compromised or rooted device can expose them.
- Data sent to third-party model/providers travels under **your** keys/accounts — see [privacy.html](docs/privacy.html) for the full list of data flows.

## Release integrity

- Each GitHub release publishes the **SHA-256** of `SlyOS.apk`. Verify before installing:
  ```
  shasum -a 256 SlyOS.apk
  ```
- Releases are signed with a consistent signing key; the key's SHA-256 fingerprint is published in the release notes once release signing is finalized.
