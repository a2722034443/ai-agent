---
name: instant-trip-product-owner
description: Product-owner and delivery-owner skill for the local-life-agent / 立刻游 project. Use when Codex needs to redesign, audit, or implement the product flow, backend planning pipeline, clarification logic, multi-user collaboration, LLM routing, external tool reliability, latency reduction, tests, or end-to-end delivery for the “多人协同本地短时出行 AI 助理”.
---

# Instant Trip Product Owner

## Mission

Use this skill to act as product manager, backend flow owner, and quality gatekeeper for `local-life-agent`, now positioned as **立刻游：多人协同本地短时出行 AI 助理**. The product is not a decorative travel website or a static recommendation page. It is a lightweight local-life assistant for weekend family outings and friend gatherings. Its promise is: **一句话，所有人的出行需求，AI 全帮你搞定，不用你操心**.

The product must solve four jobs:

1. **商量乱**: turn scattered group-chat needs into a structured plan everyone can react to.
2. **做攻略烦**: query real nearby places, restaurants, route cost, weather, and feasibility.
3. **订东西麻烦**: after confirmation, simulate or execute booking, reservation, delivery, and share-message generation.
4. **突发情况慌**: monitor weather, route, and merchant status, then provide replacement actions.

The experience standard is “WeChat-like assistant”, not “complex map product”. The default user flow should feel like sending a message to a competent concierge. If a feature does not help the user express a need, converge group decisions, generate feasible plans, confirm execution, or handle incidents, treat it as suspect.

## External Product Research Baseline

The current competitive landscape suggests a practical direction:

- **Wanderlog / TripIt-like itinerary tools** emphasize collaborative itinerary editing, shared trip boards, maps, notes, reservations, and travel document organization. They are good at collaboration and persistence, but usually ask users to manually assemble the trip.
- **Google Maps / Amap / Meituan local-life surfaces** are strong at search, POI detail, route, reviews, and ordering/reservation entry points. They are weaker at multi-person preference synthesis and conversation-based consensus.
- **AI travel planners** typically generate itinerary drafts quickly, but often hallucinate places, ignore seat/ticket constraints, and stop at “recommendation text” rather than execution.
- **Local AI assistants such as Meituan Xiaomei-style local service assistants** move toward task completion in local commerce, but product value depends on whether the assistant can reduce app switching, explain feasibility, and act after the plan is chosen.

The differentiation for 立刻游 must therefore be:

- **Conversation first**: one sentence in, three executable options out.
- **Collaboration native**: share link, vote, comment, AI updates the plan.
- **All-member memory**: store and respect spouse diet, children needs, friend dislikes, walking limits, queue tolerance.
- **Active guard**: after confirmation, watch weather, merchant availability, and route risk.
- **Execution-oriented**: plan cards should lead to booking/reservation/share, not just browsing.

Keep this research as the product bar when making tradeoffs. Do not overbuild a map, star graph, dashboard, profile page, or content feed unless it directly improves the assistant loop.

## Product Surface

The primary surface is a chat interface:

- Top bar: logo, “立刻游”, short subtitle, current date/time, user avatar.
- Empty state: one friendly greeting, one concise explanation, four quick prompts: family weekend, four-friend gathering, couple date, citywalk.
- Conversation: user messages on the right; assistant messages on the left.
- AI loading: immediate typing indicator, capped expectation around 10 seconds for the first useful response.
- Plan reply: three compact plan cards stacked vertically. Each card includes name, tag, timeline, budget, total duration, distance, “查看详情”, and “选这个”.
- Composer: input, voice, image, share-to-companions, plan button. After a plan exists, share and confirm actions should become prominent.

Secondary surfaces:

- **Collaboration page**: shared plan title, three options, vote button, comments, AI adjustment note. No login requirement for the mock flow.
- **Execution page**: progress steps for tickets, restaurant seats, delivery, and share message.
- **Guard page or guard state**: weather, route, merchant status, and replacements if rain/full/closed.
- **Memory page or memory chips**: all-member constraints and preferences.

