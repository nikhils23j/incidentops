# IncidentOps — interview notes

## 1. What it is

IncidentOps is a small incident tracker I built as a personal project: Spring Boot + JPA/H2 backend and a React/TypeScript dashboard. An incident moves strictly OPEN → ACKNOWLEDGED → RESOLVED. Two people acking at once can't both win, everyone's screen updates live over Server-Sent Events, and incidents nobody acknowledges within their severity's SLA are escalated automatically by a scheduled job. Every change is written to an append-only timeline, and resolving requires a short postmortem.

## 2. How it works

**Layers.** `IncidentController` (HTTP + validation) → `IncidentService` (all business rules, transactions) → Spring Data repositories → H2. DTO records are what goes over the wire; JPA entities never get serialised. `ApiExceptionHandler` turns every error into RFC 7807 `application/problem+json` with a stable `type` (`/problems/version-conflict`, `/problems/illegal-transition`, `/problems/validation`, `/problems/postmortem-required`, `/problems/not-found`). The frontend switches on that type.

**State machine.** `Status.allowedNext()` is the single source of truth. `Incident.transitionTo()` rejects anything else with `IllegalTransitionException` → 409, and the response says what *is* allowed. Resolving without a postmortem → 422. A parameterized test covers all 9 (from, to) pairs.

**Concurrency (the core idea).** Two layers:
1. *Explicit:* the client sends `expectedVersion`. If it doesn't match, we reject before changing anything. This catches "I had the page open for 5 minutes and someone else already acted".
2. *Implicit:* `@Version` on the entity. Hibernate issues `UPDATE … SET version = 1 WHERE id = ? AND version = 0`. If two requests both read version 0 at the same instant, both pass check 1, but only one UPDATE matches a row. The other gets `ObjectOptimisticLockingFailureException` → 409. I use `saveAndFlush` inside the service so the failure happens there and not at commit.

*Why optimistic and not pessimistic locking:* conflicts are rare, and a 409 telling the loser "someone else got it" is exactly the right UX. Holding row locks (`SELECT … FOR UPDATE`) would make requests wait instead of fail fast.

**Timeline.** `IncidentEvent` is `@Immutable` (Hibernate never issues UPDATEs for it). Its repository extends the bare `Repository` interface with only `save` and finders, so there's no update/delete API. Events are written in the same transaction as the incident change, so they can't disagree.

**Live updates.** The service publishes an `IncidentChanged` application event. `SseHub` listens with `@TransactionalEventListener(phase = AFTER_COMMIT)`, so only committed changes are broadcast; the loser of a race never shows up on anyone's screen. Dead emitters are removed when a send fails, and emitters are closed on `ContextClosedEvent` so shutdown isn't blocked. The browser uses `EventSource`, which reconnects automatically. I chose SSE over WebSockets because data only flows server → client and actions are ordinary REST calls.

**SLA + escalation.** On create, `ackDueAt = now + SLA(severity)` (5 m / 15 m / 1 h / 4 h, configurable). `SlaEscalator` runs on `@Scheduled(fixedDelay)`. It queries OPEN incidents past due and below the max level, then escalates each in **its own transaction**: level+1, reassign to the next responder, new deadline, `ESCALATED` event. If a human acks in between, that incident's optimistic lock fails, it's skipped, and the scan carries on.

**Time is injectable.** Everything reads a `java.time.Clock` bean. Tests replace it with a `MutableClock` and call the escalator directly, so SLA tests are deterministic and instant.

**Stats.** MTTA = mean/median of (acknowledgedAt − createdAt), MTTR = same with resolvedAt. Values are null rather than 0 when there's no data. They're computed in memory, which is a known limit.

**Frontend.** Plain React + CSS. `useIncidentStream` hook wraps EventSource; `useNow` ticks once a second so SLA badges count down without refetching. On a 409 version-conflict the UI shows a toast and reloads the latest state.

## 3. Key results (measured 2026-11-09, Apple M2, see results.json)

- **45 backend tests pass** (`./mvnw test`), **17 frontend tests pass** (Vitest). The web build passes.
- Concurrency tests passed **5 runs out of 5**.
- **Mutation check:** with `@Version` removed, **6 of 8** simultaneous acks returned 200; 2 of the 4 concurrency tests failed. With it, exactly 1.
- Load script race check: **50/50 rounds** of 8 simultaneous acks had exactly one winner.
- Live HTTP flow passed end-to-end, including a real scheduler escalation and 7 SSE events received by a subscriber.
- Shutdown with an open SSE client: about 30 s (graceful-shutdown timeout) → **0.3 s** after the fix.
- Latency, localhost, 8 concurrent clients, 2,000 requests each: p50 **3.5–4.1 ms** for list/create/ack, **15.6 ms** search, **64 ms** stats; p95 72–244 ms.

**What they do NOT show:**
- Not production performance. Client and server were on the same laptop, and the machine was heavily loaded by unrelated processes (load avg 230–287 on 8 cores), which is why p95 is ~20× p50.
- Not multi-instance correctness. Locking is correct across threads in one JVM on one database; SSE fan-out is in-memory, single node.
- Tests prove the behaviours I thought to test, not the absence of bugs. Nobody but me has used it.
- No security claims: there is no auth.

## 4. Two-minute answer: "Tell me about a project you're proud of" (STAR)

**Situation.**
"I'd built a basic incident tracker for practice. Create an incident, acknowledge it, resolve it, a Spring Boot API and a React page. It worked for one person. Then I thought about how it would actually be used: something breaks during, say, a hackathon demo, and three teammates all jump on it at once without knowing about each other. My app had no answer for that. Two people could acknowledge the same incident, and nobody's screen would update until they refreshed."

