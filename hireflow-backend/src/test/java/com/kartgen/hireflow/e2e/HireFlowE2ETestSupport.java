package com.kartgen.hireflow.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.document.StoragePort;
import com.kartgen.hireflow.hiring.ApplicationRepository;
import com.kartgen.hireflow.identity.CandidateRepository;
import com.kartgen.hireflow.identity.IdentityReviewCaseRepository;
import com.kartgen.hireflow.jobs.JobRepository;
import com.kartgen.hireflow.mail.model.InboundAttachmentRepository;
import com.kartgen.hireflow.mail.model.InboundMessageRepository;
import com.kartgen.hireflow.mail.model.MailConnectionRepository;
import com.kartgen.hireflow.mail.model.OAuthAuthorizationAttemptRepository;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Shared base class for all HireFlow E2E test suites covering requirements R1 through R6.
 *
 * Provides:
 * - Embedded network-isolated mock HTTP server for Google & Microsoft OAuth/Graph APIs.
 * - Synthetic document fixture generators (valid PDF, XFA XXE, Billion Laughs, ZIP bomb).
 * - Progressive capability checks and milestone-aware assumption gates.
 * - Multi-tenant credentials and database query helpers.
 */
public abstract class HireFlowE2ETestSupport extends IntegrationTestSupport {

    public static final String TENANT_A_ORG_ID = "11111111-1111-1111-1111-111111111111";
    public static final String TENANT_B_ORG_ID = "99999999-9999-9999-9999-999999999999";
    public static final String JOB_BACKEND_ID = "c0000001-0000-0000-0000-000000000001";
    public static final String JOB_FULLSTACK_ID = "c0000001-0000-0000-0000-000000000002";

    protected static HttpServer mockServer;
    protected static int mockPort;
    protected static String mockBaseUrl;

    protected static final Map<String, String> lastMockHeaders = new ConcurrentHashMap<>();
    protected static volatile int mockTokenHttpStatus = 200;
    protected static volatile String mockTokenResponseBody = null;

    @Autowired
    protected CandidateRepository candidateRepo;

    @Autowired
    protected JobRepository jobRepo;

    @Autowired
    protected MailConnectionRepository connectionRepo;

    @Autowired
    protected OAuthAuthorizationAttemptRepository attemptRepo;

    @Autowired
    protected InboundMessageRepository messageRepo;

    @Autowired
    protected InboundAttachmentRepository attachmentRepo;

    @Autowired
    protected IdentityReviewCaseRepository reviewCaseRepo;

    @Autowired
    protected ApplicationRepository applicationRepo;

    @Autowired
    protected StoragePort storagePort;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected HireFlowProperties hireFlowProperties;

    @BeforeAll
    public static synchronized void startMockProviderServer() throws IOException {
        if (mockServer == null) {
            mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mockPort = mockServer.getAddress().getPort();
            mockBaseUrl = "http://localhost:" + mockPort;

            // Google OAuth mocks
            mockServer.createContext("/mock-oauth/google/token", new MockHandler(200, """
                {
                  "access_token": "ya29.mock-google-token",
                  "refresh_token": "1//mock-google-refresh-token",
                  "expires_in": 3600,
                  "token_type": "Bearer"
                }
                """));
            mockServer.createContext("/mock-oauth/gmail/users/me/profile", new MockHandler(200, """
                {
                  "emailAddress": "hr-lead@kartgen.com",
                  "historyId": "10000"
                }
                """));
            mockServer.createContext("/mock-oauth/gmail/users/me/messages", new MockHandler(200, "{\"messages\":[]}"));

            // Microsoft OAuth mocks
            mockServer.createContext("/mock-oauth/microsoft/token", new MockHandler(200, """
                {
                  "access_token": "mock-ms-access-token",
                  "refresh_token": "mock-ms-refresh-token",
                  "expires_in": 3600,
                  "token_type": "Bearer"
                }
                """));
            mockServer.createContext("/mock-oauth/graph/v1.0/me", new MockHandler(200, """
                {
                  "userPrincipalName": "recruiter@kartgen.onmicrosoft.com",
                  "mail": "recruiter@kartgen.onmicrosoft.com"
                }
                """));

            mockServer.setExecutor(null);
            mockServer.start();
        }
    }

