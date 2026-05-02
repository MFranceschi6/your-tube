Review the current mobile diff.

Steps:
1. Inspect `git diff --stat`.
2. Inspect relevant changed files.
3. Check Android changes against `.claude/rules/android.md`.
4. Check iOS changes against `.claude/rules/ios.md`.
5. Check tests and security-sensitive files.

Return:
- Blocking issues
- Suggested improvements
- Missing tests
- Validation commands to run