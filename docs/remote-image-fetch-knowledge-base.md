# Remote Image Fetch: 403 with Valid URLs

## Context

In some providers, image URLs embedded in HTML email are valid and open correctly in mail clients, but direct server-side fetch can return `403`.

This can happen even when:

- URL is correct
- host is reachable
- TLS is valid
- request uses common headers (`User-Agent`, `Accept`, `Referer`)

## Why it happens

CDN/WAF/anti-bot layers may classify requests by more than URL validity:

- HTTP client fingerprint (TLS/ALPN/HTTP stack behavior)
- browser context hints (`Sec-Fetch-*`, client hints)
- compression negotiation
- cookie/session context
- origin/referer consistency
- bot heuristics per ASN/IP reputation

As a result, a link can be "valid for end-user clients" and still be blocked in backend fetchers.

## Observed pattern

Typical symptom:

- minimalist backend request -> many `403`
- richer browser-like request profile -> `200` for same resources

Even with browser-like headers, behavior may still differ between command-line tools and JVM `HttpClient` due to different transport fingerprints.

## Product implications

- URL validity alone is not enough to guarantee freeze/download success.
- `FAILED` on remote asset download should be treated as expected in some providers.
- tracking-pixel blocking logic should remain independent from download success.

## Engineering guidance

1. Keep tracking-pixel detection and skip-before-fetch path deterministic.
2. Use browser-like headers by default for remote image requests.
3. Keep retries with alternate request profiles.
4. Preserve failure reason in metadata (`http status 403`, `blocked by ssrf guard`, etc.).
5. Avoid external-network assertions in CI tests.
6. Prefer deterministic unit tests with fake clients.
7. If real-network verification is needed, run as manual smoke diagnostics only.

## Testing strategy (recommended)

- Unit/integration tests in repository:
  - use fake HTTP client
  - assert parser behavior
  - assert tracking pixel is skipped before network call
  - assert status accounting (`downloaded`, `failed`, `skipped`)

- Manual diagnostics (outside CI):
  - run targeted fetch checks on selected `.eml` samples
  - compare baseline vs browser-like request profiles
  - store findings in this knowledge base

## Operational playbook

When users report "email shows images in client but backend freeze gets 403":

1. Confirm parser extracted expected remote image URLs.
2. Confirm tracking candidates are skipped correctly.
3. Reproduce HTTP status with backend profile.
4. Compare with enriched browser-like profile.
5. If still blocked, classify as provider-side anti-bot policy and keep failure transparent in UI/API.

## Non-goals

- Do not couple automated tests to third-party domains.
- Do not assume third-party policy stability.
- Do not treat every `403` as a backend bug.