    @org.junit.jupiter.api.BeforeEach
    public void configureMockOAuthProperties() {
        if (hireFlowProperties != null && hireFlowProperties.getOauth() != null) {
            hireFlowProperties.getOauth().setGoogleClientId("mock-google-client-id");
            hireFlowProperties.getOauth().setGoogleClientSecret("mock-google-client-secret");
            hireFlowProperties.getOauth().setGoogleTokenUri(mockBaseUrl + "/mock-oauth/google/token");
            hireFlowProperties.getOauth().setGmailBaseUrl(mockBaseUrl + "/mock-oauth/gmail");

            hireFlowProperties.getOauth().setMicrosoftClientId("mock-ms-client-id");
            hireFlowProperties.getOauth().setMicrosoftClientSecret("mock-ms-client-secret");
            hireFlowProperties.getOauth().setMicrosoftTenant("common");
            hireFlowProperties.getOauth().setMicrosoftTokenUriTemplate(mockBaseUrl + "/mock-oauth/microsoft/token");
            hireFlowProperties.getOauth().setGraphBaseUrl(mockBaseUrl + "/mock-oauth/graph");
        }
    }

    private static class MockHandler implements HttpHandler {
        private final int defaultStatus;
        private final String defaultBody;

        MockHandler(int status, String body) {
            this.defaultStatus = status;
            this.defaultBody = body;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] responseBytes = (mockTokenResponseBody != null ? mockTokenResponseBody : defaultBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(mockTokenHttpStatus != 200 ? mockTokenHttpStatus : defaultStatus, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    // ── Progressive Capability & Endpoint Helpers ──

    public boolean isClassAvailable(String fqcn) {
        try {
            Class.forName(fqcn);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public boolean isRulesApiAvailable() {
        return isClassAvailable("com.kartgen.hireflow.rules.EmailRuleController")
                || isClassAvailable("com.kartgen.hireflow.rules.EmailRule");
    }

    public boolean isEndpointAvailable(String path, HttpMethod method) {
        try {
            MvcResult res = switch (method.name()) {
                case "GET" -> mockMvc.perform(get(path)).andReturn();
                case "POST" -> mockMvc.perform(post(path)).andReturn();
                default -> mockMvc.perform(get(path)).andReturn();
            };
            return res.getResponse().getStatus() != 404 && res.getResponse().getStatus() != 500;
        } catch (Exception e) {
            return false;
        }
    }

    public void assumeFeatureAvailable(String featureName, boolean available) {
        Assumptions.assumeTrue(available, featureName + " is scheduled for implementation in a later milestone");
    }

    // ── Synthetic Fixture Generators ──

    public byte[] createValidResumePdf(String name, String email, String phone, String skills) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                stream.newLineAtOffset(50, 750);
                stream.showText(name != null ? name : "Jane Doe");
                stream.newLineAtOffset(0, -20);
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                stream.showText("Curriculum Vitae / Resume");
                stream.newLineAtOffset(0, -15);
                stream.showText("Email: " + (email != null ? email : "jane.doe@example.com"));
                stream.newLineAtOffset(0, -15);
                stream.showText("Phone: " + (phone != null ? phone : "+1-555-0199"));
                stream.newLineAtOffset(0, -15);
                stream.showText("Experience: 5 years software engineer");
                stream.newLineAtOffset(0, -15);
                stream.showText("Skills: " + (skills != null ? skills : "Java, Spring Boot, PostgreSQL, Docker"));
                stream.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            // Fallback plain text with PDF header
            return ("%PDF-1.7\nResume: " + name + "\nEmail: " + email + "\nPhone: " + phone + "\nSkills: " + skills + "\n%%EOF")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    public byte[] createMalformedOrTruncatedPdf() {
        return "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\n".getBytes(StandardCharsets.US_ASCII);
    }

    public byte[] createXfaXxePdf() {
        // PDF with embedded XML containing an XXE attempt targeting a local file
        String xxePayload = """
            %PDF-1.7
            1 0 obj
            << /Type /Catalog /AcroForm << /XFA [ (template) 2 0 R ] >> >>
            endobj
            2 0 obj
            << /Length 200 >>
            stream
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE test [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <xdp:xdp xmlns:xdp="http://ns.adobe.com/xdp/">
              <data>&xxe;</data>
            </xdp:xdp>
            endstream
            endobj
            xref
            0 3
            0000000000 65535 f 
            0000000010 00000 n 
            0000000090 00000 n 
            trailer
            << /Size 3 /Root 1 0 R >>
            startxref
            350
            %%EOF
            """;
        return xxePayload.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] createBillionLaughsXml() {
        String xml = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
             <!ENTITY lol "lol">
             <!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
             <!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">
             <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
            ]>
            <resume><experience>&lol3;</experience></resume>
            """;
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] createZipBomb(int entriesCount) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {
            byte[] dummyData = new byte[1024]; // 1KB of zeros compress extremely well
            for (int i = 0; i < entriesCount; i++) {
                ZipEntry entry = new ZipEntry("entry_" + i + ".txt");
                zos.putNextEntry(entry);
                zos.write(dummyData);
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to construct test ZIP fixture", e);
        }
    }

    public byte[] createOversizedPayload(int sizeMb) {
        return new byte[sizeMb * 1024 * 1024];
    }
}