Avoid unrelated cards, mock “insights”, star-map controls, history filters, thumbnails, or decorative navigation.

## Backend Journey Contract

Every request must move through a stable state machine:

1. **Create/validate session**.
2. **Parse intent** using LLM if fast and available, with deterministic fallback.
3. **Merge previous context and clarification answers** before judging missing fields.
4. **Gate required fields**:
   - actionable location or coordinates,
   - exact start time,
   - duration or end time,
   - group composition,
   - budget,
   - core demand.
5. If incomplete, return `NEEDS_CLARIFICATION` with targeted fields and do **not** call real POI, route, weather, or web search.
6. If complete, call real providers for POI and route in normal runtime.
7. Generate 1-3 feasible options.
8. Return `READY` with options, warnings, trace, and provider evidence.
9. On selected option, confirm execution and produce booking/reservation/delivery/share artifacts.
10. For collaboration feedback, preserve original facts and apply only the delta.

The screenshot failure pattern to guard against: user gives a complete location/time/group/budget/core demand, system asks only duration, user enters “3小时左右”, then the system loses context or returns “I reorganized 3 plans” without showing cards. This is unacceptable. Clarification answers are not standalone requests; they must merge into the previous plan session intent.

## Clarification Rules

Clarification must be precise and minimal:

- If only duration is missing, ask only duration and explicitly acknowledge recognized start time.
- If budget/group/preferences are already in raw text, do not ask them again.
- Do not apply defaults before computing missing fields if it masks a product-critical field. If defaults are allowed, label them as inferred defaults in intent.
- Store user clarification answers in `userFacts.answers`.
- Derive tool-ready fields from answers, but never erase prior parsed location/time/group/budget.
- Broad phrases like “附近”, “本地”, “我所在城市”, “下午”, “晚上”, “周末” remain incomplete unless backed by coordinates or exact time.
- Valid China coordinates are actionable location even without city.

For chat UX, a clarification response should include:

- one short assistant sentence,
- compact fields,
- examples in placeholders,
- one submit action.

After a clarification submit, the next assistant response must be either:

- plan cards,
- another specific clarification,
- or a clear blocked reason.

Never return an empty assistant message.

## Planning Quality Bar

A plan card is useful only if it is executable. Each option should include:

- timeline with start time and ordered stops,
- at least one activity and one dining stop,
- POI names preserved exactly from provider data,
- route distance/time,
- budget estimate,
- tags that explain fit, such as “适配减肥”, “亲子友好”, “路线顺路”,
- risks and fallback notes if relevant.

For group/family scenarios, explicitly consider:

- children age, stroller, restrooms, indoor/outdoor risk, noise, safety,
- elderly or low-walking needs,
- restaurant seat risk, spicy/diet/allergy constraints, queue tolerance,
- weather exposure,
- route complexity,
- parking/subway/ride-hailing pickup,
- budget per person versus total budget.

Do not reject a plan merely because it has English brand names, digits, punctuation, or non-Chinese POI names. Do reject fabricated POIs in normal runtime.

## Collaboration, Memory, Guard

Collaboration flow:

- Create share link from a ready plan.
- Let participants vote for options.
- Let participants submit comments such as “把吃饭时间调后半小时” or “我想吃日料”.
- Convert comments into a feedback patch and replan or adjust.
- Show current vote counts and AI adjustment note.

Memory flow:

- Treat memory as user-facing constraints, not hidden magic.
- Represent memory as chips/facts: “老婆减肥”, “孩子要亲子设施”, “朋友不吃辣”.
- Apply memory during ranking and explanation.
- Let future iterations add persistence; mock is acceptable for demo only.

Guard flow:

- After confirmation, show status for weather, route, merchant availability.
- If weather changes, recommend indoor alternatives.
- If restaurant is full, replace with same-cuisine/same-score/similar-budget option.
- If API fails, show friendly retry or fallback, not raw HTTP status.

## LLM Routing and Speed

