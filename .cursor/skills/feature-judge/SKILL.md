---
name: feature-judge
description: Review a feature's changes like a strict PR reviewer, verifying correctness against the feature context and compliance with the project rules. Use when the user asks to review a feature, judge the implementation, act as PR reviewer, or verify the work before merge.
disable-model-invocation: true
---

# Feature Judge (PR Reviewer)

Act as an independent PR reviewer for the feature's changes. Judge the work against three references, in this priority order:

1. `docs/features/<feature-slug>/context.md` — is the required behavior fully and correctly delivered?
2. The project rules (`.cursor/rules/`) — does the code comply with every applicable rule?
3. General good practice — correctness, readability, safety, maintainability.

You are reviewing, not fixing. Produce a verdict and findings; only apply changes if the user asks.

## Workflow

1. Read the project rules, `context.md`, and `plan.md`.
2. Get the full diff of the feature (branch changes or the change set the user points at). Review **all** changed files, not just the interesting ones.
3. **Behavior audit**: walk `context.md` requirement by requirement and verify each one is implemented and covered by a test. Missing behavior or untested behavior is a critical finding.
4. **Rules audit**: check the diff against each applicable project rule (architecture boundaries, error/result modeling, mapping, API documentation, testing conventions and placement, style). Cite the violated rule by name in each finding.
5. **Test quality audit**: tests assert observable behavior from the context (not implementation internals), cover business errors and rollback-worthy failures separately, are independent, and were not weakened to make the implementation pass.
6. **Code quality audit**: bugs, race conditions, resource leaks, blocking calls in reactive flows, unhandled failure paths, dead code, scope creep beyond the plan, missing migrations/config.
7. Run the full test suite and linter yourself; do not trust claims that they pass.

## Verdict Format

```
## Verdict: APPROVE | REQUEST CHANGES

## Findings
- 🔴 Critical (must fix before merge): behavior missing/wrong, rule violation, untested requirement, failing tests
- 🟡 Suggestion (should improve): weak tests, unclear naming, minor rule drift
- 🟢 Nice to have (optional)

## Requirement Coverage
| Context requirement | Implemented | Tested |
```

Each finding names the file, the problem, why it matters (rule or requirement violated), and what to do instead. `REQUEST CHANGES` whenever any critical finding exists — do not approve with known criticals.
