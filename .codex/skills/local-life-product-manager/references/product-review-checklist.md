# Product Review Checklist

Use this checklist with the `local-life-product-manager` skill when reviewing `local-life-agent`.

## Journey Gates

- Session creation works even when Redis or optional infrastructure is absent.
- Vague first requests return `NEEDS_CLARIFICATION` and call no POI, route, weather, or web-search tools.
- Clarification cards expose `options` A/B/C, `suggestions`, `allowCustom=true`, reasons, and expected answer hints.
- Clarification answers are accepted as strings and preserved under `intent.userFacts.answers`.
- Derived structured fields are available under `intent.derived` for tool calls.
- Location placeholders such as `附近`, `我附近`, `我所在城市`, `地铁站附近`, and `本地` are rejected as incomplete anchors.
- Valid coordinates are accepted as a concrete anchor without requiring a city name.
- Broad time expressions such as `下午`, `晚上`, `周末`, and `下班后` are not treated as exact start times.
- Complete requests produce feasible plans in test/mock profile or specific blocked reasons in real-provider runtime.
- Feedback keeps previous context and does not require the user to restate the whole request.
- Feedback on a `NEEDS_CLARIFICATION` session does not throw missing-option errors.
- Confirmation works only after a ready plan and returns Chinese simulated booking/order/share details.
- Trace and warnings explain provider behavior without exposing raw stack traces.

## Product Quality Gates

- No normal-runtime fake POI fallback is introduced.
- No hardcoded city-specific suggestions appear when the user's city is unknown.
- Requested plan count is clamped to 1-5; fewer returned plans include an explanation.
- Real POI names are preserved exactly, including English names, digits, symbols, and punctuation.
- Weather and web verification failures degrade gracefully and do not invent data.
- The planning agent considers children, elderly users, mobility, allergies, diet, weather, queue tolerance, parking, transit, pets, and indoor/outdoor needs.
- UI text is Chinese except real POI names and technical provider/trace labels.
- The first screen is the usable planning experience, not a marketing landing page.
- The frontend clearly distinguishes clarification, blocked, ready, feedback, and confirmation states.

## Engineering Quality Gates

- Backend tests pass with the Maven wrapper.
- Frontend build passes with the package scripts.
- At least one local HTTP smoke test is run when a backend can start locally.
- Tests cover every changed behavior with deterministic data.
- No secrets, build outputs, logs, `target`, `dist`, `external-repos`, or local config are staged.
- Existing user changes are preserved.

## Common Failure Patterns To Hunt

- Redis connection exceptions leaking as 500.
- Vague `附近` treated as concrete location.
- Hardcoded 大连 or another default city leaking to nationwide users.
- Broad `下午` treated as a schedulable start time.
- Feedback on clarification sessions causing plan-option lookup failures.
- Weather or search using a default city when the user provided only coordinates.
- English POI names rejected as fake by over-strict validation.
- Missing provider API keys causing an unhelpful stack trace instead of a product message.
- Optional enrichment failure being presented as successful real data.
