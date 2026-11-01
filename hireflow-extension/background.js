// HireFlow AI — Background Service Worker (V2.0.1)

if (typeof chrome !== "undefined" && chrome.runtime && chrome.runtime.onMessage) {
  chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === "VERIFY_AND_UPLOAD_FILE") {
      (async () => {
        try {
          const res = await processVerifyAndUpload(request.fileObj, request.config);
          sendResponse({ success: true, data: res });
        } catch (err) {
          sendResponse({ success: false, error: err.message });
        }
      })();
      return true;
    }
  });
}

async function processVerifyAndUpload(fileObj, config) {
  if (!config.apiToken || config.apiToken.trim() === "") {
    throw new Error("Missing Extension Token! Go to Tab 3 (Security & API) and set your Bearer token.");
  }

  // 1. Obtain raw ArrayBuffer (either from content.js base64 or via background fetch)
  let arrayBuffer;
  try {
    if (fileObj.base64Data) {
      arrayBuffer = base64ToArrayBuffer(fileObj.base64Data);
    } else if (fileObj.downloadUrl) {
      const resp = await fetch(fileObj.downloadUrl, {
        method: "GET",
        credentials: "include"
      });
      if (!resp.ok) {
        throw new Error(`Gmail returned HTTP status ${resp.status}`);
      }
      arrayBuffer = await resp.arrayBuffer();
    } else {
      throw new Error("No download URL or attachment data found in Gmail DOM.");
    }
  } catch (downloadErr) {
    throw new Error(`Gmail Attachment Fetch Failed: ${downloadErr.message}`);
  }

  if (arrayBuffer.byteLength < 200) {
    throw new Error(`File too small (${arrayBuffer.byteLength} bytes) — likely not a valid resume.`);
  }

  // 2. Magic Byte & Smart Resume Content Gatekeeper
  const gatekeeper = inspectBytesForResume(arrayBuffer, fileObj.fileName);
  if (!gatekeeper.valid) {
    return {
      status: "REJECTED_NOT_RESUME",
      fileName: fileObj.fileName,
      reason: gatekeeper.reason
    };
  }

  // 3. Compute SHA-256 Hash in memory
  const hashBuffer = await crypto.subtle.digest("SHA-256", arrayBuffer);
  const sha256Hex = Array.from(new Uint8Array(hashBuffer))
    .map(b => b.toString(16).padStart(2, "0"))
    .join("");

  // 4. Check Local SHA-256 Deduplication Cache
  const store = await chrome.storage.local.get(["uploadedHashes"]);
  const uploadedHashes = store.uploadedHashes || {};
  if (uploadedHashes[sha256Hex]) {
    return {
      status: "SKIPPED_DUPLICATE",
      fileName: fileObj.fileName,
      sha256: sha256Hex
    };
  }

  // 4b. Pre-check Server-Side Hashes (skip upload if already in database)
  const baseUrl = (config.apiUrl || "http://localhost:8080").replace(/\/+$/, "");
  try {
    const preCheckResp = await fetch(`${baseUrl}/api/v1/uploads/check-hashes`, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${config.apiToken.trim()}`,
        "Content-Type": "application/json",
        "X-Tenant-ID": (config.tenantId || "default-tenant").trim()
      },
      body: JSON.stringify({ sha256List: [sha256Hex] })
    });
    if (preCheckResp.ok) {
      const preCheckData = await preCheckResp.json();
      if (preCheckData.existingHashes && preCheckData.existingHashes.includes(sha256Hex)) {
        uploadedHashes[sha256Hex] = {
          fileName: fileObj.fileName,
          uploadedAt: new Date().toISOString()
        };
        await chrome.storage.local.set({ uploadedHashes });
        return {
          status: "SKIPPED_DUPLICATE",
          fileName: fileObj.fileName,
          sha256: sha256Hex
        };
      }
    }
  } catch (_) {
    // If pre-check fails (e.g. offline/network), proceed to main upload flow
  }

  // 5. Build Multipart Form Data & Send with Bearer Token to Spring Boot
  const formData = new FormData();
  const blob = new Blob([arrayBuffer], { type: gatekeeper.detectedMime });
  formData.append("file", blob, fileObj.fileName);
  formData.append("senderEmail", fileObj.senderEmail || "unknown@sender.com");
  formData.append("subject", fileObj.subject || "No Subject");
  formData.append("sha256", sha256Hex);
  formData.append("sourceType", "EXT_GMAIL");

  let uploadResponse;
  try {
    uploadResponse = await fetch(`${baseUrl}/api/v1/uploads/extension-intake`, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${config.apiToken.trim()}`,
        "X-Tenant-ID": (config.tenantId || "default-tenant").trim()
      },
      body: formData
    });
  } catch (networkErr) {
    throw new Error(
      `Spring Boot Offline: Connection to "${baseUrl}" was refused. Please ensure the backend server is running on ${baseUrl}.`
    );
  }

  if (uploadResponse.status === 401 || uploadResponse.status === 403) {
    throw new Error(`Backend rejected security token (HTTP ${uploadResponse.status}). Check your token in Tab 3.`);
  }
  if (!uploadResponse.ok) {
    throw new Error(`Spring Boot server error: HTTP ${uploadResponse.status}`);
  }

  // 6. Save SHA-256 to local storage so we don't re-upload duplicates
  uploadedHashes[sha256Hex] = {
    fileName: fileObj.fileName,
    uploadedAt: new Date().toISOString()
  };
  await chrome.storage.local.set({ uploadedHashes });

  return {
    status: "UPLOADED",
    fileName: fileObj.fileName,
    sizeBytes: arrayBuffer.byteLength,
    sha256: sha256Hex
  };
}

