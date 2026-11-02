# HireFlow — Real-Credential Human Acceptance Testing Guide

This guide provides step-by-step instructions for the project owner and QA engineers to configure and execute end-to-end human acceptance testing using **real Google Gmail** and **real Microsoft 365** credentials against the HireFlow platform.

---

## 1. Exact Configuration Inventory

The table below lists every environment variable and configuration property genuinely supported by the HireFlow backend (`application.yml`, `application-local.yml`, and `application-prod.yml`):

| Variable | Provider / Subsystem | Where to Obtain It | Where to Configure It | Required For | Local / Prod |
|---|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Core Framework | Developer decision | System env / PowerShell | Application boot (`local`, `prod`, `test`) | Both (`local` for dev, `prod` for production) |
| `SERVER_PORT` | HTTP Server | System admin (default `8080`) | System env / `application.yml` | Port binding | Both (Default: `8080`) |
| `GOOGLE_OAUTH_CLIENT_ID` | Google Cloud | Google Cloud Console &rarr; Credentials | PowerShell / `.env` / Prod Secret | Google Gmail OAuth flow | Optional in dev, Required for Gmail |
| `GOOGLE_OAUTH_CLIENT_SECRET` | Google Cloud | Google Cloud Console &rarr; Credentials | PowerShell / Prod Secret Manager | Server-side code exchange | Optional in dev, Required for Gmail |
| `GOOGLE_OAUTH_REDIRECT_URI` | Google Cloud | Derived from deployment URL | PowerShell / `application.yml` | Redirect matching | Both (`http://localhost:8080/api/v1/mail-connections/oauth/google/callback`) |
| `MICROSOFT_OAUTH_CLIENT_ID` | Microsoft Entra ID | Entra ID &rarr; App Registrations | PowerShell / `.env` / Prod Secret | Microsoft 365 OAuth flow | Optional in dev, Required for M365 |
| `MICROSOFT_OAUTH_CLIENT_SECRET` | Microsoft Entra ID | Entra ID &rarr; Certificates & secrets | PowerShell / Prod Secret Manager | Server-side code exchange & PKCE | Optional in dev, Required for M365 |
| `MICROSOFT_OAUTH_REDIRECT_URI` | Microsoft Entra ID | Derived from deployment URL | PowerShell / `application.yml` | Redirect matching | Both (`http://localhost:8080/api/v1/mail-connections/oauth/microsoft/callback`) |
| `MICROSOFT_OAUTH_TENANT_ID` | Microsoft Entra ID | Entra ID &rarr; Overview (`common`) | PowerShell / `application.yml` | Multi-tenant auth token endpoint | Both (Default: `common`) |
| `JWT_SECRET` | Auth & RBAC | Generated 256-bit secret string | PowerShell / Prod Secret Manager | Signing recruiter JWT tokens | Both (&ge; 32 characters in prod) |
| `CRYPTO_SECRET_KEY` | Credential Encryption | 64-hex char AES-256 key | PowerShell / Prod Secret Manager | AES-256-GCM token encryption at rest | Both (64 hex characters) |
| `STORAGE_PROVIDER` | Document Storage | Developer decision (`local` or `s3`) | `application.yml` | Resume binary persistence | Both (Default `local` in dev, `s3` in prod) |
| `LOCAL_STORAGE_PATH` | Local Storage | Filesystem path | `application.yml` | Local file storage root | Local (Default `./.local-storage/s3-bucket`) |
| `LOCAL_URL_SECRET` | Local Storage | Random secret string | `application.yml` | HMAC signing of presigned URLs | Local (Default `hireflow-local-hmac-key`) |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | Database | PostgreSQL server | Prod environment | PostgreSQL connection | Production only (Local uses in-memory H2) |
| `DB_USERNAME`, `DB_PASSWORD` | Database | PostgreSQL server | Prod Secret Manager | PostgreSQL authentication | Production only |

---

## 2. Step-by-Step Google Cloud Console Setup

Follow these exact steps to register your Google OAuth credentials:

