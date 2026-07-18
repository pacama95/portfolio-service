---
name: feature-plan
description: Gather the full context of a new feature and produce a clear implementation plan before any code is written. Use when the user wants to start a new feature, asks to plan a feature, or mentions feature context gathering or design docs.
disable-model-invocation: true
---

# Feature Plan

Produce two artifacts for the feature, language-agnostic, under `docs/features/<feature-slug>/`:

- `context.md` — what the feature is and why (the behavioral contract)
- `plan.md` — how it will be built (the implementation plan)

These artifacts drive the `feature-tests`, `feature-implement`, and `feature-judge` skills. Write them so someone (or an agent) with no access to this conversation could execute the plan.

## Workflow

1. **Read the project rules first** (`.cursor/rules/`). The plan must comply with the project's architecture, testing, and style rules — cite the relevant rules in the plan.
2. **Explore the codebase** to understand what already exists: entry points, layers/modules the feature touches, similar existing features to mirror, integration points (APIs, queues, databases, external services).
3. **Resolve ambiguity**: if requirements have real gaps or trade-offs only the user can decide, ask now — never during implementation.
4. **Write `context.md`**:
   - Problem statement and goal (why this feature exists)
   - Functional requirements as observable behaviors: inputs, outputs, side effects
   - Business rules and edge cases, including expected business errors/rejections
   - Failure modes: what must roll back, what must be retried, what is ignored
   - Out of scope (explicitly)
   - External contracts: API shapes, event schemas, data model changes — defined by behavior, not by implementation types
5. **Write `plan.md`**:
   - Affected components/layers and new ones to create, following the project's architecture rules
   - Public contracts to introduce (interfaces/ports, commands, results, errors) — signatures only
   - Data/schema migrations
   - Step-by-step implementation order, each step small and independently verifiable
   - Testing strategy: which behaviors get unit tests, which get integration tests, what gets stubbed
   - Risks and open questions
6. **Get confirmation** from the user on the plan before implementation starts.

## Rules

- The context describes **behavior**, never implementation details — `feature-tests` uses it to write tests without seeing code.
- Every requirement in `context.md` must be testable; if you cannot phrase it as an observable behavior, rewrite it.
- Keep the plan consistent with the project rules; where a rule dictates structure (architecture, error modeling, testing layout), reference it instead of restating it.
