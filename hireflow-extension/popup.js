// HireFlow AI — Real Production Popup Controller (V2.0.2)

let scannedItems = [];

const DEFAULT_FILTERS = {
  useStandardFilter: true,
  customKeywords: "java, spring boot, react, full stack, developer, engineer",
  strictCustomMatch: false,
  excludeKeywords: "invoice, receipt, bill, salary slip, tax, bank statement, otp",
  minScore: 40
};

const DEFAULT_AUTH = {
  apiUrl: "http://localhost:8080",
  tenantId: "11111111-1111-1111-1111-111111111111",
  apiToken: "hf_ext_live_org_kartgen_01_demo12345"
};

document.addEventListener("DOMContentLoaded", async () => {
  setupTabs();
  await loadSavedSettings();
  bindActions();
});

function setupTabs() {
  const tabBtns = document.querySelectorAll(".tab-btn");
  const panels = document.querySelectorAll(".panel");

  tabBtns.forEach(btn => {
    btn.addEventListener("click", () => {
      tabBtns.forEach(b => b.classList.remove("active"));
      panels.forEach(p => p.classList.remove("active"));
      btn.classList.add("active");
      const targetId = btn.getAttribute("data-tab");
      const targetPanel = document.getElementById(targetId);
      if (targetPanel) {
        targetPanel.classList.add("active");
      }
    });
  });
}

async function loadSavedSettings() {
  const data = await chrome.storage.local.get(["filters", "auth"]);
  const filters = { ...DEFAULT_FILTERS, ...(data.filters || {}) };
  const auth = { ...DEFAULT_AUTH, ...(data.auth || {}) };

  // Populate Filter Tab
  document.getElementById("useStandardFilter").checked = filters.useStandardFilter;
  document.getElementById("customKeywords").value = filters.customKeywords;
  document.getElementById("strictCustomMatch").checked = filters.strictCustomMatch;
  document.getElementById("excludeKeywords").value = filters.excludeKeywords;
  document.getElementById("minScoreInput").value = filters.minScore;

  // Populate Auth Tab
  document.getElementById("apiUrlInput").value = auth.apiUrl;
  document.getElementById("tenantIdInput").value = auth.tenantId;
  document.getElementById("apiTokenInput").value = auth.apiToken;

  await updateBadges(auth.apiToken, auth.apiUrl);
}

async function updateBadges(token, apiUrl = "http://localhost:8080") {
  const authBadge = document.getElementById("authBadge");
  const serverBadge = document.getElementById("serverBadge");
  const cleanApiUrl = (apiUrl || "http://localhost:8080").trim().replace(/\/+$/, "");

  if (token && token.trim().length >= 10) {
    authBadge.textContent = "Token Configured";
    authBadge.className = "badge badge-ok";
  } else {
    authBadge.textContent = "No Token";
    authBadge.className = "badge badge-warn";
  }

  if (serverBadge) {
    try {
      const res = await fetch(`${cleanApiUrl}/actuator/health`, { method: "GET" });
      if (res.ok) {
        serverBadge.textContent = "Server Online";
        serverBadge.className = "badge badge-ok";
      } else {
        serverBadge.textContent = "Server " + res.status;
        serverBadge.className = "badge badge-warn";
      }
    } catch (_) {
      serverBadge.textContent = "Server Offline";
      serverBadge.className = "badge badge-warn";
    }
  }
}

