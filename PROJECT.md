# Project: HireFlow — Premium Frontend Redesign
## Light-First Enterprise SaaS UI/UX Transformation

## Architecture
- **Frontend Stack**: React 18, TypeScript, Vite 5, Tailwind CSS 3.4, Lucide React, Vitest. Located in `hireflow-frontend/`.
  - Static production build targets `hireflow-backend/src/main/resources/static/` via `vite.config.ts`.
  - Development proxy maps `/api` -> `http://localhost:8080` and `/actuator` -> `http://localhost:8080`.
  - Design Direction: Refined Light Theme by default (`#F7F8FA` bg, `#FFFFFF` surface, `#0F766E` brand teal), with full Dark Theme toggle (`#0B1220` bg, `#111827` surface, `#2DD4BF` brand teal).
  - State & Theme: `ThemeContext` toggling `.dark` on `document.documentElement` with non-sensitive persistence in `localStorage['hireflow_theme']`.
  - Security & Auth: In-memory token storage (`currentAuthToken`), zero credentials in `localStorage`, strict `postMessage` origin checks for OAuth, safe markdown rendering.
- **Backend Stack**: Spring Boot 3.3.5 modular monolith exposing REST APIs under `/api/v1/*` and Actuator at `/actuator/*`.
  - `SpaForwardController` routes client SPA paths to `/index.html`.
  - Integration invariant: `index.html` must preserve `<noscript>` tag containing `"HireFlow AI"`, `"Test Console"`, `"autoLogin"`.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Design System & Token Configuration | Define semantic light/dark palettes, brand teal scales, surface tokens, text, border, and focus ring in `tailwind.config.js` | M1-UI | Survey 1 / ORIGINAL_REQUEST §3 |
