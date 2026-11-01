// HireFlow AI — Smart Gmail Content Script (V2.0.1)

if (typeof chrome !== "undefined" && chrome.runtime && chrome.runtime.onMessage) {
  chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === "SCAN_GMAIL_SMART") {
      (async () => {
        try {
          const items = await scanGmailWithFilters(request.filters);
          sendResponse({ success: true, items });
        } catch (err) {
          sendResponse({ success: false, error: err.message });
        }
      })();
      return true;
    }

    if (request.action === "EXTRACT_AND_FETCH_ITEM") {
      (async () => {
        try {
          const files = await extractAndFetchBytesInPage(request.item);
          sendResponse({ success: true, files });
        } catch (err) {
          sendResponse({ success: false, error: err.message });
        }
      })();
      return true;
    }
  });
}

/**
 * 1. Scans both Opened Email view AND Inbox List rows using Standard + User-Defined Filters
 */
async function scanGmailWithFilters(filters) {
  const results = [];
  const standardWords = ["resume", "cv", "curriculum vitae", "application", "candidate", "job", "hiring", "developer", "engineer", "profile", "bio"];
  const userWords = parseCsv(filters.customKeywords);
  const excludeWords = parseCsv(filters.excludeKeywords);
  const minScore = Number(filters.minScore) || 40;

  // CASE A: Check if an email thread is currently OPEN on screen
  const openSubjectEl = document.querySelector("h2.hP");
  const openMessages = document.querySelectorAll("div.adn, div[data-message-id]");

  if (openSubjectEl && openMessages.length > 0) {
    const subject = openSubjectEl.innerText.trim();
    const senderEl = document.querySelector("span.gD");
    const senderEmail = senderEl ? (senderEl.getAttribute("email") || senderEl.innerText) : "unknown@sender.com";
    const senderName = senderEl ? (senderEl.getAttribute("name") || senderEl.innerText) : "";

    // Force Gmail to hydrate attachment [download_url] attributes by dispatching hover events
    const hoverTargets = document.querySelectorAll("div.aQH div.aZI, div.aSH, span.aV3, [download_url]");
    hoverTargets.forEach(el => {
      el.dispatchEvent(new MouseEvent("mouseover", { bubbles: true }));
      el.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true }));
    });
    await sleep(150);

    const attachments = extractOpenEmailAttachments();
    attachments.forEach((att, idx) => {
      const evalResult = scoreCandidateItem({
        subject,
        senderEmail,
        fileName: att.fileName,
        snippet: "",
        hasPdfOrDocx: true
      }, filters, standardWords, userWords, excludeWords);

      if (evalResult.score >= minScore && !evalResult.excluded) {
        results.push({
          id: `open_att_${idx}_${Date.now()}`,
          mode: "OPEN_ATTACHMENT",
          rowKey: null,
          senderEmail,
          senderName,
          subject,
          fileName: att.fileName,
          mimeType: att.mimeType,
          downloadUrl: att.downloadUrl,
          score: evalResult.score,
          matchedReasons: evalResult.reasons
        });
      }
    });

    if (results.length > 0) {
      return results;
    }
  }

  // CASE B: Scan Gmail Inbox / Search List Rows (tr.zA)
  const inboxRows = document.querySelectorAll("tr.zA");
  inboxRows.forEach((row, index) => {
    const senderEl = row.querySelector("span[email], span.yP, span.zF");
    const senderEmail = senderEl ? (senderEl.getAttribute("email") || senderEl.innerText.trim()) : "unknown@sender.com";
    const senderName = senderEl ? (senderEl.getAttribute("name") || senderEl.innerText.trim()) : "";
    const subjectEl = row.querySelector(".bog, .y6");
    const subject = subjectEl ? subjectEl.innerText.trim() : "No Subject";
    const snippetEl = row.querySelector(".y2");
    const snippet = snippetEl ? snippetEl.innerText.trim() : "";

    // Find attachment chips on the row (.brc, .brg, or attachment icon)
    const chipEls = row.querySelectorAll(".brc, .brg, [ title*='.pdf' ], [ title*='.docx' ], [ title*='.doc' ]");
    const chipNames = Array.from(chipEls).map(c => c.getAttribute("title") || c.innerText.trim()).filter(Boolean);
    const combinedChips = chipNames.join(", ");

    // Check if row has PDF/DOCX chip or attachment indicator
    const rowTextLower = (subject + " " + snippet + " " + combinedChips).toLowerCase();
    const hasDocExtension = /\.pdf|\.docx|\.doc\b/.test(rowTextLower);
    const hasAttachmentIcon = chipNames.length > 0 || row.querySelector("td.yf img, div.yE") !== null;

    // Stamp row with a temporary attribute so our Auto-Walker can click it reliably
    const rowKey = `hf_row_${index}`;
    row.setAttribute("data-hf-row", rowKey);

    const evalResult = scoreCandidateItem({
      subject,
      senderEmail,
      fileName: combinedChips || subject,
      snippet,
      hasPdfOrDocx: hasDocExtension || hasAttachmentIcon
    }, filters, standardWords, userWords, excludeWords);

    if (evalResult.score >= minScore && !evalResult.excluded) {
      results.push({
        id: `inbox_row_${index}`,
        mode: "INBOX_ROW_AUTO",
        rowKey,
        senderEmail,
        senderName,
        subject,
        fileName: combinedChips || "Auto-extract PDF/DOCX on upload",
        mimeType: "application/pdf",
        downloadUrl: null, // Will be auto-extracted when user clicks Upload!
        score: evalResult.score,
        matchedReasons: evalResult.reasons
      });
    }
  });

  return results;
}

