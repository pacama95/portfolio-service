---
name: feature-tests
description: Write tests for a feature from its context and plan only, never from the implementation, enabling TDD without implementation bias. Use when the user asks to write tests for a planned feature, do TDD, or create tests before implementing.
disable-model-invocation: true
---

# Feature Tests (TDD, Context-Driven)

Write the feature's tests **exclusively from `docs/features/<feature-slug>/context.md` and `plan.md`** plus the public contracts declared in the plan. The tests define the specification; the implementation must conform to them — not the other way around.

## Hard Constraint: No Implementation Bias

- **Never read the implementation** of the feature under test (existing or in-progress). Do not open the classes/modules being implemented, and do not derive assertions from how the code currently behaves.
- You **may** read: `context.md`, `plan.md`, the public contract declarations named in the plan (interfaces/ports, command/result/error types), project rules, test infrastructure, and existing tests of *other* features as style references.
- If a behavior needed for a test is missing from the context, that is a gap in the context — update `context.md` (or ask the user), don't peek at code to fill it in.
- Assert on **observable behavior**: returned results, emitted events, persisted state, HTTP responses. Never on internal call sequences or private structure, except to verify a declared side effect happens (e.g. a port was invoked with the right command).

## Workflow

1. Read the project testing rules (`.cursor/rules/`) and follow their conventions: test locations/source sets, mocking style, database/state fixtures, external service stubbing.
2. Read `context.md` and `plan.md`. Extract every requirement, business rule, edge case, and failure mode into a test list. Every behavior in the context maps to at least one test.
3. Write **unit tests** for each behavior of each planned component, driving through its public contract with collaborators stubbed as the plan specifies.
4. Write **integration tests** for each externally observable flow (endpoint, event consumption, scheduled job) per the plan's testing strategy.
5. Cover explicitly, as separate tests:
   - Happy paths
   - Expected business errors/rejections (these are normal outcomes, not exceptions)
   - Rollback-worthy failures (infrastructure errors) and their observable effect (rollback, retry, error response)
   - Edge cases named in the context
6. Tests must **compile against the planned contracts and fail** (red) before implementation exists. Stub/skeleton declarations may be created only as empty contracts matching the plan — no logic.
7. Produce a short coverage map at the end: each context requirement and the test(s) that verify it. Flag any requirement you could not test and why.

## Style

- Test names state the behavior: `rejects duplicate transaction`, not `test execute 2`.
- One behavior per test; arrange/act/assert kept obvious.
- Tests are independent and repeatable: own their fixtures, no shared mutable state, no ordering assumptions.
