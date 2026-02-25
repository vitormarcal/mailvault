Before implementing any feature in MailVault, follow these mandatory rules.

Goal
Keep the project simple, secure, consistent, and incremental. Do not add unnecessary complexity.

Mandatory architecture

1) Package structure (do not change):
   dev.marcal.mailvault
    - api        -> DTOs (request/response)
    - web        -> REST Controllers
    - service    -> business logic
    - repository -> data access via JdbcTemplate
    - domain     -> internal models
    - config     -> Spring configuration
    - util       -> technical helpers

2) Persistence
- Use JdbcTemplate (DO NOT use JPA/Hibernate)
- SQLite as the primary database
- Flyway for all schema changes
- No table created outside migrations

3) Code style
- Idiomatic Kotlin
- Data classes for DTOs and domain
- No giant classes (>300 lines)
- One service per clear responsibility
- No heavy logic inside controllers
- Controllers only orchestrate and return DTOs

4) Errors
- Create a GlobalExceptionHandler (@ControllerAdvice)
- Return standard JSON:
  {
  "error": "CODE",
  "message": "Clear description",
  "timestamp": "ISO-8601"
  }
- Do not return stacktrace to the client

5) Logging
- Use the standard Spring logger
- Never log:
    - full email content
    - attachments
    - raw HTML
- It is allowed to log message id, path, and summarized errors

6) Mandatory security
- Never render HTML without sanitization
- Never load remote resources automatically
- Block any URL with scheme different from http/https
- Prepare code for an SSRF guard in the future
- Serve files with:
    - X-Content-Type-Options: nosniff
    - Appropriate Content-Disposition
- Do not use eval, unnecessary dynamic reflection, or arbitrary execution

7) Configuration
   Create MailVaultProperties in config:
- rootEmailsDir (default: ./data/emails)
- storageDir (default: ./data/storage)
- maxAssetsPerMessage
- maxAssetBytes
- etc (extensible)

Load via @ConfigurationProperties.

8) Performance
- Always paginate listings
- Never load all emails into memory
- Use streams for large file reading
- Limit downloads by size

9) Tests
- Create at least:
    - 1 context test (already exists)
    - 1 repository test
    - 1 main service test when relevant
- Full coverage is not required, but critical parts must be tested

10) README
    Always update README when:
- adding a migration
- adding an endpoint
- adding a new configuration

11) Docker-ready
- Do not use absolute paths
- Everything must be configurable through properties
- Database and storage must work via volumes

12) Simplicity rule
    If there are two solutions:
- choose the simplest one that solves the problem
- avoid additional frameworks
- avoid premature abstractions

13) Incremental rule
    Before implementing any large feature:
- Write a short plan (checklist)
- Then implement

14) Forbidden
- Add authentication
- Add multi-tenant support
- Add async queue
- Add distributed cache
- Add features outside the scope defined in subsequent prompts

15) General quality criteria
    The code must:
- Compile
- Start with ./gradlew bootRun
- Not generate critical warnings
- Have clear separation of responsibilities

16) Mandatory finalization step
- After completing any code change, always run `./gradlew ktlintFormat`
- If this step reveals issues, fix them automatically before finishing the task
- Only finish when formatting is applied and related issues are resolved

17) Documentation-first when appropriate
- Before implementing or debugging, check existing project documentation when relevant (README, docs/, migration notes, runbooks)
- Prefer reusing documented decisions/patterns over creating parallel approaches
- If docs and code diverge, align code with the approved direction or update docs explicitly

18) Knowledge retention (mandatory)
- When discovering non-obvious behavior, incident learnings, provider quirks, or operational decisions that can be lost, create or update a document under `docs/`
- Write concise, reusable guidance (problem, symptoms, root cause or hypothesis, decision, and validation path)
- If this new knowledge affects usage/configuration/operations, also add a reference in README

Now confirm understanding of these rules and wait for the next feature prompt.

Additional mandatory rule for any new MailVault feature:

Before writing code, you MUST:

1) Write a short checklist plan (maximum 12 items)
2) Briefly explain which files will be created/changed
3) Explain which migrations will be needed (if any)
4) Confirm acceptance criteria

Only after presenting the plan, wait for confirmation before implementing.

Goal:
- Avoid rushed implementation
- Ensure coherent architecture
- Reduce rework
- Keep incremental delivery

Never skip this step.
