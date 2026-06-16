# AgentPermissions

The 5-tier permission + receipt engine, per tool.

| Tier | Behavior |
|---|---|
| read only | reads data, never writes |
| draft only | prepares an action, never executes/sends |
| ask before action | executes only after explicit user confirm |
| autonomous | executes within scope, logs a receipt |
| blocked | tool disabled entirely |

Global **pause** kill-switch drops every tool to Manual Mode instantly. Every executed action
emits a receipt: `{timestamp, tool, args (redacted), tier, result, reversible, undo?}`.
Receipts are append-only and surfaced on the Memory screen.