1. **Create or Select Project**: Navigate to the [Google Cloud Console](https://console.cloud.google.com/) and create a new project (e.g., `hireflow-dev`).
2. **Enable Gmail API**: Go to **APIs & Services &rarr; Library**, search for `Gmail API`, and click **Enable**.
3. **Configure OAuth Consent Screen**:
   - Go to **APIs & Services &rarr; OAuth consent screen**.
   - Select User Type: **External** (or **Internal** if using a Google Workspace organization).
   - Enter App name (`HireFlow`), User support email, and Developer contact information.
4. **Configure Test Users**:
   - Under **Test users**, click **Add Users** and add your test Gmail/Workspace email address.
   - *Note: While the app is in "Testing" publishing status, only designated test users can authenticate.*
5. **Configure Scopes**:
   - Add scopes:
     - `https://www.googleapis.com/auth/gmail.readonly` (Read resources, messages, and attachments)
     - `https://www.googleapis.com/auth/userinfo.email` (Discover connected mailbox address)
6. **Create OAuth 2.0 Web Client ID**:
   - Go to **APIs & Services &rarr; Credentials &rarr; Create Credentials &rarr; OAuth client ID**.
   - Application type: **Web application**.
   - Name: `HireFlow Local Web Client`.
   - **Authorized JavaScript origins**: `http://localhost:8080` (and `http://localhost:5173` if running Vite dev server).
   - **Authorized redirect URIs**: `http://localhost:8080/api/v1/mail-connections/oauth/google/callback`
7. **Obtain Credentials**: Copy the **Client ID** and **Client Secret**.

> [!IMPORTANT]
> **Google Restricted Scope & CASA Assessment Notice**:  
> The scope `https://www.googleapis.com/auth/gmail.readonly` is classified by Google as a **Restricted Scope**. For public production access, Google requires a Cloud Application Security Assessment (CASA Tier 2) by an authorized third-party lab. In local development and internal testing, designated "Test Users" can connect without public verification. Official reference: [Google OAuth API Scopes](https://developers.google.com/identity/protocols/oauth2/scopes#gmail).

---

## 3. Step-by-Step Microsoft Entra ID Setup

Follow these steps to register your Microsoft 365 OAuth application:

1. **Open Entra Admin Center**: Navigate to the [Microsoft Entra admin center](https://entra.microsoft.com/) or Azure Portal &rarr; Microsoft Entra ID.
2. **Register an Application**:
   - Go to **Identity &rarr; Applications &rarr; App registrations &rarr; New registration**.
   - Name: `HireFlow M365 Ingestion`.
   - Supported account types: **Accounts in any organizational directory (Any Microsoft Entra ID tenant - Multitenant) and personal Microsoft accounts** (or single-tenant if testing within one organization).
   - Redirect URI: Select platform **Web** and enter `http://localhost:8080/api/v1/mail-connections/oauth/microsoft/callback`.
3. **Create Client Secret**:
   - Go to **Certificates & secrets &rarr; Client secrets &rarr; New client secret**.
   - Add a description (e.g., `hireflow-local-dev`) and set an expiration (e.g., 6 months).
   - **Immediately copy the Value** (not the Secret ID).
4. **Configure API Permissions**:
   - Go to **API permissions &rarr; Add a permission &rarr; Microsoft Graph &rarr; Delegated permissions**.
   - Add the following minimum required permissions:
     - `Mail.Read` (Read user mail and attachments)
     - `User.Read` (Sign in and read user profile / mailbox address)
     - `offline_access` (Maintain access to data / issue refresh tokens)
   - Click **Grant admin consent** if testing within your corporate tenant.
5. **Note Client IDs**: From the **Overview** page, record the **Application (client) ID**.

---

## 4. Safe Secret Placement Guide (Windows PowerShell)

Set environment variables in your active terminal session without committing them or exposing them in scripts:

```powershell
# 1. Navigate to the repository root
cd c:\Users\ragha\Desktop\hireflow

# 2. Configure mandatory runtime profile
$env:SPRING_PROFILES_ACTIVE = "local"

# 3. Configure Google OAuth Credentials (replace with your real credentials)
$env:GOOGLE_OAUTH_CLIENT_ID = "YOUR_GOOGLE_CLIENT_ID.apps.googleusercontent.com"
$env:GOOGLE_OAUTH_CLIENT_SECRET = "YOUR_GOOGLE_CLIENT_SECRET"
$env:GOOGLE_OAUTH_REDIRECT_URI = "http://localhost:8080/api/v1/mail-connections/oauth/google/callback"

# 4. Configure Microsoft OAuth Credentials (replace with your real credentials)
$env:MICROSOFT_OAUTH_CLIENT_ID = "YOUR_MICROSOFT_CLIENT_ID_UUID"
$env:MICROSOFT_OAUTH_CLIENT_SECRET = "YOUR_MICROSOFT_CLIENT_SECRET"
$env:MICROSOFT_OAUTH_REDIRECT_URI = "http://localhost:8080/api/v1/mail-connections/oauth/microsoft/callback"
$env:MICROSOFT_OAUTH_TENANT_ID = "common"

# 5. Confirm environment variables are populated WITHOUT printing raw secrets:
Write-Host "Google Client ID set: " (![string]::IsNullOrEmpty($env:GOOGLE_OAUTH_CLIENT_ID))
Write-Host "Microsoft Client ID set: " (![string]::IsNullOrEmpty($env:MICROSOFT_OAUTH_CLIENT_ID))

# 6. Start the Spring Boot Backend (compiles and serves the React SaaS frontend)
cd hireflow-backend
.\mvnw.cmd spring-boot:run
```

To stop the application and clean up temporary session variables:
```powershell
# Press Ctrl+C to stop Spring Boot, then clear variables:
Remove-Item Env:\GOOGLE_OAUTH_CLIENT_ID
Remove-Item Env:\GOOGLE_OAUTH_CLIENT_SECRET
Remove-Item Env:\MICROSOFT_OAUTH_CLIENT_ID
Remove-Item Env:\MICROSOFT_OAUTH_CLIENT_SECRET
```

---

## 5. Human Acceptance Testing Scenarios Checklist

Use this checklist during manual verification with your real mailboxes:

### Scenario 1: Authentication & SaaS Navigation
- [ ] Open `http://localhost:8080/` in Chrome/Edge.
- [ ] Verify clean enterprise login screen appears.
- [ ] Log in with seeded admin credentials (`hr@kartgen.com` / `password123`).
- [ ] Verify dashboard loads with real metrics (total candidates, active jobs, pipeline summary).
- [ ] Click through sidebar navigation (Candidates, Jobs, Applications, Mailboxes, Rules, Activity, Settings, Docs).
- [ ] Open the **Documentation** page: verify markdown docs render with syntax highlighting and navigation.

### Scenario 2: Google Workspace / Gmail Real Connection
- [ ] Navigate to **Mailboxes** (`http://localhost:8080/mailboxes`).
- [ ] Click **Connect Google Workspace**.
- [ ] Verify centered OAuth popup opens to Google's consent screen.
- [ ] Select your Google account and grant permissions.
- [ ] Verify popup closes automatically, and the Mailboxes page updates without a page refresh.
- [ ] Confirm your real email address appears in the connected mailboxes list with a green `ACTIVE` badge.
- [ ] Inspect browser DevTools (Network & Storage): verify **zero refresh tokens or client secrets** appear in client memory or responses.

### Scenario 3: Microsoft 365 Real Connection
- [ ] Click **Connect Microsoft 365**.
- [ ] Verify popup navigates to `login.microsoftonline.com`.
- [ ] Log in and consent to permissions.
- [ ] Confirm popup completes and returns to HireFlow.
- [ ] Confirm your real Microsoft email address appears with a green `ACTIVE` badge.

### Scenario 4: Recruitment Rule Engine Configuration
- [ ] Navigate to **Rules** (`http://localhost:8080/rules`).
- [ ] Click **Create Rule**.
- [ ] Set Rule Name: `Senior Java Applications`.
- [ ] Subject Keywords: `resume, application, java, engineer`.
- [ ] Target Job: Select *Java Backend Developer*.
- [ ] Attachment Filter: Require attachment, allowed extensions: `pdf, docx`.
- [ ] Click **Test Simulation**: Enter a sample subject and body; verify the rule engine shows matched conditions and selected job explanation.
- [ ] Save Rule and toggle Enabled/Disabled and Move Up/Down priority.

### Scenario 5: Live Email Synchronization & Candidate Ingestion
- [ ] From your personal email, send an email to your connected test mailbox with:
  - Subject: `Application for Java Backend Developer - John Doe`
  - Body: `Please find attached my resume for the Java position.`
  - Attachment: Attach a valid PDF resume (`john_doe_resume.pdf`).
- [ ] On HireFlow **Mailboxes** page, click **Sync Now** on your connected mailbox.
- [ ] Navigate to **Activity** (`http://localhost:8080/activity`):
  - Verify the message appears with lifecycle: `DISCOVERED` &rarr; `RULE_EVALUATED` &rarr; `ACCEPTED` &rarr; `PARSED` &rarr; `COMPLETED`.
- [ ] Navigate to **Candidates** (`http://localhost:8080/candidates`):
  - Verify John Doe was automatically extracted and listed.
  - Click on the candidate row: slide-over drawer shows extracted skills, contact info, and resume version.
- [ ] Navigate to **Applications** (`http://localhost:8080/applications`):
  - Verify John Doe is linked to *Java Backend Developer* in the `NEW` stage.

### Scenario 6: Reauthorization & Revoked Consent
- [ ] In Google Account &rarr; Security &rarr; Third-party apps with account access, remove access for `HireFlow`.
- [ ] Trigger **Sync Now** in HireFlow.
- [ ] Verify connection state updates to `REAUTH_REQUIRED` with an amber warning banner.
- [ ] Click **Reauthorize Now** to restore the connection.

---

## 6. Clear Distinction: Automated vs. Real-Credential Testing

| Testing Category | Verification Mechanism | What It Proves | What It Does NOT Prove |
|---|---|---|---|
| **Unit & Slice Tests** | JUnit 5, Mockito, MockRestServiceServer | Rule matching logic, state machine transitions, PKCE challenge generation, token encryption | Real Google/Microsoft network socket handling |
| **Opaque-Box E2E Tests** | WireMock / Embedded HTTP mock servers | Full controller lifecycle, expired state rejection, 10-thread replay race prevention | Cloud provider rate limits and consent screen UX |
| **Frontend Security Tests** | Node.js AST analysis, Vitest, JSDOM | PostMessage origin filtering, XSS escaping, zero client storage of tokens | Third-party cookie policies in Safari/Brave |
| **Human Acceptance Testing** | Live Google Cloud Console, Microsoft Entra, real webmail | **Real end-to-end OAuth consent, actual IMAP/REST API sync, real resume ingestion** | High-volume production load or distributed network partitions |

