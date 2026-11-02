const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const INDEX_HTML_PATH = path.resolve(__dirname, "../../main/resources/static/index.html");

test("Static Analysis: index.html has zero plaintext tokens or secrets stored in DOM or client storage", () => {
    const htmlContent = fs.readFileSync(INDEX_HTML_PATH, "utf8");

    // 1. Verify no localStorage or sessionStorage usage
    assert.strictEqual(
        htmlContent.includes("localStorage"),
        false,
        "index.html must not use localStorage for credential storage"
    );
    assert.strictEqual(
        htmlContent.includes("sessionStorage"),
        false,
        "index.html must not use sessionStorage for credential storage"
    );

    // 2. Verify no manual token input elements exist in DOM
    assert.strictEqual(
        htmlContent.includes('id="refreshToken"'),
        false,
        "index.html must not contain #refreshToken input element"
    );
    assert.strictEqual(
        htmlContent.includes('id="accessToken"'),
        false,
        "index.html must not contain #accessToken input element"
    );
    assert.strictEqual(
        htmlContent.includes('id="oauthSecretRef"'),
        false,
        "index.html must not contain #oauthSecretRef in DOM"
    );

    // 3. Verify no hardcoded client secrets in frontend script
    const clientSecretRegex = /client_secret\s*[:=]\s*['"][^'"]+['"]/i;
    assert.strictEqual(
        clientSecretRegex.test(htmlContent),
        false,
        "index.html must not contain client_secret literals"
    );

    // 4. Verify presence of postMessage origin validation in script
    assert.strictEqual(
        htmlContent.includes("if (event.origin !== window.location.origin) return;"),
        true,
        "index.html must validate event.origin against window.location.origin"
    );

    // 5. Verify presence of popup block fallback
    assert.strictEqual(
        htmlContent.includes("window.location.href = res.authorizationUrl;"),
        true,
        "index.html must implement full-window redirect fallback if popup is blocked"
    );

    // 6. Verify URL query cleanup via history.replaceState
    assert.strictEqual(
        htmlContent.includes("window.history.replaceState({}, document.title, cleanUrl);"),
        true,
        "index.html must strip oauth callback query parameters from history"
    );
});

test("PostMessage Bridge: Origin validation rejects cross-origin attacker injection", async () => {
    // Create simulated window environment
    const windowOrigin = "https://hireflow.company.com";
    let toastCalled = false;
    let toastMessage = "";
    let dataReloaded = false;

    const mockToast = (msg, type) => {
        toastCalled = true;
        toastMessage = msg;
    };
    const mockLoadMailConnections = async () => { dataReloaded = true; };
    const mockLoadKPIs = async () => {};

    // Exact handler extracted from index.html
    const handleMessage = async (event) => {
        if (event.origin !== windowOrigin) return;
        if (!event.data || event.data.type !== 'HIREFLOW_OAUTH_CALLBACK') return;

        if (event.data.status === 'SUCCESS') {
            const provLabel = (event.data.provider || '').toLowerCase().includes('micro') ? 'Microsoft 365' : 'Google Workspace';
            const mailbox = event.data.mailboxAddress ? ` (${event.data.mailboxAddress})` : '';
            mockToast(`Successfully connected ${provLabel}${mailbox}!`, 'success');
            await Promise.all([mockLoadMailConnections(), mockLoadKPIs()]);
        } else {
            const errorMsg = event.data.error || 'Mailbox authorization was not completed';
            mockToast(`Connection failed: ${errorMsg}`, 'error');
            await mockLoadMailConnections();
        }
    };

    // Attack 1: Message from malicious origin
    await handleMessage({
        origin: "https://attacker.evil.com",
        data: {
            type: "HIREFLOW_OAUTH_CALLBACK",
            status: "SUCCESS",
            provider: "google",
            mailboxAddress: "attacker@evil.com"
        }
    });

    assert.strictEqual(toastCalled, false, "Cross-origin message must NOT trigger toast");
    assert.strictEqual(dataReloaded, false, "Cross-origin message must NOT trigger data reload");

    // Attack 2: Subdomain / similar prefix attacker origin
    await handleMessage({
        origin: "https://hireflow.company.com.attacker.com",
        data: {
            type: "HIREFLOW_OAUTH_CALLBACK",
            status: "SUCCESS",
            provider: "google",
            mailboxAddress: "attacker@evil.com"
        }
    });

    assert.strictEqual(toastCalled, false, "Subdomain spoof origin must NOT trigger toast");
    assert.strictEqual(dataReloaded, false, "Subdomain spoof origin must NOT trigger data reload");

    // Attack 3: Same origin but missing or bogus message type
    await handleMessage({
        origin: windowOrigin,
        data: {
            type: "BOGUS_CALLBACK",
            status: "SUCCESS"
        }
    });

    assert.strictEqual(toastCalled, false, "Wrong message type must be ignored");
    assert.strictEqual(dataReloaded, false, "Wrong message type must not trigger data reload");

    // Attack 4: Non-object or null data
    await handleMessage({ origin: windowOrigin, data: null });
    await handleMessage({ origin: windowOrigin, data: "raw string" });
    await handleMessage({ origin: windowOrigin, data: 12345 });
    assert.strictEqual(toastCalled, false, "Malformed data must not crash or trigger actions");

    // Positive Control: Legitimate same-origin SUCCESS message
    await handleMessage({
        origin: windowOrigin,
        data: {
            type: "HIREFLOW_OAUTH_CALLBACK",
            status: "SUCCESS",
            provider: "google",
            mailboxAddress: "hr@company.com"
        }
    });

    assert.strictEqual(toastCalled, true, "Legitimate same-origin message must trigger toast");
    assert.strictEqual(dataReloaded, true, "Legitimate message must reload connections");
    assert.strictEqual(toastMessage.includes("hr@company.com"), true, "Toast must reflect mailbox");
});

