# HireFlow AI — Chrome Extension Architecture & Intake Guide

The HireFlow Chrome Extension enables recruiters and hiring managers to capture, score, and intake candidate resumes directly from webmail clients (Gmail, Outlook 365) and recruiting platforms into the HireFlow ATS.

---

## 1. Extension Architecture (Manifest V3)

The extension is located in `hireflow-extension/` and strictly complies with Chrome Manifest V3 security requirements:

```
hireflow-extension/
├── manifest.json       # Manifest V3 configuration & permission declarations
├── background.js       # Background service worker (API communication, SHA-256 pre-check, upload)
├── content.js          # In-page DOM inspector (webmail resume detection, scoring, privacy gating)
├── popup.html          # Extension action popup user interface
├── popup.js            # Controller managing authentication tokens and quick upload triggers
└── test/
    └── extension.test.js # Automated unit tests for magic bytes, privacy filters, and scoring
```

---

## 2. Least-Privilege Permissions Model

In compliance with Chrome Web Store review guidelines and enterprise security policies, the extension eliminates unnecessary broad permissions:

- **Permissions**:
  - `"storage"`: Persists the recruiter's extension token (`hf_ext_...`), server URL, and auto-intake preferences securely in Chrome local storage.
  - `"activeTab"`: Grants contextual access to the tab only when the user invokes the extension.
  - `"declarativeContent"`: Enables extension badge indicators on matching webmail surfaces without passive background tracking.
- **Host Permissions**:
  - `*://mail.google.com/*`
  - `*://outlook.live.com/*`
  - `*://outlook.office.com/*`
  - `*://outlook.office365.com/*`
  - `http://localhost:8080/*` (local development)
- **Optional Host Permissions**:
  - `https://*/*` (requested on-demand if the recruiter explicitly desires to intake candidates on external ATS platforms).

---

## 3. Privacy Gatekeeper & Invoice Trap Protection

To prevent accidental ingestion of sensitive financial documents, receipts, contracts, and invoices frequently received in email inboxes:

1. **Magic Byte Signature Inspection**:
   - Inspects the raw initial bytes of every candidate attachment before uploading:
     - PDF: `%PDF-` (`0x25 0x50 0x44 0x46 0x2D`)
     - Word DOCX: `PK\x03\x04` (`0x50 0x4B 0x03 0x04`)
   - Non-matching binary streams are immediately rejected.
2. **Invoice Trap Filtering**:
   - Content strings and filenames are scanned against a negative keyword dictionary:
     `invoice`, `receipt`, `bill`, `payment`, `tax`, `statement`, `remittance`, `order confirmation`
   - If trap keywords are present and positive resume indicators (`experience`, `education`, `skills`, `curriculum vitae`, `resume`) are absent, ingestion is aborted with a user-facing security alert.

---

## 4. SHA-256 Pre-Check Intake Flow

To conserve bandwidth and prevent redundant storage calls:

```
[Content Script] ──(Discovers attachment)──> [Background Worker]
                                                    |
                                            1. Compute SHA-256
                                                    |
                                                    v
                                    POST /api/v1/uploads/check-hashes
                                    { "sha256List": [sha256] }
                                                    |
                          +-------------------------+-------------------------+
                          |                                                   |
                     (Hash Exists)                                      (New File)
                          |                                                   |
                          v                                                   v
           Reuse existing candidate profile                       POST /api/v1/uploads/extension-intake
           and attach to target job.                              (Multipart file + metadata)
```

---

## 5. Automated Extension Testing

The extension logic includes an automated unit test suite executed via Node.js (`hireflow-extension/test/extension.test.js`):

```powershell
node --test hireflow-extension/test/extension.test.js
```

### Coverage:
1. Valid PDF magic byte verification (`%PDF-1.4`)
2. Valid DOCX PK zip header verification
3. Non-resume binary rejection
4. Privacy gatekeeper invoice trap detection
5. Base64 encoding/decoding byte-preservation roundtrip
6. File extension validation (`.pdf`, `.docx`, `.txt`)
7. Comma-separated keyword parsing
8. Smart scoring algorithm weighting and exclusion criteria

---

## 6. How to Load the Extension in Chrome

1. Open Google Chrome and navigate to `chrome://extensions/`.
2. Enable **Developer mode** using the toggle switch in the upper right corner.
3. Click **Load unpacked**.
4. Select the `c:\Users\ragha\Desktop\hireflow\hireflow-extension` directory.
5. Click on the HireFlow extension icon in the Chrome toolbar.
6. Enter your backend URL (`http://localhost:8080`) and your Extension Token.
7. Click **Save Settings**. The extension is now operational and connected to your local HireFlow instance.