| 2 | Theme Context Engine & Persistence | Implement `ThemeContext.tsx` with light default, dark mode, `localStorage` theme preference persistence, and DOM class synchronization | M1-UI | Survey 1 / ORIGINAL_REQUEST §3.4 |
| 3 | Global Styles & Typography Hierarchy | Configure Inter/system typography scale, light/dark scrollbars, base body styles in `index.css`, clean `index.html` preserving `<noscript>` invariant | M1-UI | Survey 1 / ORIGINAL_REQUEST §3.5 |
| 4 | Dual-Theme Common UI Primitives | Upgrade `Button.tsx`, `Badge.tsx` (pastels in light, high contrast), `Drawer.tsx`, `Modal.tsx`, and `Toast.tsx` with dual-theme support | M1-UI | Survey 1 / ORIGINAL_REQUEST §3 |
| 5 | Network Error Normalization | Intercept `TypeError: Failed to fetch` in `api.ts` to provide actionable diagnostics when backend port 8080 is unreachable | M1-UI | Survey 2 / ORIGINAL_REQUEST §8 |
| 6 | Actuator Health Integration Proxy | Add `/actuator` proxy in `vite.config.ts` and implement `api.getSystemHealth()` querying `/actuator/health` | M1-UI | Survey 2 / ORIGINAL_REQUEST §5 |
| 7 | Responsive Fluid Application Layout | Replace `max-w-7xl` in `AppLayout.tsx` with responsive fluid container (`max-w-[1536px]` with generous padding) eliminating awkward dead space | M2-UI | Survey 1 / ORIGINAL_REQUEST §4 |
| 8 | Refined Sidebar with Mobile Drawer | Upgrade `Sidebar.tsx` with light-mode aesthetics, desktop collapse (`w-64`<->`w-20`), mobile drawer overlay with backdrop, brand logo, and org badge | M2-UI | Survey 1 / ORIGINAL_REQUEST §4 |
| 9 | Application Header & Breadcrumbs | Modernize `Header.tsx` with dynamic route breadcrumbs, mobile hamburger menu toggle, org badge, and docs shortcut | M2-UI | Survey 1 / ORIGINAL_REQUEST §4 |
| 10 | Header Theme Toggle Button | Accessible Sun/Moon toggle button in application header with ARIA labels, updating application theme immediately | M2-UI | Survey 1 / ORIGINAL_REQUEST §3.4 |
| 11 | Real System Status Indicator | Connect Header's status badge to live `api.getSystemHealth()` probe (Green "Operational" vs Amber/Red "Offline / Unreachable") | M2-UI | Survey 2 / ORIGINAL_REQUEST §5 |
| 12 | Dashboard Redesign & KPI Hierarchy | Differentiate primary KPI cards (Candidates, Resumes, Queue) with visual hierarchy, accent borders, readable typography, and trend indicators | M3-UI | Survey 2 / ORIGINAL_REQUEST §5 |
| 13 | Compact Review Queue Panel | Make review cases panel compact and clean when empty, expanding cleanly when pending cases exist | M3-UI | Survey 2 / ORIGINAL_REQUEST §5 |
| 14 | Operational Insights Dashboard Widgets | Replace redundant quick-nav links with operational insights (live mailbox health widget, ingestion velocity, quick actions) | M3-UI | Survey 2 / ORIGINAL_REQUEST §5 |
| 15 | Mailboxes Workspace & OAuth Reauth | Refine Mailboxes screen (Google, Microsoft, IMAP), add inline Reauthorize button for `REAUTH` status, preserve M1 OAuth security invariants | M3-UI | Survey 2 / ORIGINAL_REQUEST §6.F |
| 16 | Processing Activity Audit Timeline | Redesign Activity screen with status filters, detailed modal inspection, and real message retry (`POST /api/v1/operations/messages/{id}/retry`) | M3-UI | Survey 2 / ORIGINAL_REQUEST §6.H |
| 17 | Settings Screen & Appearance Selector | Redesign Settings screen with org identity, Webhook HMAC keys, extension token, and explicit Light/Dark appearance selector | M3-UI | Survey 2 / ORIGINAL_REQUEST §6.I |
| 18 | Candidates Directory & Detail Drawer | Redesign Candidates workspace: search, filters, pagination, profile drawer with version selector, resume PDF preview, and application history | M4-UI | Survey 3 / ORIGINAL_REQUEST §6.C |
| 19 | Strict Genuine Match Score Enforcement | Enforce genuine score rule: display `{matchScore}% fit` when present or explicit `"Unranked"` (zero synthetic scores) | M4-UI | Survey 3 / ORIGINAL_REQUEST §6.C |
| 20 | Job Openings Workspace & Status Alignment | Redesign Jobs screen with requisition grid, create job modal, revisions modal, 60/20/20 matches drawer, and align `OPEN` vs `ACTIVE` status | M4-UI | Survey 3 / ORIGINAL_REQUEST §6.D |
| 21 | Applications Kanban & Audit Timeline | Redesign Applications screen with Kanban board & table views, 6 stages, stage transition modal with notes, and immutable audit history timeline | M4-UI | Survey 3 / ORIGINAL_REQUEST §6.E |
| 22 | Recruitment Rules Priority Builder | Redesign Rules screen with priority reordering (Move Up/Down), enable/disable toggle, rule builder modal, and dry-run preview simulator | M4-UI | Survey 3 / ORIGINAL_REQUEST §6.G |
| 23 | In-App Documentation Viewer | Redesign Docs screen with category navigation, article search, safe markdown rendering, code snippet copy-to-clipboard | M4-UI | Survey 3 / ORIGINAL_REQUEST §6.J |
| 24 | Enterprise SaaS Login Screen | Redesign Login screen with premium branding, visible theme toggle, form validation, and one-click demo login (`hr@kartgen.com`) | M4-UI | Survey 3 / ORIGINAL_REQUEST §6.A |
| 25 | Quality Gates, Cross-Device & Build Delivery | Execute `npm test`, `npm run build` targeting backend static dir, backend integration tests, responsive verification, and auditor review | M5-UI | Survey 1,2,3 / ORIGINAL_REQUEST §12 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1-UI | Design System, Theming Engine, UI Primitives & Error Handling | Features 1, 2, 3, 4, 5, 6 (Tokens, ThemeContext, index.css/html, Button/Badge/Drawer/Modal/Toast, api.ts error handling, Actuator proxy) | none | DONE |
| M2-UI | Application Shell, Navigation & System Telemetry | Features 7, 8, 9, 10, 11 (AppLayout fluid container, Sidebar with mobile drawer, Header with breadcrumbs, Theme Toggle, live health check) | M1-UI | DONE |
| M3-UI | Operations Workspace & Dashboards | Features 12, 13, 14, 15, 16, 17 (Dashboard KPI hierarchy & widgets, Mailboxes with Reauth, Activity timeline & retry, Settings with appearance) | M1-UI, M2-UI | IN_PROGRESS |
| M4-UI | Recruitment Management Workflows | Features 18, 19, 20, 21, 22, 23, 24 (Candidates & genuine scores, Jobs & status fix, Applications Kanban, Rules builder, Docs, Login) | M1-UI, M2-UI | PLANNED |
| M5-UI | Quality Verification, Accessibility & Build Packaging | Feature 25 (Vitest tests, production build, backend integration tests, responsive audit, Reviewer, Challenger, and Forensic Auditor gates) | M1-UI, M2-UI, M3-UI, M4-UI | PLANNED |