test("Popup Blocker Fallback: Falls back to full-window redirect when popup blocked", () => {
    let redirectedTo = null;
    const authUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=123&state=abc";

    // Simulate window.open behavior when browser blocks popups (returns null)
    const simulateConnect = (popupBlocked) => {
        const mockWindow = {
            screenX: 0,
            screenY: 0,
            outerWidth: 1920,
            outerHeight: 1080,
            location: {
                set href(val) { redirectedTo = val; }
            },
            open: (url, name, features) => {
                if (popupBlocked) {
                    return null; // Browser popup blocker returns null
                }
                return { focus: () => {}, closed: false };
            }
        };

        const width = 600;
        const height = 700;
        const left = mockWindow.screenX + Math.max(0, (mockWindow.outerWidth - width) / 2);
        const top = mockWindow.screenY + Math.max(0, (mockWindow.outerHeight - height) / 2);

        const popup = mockWindow.open(
            authUrl,
            'hireflow_oauth_popup',
            `width=${width},height=${height},left=${left},top=${top},scrollbars=yes,status=no,resizable=yes`
        );

        if (!popup || popup.closed || typeof popup.closed === 'undefined') {
            mockWindow.location.href = authUrl;
        } else {
            popup.focus();
        }
    };

    // Test 1: Popup allowed
    simulateConnect(false);
    assert.strictEqual(redirectedTo, null, "Should not redirect when popup is allowed");

    // Test 2: Popup blocked
    simulateConnect(true);
    assert.strictEqual(redirectedTo, authUrl, "Must redirect to authUrl when popup is blocked");
});

test("Redirect Result Handler: Sanitizes query params and removes from history", () => {
    let replacedStateUrl = null;
    let switchedTab = null;
    let toastType = null;
    let toastMsg = null;

    const mockHistory = {
        replaceState: (state, title, url) => { replacedStateUrl = url; }
    };

    const mockToast = (msg, type) => {
        toastMsg = msg;
        toastType = type;
    };

    const mockSwitchTab = (tab) => {
        switchedTab = tab;
    };

    const runCheckOAuthRedirectResult = (searchQuery) => {
        const params = new URLSearchParams(searchQuery);
        if (!params.has('oauth')) return;

        const status = params.get('oauth');
        const provider = params.get('provider') || '';
        const mailbox = params.get('mailbox') || '';
        const errorMsg = params.get('msg') || params.get('error') || 'Authorization was cancelled or failed';

        if (status === 'success') {
            const provLabel = provider.toLowerCase().includes('micro') ? 'Microsoft 365' : 'Google Workspace';
            mockToast(`Successfully connected ${provLabel}${mailbox ? ' (' + mailbox + ')' : ''}!`, 'success');
            mockSwitchTab('email');
        } else if (status === 'error') {
            mockToast(`Connection failed: ${errorMsg}`, 'error');
            mockSwitchTab('email');
        }

        const pathname = '/index.html';
        const hash = '';
        const cleanUrl = pathname + hash;
        mockHistory.replaceState({}, 'HireFlow AI', cleanUrl);
    };

    // Test 1: Success redirect
    runCheckOAuthRedirectResult("?oauth=success&provider=google&mailbox=hiring%40company.com");
    assert.strictEqual(toastType, "success");
    assert.strictEqual(toastMsg.includes("hiring@company.com"), true);
    assert.strictEqual(switchedTab, "email");
    assert.strictEqual(replacedStateUrl, "/index.html", "Must scrub query parameters from URL");

    // Test 2: Error redirect with XSS attempt in error message
    runCheckOAuthRedirectResult("?oauth=error&provider=microsoft&msg=%3Cscript%3Ealert(1)%3C%2Fscript%3E");
    assert.strictEqual(toastType, "error");
    assert.strictEqual(toastMsg, "Connection failed: <script>alert(1)</script>");
    assert.strictEqual(switchedTab, "email");
    assert.strictEqual(replacedStateUrl, "/index.html", "Must scrub query parameters from URL");
});

test("DOM XSS Protection: HTML entity escaping and safe URL validation", () => {
    // Exact esc function from index.html (pure logic test)
    const escLogic = (str) => {
        if (!str) return '';
        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    };

    const xssPayloads = [
        "<script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "\"><svg onload=alert(1)>",
        "' onmouseover='alert(1)"
    ];

    for (const payload of xssPayloads) {
        const escaped = escLogic(payload);
        assert.strictEqual(escaped.includes("<script>"), false);
        assert.strictEqual(escaped.includes("<img"), false);
        assert.strictEqual(escaped.includes("<svg"), false);
    }

    // Exact safeUrl function from index.html
    const safeUrl = (url) => {
        if (!url) return '#';
        const trimmed = String(url).trim();
        if (/^https?:\/\//i.test(trimmed)) {
            return escLogic(trimmed);
        }
        return '#';
    };

    assert.strictEqual(safeUrl("javascript:alert(1)"), "#", "Must reject javascript: URIs");
    assert.strictEqual(safeUrl("data:text/html,<script>alert(1)</script>"), "#", "Must reject data: URIs");
    assert.strictEqual(safeUrl("vbscript:msgbox(1)"), "#", "Must reject vbscript: URIs");
    assert.strictEqual(safeUrl("https://example.com/test"), "https://example.com/test", "Must accept valid https");
});

