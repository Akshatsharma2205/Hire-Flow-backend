# HireFlow AI — Smart Resume Extractor (V2.0.1)

A Manifest V3 Chrome Extension that turns Gmail into a smart, hands-free resume ingestion pipeline for the Kartgen HireFlow AI platform.

---

## ⚡ Key Capabilities

1. **⚡ Dual Mode (Live Spring Boot Backend & Offline Test Mode)**:
   - **Live Backend Mode (Default)**: Streams verified PDF/DOCX resumes directly to Spring Boot at `POST http://localhost:8080/api/v1/uploads/extension-intake` with Bearer token authentication and SHA-256 pre-check (`POST /api/v1/uploads/check-hashes`).
   - **Offline Test Mode**: When working without a running server, enable the toggle to extract bytes, inspect `%PDF-` / `PK` binary signatures, run the privacy gatekeeper to block invoice traps, and test deduplication locally in Chrome.

2. **🟢 Dynamic Backend Health Check**:
   - The extension header actively pings `http://localhost:8080/actuator/health` and displays **Server Online** or **Server Offline** in real time.

3. **Pre-Seeded Dev Credentials**:
   - **Backend Base URL**: `http://localhost:8080`
   - **Pre-Seeded Token**: `hf_ext_live_org_kartgen_01_demo12345`
   - **Organization ID**: `11111111-1111-1111-1111-111111111111` (Kartgen Technologies)
   - One-click button in Tab 3: **"Set Pre-Seeded Dev Token"** automatically configures and saves these credentials.

4. **Hands-Free Inbox Auto-Walker**:
   - Automatically navigates into matching Gmail inbox threads, triggers hover to hydrate attachment links, extracts bytes, and steps back to the inbox.

---

## 🚀 How to Install and Test in Google Chrome

1. Open **Google Chrome** and navigate to `chrome://extensions`.
2. Enable **Developer mode** (toggle in the top-right corner).
3. Click **Load unpacked** and select this directory:
   ```
   c:\Users\ragha\Desktop\hireflow\hireflow-extension
   ```
4. Pin the **HireFlow AI** puzzle icon to your Chrome toolbar.
5. Open the extension popup:
   - Verify the badges show **Server Online** and **Token Configured**.
   - If needed, open **Tab 3 (Security & API)** and click **"Set Pre-Seeded Dev Token"**.
6. Switch to your **[Gmail tab](https://mail.google.com/)** and refresh (F5).
7. Open any email containing a PDF/DOCX resume (or scan your inbox list):
   - Click **"Scan Gmail"**
   - Click **"Extract, Verify & Process Selected Resumes"**
8. Open the HireFlow Web Dashboard at `http://localhost:8080/index.html`:
   - Switch to **"🧩 Chrome Extension"** tab to see incoming requests, run pre-checks, and watch candidate profiles and 60/20/20 match scores update live!

---

## 📁 File Structure

```
hireflow-extension/
├── manifest.json   # MV3 configuration with *.google.com host permissions
├── popup.html      # 3-tab UI with Server Online badge & Offline Mode toggle
├── popup.js        # Controller with live health ping, token manager, and logger
├── content.js      # Gmail DOM scanner, Auto-Walker, and attachment extractor
├── background.js   # Service worker: byte inspection, SHA-256 hashing & multipart upload
└── README.md       # Setup & documentation
```
