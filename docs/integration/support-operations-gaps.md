# Support Operations (WP09) — Integration Gaps

Backend module: `modules/operations` (branch `feature/support-operations`).

This document lists each Figma / product expectation that the current backend **cannot** satisfy with real data today, and what is needed to close it. Until a gap is closed, the corresponding API section returns `available: false` with **no rows and no numbers** and a human-readable `reason` (never placeholder or assumed values). No other members' modules were modified to produce this list.

| # | Capability (Figma / spec) | Owner WP | Method / contract needed | Current backend behaviour |
|---|---|---|---|---|
| 1 | **Late-start tracking** — bookings past their scheduled START that have not started, with minutes-late and urgency (MEDIUM <2h, HIGH 2–4h, CRITICAL >4h). | WP05 (Booking) | A read on `BookingStatsFacade`, e.g. `List<LateStart> findLateStarts(int limit)` returning booking ref/service/customer/provider/ids, scheduled start, and minutes late. | `LateStartsPort` is a stub. `GET /late-cases` returns `lateStarts.available=false`, no rows, no per-day chart, no urgency counts, reason: "Late-start tracking is not available yet (WP05)". |
| 2 | **Timed suspension** (e.g. suspend for N days with auto-reactivation) and **Ban**. | WP01 (Auth) | A timed-suspension status + scheduler, and a BAN/BANNED status, on the auth account model and the Admin Users suspend flow. | Not implemented. Operations exposes only the existing permanent Admin Users suspend (`REPEATED_CANCELLATIONS`), which stays until an authorized Admin reactivates. No BAN action, no `reviewOn`/timed suspension anywhere. |
| 3 | **User warning notification** — notifying a customer/provider when a warning is issued. | WP06 (Notification) | A `NotificationType` for an account warning (and the matching email policy/text). | No suitable `NotificationType` exists. Warning-type actions are **recorded in `ops_case_actions` only**; no notification is sent (TODO once WP06 adds the type). |
| 4 | **Cancellation rate %** — cancellations as a percentage of total bookings in the same window. | WP05 (Booking) | `BookingStatsFacade` to return the period booking totals per user alongside `findRepeatCancellations`. | `findRepeatCancellations` returns counts only. The rate column is **omitted** (never computed from an assumed denominator). |
| 5 | **Category / service filters** on operations lists (e.g. filter overdue/late cases by service or category). | WP05 (Booking) / WP03 (Listing) | A query contract exposing booking service/category for filtering, and/or customer/provider **display names**. | `BookingSummary` exposes ids, ref and service title only. Lists show ids + ref + service title; no name or category/service filtering. |
| 6 | **Dispute NO_SHOW read by category** for the "No-show reports" section. | WP09 (Dispute — same owner) | `DisputeService` read returning NO_SHOW disputes with their real status. | Added within this WP09 work (own module). "No-show reports" lists NO_SHOW disputes with their real dispute status and a link; no urgency/minutes-late fields. |
| 7 | **Super Admin platform settings** — a configurable cancellation threshold. | WP (Super Admin) | A platform-settings read for the cancellation threshold. | No settings feature exists. Threshold comes from config `app.operations.repeat-cancellation.min-count` (default 4) / `days` (30). If a settings read is added later, prefer it over config. |

## Notes
- Every "available=false" response carries a `reason` string naming the missing dependency so the frontend can show an honest empty state.
- No emails or phone numbers are exported or logged; CSV exports carry ids/refs and names already shown in the list only, are formula-injection safe, and are capped at 5000 rows.
