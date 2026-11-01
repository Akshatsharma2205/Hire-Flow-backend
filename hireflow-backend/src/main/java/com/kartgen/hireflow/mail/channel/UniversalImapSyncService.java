package com.kartgen.hireflow.mail.channel;

import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.document.ResumeGatekeeperService;
import com.kartgen.hireflow.mail.model.MailConnection;
import com.kartgen.hireflow.mail.model.MailConnectionRepository;
import com.kartgen.hireflow.mail.service.MessageIngestionService;
import jakarta.mail.*;
import jakarta.mail.internet.MimeUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/**
 * Channel 2: Universal IMAPS + App Password + Smart Attachment Ingestion.
 * Connects to ANY IMAP server, tracks UID cursor, skips message bodies for privacy,
 * and extracts PDF/DOCX attachments through the intake pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UniversalImapSyncService {

    private final MailConnectionRepository connectionRepo;
    private final MessageIngestionService ingestionService;
    private final ResumeGatekeeperService gatekeeper;
    private final HireFlowProperties props;

    private static final Set<String> RESUME_MIMES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword"
    );

    private static final Set<String> RESUME_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".doc"
    );

    public record AttachmentItem(String fileName, byte[] data) {}

    public SyncResult syncConnection(MailConnection conn) {
        if (!"IMAP_APP_PASSWORD".equals(conn.getProvider())) {
            log.debug("Skipping non-IMAP connection: {}", conn.getId());
            return new SyncResult("ERROR", 0, 0, null, "Provider is not IMAP_APP_PASSWORD");
        }

        Store store = null;
        Folder folder = null;
        try {
            // Decrypt app password
            String appPassword = CryptoUtil.decrypt(
                    conn.getOauthSecretRef(), props.getCrypto().getSecretKey());

            validateHost(conn.getImapHost());

            // Connect via IMAPS
            Properties mailProps = new Properties();
            mailProps.put("mail.store.protocol", "imaps");
            mailProps.put("mail.imaps.host", conn.getImapHost());
            mailProps.put("mail.imaps.port", String.valueOf(conn.getImapPort() != null ? conn.getImapPort() : 993));
            mailProps.put("mail.imaps.ssl.enable", "true");
            mailProps.put("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3");
            mailProps.put("mail.imaps.partialfetch", "true");
            mailProps.put("mail.imaps.timeout", "30000");
            mailProps.put("mail.imaps.connectiontimeout", "15000");

            Session session = Session.getInstance(mailProps);
            store = session.getStore("imaps");
            store.connect(conn.getImapHost(), conn.getMailboxAddress(), appPassword);

            // Open folder READ_ONLY (never alter emails)
            String folderName = conn.getFolderFilter() != null && !conn.getFolderFilter().isBlank()
                    ? conn.getFolderFilter().trim() : "INBOX";
            folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);

            if (!(folder instanceof UIDFolder uidFolder)) {
                String err = "Folder " + folderName + " does not support IMAP UID tracking";
                log.warn(err);
                conn.setStatus("ERROR");
                connectionRepo.save(conn);
                return new SyncResult("ERROR", 0, 0, null, err);
            }

            int totalInFolder = folder.getMessageCount();
            if (totalInFolder == 0) {
                conn.setStatus("ACTIVE");
                conn.setLastSuccessAt(Instant.now());
                connectionRepo.save(conn);
                return new SyncResult("ACTIVE", 0, 0, "Mailbox folder " + folderName + " is empty (0 emails).", null);
            }

            // Fetch messages by UID
            Message[] messages;
            boolean isInitialSync = (conn.getSyncCursor() == null || conn.getSyncCursor().isBlank());
            long startUid = 1;

            if (isInitialSync) {
                // Initial sync: fetch up to the latest 50 messages to prevent timeout on large mailboxes
                int startSeq = Math.max(1, totalInFolder - 49);
                messages = folder.getMessages(startSeq, totalInFolder);
            } else {
                try {
                    startUid = Long.parseLong(conn.getSyncCursor().trim()) + 1;
                } catch (NumberFormatException e) {
                    startUid = 1;
                }
                messages = uidFolder.getMessagesByUID(startUid, UIDFolder.LASTUID);
            }

            if (messages == null || messages.length == 0) {
                conn.setStatus("ACTIVE");
                conn.setLastSuccessAt(Instant.now());
                connectionRepo.save(conn);
                return new SyncResult("ACTIVE", 0, 0, "No new emails found since last sync.", null);
            }

            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.CONTENT_INFO);
            fp.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(messages, fp);

            long highestUid = startUid - 1;
            int scannedCount = 0;
            int ingestedCount = 0;

            for (Message msg : messages) {
                long uid;
                try {
                    uid = uidFolder.getUID(msg);
                } catch (Exception ex) {
                    continue;
                }

                try {
                    int ingestedFromMsg = processMessageAttachments(conn, msg, uid);
                    ingestedCount += ingestedFromMsg;
                    scannedCount++;
                    if (uid > highestUid) highestUid = uid;
                } catch (Exception e) {
                    log.warn("Error processing message UID={} on {}: {}", uid, conn.getMailboxAddress(), e.getMessage());
                    break;
                }
            }

            if (highestUid >= startUid || isInitialSync) {
                if (highestUid > 0) conn.setSyncCursor(String.valueOf(highestUid));
                conn.setLastSuccessAt(Instant.now());
            }
            conn.setStatus("ACTIVE");
            connectionRepo.save(conn);

            String summary = String.format("Sync successful. Scanned %d email(s), ingested %d resume attachment(s).",
                    scannedCount, ingestedCount);
            log.info("IMAP {}: {}", conn.getMailboxAddress(), summary);
            return new SyncResult("ACTIVE", scannedCount, ingestedCount, summary, null);

        } catch (AuthenticationFailedException e) {
            String err = "IMAP authentication failed for " + conn.getMailboxAddress()
                    + ". Check your mailbox address and App Password. Note: Gmail/Yahoo/Outlook require a generated App Password, not your standard login password.";
            log.error(err);
            conn.setStatus("REAUTH");
            connectionRepo.save(conn);
            return new SyncResult("REAUTH", 0, 0, null, err);
        } catch (Exception e) {
            String err = "IMAP sync error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.error("IMAP error on {}: {}", conn.getMailboxAddress(), err, e);
            conn.setStatus("ERROR");
            connectionRepo.save(conn);
            return new SyncResult("ERROR", 0, 0, null, err);
        } finally {
            try { if (folder != null && folder.isOpen()) folder.close(false); } catch (Exception ignored) {}
            try { if (store != null) store.close(); } catch (Exception ignored) {}
        }
    }

    private int processMessageAttachments(MailConnection conn, Message msg, long uid) throws Exception {
        String senderEmail = null;
        String senderName = null;
        Address[] from = msg.getFrom();
        if (from != null && from.length > 0) {
            if (from[0] instanceof jakarta.mail.internet.InternetAddress ia) {
                senderEmail = ia.getAddress();
                senderName = ia.getPersonal();
            } else {
                senderEmail = from[0].toString();
            }
        }
        String subject = msg.getSubject() != null ? msg.getSubject() : "No Subject";
        String internetMessageId = null;
        String[] messageIdHeaders = msg.getHeader("Message-ID");
        if (messageIdHeaders != null && messageIdHeaders.length > 0) {
            internetMessageId = messageIdHeaders[0];
        }

        List<AttachmentItem> attachments = new ArrayList<>();
        extractAttachmentsRecursive(msg, attachments);

        int ingested = 0;
        int index = 0;
        for (AttachmentItem item : attachments) {
            ResumeGatekeeperService.GatekeeperResult gk = gatekeeper.evaluate(item.data(), item.fileName());
            if (!gk.accepted()) {
                log.info("Gatekeeper skipped non-resume file '{}' from {}: {}", item.fileName(), senderEmail, gk.rejectionReason());
                continue;
            }

            String providerMessageId = uid + ":" + index++;
            String effectiveSource = isForwarded(subject, senderEmail) ? "FORWARDED" : "IMAP";
            ingestionService.ingestMailAttachment(
                    conn.getOrganizationId(), conn.getId(), providerMessageId,
                    internetMessageId, senderEmail, senderName, subject,
                    effectiveSource, item.data(), item.fileName());
            ingested++;
        }
        return ingested;
    }

    private void extractAttachmentsRecursive(Part part, List<AttachmentItem> out) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                extractAttachmentsRecursive(mp.getBodyPart(i), out);
            }
            return;
        }

        String contentType = part.getContentType() != null ? part.getContentType().toLowerCase(Locale.ROOT) : "";
        if (contentType.startsWith("text/plain") || contentType.startsWith("text/html")) {
            return;
        }

        String rawFileName = part.getFileName();
        String fileName = null;
        if (rawFileName != null && !rawFileName.isBlank()) {
            try {
                fileName = MimeUtility.decodeText(rawFileName.trim());
            } catch (Exception e) {
                fileName = rawFileName.trim();
            }
        }

        final String resolvedFileName = fileName;
        boolean hasResumeExt = resolvedFileName != null && RESUME_EXTENSIONS.stream().anyMatch(ext -> resolvedFileName.toLowerCase(Locale.ROOT).endsWith(ext));
        boolean hasResumeMime = RESUME_MIMES.stream().anyMatch(contentType::startsWith);

        if (hasResumeExt || hasResumeMime) {
            byte[] data = readPartData(part);
            if (data != null && data.length > 100) {
                String safeName = (resolvedFileName != null && !resolvedFileName.isBlank())
                        ? resolvedFileName : ("resume_" + (out.size() + 1) + (contentType.contains("word") ? ".docx" : ".pdf"));
                out.add(new AttachmentItem(safeName, data));
            }
        }
    }

    private boolean isForwarded(String subject, String senderEmail) {
        if (subject != null) {
            String lower = subject.toLowerCase(Locale.ROOT);
            if (lower.startsWith("fwd:") || lower.startsWith("fw:") || lower.startsWith("forward")) {
                return true;
            }
        }
        if (senderEmail != null) {
            String lower = senderEmail.toLowerCase(Locale.ROOT);
            for (String prefix : new String[]{"hr@", "recruit@", "careers@", "jobs@", "placement@", "agency@"}) {
                if (lower.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    private void validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("IMAP host cannot be blank");
        }
        try {
            for (java.net.InetAddress addr : java.net.InetAddress.getAllByName(host)) {
                String ip = addr.getHostAddress();
                if (ip.startsWith("169.254.") || "metadata.google.internal".equalsIgnoreCase(host)) {
                    throw new IllegalArgumentException("Access to cloud metadata service is prohibited: " + host);
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("Unknown IMAP host: " + host);
        }
    }

    private byte[] readPartData(Part part) {
        try (InputStream is = part.getInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int read;
            int total = 0;
            while ((read = is.read(buf)) != -1) {
                total += read;
                if (total > 25 * 1024 * 1024) { // 25MB cap
                    log.warn("Attachment exceeded 25MB cap; skipping");
                    return null;
                }
                bos.write(buf, 0, read);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to read attachment data: {}", e.getMessage());
            return null;
        }
    }
}
