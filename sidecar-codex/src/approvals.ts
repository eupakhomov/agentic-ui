/**
 * Maps our binary allow/deny decision onto whatever decision Codex's approval
 * request actually offers. Confirmed live (Task 1) that `availableDecisions` is
 * per-request, not a fixed enum: a command-exec approval may offer only
 * `["accept", {acceptWithExecpolicyAmendment}, "cancel"]` (no plain "decline"), while
 * a file-change approval may omit `availableDecisions` entirely. See
 * docs/plan/phase-5.13-codex-provider.md "Deny semantics differ by item kind" —
 * denying a command with no "decline" offered can abort the whole turn, not just
 * that tool call. That's a real Codex behavior, not something this mapping can paper
 * over; it only picks the least-wrong legal option.
 */

export function allowDecision(): string {
  return 'accept';
}

export function denyDecision(availableDecisions: unknown): string {
  const avail = Array.isArray(availableDecisions) ? availableDecisions : [];
  if (avail.includes('decline')) return 'decline';
  if (avail.includes('cancel')) return 'cancel';
  // No availableDecisions offered at all (observed for some fileChange requests) —
  // "decline" is the documented FileChangeApprovalDecision/CommandExecutionApprovalDecision
  // member; send it rather than relying on the server's undocumented leniency toward
  // arbitrary strings (observed in Task 1, not something to depend on).
  return 'decline';
}
