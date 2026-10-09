# Web application guide

## Architecture

This is a Svelte 5/Vite SPA, not SvelteKit.

- `App.svelte` owns login state and hash-based routing.
- `pages/` contains route-level screens.
- `components/` contains shared editors such as `SpecEditor`.
- `lib/api.ts` is the same-origin `/api` client.
- `lib/live.svelte.ts` owns the single shared SSE connection and reactive live state.
- `lib/types.ts` manually mirrors Kotlin domain and API JSON.

Use Svelte 5 runes already established in the codebase (`$state`, `$derived`, `$effect`, `$props`, and `$bindable`). Rune-using TypeScript modules must retain the `.svelte.ts` suffix.

## Integration invariants

- Authentication is an HttpOnly session cookie. Keep requests same-origin through `/api`; do not expose or store the ArtifactsMMO token or admin key in browser state.
- Keep one `EventSource` in `lib/live.svelte.ts`. Pages react to shared state or tick counters and refetch through `lib/api.ts`; do not open per-page streams.
- Vite proxies `/api` to the backend during development; Caddy provides the same routing in production.
- When Kotlin DTOs, `QueueModels`, or `TaskSpec` change, update `lib/types.ts` and every affected builder/formatter. Sealed Kotlin values arrive as `kind`-discriminated unions.
- Keep hash routing unless deployment routing is deliberately changed; it allows Caddy to serve `index.html` without SPA fallback rules.

## Validation

From `web/`, run:

```bash
npm run check
npm run build
```

No frontend unit-test, ESLint, or Prettier configuration currently exists; do not claim those checks ran.
