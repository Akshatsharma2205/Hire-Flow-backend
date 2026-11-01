const test = require("node:test");
const assert = require("node:assert/strict");

const {
  inspectBytesForResume,
  base64ToArrayBuffer
} = require("../background.js");

const {
  isSupportedResumeFile,
  parseCsv,
  scoreCandidateItem,
  arrayBufferToBase64
} = require("../content.js");

test("Magic byte inspection: detects valid PDF with resume content", () => {
  const content = "%PDF-1.7\nExperience: Senior Software Engineer\nSkills: Java, Spring Boot, React";
  const encoder = new TextEncoder();
  const buffer = encoder.encode(content).buffer;

  const result = inspectBytesForResume(buffer, "john_doe_resume.pdf");
  assert.equal(result.valid, true);
  assert.equal(result.detectedMime, "application/pdf");
});

test("Magic byte inspection: detects valid DOCX header with resume content", () => {
  // DOCX header begins with PK (0x50, 0x4B)
  const header = new Uint8Array([0x50, 0x4B, 0x03, 0x04]);
  const text = new TextEncoder().encode(" John Doe Experience: 5 years Education: Computer Science developer");
  const fullBytes = new Uint8Array(header.length + text.length);
  fullBytes.set(header, 0);
  fullBytes.set(text, header.length);

  const result = inspectBytesForResume(fullBytes.buffer, "resume.docx");
  assert.equal(result.valid, true);
  assert.equal(result.detectedMime, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
});

test("Magic byte inspection: rejects non-resume file signature", () => {
  const text = "Random text without pdf or pk header";
  const buffer = new TextEncoder().encode(text).buffer;

  const result = inspectBytesForResume(buffer, "notes.txt");
  assert.equal(result.valid, false);
  assert.match(result.reason, /File signature is neither a valid PDF/);
});

test("Privacy Gatekeeper: blocks invoices and receipts lacking resume signals", () => {
  const content = "%PDF-1.4\nTax Invoice\nGSTIN: 29ABCDE1234F1Z5\nBill to: Acme Corp\nPayment Receipt";
  const buffer = new TextEncoder().encode(content).buffer;

  const result = inspectBytesForResume(buffer, "tax_invoice_march.pdf");
  assert.equal(result.valid, false);
  assert.match(result.reason, /Blocked by Privacy Gatekeeper/);
});

test("Privacy Gatekeeper: allows resume containing incidental invoice word if resume signals present", () => {
  const content = "%PDF-1.4\nExperience: Software Engineer at Payment Receipt Systems\nSkills: Java, Spring\nEducation: B.Tech";
  const buffer = new TextEncoder().encode(content).buffer;

  const result = inspectBytesForResume(buffer, "developer_resume.pdf");
  assert.equal(result.valid, true);
});

test("Base64 roundtrip conversion preserves binary bytes exactly", () => {
  const original = new Uint8Array([0, 1, 2, 255, 128, 64, 32, 16, 8, 4, 2, 1]);
  const b64 = arrayBufferToBase64(original.buffer);
  const recovered = new Uint8Array(base64ToArrayBuffer(b64));

  assert.deepEqual(Array.from(recovered), Array.from(original));
});

test("File extension support checker handles valid and invalid extensions", () => {
  assert.equal(isSupportedResumeFile("resume.pdf"), true);
  assert.equal(isSupportedResumeFile("candidate.DOCX"), true);
  assert.equal(isSupportedResumeFile("profile.doc"), true);
  assert.equal(isSupportedResumeFile("avatar.png"), false);
  assert.equal(isSupportedResumeFile("invoice.xlsx"), false);
  assert.equal(isSupportedResumeFile(""), false);
  assert.equal(isSupportedResumeFile(null), false);
});

test("CSV parser handles empty strings, whitespace and lowercase conversion", () => {
  assert.deepEqual(parseCsv("Java, Spring Boot, React, AWS "), ["java", "spring boot", "react", "aws"]);
  assert.deepEqual(parseCsv(""), []);
  assert.deepEqual(parseCsv(null), []);
  assert.deepEqual(parseCsv("   "), []);
});

test("Smart candidate scorer: high score for matching resume with file and standard keywords", () => {
  const filters = { useStandardFilter: true, strictCustomMatch: false };
  const standardWords = ["resume", "cv", "developer", "engineer"];
  const userWords = ["java", "spring"];
  const excludeWords = ["invoice", "receipt"];

  const data = {
    subject: "Application for Senior Java Developer",
    fileName: "rahul_sharma_resume.pdf",
    snippet: "Experienced Java and Spring Boot engineer applying for role",
    senderEmail: "rahul@example.com",
    hasPdfOrDocx: true
  };

  const result = scoreCandidateItem(data, filters, standardWords, userWords, excludeWords);
  assert.equal(result.excluded, false);
  assert.ok(result.score >= 70, `Expected score >= 70, got ${result.score}`);
  assert.ok(result.reasons.length > 0);
});

test("Smart candidate scorer: immediate exclusion for trap words", () => {
  const filters = { useStandardFilter: true, strictCustomMatch: false };
  const standardWords = ["resume", "cv"];
  const userWords = [];
  const excludeWords = ["invoice", "tax", "bill"];

  const data = {
    subject: "Your Electricity Bill & Tax Invoice",
    fileName: "invoice.pdf",
    snippet: "Attached is the bill for March",
    senderEmail: "billing@utility.com",
    hasPdfOrDocx: true
  };

  const result = scoreCandidateItem(data, filters, standardWords, userWords, excludeWords);
  assert.equal(result.excluded, true);
  assert.equal(result.score, 0);
  assert.match(result.reasons[0], /Excluded/);
});

test("Smart candidate scorer: strict custom match requires user keyword", () => {
  const filters = { useStandardFilter: true, strictCustomMatch: true };
  const standardWords = ["resume", "developer"];
  const userWords = ["golang", "kubernetes"];
  const excludeWords = [];

  const data = {
    subject: "Java Developer Resume",
    fileName: "candidate_resume.pdf",
    snippet: "Experienced in Python and Java",
    senderEmail: "cand@example.com",
    hasPdfOrDocx: true
  };

  const result = scoreCandidateItem(data, filters, standardWords, userWords, excludeWords);
  assert.equal(result.excluded, true);
  assert.equal(result.score, 0);
  assert.match(result.reasons[0], /Missing required user keyword/);
});
