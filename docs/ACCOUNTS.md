# Accounts — the open identity module

`accounts` is the **open-core**, **opt-in** identity layer: it gives a
self-hosted Graphden real users, passwords, sessions and — the point of the
whole design — **account linking** (one person, many sign-in methods). The
multi-tenant *policy* on top (org membership, operator-by-email, enforced 2FA)
stays in the private `graphden-tenancy` addon; `accounts` is everything that is
safe and useful without it.

It is **off by default**. Turn it on by naming its config fragment in
`GRAPHDEN_ADDON_CONFIGS`:

```
GRAPHDEN_ADDON_CONFIGS=graphden/accounts/addon.edn
```

Omit it and core runs exactly as before (single-token bearer, or fully open with
no `AUTH_TOKEN`).

## Data model (three non-versioned entities)

| Entity | Is | Key fields |
|--------|----|-----------|
| `:account` | a **person** — the stable authz subject downstream grants/roles key on | `id`, `display-name?`, `primary-email?` (UNIQUE-when-present; the linking + operator-by-email lookup, set only from a **verified** email), `status` (`active`/`suspended`), `created-at` |
| `:identity` | **one way to sign in**, bound to an account (1 account ↔ N identities → linking) | `account-id`, `provider` (`password`/`google`/`github`/`telegram`), `subject` (provider's stable id; the lower-cased email for `password`), `secret-data?` (bcrypt hash for `password`), `email?`, `email-verified?`, `created-at`. **UNIQUE (provider, subject)** |
| `:session` | an authenticating token, org-agnostic | `token-hash` (SHA-256, UNIQUE — the raw token is never stored), `account-id`, `expires-at?` (nil = never), `kind?` (nil/`api` authenticate), `label?`, `created-at` |

All three are non-versioned platform entities (like the tenancy identity
entities) — no version mirror, the cheap schema path.

## Secrets

- Passwords: **bcrypt**, cost 12 (`accounts.core/hash-password`).
- Sessions: only the **SHA-256 hash** of the token is stored. The raw token
  exists exactly once — the return value of `mint-session!` handed to the client
  (as an HttpOnly `gd_session` cookie for the browser, or a bearer for API/CLI).

## The provider

`accounts.provider/AccountsAuthProvider` implements the core
`graphden.auth.provider/AuthProvider` seam. It reads the bearer **or** the
`gd_session` cookie, resolves it through `accounts.core/authenticate-token` to an
ACTIVE account, and returns an **org-agnostic** principal
`{:authenticated? :user-id :user}`. Open core has no orgs; when the tenancy
addon is present it resolves the org from the account's membership at
request-scope time.

Wired at `:accounts/provider` and swapped into `:exec/context`'s
`:auth-provider` — the same swap-point the tenancy `storage-token-provider` uses.

## Sequencing (this module vs tenancy)

`accounts` is authoritative for identity/credentials/sessions. The existing
tenancy `:user`/`:token` model is **subsumed** by `:account`/`:session` in the
Phase-4 cutover (operator-by-email), where the tenancy layer starts resolving
org from account membership instead of a denormalized `:token.org`. Until then,
`accounts` stands alone for self-hosted; there is no permanent dual model.

## Roadmap of the epic

0. **Identity foundation** — this module (schema, password provider, sessions,
   linking primitive, `AuthProvider`). ✅
1. Email + verification (Resend) — promotes a verified email to
   `:account.primary-email`.
2. Social providers — Google (OIDC), GitHub (OAuth), Telegram (login widget);
   each a config-gated module resolving to an `:identity` row, auto-linking by
   **verified** email where the provider vouches one.
3. Account-linking UI — attach/detach identities on a signed-in account.
4. Operator-by-email + tenancy cutover onto `accounts`.
5. 2FA (TOTP) — self-serve, plus tenancy-enforced for a user/role/group.
