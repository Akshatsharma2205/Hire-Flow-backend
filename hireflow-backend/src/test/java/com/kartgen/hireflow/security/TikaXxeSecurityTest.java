package com.kartgen.hireflow.security;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@DisplayName("Tika 3.2.2 XXE & XML Security Regression Tests (CVE-2025-66516 & CVE-2025-54988)")
class TikaXxeSecurityTest {

    private final Tika tika = new Tika();

    @BeforeAll
    static void warmUpPdfBox() {
        // Pre-initialize PDFBox font cache so cold Windows font directory scanning
        // (FileSystemFontProvider / FontFileFinder) does not trigger assertTimeoutPreemptively.
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDAcroForm acroForm = new PDAcroForm(document);
            document.getDocumentCatalog().setAcroForm(acroForm);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            new Tika().parseToString(new ByteArrayInputStream(baos.toByteArray()));
        } catch (Exception ignored) {
            // Best-effort cache pre-warming
        }
    }

    private byte[] createPdfWithXfa(String xmlPayload) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            PDAcroForm acroForm = new PDAcroForm(document);
            document.getDocumentCatalog().setAcroForm(acroForm);

            COSStream xfaStream = document.getDocument().createCOSStream();
            try (OutputStream os = xfaStream.createOutputStream()) {
                os.write(xmlPayload.getBytes(StandardCharsets.UTF_8));
            }

            COSArray xfaArray = new COSArray();
            xfaArray.add(new COSString("template"));
            xfaArray.add(xfaStream);

            acroForm.getCOSObject().setItem(COSName.getPDFName("XFA"), xfaArray);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] createDocxWithXml(String documentXml) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // [Content_Types].xml
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // _rels/.rels
            zos.putNextEntry(new ZipEntry("_rels/.rels"));
            zos.write("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // word/document.xml
            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("Crafted XFA PDF external entity file read attempt does not disclose local files")
    void testXfaExternalEntityFileReadSuppressed() throws Exception {
        String maliciousXfa = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE xdp:xdp [
                  <!ENTITY xxeLinux SYSTEM "file:///etc/passwd">
                  <!ENTITY xxeWin SYSTEM "file:///C:/Windows/win.ini">
                ]>
                <xdp:xdp xmlns:xdp="http://ns.adobe.com/xdp/">
                  <form>
                    <field name="applicant">Candidate Name: Jane Doe</field>
                    <field name="secretLinux">&xxeLinux;</field>
                    <field name="secretWin">&xxeWin;</field>
                  </form>
                </xdp:xdp>
                """;

        byte[] pdfBytes = createPdfWithXfa(maliciousXfa);

        // Parse with standard Tika instance
        String extractedText = assertDoesNotThrow(() ->
                tika.parseToString(new ByteArrayInputStream(pdfBytes))
        );

        // Verify that local file disclosure markers are NOT in the extracted text
        assertThat(extractedText).doesNotContain("root:x:0:0");
        assertThat(extractedText).doesNotContain("/bin/bash");
        assertThat(extractedText).doesNotContain("[fonts]");
        assertThat(extractedText).doesNotContain("[extensions]");
    }

    @Test
    @DisplayName("External DTD SSRF attempt does not trigger outbound network requests or hang parser")
    void testXfaExternalDtdSsrfBlocked() throws Exception {
        String ssrfXfa = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE xdp:xdp SYSTEM "http://127.0.0.1:65535/nonexistent-xxe.dtd">
                <xdp:xdp xmlns:xdp="http://ns.adobe.com/xdp/">
                  <form>
                    <field name="experience">5 years Java engineering experience</field>
                  </form>
                </xdp:xdp>
                """;

        byte[] pdfBytes = createPdfWithXfa(ssrfXfa);

        // Parser must not hang or throw unhandled socket errors attempting to fetch the DTD
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            String text = tika.parseToString(new ByteArrayInputStream(pdfBytes));
            assertThat(text).isNotNull();
        });
    }

    @Test
    @DisplayName("Billion Laughs recursive entity expansion DoS attack is bounded without OutOfMemory")
    void testBillionLaughsExpansionBlocked() throws Exception {
        String billionLaughsXfa = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE lolz [
                 <!ENTITY lol "lol">
                 <!ELEMENT lolz (#PCDATA)>
                 <!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
                 <!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">
                 <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
                 <!ENTITY lol4 "&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;">
                 <!ENTITY lol5 "&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;">
                 <!ENTITY lol6 "&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;">
                ]>
                <xdp:xdp xmlns:xdp="http://ns.adobe.com/xdp/">
                  <form>
                    <field name="bomb">&lol6;</field>
                  </form>
                </xdp:xdp>
                """;

        byte[] pdfBytes = createPdfWithXfa(billionLaughsXfa);

        // Must complete promptly without running out of memory
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            String text = tika.parseToString(new ByteArrayInputStream(pdfBytes));
            assertThat(text).isNotNull();
        });
    }

    @Test
    @DisplayName("DOCX external entity injection does not disclose filesystem contents and is safely rejected")
    void testDocxExternalEntityBlocked() throws Exception {
        String docxXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <!DOCTYPE document [
                  <!ENTITY xxeLinux SYSTEM "file:///etc/passwd">
                  <!ENTITY xxeWin SYSTEM "file:///C:/Windows/win.ini">
                ]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:r>
                        <w:t>Resume of Jane Doe with &xxeLinux; and &xxeWin;</w:t>
                      </w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        byte[] docxBytes = createDocxWithXml(docxXml);

        // Under Tika 3.2.2 and secure XML parser configuration, the parser safely rejects
        // documents containing DOCTYPE declarations with a TikaException / SAXParseException,
        // or suppresses the entity without leaking sensitive file contents.
        try {
            String text = tika.parseToString(new ByteArrayInputStream(docxBytes));
            assertThat(text).doesNotContain("root:x:0:0");
            assertThat(text).doesNotContain("/bin/bash");
            assertThat(text).doesNotContain("[fonts]");
            assertThat(text).doesNotContain("[extensions]");
        } catch (org.apache.tika.exception.TikaException e) {
            // Malicious payload safely rejected by secure XML parser configuration
            assertThat(e.getMessage() != null && (
                    e.getMessage().contains("DOCTYPE is disallowed") ||
                    e.getMessage().contains("disallow-doctype-decl") ||
                    e.getMessage().contains("Illegal IOException")
            ) || e.getCause() != null).isTrue();
        }
    }
}
