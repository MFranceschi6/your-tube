# Security rules

Never expose, print, commit, or modify secrets.

Sensitive files include:
- `.env*`
- `GoogleService-Info.plist`
- `google-services.json`
- `*.p8`
- `*.mobileprovision`
- signing keystores
- provisioning profiles
- certificates
- API tokens

For authentication, payment, account, or personal-data code:
1. Preserve existing security checks.
2. Avoid logging PII, tokens, or credentials.
3. Add tests for failure cases.
4. Ask before changing crypto, signing, auth, or entitlement behavior.