/**
 * 2. Smart Scoring Engine (Standard Filter + User Custom Keywords + Exclusions)
 */
function scoreCandidateItem(data, filters, standardWords, userWords, excludeWords) {
  const haystack = `${data.subject} ${data.fileName} ${data.snippet} ${data.senderEmail}`.toLowerCase();
  const reasons = [];
  let score = 0;

  // Check Exclusions first (word-boundary match)
  for (const ex of excludeWords) {
    if (ex) {
      const escaped = ex.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const re = new RegExp(`\\b${escaped}\\b`, 'i');
      if (re.test(haystack)) {
        return { score: 0, excluded: true, reasons: [`Excluded: ${ex}`] };
      }
    }
  }

  // File extension / attachment signal
  if (/\.pdf\b|\.docx\b|\.doc\b/i.test(data.fileName)) {
    score += 45;
    reasons.push("PDF/DOCX File");
  } else if (data.hasPdfOrDocx) {
    score += 25;
    reasons.push("Attachment Icon");
  }

  // Standard Resume Filter keywords
  if (filters.useStandardFilter) {
    const matchedStd = standardWords.filter(w => haystack.includes(w));
    if (matchedStd.length > 0) {
      score += Math.min(35, matchedStd.length * 15);
      reasons.push(`Std: ${matchedStd.slice(0, 2).join(", ")}`);
    }
  }

  // User-Defined Custom Keywords
  const matchedUser = userWords.filter(w => haystack.includes(w));
  if (matchedUser.length > 0) {
    score += Math.min(45, matchedUser.length * 20);
    reasons.push(`User: ${matchedUser.slice(0, 3).join(", ")}`);
  } else if (filters.strictCustomMatch && userWords.length > 0) {
    return { score: 0, excluded: true, reasons: ["Missing required user keyword"] };
  }

  return {
    score: Math.min(100, score),
    excluded: false,
    reasons: reasons.length > 0 ? reasons : ["General Match"]
  };
}

/**
 * 3. Extracts all PDF/DOCX attachment download URLs from an opened Gmail thread
 */
function extractOpenEmailAttachments() {
  const attachments = [];
  const seenUrls = new Set();

  // Method 1: Standard [download_url] attribute
  const downloadCards = document.querySelectorAll("[download_url]");
  downloadCards.forEach(card => {
    const rawAttr = card.getAttribute("download_url");
    if (!rawAttr) return;
    const firstColon = rawAttr.indexOf(":");
    const secondColon = rawAttr.indexOf(":", firstColon + 1);
    if (firstColon !== -1 && secondColon !== -1) {
      const mimeType = rawAttr.substring(0, firstColon);
      const fileName = decodeURIComponent(rawAttr.substring(firstColon + 1, secondColon));
      let downloadUrl = rawAttr.substring(secondColon + 1);

      if (downloadUrl && !downloadUrl.startsWith("http")) {
        downloadUrl = new URL(downloadUrl, window.location.origin).href;
      }

      if (isSupportedResumeFile(fileName) && !seenUrls.has(downloadUrl)) {
        seenUrls.add(downloadUrl);
        attachments.push({ fileName, mimeType, downloadUrl });
      }
    }
  });

  // Method 2: Fallback <a> tags with view=att or disp=safe
  const anchorLinks = document.querySelectorAll("a[href*='view=att'], a[href*='disp=safe']");
  anchorLinks.forEach((a, idx) => {
    let href = a.href;
    if (!href || seenUrls.has(href)) return;
    if (!href.startsWith("http")) {
      href = new URL(href, window.location.origin).href;
    }

    const cardContainer = a.closest("div.aQH, div.aSH, span") || a;
    const nameEl = cardContainer.querySelector("span.aV3, .aQA") || a;
    const fileName = (nameEl.innerText || a.getAttribute("download") || `resume_${idx + 1}.pdf`).trim();

    if (isSupportedResumeFile(fileName)) {
      seenUrls.add(href);
      attachments.push({
        fileName,
        mimeType: fileName.toLowerCase().endsWith(".pdf") ? "application/pdf" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        downloadUrl: href
      });
    }
  });

  return attachments;
}