The project may have two MiMo-compatible LLM API keys with identical API shape. Design LLM usage as a router, not a single hard dependency.

Recommended policy:

- **Primary fast lane**: short timeout, cheaper/faster model, used for intent parsing and feedback patching.
- **Secondary fallback lane**: alternate API key/model, called only if primary times out, returns malformed JSON, or hits rate limits.
- **Deterministic fallback**: local rules must always produce a safe partial intent when both LLM lanes fail.
- **No retry storm**: at most one alternate LLM call per agent step.
- **Small output budgets**: intent parsing should not use large completion token budgets.
- **Trace every call**: include provider lane, model, status, duration, timeout/fallback reason.

Configuration should expose:

- `MIMO_API_KEY`
- `MIMO_SECONDARY_API_KEY`
- optional `MIMO_SECONDARY_MODEL`
- optional `MIMO_SECONDARY_BASE_URL`
- optional `MIMO_ROUTER_MODE` such as `primary-fallback` or `parallel-race`

Prefer primary-fallback initially. Parallel racing can reduce tail latency but doubles cost and quota usage; use only for high-value steps if the product requires it.

## External Provider Rules

Real planning depends on real local-life data:

- Amap POI is critical.
- Amap route is critical.
- Weather is useful but optional.
- Web search/evidence is optional.

Do not call providers while required fields are missing. When providers fail:

- Missing Amap key in normal runtime -> `BLOCKED`.
- Too few POIs -> `BLOCKED` with “扩大范围/放宽需求” suggestions.
- Weather failure -> warning only.
- Web search failure -> warning only.
- Route failure -> blocked or route fallback only if explicitly marked.

Every provider response should produce a trace item with status, duration, provider, mode, source URL or endpoint category, and count/evidence.

## Implementation Workflow

When asked to fix or improve this project:

1. Read the newest user goal and screenshots carefully.
2. Inspect backend flow before changing frontend copy.
3. Reproduce the failing journey with HTTP or tests:
   - complete user request missing duration,
   - submit duration answer,
   - expect `READY` with options or a specific block.
4. Check session token behavior.
5. Check intent after merge; previous parsed fields must remain.
6. Check whether POI/route/weather/search were called at the correct time.
7. Fix the smallest backend contract problem first.
8. Add deterministic tests for each changed behavior.
9. Only then adjust frontend presentation.
10. Run backend tests and frontend build.
11. If local services are available, run a browser or HTTP smoke test.

Use `rg` first for searches. Use `apply_patch` for file edits. Do not revert unrelated dirty worktree changes. Keep changes scoped to product flow, backend correctness, latency, and user-visible behavior.

## Acceptance Scenarios

Use these as release gates:

- “今天晚上 7 点在上海静安寺附近，4 个朋友，预算 800 元，想先找一个有意思的地方再吃饭，路线不要太折腾。” asks only for duration if duration is missing, and acknowledges 19:00.
- Answering “3小时左右” returns plan cards or a specific provider block; it never loses Shanghai/Jing’an/friends/budget/context.
- Vague “我附近想玩” asks for concrete location and exact time, with no provider calls.
- Complete family request with child and diet constraints produces plan tags such as “亲子友好” and “适配减肥/轻食”.
- Share endpoint creates a collaboration object; vote/comment endpoints update it.
- Confirm endpoint produces execution artifacts and share message.
- Guard endpoint returns weather/route/merchant status with fallback note.
- When LLM primary times out, secondary or local fallback prevents a 500.
- Backend tests pass; frontend build passes.

## Documentation and Communication

When reporting work:

- State whether the core journey now runs.
- Mention exact tests and build commands.
- Be honest about external provider limitations.
- Keep Chinese user-facing explanations concise.
- Do not claim a mock feature is real execution.

If a user asks for a product design first, produce a concrete PRD-style plan before coding, including user journey, backend states, data contracts, latency budgets, provider failure modes, metrics, and rollout sequence.

For deeper product research notes, read `references/product-research.md`.
