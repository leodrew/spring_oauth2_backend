# Report Template — Spring OIDC/OAuth2 Best-Practices Review

Write to `docs/reviews/YYYY-MM-DD-spring-oidc-oauth2-review.md` in the target repo (create the dir if needed). Match the repo's existing doc style if it has one.

```markdown
# Spring OIDC/OAuth2 Best-Practices Review — <app-name>

> **Review date:** YYYY-MM-DD · **Source state:** <branch/commit> · **Reviewer:** <who/method>
> **Baselines (pinned):** RFC 9700 (BCP 240) · draft-ietf-oauth-browser-based-apps-<NN> (<date>, <status>) · Spring Security <x.y> reference.
> Companion docs: <the project's own auth docs, if any>

## Contents
(§0–§5 + Appendix A links)

## §0 — Executive summary
Verdict paragraph (architecture-level judgment first, then gap character).
Severity-count table. Top-3 actions.

## §1 — Scope, method, severity rubric
What was reviewed (file inventory) / NOT reviewed (out-of-repo config, IdP server, SPA…).
Method: how findings were verified (evidence + authority + docs cross-check).
The 5-tier rubric table; note severity ≠ priority.

## §2 — Findings
Ordered High → Medium → Low → Info. One subsection per finding:

### §2.N F<n> — <one-line title>
| Severity | Priority | Authority | Files |
|---|---|---|---|
| ... | P<0-3> | <spec §, verified> | <file:line> |

**Evidence.** <file:line + minimal excerpt — enough to re-verify>
**Impact.** <what actually goes wrong, in this deployment>
**Recommendation.** <the Spring API / property to use; concrete>
**Effort:** S/M/L. **Interactions:** <finding cross-refs>

## §3 — Accepted-deviations register
| # | Deviation | Authority deviated from | Rationale | Compensating controls | Revisit trigger |
(one row per documented deliberate trade-off; incomplete rows are findings)

## §4 — Prioritized fix plan
| Pri | Finding(s) | Action | Effort | Depends on | Verification | IdP-side counterpart |
(P0 = topology-critical; P1 = cheap MUST-fixes; no code changes in the review itself)

## §5 — Compliance matrix
One row per checklist requirement, grouped by lane:
| Requirement | Authority | Level | Status: Pass / Gap→F-nn / Deviation→D-n / N-A |

## Appendix A — Environment assumptions requiring ops confirmation
| # | Question for ops | Affects | If yes | If no |
(every severity that is conditional on out-of-repo facts gets a row)
```

Rules:
- Every High/Medium finding: re-open the cited `file:line` before delivering.
- Every checklist-lane requirement appears in §5 — including the Passes (passes prove coverage).
- Conditional severities (topology, domain relationships, mounted config) go to Appendix A, and the finding text says which answer changes the tier.