**Task.**
"So I set myself the goal of making it correct under concurrent use. If two people act at the same time, exactly one wins and the other is told so clearly. Everyone sees changes live. And if nobody acts, the system notices on its own. I also wanted to prove all of that with tests and measurements, not just say it."

**Action.**
"It was a personal project, and I used an AI pair-programming assistant for a lot of the code and test scaffolding. The design choices and the verification were what I focused on.
- On the backend I added optimistic locking: a version check the client sends, plus JPA's `@Version`, so the database itself rejects the second writer and the API returns a 409.
- I made every change write to an append-only timeline in the same transaction.
- For live updates I used Server-Sent Events, broadcast only after the transaction commits, so a rejected change never shows up on screen.
- I added SLA deadlines per severity, with a scheduled job that escalates unacknowledged incidents to the next person on call.
- To test the time-based logic I made the clock injectable, so a test can jump forward six minutes instantly.
- For the race I wrote a test that fires eight real HTTP requests at the same instant."

**Result.**
"I ended up with 45 backend and 17 frontend tests passing. The part I learned most from: I deliberately removed the `@Version` annotation and re-ran the race test. Six of the eight requests 'won'. With it back, exactly one wins, and a load script confirmed that across 50 rounds. I also found a real bug while testing: an open live-update connection made the server take 30 seconds to shut down. I fixed it and got that down to 0.3 seconds. It's still a single-instance, no-auth project. But I now understand concretely why things like optimistic locking and commit-aware events exist."

## 5. Likely follow-up questions

**Q: Why optimistic locking instead of `SELECT … FOR UPDATE`?**
Conflicts are rare and the right response to losing is "someone else already did it", not "wait". Optimistic locking fails fast with a 409 and holds no locks between read and write. Pessimistic locks would make the second request block and then fail anyway on the state machine.

**Q: If you have `@Version`, why also send `expectedVersion`?**
They catch different things. `@Version` only protects the window between this request's read and write. `expectedVersion` catches the human-scale race: I loaded the page at version 0, someone acked a minute ago, and now I'm reassigning based on stale info. Without it my request would read the fresh row and succeed, silently overriding what I didn't see.

**Q: What happens if you run two instances of the server?**
Locking still works, because it's enforced by the database. Two things break. SSE events only reach clients connected to the instance that made the change, and both instances would run the SLA job; the per-incident optimistic lock would stop double escalation, but it's wasteful. I'd fix it with Postgres LISTEN/NOTIFY or Redis pub/sub for events, and ShedLock or a DB advisory lock for the scheduler.

**Q: Why SSE over WebSockets?**
Data only flows server → browser; all actions are normal REST calls with validation and status codes. SSE is plain HTTP, works through the Vite proxy, auto-reconnects in the browser, and needed no extra dependency. If I needed client → server messages over the same channel, I'd use WebSockets.

**Q: How do you test something that depends on time?**
Nothing calls `Instant.now()` directly; everything uses a `Clock` bean. Tests register a `MutableClock` as `@Primary`, create a CRITICAL incident, advance 4 minutes (no escalation), advance 2 more (escalation), and call the job's method directly. The real scheduler interval is set to an hour in the test profile so it doesn't interfere.

**Q: Your p95 is 20× your p50. Why?**
The machine was saturated by unrelated processes during the run (load average around 230–287 on 8 cores), so requests were waiting for CPU. That's noted in results.json. The comparison between endpoints is still useful: `/stats` is slowest because it loads every row into memory, and search is a leading-wildcard `LIKE` that can't use an index. I'd move stats to SQL aggregates and use full-text search if it grew.

**Q: How much of this did AI write?**
A lot of the code and test boilerplate came from AI pair-programming. My part was the design (what to lock, where to flush, broadcasting after commit), the experiments, and checking every claim. I ran the mutation check, the repeated test runs and the load script myself, and I can walk through any file.

**Q: What would you do next?**
Authentication, so "actor" is a real identity. Flyway migrations instead of `ddl-auto`. Postgres instead of H2. SQL-side stats. Shared event fan-out for multiple instances. A Playwright end-to-end test with two browser windows.

## 6. Things I should be able to explain cold

- [ ] The SQL Hibernate generates for a `@Version` update, and what happens when it updates 0 rows
- [ ] The difference between the `expectedVersion` check and `@Version`, with an example each catches
- [ ] Why `saveAndFlush` rather than relying on commit-time flush
- [ ] What `@TransactionalEventListener(AFTER_COMMIT)` does and why plain `@EventListener` would be wrong here
- [ ] Why the SLA job escalates each incident in its own transaction
- [ ] How the injectable `Clock` makes SLA tests deterministic
- [ ] Why emitters were closed on `ContextClosedEvent` instead of `@PreDestroy`, and the 30 s → 0.3 s result
- [ ] SSE wire format (`event:`, `data:`, `id:`, comment heartbeats) and EventSource reconnection
- [ ] RFC 7807 fields (`type`, `title`, `status`, `detail`, `instance`) and why stable `type` URIs help the client
- [ ] 409 vs 422 vs 400 in this API and when each is returned
- [ ] How MTTA/MTTR are computed (mean and nearest-rank median), and why they return null rather than 0 with no data
- [ ] What p50/p95/p99 mean and why my tails were inflated
- [ ] What breaks with two server instances and how I'd fix each part
- [ ] Every number in the README and where it came from (results.json)