/**
 * 4. Auto-opens an Inbox Row (if needed), extracts attachment URLs, and fetches bytes in-page
 */
async function extractAndFetchBytesInPage(item) {
  let targetAttachments = [];
  let navigatedIntoRow = false;

  if (item.mode === "OPEN_ATTACHMENT" && item.downloadUrl) {
    targetAttachments = [{
      fileName: item.fileName,
      mimeType: item.mimeType,
      downloadUrl: item.downloadUrl,
      senderEmail: item.senderEmail,
      subject: item.subject
    }];
  } else if (item.mode === "INBOX_ROW_AUTO") {
    // Find the inbox row and click it to open the thread
    let row = document.querySelector(`tr.zA[data-hf-row="${item.rowKey}"]`);
    if (!row) {
      const allRows = Array.from(document.querySelectorAll("tr.zA"));
      row = allRows.find(r => r.innerText.includes(item.subject));
    }
    if (!row) {
      throw new Error(`Could not locate inbox row for "${item.subject}"`);
    }

    // Programmatically click the row inside Gmail
    const clickTarget = row.querySelector(".bog, td.xY, span") || row;
    clickTarget.dispatchEvent(new MouseEvent("mousedown", { bubbles: true }));
    clickTarget.dispatchEvent(new MouseEvent("mouseup", { bubbles: true }));
    clickTarget.click();
    navigatedIntoRow = true;

    // Wait up to 3.5 seconds for Gmail to render the opened thread DOM
    await waitForElement("h2.hP, [download_url], div.aQH", 3500);
    await sleep(350);

    // Trigger hover on attachment cards so [download_url] populates
    document.querySelectorAll("div.aQH div.aZI, div.aSH, span.aV3").forEach(el => {
      el.dispatchEvent(new MouseEvent("mouseover", { bubbles: true }));
      el.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true }));
    });
    await sleep(200);

    // Read real sender email and subject from opened thread
    const senderEl = document.querySelector("span.gD");
    const realSender = senderEl ? (senderEl.getAttribute("email") || item.senderEmail) : item.senderEmail;
    const subjectEl = document.querySelector("h2.hP");
    const realSubject = subjectEl ? subjectEl.innerText.trim() : item.subject;

    const extracted = extractOpenEmailAttachments();
    targetAttachments = extracted.map(att => ({
      ...att,
      senderEmail: realSender,
      subject: realSubject
    }));
  }

  // Fetch bytes for each attachment found
  const downloadedFiles = [];
  for (const att of targetAttachments) {
    try {
      // First try in-page fetch
      const response = await fetch(att.downloadUrl, { credentials: "include" });
      if (response.ok) {
        const buffer = await response.arrayBuffer();
        downloadedFiles.push({
          fileName: att.fileName,
          mimeType: att.mimeType,
          senderEmail: att.senderEmail,
          subject: att.subject,
          downloadUrl: att.downloadUrl,
          base64Data: arrayBufferToBase64(buffer)
        });
      } else {
        // Fallback: pass downloadUrl to background.js service worker
        downloadedFiles.push({
          fileName: att.fileName,
          mimeType: att.mimeType,
          senderEmail: att.senderEmail,
          subject: att.subject,
          downloadUrl: att.downloadUrl,
          base64Data: null
        });
      }
    } catch (e) {
      // CORS redirect: background.js will fetch using host_permissions
      downloadedFiles.push({
        fileName: att.fileName,
        mimeType: att.mimeType,
        senderEmail: att.senderEmail,
        subject: att.subject,
        downloadUrl: att.downloadUrl,
        base64Data: null
      });
    }
  }

  // If we clicked into an inbox row, navigate back to the Inbox list cleanly
  if (navigatedIntoRow) {
    window.history.back();
    await waitForElement("tr.zA", 3000);
    await sleep(250);
  }

  return downloadedFiles;
}

// Helpers
function isSupportedResumeFile(name) {
  const lower = (name || "").toLowerCase();
  return lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc");
}

function parseCsv(str) {
  if (!str) return [];
  return str.split(",").map(s => s.trim().toLowerCase()).filter(Boolean);
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function waitForElement(selector, timeoutMs = 3000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (document.querySelector(selector)) return true;
    await sleep(150);
  }
  return false;
}

function arrayBufferToBase64(buffer) {
  let binary = "";
  const bytes = new Uint8Array(buffer);
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    isSupportedResumeFile,
    parseCsv,
    scoreCandidateItem,
    arrayBufferToBase64
  };
}