## Interface Contracts
### 1. Theme Engine Contract (`ThemeContext.tsx`)
- Exports: `useTheme(): { theme: 'light' | 'dark', toggleTheme: () => void, setTheme: (t: 'light' | 'dark') => void }`
- Persistence: `localStorage.getItem('hireflow_theme')` (safe UI preference, zero auth credentials)
- DOM effect: Adds or removes `.dark` class on `document.documentElement` (`<html class="h-full">`)

### 2. Error Normalization Contract (`api.ts`)
- Catches `TypeError: Failed to fetch` or connection refusal and normalizes to:
  `HireFlow backend is unreachable. Please verify the service is running on port 8080.`
- Preserves all typed HTTP method signatures (`login`, `getCandidates`, `getJobs`, `getApplications`, `getRules`, etc.)

### 3. Backend Health Contract (`api.ts` & `vite.config.ts`)
- Proxy: `/actuator` -> `http://localhost:8080`
- Method: `api.getSystemHealth(): Promise<{ status: 'UP' | 'DOWN' | 'UNKNOWN' }>` calling `/actuator/health` (public, no auth required)
- Method: `api.getHealth(): Promise<HealthDto>` calling `/api/v1/operations/health` (authenticated org telemetry)

### 4. Job Requisition Status Contract (`JobsPage.tsx` & `JobService.java`)
- Active status value: `"OPEN"` (displayed as "Active" or "Open")
- Closed status value: `"CLOSED"`

### 5. Genuine Match Score Contract (`CandidatesPage.tsx`)
- When `matchScore !== null && matchScore !== undefined`: renders `<Badge variant="...">{matchScore}% fit</Badge>`
- When `matchScore == null`: renders `<span className="... text-xs">Unranked</span>`
- Strict prohibition: No hardcoded or dummy default scores.

## Code Layout
- `hireflow-frontend/tailwind.config.js`: Semantic light & dark design tokens
- `hireflow-frontend/index.html`: Base HTML, fonts, `<noscript>` verification invariant
- `hireflow-frontend/src/index.css`: Global styles, scrollbar tokens, base theme
- `hireflow-frontend/src/context/ThemeContext.tsx`: Theme state management & persistence
- `hireflow-frontend/src/components/common/`: Dual-theme UI primitives (`Button`, `Badge`, `Drawer`, `Modal`, `Toast`)
- `hireflow-frontend/src/components/layout/`: Shell layout (`AppLayout.tsx`, `Sidebar.tsx`, `Header.tsx`)
- `hireflow-frontend/src/services/api.ts`: API client with error normalization and Actuator health check
- `hireflow-frontend/src/pages/`: 10 SaaS screens (`DashboardPage`, `CandidatesPage`, `JobsPage`, `ApplicationsPage`, `MailboxesPage`, `RulesPage`, `ActivityPage`, `SettingsPage`, `DocsPage`, `LoginPage`)
- `hireflow-backend/src/main/resources/static/`: Build output directory for production SPA assets
