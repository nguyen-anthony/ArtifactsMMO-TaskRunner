# API client guide

## Responsibilities

- `models/` contains kotlinx-serializable ArtifactsMMO request/response shapes.
- `services/` groups endpoint wrappers by API area.
- `gateway/` owns shared cooldown, rate-limit, and error policy.
- `ApiTransport` is the single choke point for outbound API calls; services should go through `BaseApiService` rather than creating independent clients or bypassing transport controls.

Use the `artifacts-docs` submodule to verify endpoint paths, payloads, and response codes. Keep unknown response fields forward-compatible through the shared `ArtifactsJson` configuration.

## Transport invariants

- Share the transport rate limiter and cooldown tracker across services.
- Preserve cancellation instead of converting `CancellationException` into an API failure.
- A 429 may be retried for any request; 486/499 may be retried after waiting; network/5xx retries are restricted to idempotent GET or simulation calls.
- Classify ArtifactsMMO error codes centrally in `gateway/ErrorPolicy.kt`; do not scatter code-specific recovery tables through services or engine executors.
- Action responses may update cooldowns for multiple characters, including group-fight participants.

## Tests

Run `./gradlew :client:test`. Transport tests use Ktor `MockEngine` plus `runTest` virtual time; cover request count, elapsed virtual time, parsed error data, and resulting `ErrorAction` when changing gateway behavior.