function bindActions() {
  // Save Filters
  document.getElementById("saveFiltersBtn").addEventListener("click", async () => {
    const filters = {
      useStandardFilter: document.getElementById("useStandardFilter").checked,
      customKeywords: document.getElementById("customKeywords").value.trim(),
      strictCustomMatch: document.getElementById("strictCustomMatch").checked,
      excludeKeywords: document.getElementById("excludeKeywords").value.trim(),
      minScore: Number(document.getElementById("minScoreInput").value) || 40
    };
    await chrome.storage.local.set({ filters });
    const msg = document.getElementById("filterSavedMsg");
    msg.textContent = "✓ Smart filter rules saved!";
    setTimeout(() => (msg.textContent = ""), 2500);
  });

  // Clear Dedup History
  document.getElementById("clearDedupCacheBtn").addEventListener("click", async () => {
    await chrome.storage.local.remove("uploadedHashes");
    const msg = document.getElementById("filterSavedMsg");
    msg.textContent = "✓ Local SHA-256 deduplication cache cleared!";
    setTimeout(() => (msg.textContent = ""), 2500);
  });

  // Save Auth Credentials
  document.getElementById("saveAuthBtn").addEventListener("click", async () => {
    const auth = {
      apiUrl: (document.getElementById("apiUrlInput").value.trim().replace(/\/+$/, "")) || "http://localhost:8080",
      tenantId: document.getElementById("tenantIdInput").value.trim() || "11111111-1111-1111-1111-111111111111",
      apiToken: document.getElementById("apiTokenInput").value.trim()
    };
    await chrome.storage.local.set({ auth });
    await updateBadges(auth.apiToken, auth.apiUrl);
    const msg = document.getElementById("authSavedMsg");
    msg.textContent = "✓ Security credentials saved!";
    setTimeout(() => (msg.textContent = ""), 2500);
  });

  // Set Pre-Seeded Dev Token
  const useDefaultTokenBtn = document.getElementById("useDefaultTokenBtn");
  if (useDefaultTokenBtn) {
    useDefaultTokenBtn.addEventListener("click", async () => {
      const devToken = "hf_ext_live_org_kartgen_01_demo12345";
      const orgId = "11111111-1111-1111-1111-111111111111";
      document.getElementById("apiTokenInput").value = devToken;
      document.getElementById("tenantIdInput").value = orgId;
      const auth = {
        apiUrl: (document.getElementById("apiUrlInput").value.trim().replace(/\/+$/, "")) || "http://localhost:8080",
        tenantId: orgId,
        apiToken: devToken
      };
      await chrome.storage.local.set({ auth });
      await updateBadges(devToken, auth.apiUrl);
      const msg = document.getElementById("authSavedMsg");
      msg.textContent = "✓ Pre-seeded token activated!";
      setTimeout(() => (msg.textContent = ""), 3000);
    });
  }

  // Select All / Clear
  document.getElementById("selectAllBtn").addEventListener("click", () => {
    document.querySelectorAll("#resultsList input[type='checkbox']").forEach(cb => (cb.checked = true));
  });
  document.getElementById("selectNoneBtn").addEventListener("click", () => {
    document.querySelectorAll("#resultsList input[type='checkbox']").forEach(cb => (cb.checked = false));
  });

  // Scan Button
  document.getElementById("scanBtn").addEventListener("click", runSmartScan);

  // Upload Button
  document.getElementById("uploadBtn").addEventListener("click", runBatchExtractionAndUpload);
}

async function runSmartScan() {
  const statusBox = document.getElementById("statusBox");
  const resultsList = document.getElementById("resultsList");
  const uploadBtn = document.getElementById("uploadBtn");
  const toolbar = document.getElementById("selectionToolbar");

  resultsList.innerHTML = "";
  statusBox.textContent = "Applying Smart Filters & scanning Gmail DOM...";

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab || !tab.url || !tab.url.includes("mail.google.com")) {
    statusBox.textContent = "Please switch to an open https://mail.google.com tab first.";
    return;
  }

  const stored = await chrome.storage.local.get(["filters"]);
  const filters = { ...DEFAULT_FILTERS, ...(stored.filters || {}) };

  chrome.tabs.sendMessage(tab.id, { action: "SCAN_GMAIL_SMART", filters }, (response) => {
    if (chrome.runtime.lastError || !response) {
      statusBox.textContent = "Please refresh your Gmail tab once (F5) so the content script loads.";
      return;
    }
    if (!response.success) {
      statusBox.textContent = `Scan error: ${response.error}`;
      return;
    }

    scannedItems = response.items || [];
    if (scannedItems.length === 0) {
      toolbar.style.display = "none";
      uploadBtn.style.display = "none";
      statusBox.textContent = "No matching resume emails found on this view. Open an email thread with a PDF/DOCX or adjust filters in Tab 2.";
      return;
    }

    toolbar.style.display = "flex";
    document.getElementById("matchSummary").textContent = `${scannedItems.length} qualified match(es)`;
    uploadBtn.style.display = "block";
    statusBox.textContent = `Found ${scannedItems.length} candidate email(s). Ready for extraction.`;

    scannedItems.forEach((item, idx) => {
      const card = document.createElement("div");
      card.className = "card";

      const reasonsHtml = (item.matchedReasons || [])
        .map(r => `<span class="tag">${escapeHtml(r)}</span>`)
        .join("");

      card.innerHTML = `
        <input type="checkbox" data-index="${idx}" checked>
        <div class="card-body">
          <div class="card-title">${escapeHtml(item.subject)}</div>
          <div class="card-meta">From: ${escapeHtml(item.senderEmail)} · File: ${escapeHtml(item.fileName)}</div>
          <div class="tags">
            <span class="tag tag-score">${item.score}% Match</span>
            <span class="tag">${item.mode === "OPEN_ATTACHMENT" ? "Direct Attachment" : "Inbox Auto-Extract"}</span>
            ${reasonsHtml}
          </div>
        </div>
      `;
      resultsList.appendChild(card);
    });
  });
}

