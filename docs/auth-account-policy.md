# Authentication account policy

Korean version: [인증 및 계정 정책](auth-account-policy.ko.md)

## Password reset

Implemented behavior:

- `POST /api/v1/auth/password-reset/requests` always returns `202 Accepted` with the same message, whether the email exists or not.
- Password reset is available only for accounts linked to the `EMAIL` provider. Social-only users must reset credentials at Google, Apple, or Kakao.
- The server creates a cryptographically random 32-byte token. Only its SHA-256 hash is stored.
- A token expires after 30 minutes, is single-use, and all older active tokens for that user are revoked.
- Requests for the same account are silently ignored for 60 seconds to reduce email abuse.
- After password reset, `authVersion` is incremented so every previously issued JWT is rejected.
- Development uses log delivery. Production defaults to SMTP and must never log raw reset tokens.

Operational decisions still required:

- Add IP/email rate limits at Nginx or Redis level. Account cooldown alone does not stop distributed abuse.
- Queue email delivery through an outbox or managed mail service with retries and delivery metrics. The current after-commit sender logs a delivery failure but does not retry.
- Configure SPF, DKIM, DMARC and a verified sender domain.
- Decide whether password strength should require breached-password checks. Character-composition rules alone generally produce worse passwords.
- Add scheduled cleanup for expired token rows after the audit retention period.

## Account deletion

Implemented behavior:

- `DELETE /api/v1/auth/me` requires a valid access token and `confirmation: "DELETE"`.
- Email accounts must also provide the current password.
- All login provider links are deleted, active reset tokens are revoked, and the user row is soft-deleted.
- Email and display name are anonymized; role and subscription tier are reset.
- `authVersion` is incremented, so existing JWTs stop working immediately.
- The same email or social identity may create a new account after withdrawal.

Operational decisions still required:

- Social-only accounts currently use a valid JWT plus explicit confirmation. Before production, require recent Google/Apple/Kakao reauthentication or a short-lived deletion challenge.
- Define whether withdrawal is immediate or has a 7-30 day recovery period. The current implementation is immediate and irreversible from the API.
- User-owned prayers, journals, AI conversations, group posts, and analytics still reference the anonymized user row. Decide per data type whether to delete, anonymize, retain for group integrity, or retain for legal/accounting obligations.
- App Store and Google Play subscriptions are not automatically cancelled by deleting the My Pasture account. Show this clearly and process store server notifications.
- Define backup deletion and legal retention windows, and publish them in the privacy policy.
- Add a withdrawal audit event without storing unnecessary personal data.
- Consider a re-registration cooldown if abuse of free quotas or rewards becomes a problem.
