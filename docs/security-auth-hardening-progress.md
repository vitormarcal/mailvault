# Security/Auth Hardening Progress

Branch: `security-authz-hardening`

## Goal
Harden authentication and authorization flow, ensuring only necessary routes are public and reducing attack surface.

## Stages
- [x] Stage 1: Create branch and initialize progress tracking document.
- [x] Stage 2: Enable and enforce CSRF for session auth, harden session/cookie defaults, and adapt frontend requests.
- [x] Stage 3: Protect initial bootstrap with installation token gate.
- [x] Stage 4: Add/adjust security tests for public/protected route matrix and new controls.
- [ ] Stage 5: Update README/docs and finalize hardening notes.

## Notes
- This document is the continuity source. If context is compacted/reset, read this file first.
- Commit after each stage completes successfully.
- Stage 2 completed with passing tests:
  - `./gradlew test --tests "*MessagesControllerIntegrationTest" --tests "*SecurityConfigTest"`
- Stage 3+4 completed with passing tests:
  - `./gradlew test --tests "*SetupControllerSecurityIntegrationTest" --tests "*MessagesControllerIntegrationTest" --tests "*SecurityConfigTest"`
