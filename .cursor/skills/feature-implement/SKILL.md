---
name: feature-implement
description: Implement a planned feature following its context, plan, and the project rules, making the pre-written tests pass. Use when the user asks to implement a feature that has a plan, build the planned feature, or make the feature tests green.
disable-model-invocation: true
---

# Feature Implement

Implement the feature defined in `docs/features/<feature-slug>/context.md` and `plan.md`. The plan is the source of truth for *what* to build; the project rules are the source of truth for *how*.

## Workflow

1. **Read the project rules** (`.cursor/rules/`) and keep them loaded: architecture layout, error/result modeling, mapping, API documentation, and testing rules are binding — not suggestions.
2. **Read `context.md` and `plan.md`** fully before writing code. If tests already exist from `feature-tests`, read them too — they are the executable specification and must pass unmodified.
3. **Follow the plan's step order.** Implement one step at a time; keep the build compiling and existing tests green after each step.
4. Implement outside-in against the contracts the plan declares: create the public contracts (interfaces/ports, commands, results, errors) exactly as planned first, then fill in behavior.
5. Run the feature's tests continuously. The goal state is: all pre-written tests pass, no test was altered to fit the implementation.
6. Verify at the end: full test suite (unit + integration) passes, linter is clean, and every plan step is done.

## Rules

- **Do not change the tests to make them pass.** If a test appears wrong, it means the context, plan, and implementation disagree — stop, state the conflict, and resolve it at the context/plan level (with the user if it's a requirements question), then update tests via the `feature-tests` constraints.
- **Do not silently deviate from the plan.** If reality forces a design change (missing capability, unforeseen constraint), update `plan.md` with the change and its reason as part of the same work.
- Scope discipline: implement what the plan says and nothing more — no drive-by refactors, no speculative extensibility, no features from the "out of scope" list.
- Every rule violation the linter or reviewer would flag is yours to fix now: architecture boundaries, error modeling, mapping conventions, documentation annotations, test placement.
- New behavior discovered mid-implementation (an edge case the context missed) gets added to `context.md` and covered by a test before the implementation handles it.
