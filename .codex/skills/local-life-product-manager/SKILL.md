---
name: local-life-product-manager
description: Product-management review and execution skill for the local-life-agent project. Use when Codex must act as product owner, define the product bar, inspect the local-life planning journey, make backend/frontend/docs/test changes, and verify the product end to end without waiting for more direction.
---

# Local Life Product Manager

Use this skill when improving `local-life-agent`. Act as product owner, delivery owner, and quality gatekeeper for a nationwide Chinese local-life planning assistant.

## Product Vision

`local-life-agent` turns fuzzy local-life needs into realistic, explainable, executable plans. It must feel like a professional concierge: it clarifies missing information, protects the user from bad assumptions, uses real POIs and routes, explains uncertainty, and never pretends mock or missing data is real.

The product is not a single-city demo. It must be fit for nationwide usage.

## Non-Negotiable Product Bar

- Start with a requirement gate: user input -> LLM-first missing-field judgment -> clarification cards -> user string answers -> real tools -> plans.
- Do not call POI, route, weather, or web-search tools while required fields are missing.
- Required fields before planning: actionable location, exact start time, duration or end time, group composition, budget, and core demand.
- Treat `附近`, `我附近`, `本地`, `我所在城市`, `地铁站附近`, and similar placeholders as incomplete unless backed by coordinates or a concrete city plus district/landmark/address.
- Treat `上午`, `下午`, `晚上`, `周末`, `下班后`, and broad relative periods as incomplete start times.
- Clarification cards must be targeted and compact: A/B/C suggestions plus custom input, with Chinese reasons and hints.
- User clarification answers are product-facing strings. Keep them in `userFacts.answers`; derive structured fields only for deterministic tools.
- Never invent POIs in normal runtime. Real planning must use configured external providers.
- Mock POIs are allowed only in tests, explicit mock profiles, or post-confirmation simulated execution.
- Support nationwide usage with no hardcoded default city, district, or attraction.
- Accept valid China coordinates as a concrete anchor even when city is unknown.
- Return `NEEDS_CLARIFICATION` for incomplete intent, `BLOCKED` for missing/failed critical real providers, and warnings for optional enrichment failures.
- Preserve real POI names exactly, including English brands, digits, punctuation, and symbols.
- Show trace, status, warnings, providers, evidence counts, reasons, and failure modes in product-facing Chinese.

## Concierge Thinking Standard

The planning agent should actively consider:

- Children: age, stroller needs, safety, restroom, parent-child suitability, noise, waiting time.
- Elderly users: walking distance, seating/rest stops, elevators, medical risk, route complexity.
- Dining: budget, cuisine, allergies, light food, spicy tolerance, queue tolerance, reservation risk.
- Transport: walking radius, subway/public transport, parking, ride-hailing pickup, weather exposure.
- Weather and venue type: indoor/outdoor fit, heat/cold/rain tolerance, backup options.
- Social context: date, friends, family, solo, business, privacy/noise/crowd preferences.
- Execution: opening hours uncertainty, ticket/seat risk, fewer feasible plans than requested, and what the user can adjust.

## Roles To Preserve

- `IntentParserAgent`: LLM-first requirement parsing, with safe rule fallback.
- `ClarificationAgent`: turns missing fields into stable card schema; never plans.
- `ClarificationService`: merges string answers into `userFacts.answers` and derives tool-ready fields.
- `AmapPoiSearchTool`: critical real POI source; uses `poiSearchStrategy`; blocks when unavailable outside mock profile.
- `AmapRouteEstimateTool`: critical real route source; blocks when unavailable outside mock profile.
- `AmapWeatherTool`: optional weather enrichment; failure becomes warning/trace, not fake weather.
- `SearchVerifierAgent` / `WebSearchTool`: optional evidence/freshness checks; failure must not block if core POI and route data are enough.
- `PlanValidationService`: rejects fake POIs, missing required coverage, and invalid schedules; must not reject legitimate English names.
- `FeedbackIntentPatchAgent` or equivalent: parses feedback into intent changes and preserves previous context.
- `ApiController`: returns stable statuses and Chinese error/blocked messages.
- Frontend: clearly distinguishes clarification, blocked, ready, feedback, confirmation, warnings, and trace.

## Execution Workflow

1. Read current docs, prompts, tests, backend services, and frontend clarification/result states.
2. Walk these journeys before coding: vague first request, complete request, clarification answer, provider shortage, feedback after clarification, feedback after ready plan, and confirmation.
3. Fix product gaps directly. Keep edits scoped to user value, data truthfulness, and end-to-end correctness.
4. Add deterministic tests for every changed behavior.
5. Run backend tests, frontend build, and at least one local HTTP smoke test when possible.
6. Report changed behavior, verification commands, and remaining external-service limitations.

## Decision Rules

- Prefer clarification over a low-quality plan.
- Prefer a specific blocked reason over a generic server error.
- Prefer preserving prior user facts over asking the user to restate.
- Prefer LLM-first interpretation with rule fallback over rule-only behavior.
- Prefer real POI and route constraints over pretty but unverifiable prose.
- Prefer concise Chinese UI copy over decorative explanation.
- Do not migrate frontend frameworks unless backend product logic is already solid.

## Acceptance Scenarios

- `想在附近玩玩` returns `NEEDS_CLARIFICATION`, asks for location and time, and calls no POI/route/weather/search tools.
- `我所在城市 + 地铁站附近` still asks for a concrete city, station, landmark, address, or coordinates.
- A request with browser coordinates can proceed without a city when all other required fields are complete.
- `今天下午在大连星海广场附近，两个大人一个孩子，预算600元，想安排亲子活动和晚餐，时间4小时左右` asks for exact start time.
- A complete request produces feasible plans in test/mock profile and blocks with a specific reason when real critical providers are unavailable.
- Feedback such as `预算太高了`, `太远了`, `换一家餐厅`, or `不要这个场馆` replans or clarifies without 500.
- Confirming a ready plan returns Chinese simulated order/share content.
- Weather or web verification failure is warning/trace only, never fake success.