async function runBatchExtractionAndUpload() {
  const checkboxes = Array.from(document.querySelectorAll("#resultsList input[type='checkbox']:checked"));
  const statusBox = document.getElementById("statusBox");
  const progressWrap = document.getElementById("progressWrap");
  const progressBar = document.getElementById("progressBar");
  const uploadBtn = document.getElementById("uploadBtn");

  if (checkboxes.length === 0) {
    statusBox.textContent = "Please select at least one candidate row to process.";
    return;
  }

  const stored = await chrome.storage.local.get(["auth"]);
  const auth = { ...DEFAULT_AUTH, ...(stored.auth || {}) };

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  uploadBtn.disabled = true;
  progressWrap.style.display = "block";
  progressBar.style.width = "0%";
  
  statusBox.textContent = `Starting resume extraction for ${checkboxes.length} selected item(s) to Spring Boot at ${auth.apiUrl}...\n`;

  let processedCount = 0;
  let skippedCount = 0;
  let rejectedCount = 0;

  for (let i = 0; i < checkboxes.length; i++) {
    const idx = Number(checkboxes[i].getAttribute("data-index"));
    const item = scannedItems[idx];

    progressBar.style.width = `${Math.round(((i + 0.5) / checkboxes.length) * 100)}%`;
    statusBox.textContent += `\n[${i + 1}/${checkboxes.length}] Scanning email: "${item.subject.substring(0, 32)}..."\n`;

    const extractRes = await sendTabMessagePromise(tab.id, {
      action: "EXTRACT_AND_FETCH_ITEM",
      item
    });

    if (!extractRes || !extractRes.success || !extractRes.files || extractRes.files.length === 0) {
      statusBox.textContent += `   ⚠️ No PDF/DOCX attachment found inside this email thread.\n`;
      continue;
    }

    for (const fileObj of extractRes.files) {
      statusBox.textContent += `   ⬇️ Fetching bytes for: ${fileObj.fileName}...\n`;

      const uploadRes = await sendRuntimeMessagePromise({
        action: "VERIFY_AND_UPLOAD_FILE",
        fileObj,
        config: auth
      });

      if (!uploadRes || !uploadRes.success) {
        statusBox.textContent += `   ❌ Error: ${uploadRes ? uploadRes.error : "Unknown error"}\n`;
      } else {
        const d = uploadRes.data;
        if (d.status === "UPLOADED") {
          processedCount++;
          statusBox.textContent += `   ✅ [UPLOADED TO BACKEND]: ${d.fileName} (${Math.round(d.sizeBytes / 1024)} KB | SHA-256: ${d.sha256.substring(0, 10)}...)\n`;
        } else if (d.status === "SKIPPED_DUPLICATE") {
          skippedCount++;
          statusBox.textContent += `   ⏭️ Skipped Duplicate: ${d.fileName} (SHA-256 already recorded)\n`;
        } else if (d.status === "REJECTED_NOT_RESUME") {
          rejectedCount++;
          statusBox.textContent += `   🛡️ Blocked: ${d.fileName} (${d.reason})\n`;
        }
      }

      await new Promise(r => setTimeout(r, 400));
    }

    progressBar.style.width = `${Math.round(((i + 1) / checkboxes.length) * 100)}%`;
    statusBox.scrollTop = statusBox.scrollHeight;
  }

  statusBox.textContent += `\nCompleted! Ingested: ${processedCount} | Duplicates: ${skippedCount} | Skipped Non-Resumes: ${rejectedCount}`;
  uploadBtn.disabled = false;
}

function sendTabMessagePromise(tabId, payload) {
  return new Promise(resolve => {
    chrome.tabs.sendMessage(tabId, payload, res => {
      if (chrome.runtime.lastError) {
        resolve({ success: false, error: chrome.runtime.lastError.message });
      } else {
        resolve(res);
      }
    });
  });
}

function sendRuntimeMessagePromise(payload) {
  return new Promise(resolve => {
    chrome.runtime.sendMessage(payload, res => {
      if (chrome.runtime.lastError) {
        resolve({ success: false, error: chrome.runtime.lastError.message });
      } else {
        resolve(res);
      }
    });
  });
}

function escapeHtml(str) {
  return String(str || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}
