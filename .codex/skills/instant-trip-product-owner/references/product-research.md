# Product Research Notes

## PM Method Baseline

Strong product design work should start from the user job, not from a UI artifact. A PRD or product design proposal should answer:

1. Who is the user and what situation creates the need?
2. What job are they trying to complete?
3. What prevents them from completing it today?
4. What is the product promise?
5. What is the simplest complete journey?
6. What data and service contracts make the promise true?
7. What failures are expected, and how does the product recover?
8. What metrics show the product is working?

For 立刻游, the job is not “browse places”. The job is “make a weekend local outing happen with other people without chat chaos, research effort, app switching, or last-minute panic.”

## Competitor Pattern Summary

### Collaborative travel itinerary products

Products like Wanderlog and similar itinerary planners emphasize shared trip plans, collaborative editing, route maps, notes, reservations, and structured itineraries. Their strength is persistence and group visibility. Their weakness for local short outings is that users still do much of the planning and decision work themselves.

Implication for 立刻游: make collaboration native, but keep the main workflow assistant-led rather than board-led.

### Maps and local-life platforms

Google Maps, Amap, Meituan, Dianping, and similar products are strong for POI search, ratings, routing, business hours, ordering, and reservation entry points. Their weakness is cross-person synthesis and end-to-end task orchestration. Users still jump among search, chat, restaurant booking, route planning, and payment.

Implication for 立刻游: use map/local-life data as tools, not as the primary UI. The value is in deciding, bundling, confirming, and guarding.

### AI itinerary generators

AI itinerary tools can quickly draft plans, but many do not verify POIs, availability, route cost, opening hours, or group constraints. They often stop at text generation.

Implication for 立刻游: do not let LLM prose become the product. Tool-backed execution is the product.

### Local AI assistants

Local service AI assistants trend toward booking, ordering, and local commerce task completion. Their success depends on trust: accurate data, transparent limitations, and visible progress.

Implication for 立刻游: show source, status, warnings, and execution progress. Hide raw technical errors.

## Metrics

Suggested metrics:

- Time to first useful response.
- Clarification turns per successful plan.
- Percentage of complete requests that produce ready plans.
- Provider block rate by reason.
- Share link creation rate.
- Vote/comment participation rate.
- Confirmation rate.
- Execution failure replacement rate.
- User retry rate after network/provider failure.

## Latency Budget

Target:

- Clarification decision: under 3 seconds.
- Ready plan: under 15 seconds in common cities; under 30 seconds worst acceptable.
- Individual provider call: around 3 seconds preferred, timeout controlled.
- LLM primary lane: short timeout.
- LLM fallback lane: one attempt only.

Avoid serial work where possible, but preserve the required-field gate before calling providers.

## Product Risks

- Asking too many questions after the user already provided enough context.
- Losing context after a clarification answer.
- Generating a “success” message without visible plan cards.
- Fabricating POIs or hiding provider failure.
- Adding visual complexity that does not improve task completion.
- Treating multi-user collaboration as a share screenshot instead of a stateful decision loop.
- Overusing LLM where rules are faster and safer.
