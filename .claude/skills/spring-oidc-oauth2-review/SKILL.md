---
name: spring-oidc-oauth2-review
description: Use when reviewing or auditing a Spring Boot application's OAuth2/OIDC security — oauth2Login, oauth2Client, resource server, or BFF against Keycloak or another IdP — or when asked to assess PKCE, auth cookies, CSRF, logout, token refresh/storage, or proxy/forwarded-header configuration against best practices (RFC 9700, OAuth browser-based-apps BCP, Spring Security 6.x)
---

# Spring OIDC/OAuth2 Best-Practices Review

## Overview

Produces a severity-ranked findings report + compliance matrix + prioritized fix plan. Every finding carries `file:line` evidence AND a verified authority citation. Review-only: no code changes.

## When NOT to use

Non-Spring stacks; reviewing the IdP (authorization server) itself; applying fixes — review first, remediate as a separate effort.

## Procedure

1. **Inventory first — code AND context.** Establish: client type (public/confidential), grant, session strategy, token storage, and **deployment topology** (TLS-terminating proxy? replicas? affinity mechanism?). Read the project's own docs/ADRs/CLAUDE.md **before flagging anything** — a documented deliberate trade-off is a *deviation to register*, not a defect to report.
2. **Dispatch parallel read-only agents**, one per lane of `references/checklist.md`: ① authorization request/PKCE/redirects ② session, cookies, CSRF ③ logout ④ token lifecycle/refresh/storage ⑤ headers, CORS, proxy ⑥ build, authorization, tests. Each returns candidate findings with `file:line` evidence.
3. **Verify every candidate against an authority** (checklist row, or fetch the spec text). Check §-numbers and version claims against the published source. No citation → drop or mark Info.
4. **Assign severity with topology weighting** (rubric below), then priority separately: a cheap MUST-fix outranks an expensive Medium.
5. **Check recommendation safety.** Trace what a recommended change interacts with (see Red flags). A recommendation that breaks the app's documented design is a review defect.
6. **Write the report** from `references/report-template.md` into the target repo's docs tree (date-prefixed). Re-open every cited `file:line` before delivering.

## Severity rubric

| Tier | Meaning |
|---|---|
| High | MUST violation or broken security behavior *in the actual deployment topology* |
| Medium | MUST with compensating controls, or SHOULD with real impact |
| Low | Hygiene, low exploitability |
| Info | Observation or already-tracked |
| Accepted deviation | Documented trade-off: rationale + compensating controls + revisit trigger |

**Topology rule:** missing `server.forward-headers-strategy` behind a TLS-terminating proxy is **High** (breaks `{baseUrl}` redirect URIs, `isSecure()`, HSTS) — not config hygiene.

## Red flags — common review failures

- Recommending back-channel logout **without checking whether sessions are instance-local** (the logout token has no instance affinity — it misfires against in-memory sessions)
- Recommending refresh-token rotation ON **without counting concurrent refreshers** (tabs × refresh layers × replicas → `invalid_grant` races)
- Recommending removal of a scheduler/filter without reading why it exists
- Flagging a documented deliberate trade-off as a defect
- "SameSite=Strict is a finding" — check the OAuth callback first: Strict breaks cross-site top-level returns
- Citing a spec section or CVE fix version from memory
- "It works in prod, so proxy headers must be fine" — verify, don't infer

## References

- `references/checklist.md` — full per-lane requirement checklist with Spring grep targets
- `references/report-template.md` — report skeleton