/**
 * Verifies Magic Bytes (%PDF- or PK zip header for DOCX) and checks for non-resume traps
 */
function inspectBytesForResume(arrayBuffer, fileName) {
  const bytes = new Uint8Array(arrayBuffer);
  const headerStr = String.fromCharCode(...bytes.slice(0, 8));

  const isPdf = headerStr.startsWith("%PDF-");
  const isZipDocx = bytes[0] === 0x50 && bytes[1] === 0x4B; // 'PK' header for DOCX
  const isDoc = bytes[0] === 0xD0 && bytes[1] === 0xCF && bytes[2] === 0x11 && bytes[3] === 0xE0; // OLE2 header for .doc

  if (!isPdf && !isZipDocx && !isDoc) {
    return {
      valid: false,
      reason: "File signature is neither a valid PDF (%PDF-), DOCX (PK), nor DOC document."
    };
  }

  // Decode printable ASCII strings from the first 120KB to check for invoice/receipt traps
  const sampleLength = Math.min(bytes.length, 120000);
  let rawText = "";
  for (let i = 0; i < sampleLength; i++) {
    const c = bytes[i];
    if (c >= 32 && c <= 126) rawText += String.fromCharCode(c);
  }
  const lowerText = (rawText + " " + fileName).toLowerCase();

  // Reject clear tax invoices / utility bills if no resume words are present
  const trapWords = ["tax invoice", "gstin", "bill to", "payment receipt", "boarding pass", "e-ticket"];
  const resumeWords = ["experience", "education", "skills", "project", "linkedin", "github", "university", "resume", "cv", "developer", "engineer"];

  const hasTrap = trapWords.some(w => lowerText.includes(w));
  const hasResumeSignal = resumeWords.some(w => lowerText.includes(w));

  if (hasTrap && !hasResumeSignal) {
    return {
      valid: false,
      reason: "Blocked by Privacy Gatekeeper: Document appears to be an invoice/receipt, not a resume."
    };
  }

  return {
    valid: true,
    detectedMime: isPdf ? "application/pdf" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
  };
}

function base64ToArrayBuffer(base64) {
  const binary = atob(base64);
  const len = binary.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    inspectBytesForResume,
    base64ToArrayBuffer,
    processVerifyAndUpload
  };
}

