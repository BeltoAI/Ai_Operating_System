# AgentSystemService

Long-running brain behind AgentShell.

- **Now (normal app):** foreground/bound service. Hosts the LLM router, ingests notifications
  via `AgentNotificationListener`, computes "things that matter," holds memory/context, writes
  the audit log.
- **With Device Owner:** gains policy control (kiosk, app hiding, restrictions) through
  `DevicePolicyManager` — no root, no unlock.
- **Portable track only:** can become a platform-signed privileged service in an AOSP/GSI build.

Responsibilities: priority engine · memory store · tool dispatch · permission enforcement ·
local/cloud routing with redaction · append-only receipts.
