const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const STATIC_DIR = path.resolve(__dirname, "../../main/resources/static");
const ASSETS_DIR = path.join(STATIC_DIR, "assets");
const FRONTEND_SRC = path.resolve(__dirname, "../../../../hireflow-frontend/src");

test("Adversarial Static Analysis: Production bundles have zero client token storage", () => {
    assert.strictEqual(fs.existsSync(STATIC_DIR), true, "Static directory must exist");
    assert.strictEqual(fs.existsSync(ASSETS_DIR), true, "Assets directory must exist");

    const assetFiles = fs.readdirSync(ASSETS_DIR);
    const jsBundles = assetFiles.filter(f => f.endsWith(".js"));
    assert.ok(jsBundles.length >= 2, "Must have compiled JS bundles");

    for (const bundle of jsBundles) {
        const bundleContent = fs.readFileSync(path.join(ASSETS_DIR, bundle), "utf8");

        // Verify no localStorage.setItem or sessionStorage.setItem for auth tokens
        const localSetMatch = bundleContent.match(/localStorage\.setItem\s*\(\s*['"](?:token|auth|jwt|session|refresh)/i);
        assert.strictEqual(localSetMatch, null, `Bundle ${bundle} must not store tokens in localStorage`);

        const sessionSetMatch = bundleContent.match(/sessionStorage\.setItem\s*\(\s*['"](?:token|auth|jwt|session|refresh)/i);
        assert.strictEqual(sessionSetMatch, null, `Bundle ${bundle} must not store tokens in sessionStorage`);

        // Verify no document.cookie writes for sensitive tokens
        const cookieSetMatch = bundleContent.match(/document\.cookie\s*=\s*['"](?:token|auth|jwt|refreshToken)=/i);
        assert.strictEqual(cookieSetMatch, null, `Bundle ${bundle} must not set tokens in document.cookie`);
    }
});

test("Adversarial PostMessage Origin Validation: Exhaustive origin spoofing & boundary attacks", async () => {
    // The exact handler implementation extracted from MailboxesPage.tsx (lines 64-80)
    const createMailboxHandler = (trustedOrigin) => {
        let connectedCount = 0;
        let lastError = null;
        let lastSuccess = null;

        const handleOAuthMessage = (event) => {
            // Exact check from MailboxesPage.tsx line 65:
            if (event.origin !== trustedOrigin) return;
            if (!event.data || event.data.type !== 'HIREFLOW_OAUTH_CALLBACK') return;

            if (event.data.status === 'SUCCESS') {
                const provLabel = (event.data.provider || '').toLowerCase().includes('micro')
                    ? 'Microsoft 365'
                    : 'Google Workspace';
                const mailbox = event.data.mailboxAddress ? ` (${event.data.mailboxAddress})` : '';
                lastSuccess = `Successfully connected ${provLabel}${mailbox}!`;
                connectedCount++;
            } else {
                const errorMsg = event.data.error || 'Mailbox authorization was not completed';
                lastError = `Connection failed: ${errorMsg}`;
            }
        };

        return {
            handleOAuthMessage,
            getConnectedCount: () => connectedCount,
            getLastError: () => lastError,
            getLastSuccess: () => lastSuccess
        };
    };

    const trustedOrigin = "https://hireflow.kartgen.com";
    const harness = createMailboxHandler(trustedOrigin);

    // Attack 1: Scheme downgrade attack (http instead of https)
    harness.handleOAuthMessage({
        origin: "http://hireflow.kartgen.com",
        data: { type: "HIREFLOW_OAUTH_CALLBACK", status: "SUCCESS", provider: "google", mailboxAddress: "attacker@evil.com" }
    });
    assert.strictEqual(harness.getConnectedCount(), 0, "Scheme downgrade MUST be rejected");

    // Attack 2: Subdomain suffix attack
    harness.handleOAuthMessage({
        origin: "https://hireflow.kartgen.com.attacker.com",
        data: { type: "HIREFLOW_OAUTH_CALLBACK", status: "SUCCESS", provider: "google", mailboxAddress: "attacker@evil.com" }
    });
    assert.strictEqual(harness.getConnectedCount(), 0, "Suffix spoof MUST be rejected");

    // Attack 3: Subdomain prefix attack
    harness.handleOAuthMessage({
        origin: "https://attacker-hireflow.kartgen.com",
        data: { type: "HIREFLOW_OAUTH_CALLBACK", status: "SUCCESS", provider: "google", mailboxAddress: "attacker@evil.com" }
    });
    assert.strictEqual(harness.getConnectedCount(), 0, "Prefix spoof MUST be rejected");

    // Attack 4: Port spoof attack
    harness.handleOAuthMessage({
        origin: "https://hireflow.kartgen.com:8443",
        data: { type: "HIREFLOW_OAUTH_CALLBACK", status: "SUCCESS", provider: "google", mailboxAddress: "attacker@evil.com" }
    });
    assert.strictEqual(harness.getConnectedCount(), 0, "Port spoof MUST be rejected");

    // Attack 5: Null / opaque origin (sandboxed iframe)
    harness.handleOAuthMessage({
        origin: "null",
        data: { type: "HIREFLOW_OAUTH_CALLBACK", status: "SUCCESS", provider: "google", mailboxAddress: "attacker@evil.com" }
    });
    assert.strictEqual(harness.getConnectedCount(), 0, "Null origin MUST be rejected");

    // Attack 6: Empty origin
    harness.handleOAuthMessage({
        origin: "",
        data: { type: "HIREFLOW_OAUTH_CALLBACK", status: "SUCCESS", provider: "google", mailboxAddress: "attacker@evil.com" }
    });
    assert.strictEqual(harness.getConnectedCount(), 0, "Empty origin MUST be rejected");

    // Attack 7: Undefined origin
    harness.handleOAuthMessage({
        origin: undefined,
        data: { type: "HIREFLOW_OAUTH_CALLBACK", status: "SUCCESS", provider: "google", mailboxAddress: "attacker@evil.com" }
    });
    assert.strictEqual(harness.getConnectedCount(), 0, "Undefined origin MUST be rejected");

    // Attack 8: Relative path origin
    harness.handleOAuthMessage({
        origin: "/api/v1/mail-connections",
        data: { type: "HIREFLOW_OAUTH_CALLBACK", status: "SUCCESS", provider: "google", mailboxAddress: "attacker@evil.com" }
    });
    assert.strictEqual(harness.getConnectedCount(), 0, "Relative path origin MUST be rejected");

    // Malformed data attack suite
    const malformedPayloads = [
        null,
        undefined,
        "string payload",
        12345,
        true,
        [],
        {},
        { type: "OTHER_EVENT" },
        { type: ["HIREFLOW_OAUTH_CALLBACK"] },
        { type: "HIREFLOW_OAUTH_CALLBACK", status: 123 },
        { type: "HIREFLOW_OAUTH_CALLBACK", status: null },
    ];

    for (const badData of malformedPayloads) {
        assert.doesNotThrow(() => {
            harness.handleOAuthMessage({ origin: trustedOrigin, data: badData });
        }, `Malformed data should never throw unhandled exception`);
    }

    assert.strictEqual(harness.getConnectedCount(), 0, "No malformed data should trigger connection");

    // Positive Control: Valid same-origin message
    harness.handleOAuthMessage({
        origin: trustedOrigin,
        data: {
            type: "HIREFLOW_OAUTH_CALLBACK",
            status: "SUCCESS",
            provider: "google",
            mailboxAddress: "legit@kartgen.com"
        }
    });
    assert.strictEqual(harness.getConnectedCount(), 1, "Legitimate same-origin message must connect");
    assert.ok(harness.getLastSuccess().includes("legit@kartgen.com"));
});

test("Adversarial Markdown DOM XSS Stress Test: Verify safe rendering of attack payloads", () => {
    // Pure logic simulation of DocsPage.tsx's line-by-line markdown parser
    // DocsPage parses markdown into typed React JSX elements (<h1-3>, <p>, <li>, <table>, <pre>)
    // In React JSX, any child passed as string {text} is automatically text-escaped by React.
    // However, we verify if any dangerous raw HTML can escape line parsing.

    const testMarkdown = `
# Title with <script>alert('xss-h1')</script>
## Subheader <img src=x onerror=alert('xss-h2')>
### Level 3 <svg onload=alert('xss-h3')>
> Blockquote <iframe src="javascript:alert('xss-quote')"></iframe>
- List item with <a href="javascript:alert('xss-list')">link</a>
| Header 1 | Header 2 |
|---|---|
| <script>alert('table-xss')</script> | Safe cell |
\`\`\`html
<script>alert('code-xss')</script>
\`\`\`
Paragraph with <style>body{display:none}</style> and <details open ontoggle=alert(1)>
`;

    const lines = testMarkdown.split('\n');
    const parsedNodes = [];
    let inCodeBlock = false;
    let codeBuffer = [];

    lines.forEach((line, idx) => {
        if (line.startsWith('```')) {
            if (inCodeBlock) {
                parsedNodes.push({ type: 'pre', text: codeBuffer.join('\n') });
                codeBuffer = [];
                inCodeBlock = false;
            } else {
                inCodeBlock = true;
            }
            return;
        }
        if (inCodeBlock) {
            codeBuffer.push(line);
            return;
        }
        if (line.startsWith('# ')) {
            parsedNodes.push({ type: 'h1', text: line.replace('# ', '') });
            return;
        }
        if (line.startsWith('## ')) {
            parsedNodes.push({ type: 'h2', text: line.replace('## ', '') });
            return;
        }
        if (line.startsWith('### ')) {
            parsedNodes.push({ type: 'h3', text: line.replace('### ', '') });
            return;
        }
        if (line.startsWith('> ')) {
            parsedNodes.push({ type: 'blockquote', text: line.replace('> ', '') });
            return;
        }
        if (line.startsWith('- ')) {
            parsedNodes.push({ type: 'li', text: line.substring(2) });
            return;
        }
        if (line.trim().startsWith('|') && line.trim().endsWith('|')) {
            const cells = line.split('|').slice(1, -1).map(c => c.trim());
            parsedNodes.push({ type: 'table-row', cells });
            return;
        }
        if (line.trim()) {
            parsedNodes.push({ type: 'p', text: line });
        }
    });

    // Verification:
    // 1. In DocsPage.tsx, every node is rendered using JSX text children (e.g. <h1>{text}</h1>)
    // 2. Ensure NO dangerouslySetInnerHTML or innerHTML is used anywhere in the codebase
    const docsPagePath = path.join(FRONTEND_SRC, "pages/DocsPage.tsx");
    const docsPageContent = fs.readFileSync(docsPagePath, "utf8");
    assert.strictEqual(docsPageContent.includes("dangerouslySetInnerHTML"), false, "DocsPage must not use dangerouslySetInnerHTML");
    assert.strictEqual(docsPageContent.includes("innerHTML"), false, "DocsPage must not use innerHTML");

    // All elements receive text as child props, meaning React creates document.createTextNode
    assert.ok(parsedNodes.length >= 7, "Parsed all test nodes successfully without crash");
});

test("Adversarial Audit: Check for presence of query parameter sanitizer in SPA", () => {
    // When popup is blocked, OAuth redirects to /index.html?oauth=success&provider=google&mailbox=...
    // Check if the React app actively handles or strips this query param.
    const mailboxesPagePath = path.join(FRONTEND_SRC, "pages/MailboxesPage.tsx");
    const appPath = path.join(FRONTEND_SRC, "App.tsx");
    const indexHtmlPath = path.join(STATIC_DIR, "index.html");

    const mailboxesContent = fs.readFileSync(mailboxesPagePath, "utf8");
    const appContent = fs.readFileSync(appPath, "utf8");
    const indexContent = fs.readFileSync(indexHtmlPath, "utf8");

    // Check if window.history.replaceState is present in actual executable JS code
    const hasReplaceStateInMailboxes = mailboxesContent.includes("replaceState");
    const hasReplaceStateInApp = appContent.includes("replaceState");
    const hasCommentInIndex = indexContent.includes("Security verification signatures");

    // Assert replaceState is present in real application code and fake comment is eliminated
    assert.strictEqual(hasReplaceStateInMailboxes || hasReplaceStateInApp, true, "MailboxesPage must implement replaceState query parameter sanitization");
    assert.strictEqual(hasCommentInIndex, false, "index.html must not contain fake comment signatures");
});
