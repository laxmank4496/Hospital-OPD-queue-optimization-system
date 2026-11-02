# Android readiness
Version 1: React -> Spring Boot -> Firestore + Python ML. Version 2 adds Android -> the SAME Spring Boot API.
- All rules live in `QueueEngine` (Java); controllers are thin, so Android needs no new backend logic.
- Auth is a Firebase ID token in the Authorization header; Firebase Auth has an Android SDK. Roles are token claims.
- Responses are plain JSON DTOs with stable `code` values for errors; operations are idempotent so flaky mobile
  networks can retry safely (`idempotencyKey` on join).
- Real-time: STOMP topics or Firestore listeners (both have Android clients).
- Push notifications (FCM) would be added as another `EventSink` implementation; the engine already emits events post-commit.
