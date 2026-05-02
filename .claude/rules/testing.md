# Testing rules

For every non-trivial change:

1. Identify the smallest meaningful test.
2. Run it if the environment supports it.
3. If it cannot be run, explain why and provide the exact command the developer should run.

Do not claim tests passed unless the command was actually executed successfully.

Prefer fast tests first:
- Android unit tests before instrumented tests.
- iOS unit tests before UI tests.