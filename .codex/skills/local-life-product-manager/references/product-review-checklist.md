# Product Review Checklist

Use this checklist when reviewing `local-life-agent`.

## User Journey

- Create session should work even when Redis is absent.
- Incomplete request should never call real POI planning.
- Clarification answers should merge into intent and avoid repeated asks for already completed fields.
- Location placeholders must be rejected.
- Coordinates must be accepted as a real anchor.
- Complete request should produce plans or a specific blocked reason.
- Feedback should keep previous context and not require the user to restate everything.
- Confirm should work only after a ready plan.

## Quality Gates

- Backend tests pass with project Maven settings.
- Frontend build passes.
- No secret files, build outputs, logs, `target`, `dist`, `external-repos`, or local config are staged.
- UI text is Chinese except real POI names and technical trace/provider names.
- Normal runtime mock POI is disabled unless explicitly configured for tests.

## Common Failure Patterns

- Redis connection exceptions leaking as 500.
- Vague `附近` treated as concrete location.
- Hardcoded 大连 suggestions shown to nationwide users.
- Broad `下午` treated as a start time.
- Feedback on `NEEDS_CLARIFICATION` plan causing missing option errors.
- Weather or search using default city when user provided only coordinates.
