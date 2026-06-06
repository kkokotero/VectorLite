# Contributing to VectorLite

Thanks for helping improve VectorLite.

This project is a public Android library, so changes should stay small, clear, and easy to review.

## Before you start

- Read the README first.
- Prefer small, focused changes.
- Keep public-facing code and documentation in English.
- Do not add AI attribution or `Co-Authored-By` lines to commits.

## Recommended workflow

1. Create a branch for your change.
2. Make the smallest useful edit.
3. Run the relevant tests.
4. Update documentation if the public API changes.
5. Keep commits conventional and focused.

## Code style

- Use Kotlin idioms and clear naming.
- Keep public APIs readable.
- Prefer simple examples over dense abstractions.
- Keep comments brief and useful.

## Testing

Run the relevant module tests before opening a pull request. For changes in VectorLite, validate:

- unit tests
- Android instrumented tests when SQLite behavior changes
- compilation for the touched modules

## Documentation

If you change:

- a public method
- a builder option
- a query helper
- a service contract

please update the README or KDoc so the public API stays understandable.

## Pull requests

PRs should explain:

- what changed
- why it changed
- how it was verified

Keep the review path easy. If a change can be split into smaller PRs, split it.

