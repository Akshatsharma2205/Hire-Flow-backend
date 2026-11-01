package com.kartgen.hireflow.mail.channel;

import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.document.ResumeFileRepository;
import com.kartgen.hireflow.mail.service.MessageIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class ExtensionIntakeController {

    private final ResumeFileRepository resumeFileRepo;
    private final MessageIngestionService ingestionService;

    /**
     * SHA-256 pre-check so the Chrome Extension can skip uploading existing files.
     */
    @PostMapping("/check-hashes")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER')")
    public ResponseEntity<?> checkHashes(@RequestBody Map<String, List<String>> body) {
        UUID orgId = TenantContext.getOrganizationId();
        List<String> sha256List = body.getOrDefault("sha256List", List.of());

        if (sha256List.isEmpty()) {
            return ResponseEntity.ok(Map.of("existingHashes", List.of()));
        }

        List<String> existing = resumeFileRepo.findExistingHashes(orgId, sha256List);
        return ResponseEntity.ok(Map.of("existingHashes", existing));
    }

    /**
     * Chrome Extension V2 intake. Accepts multipart with file + metadata.
     * Returns 202 Accepted immediately (< 50ms target).
     */
    @PostMapping("/extension-intake")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER')")
    public ResponseEntity<?> extensionIntake(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "senderEmail", required = false) String senderEmail,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "sha256", required = false) String sha256,
            @RequestParam(value = "sourceType", defaultValue = "EXT_GMAIL") String sourceType,
            @RequestParam(value = "jobId", required = false) UUID jobId
    ) {
        UUID orgId = TenantContext.getOrganizationId();
        try {
            byte[] data = file.getBytes();
            UUID attachmentId = ingestionService.ingestFromExtension(
                    orgId, data, file.getOriginalFilename(),
                    senderEmail, subject, sha256, sourceType, jobId);

            return ResponseEntity.accepted().body(Map.of(
                    "status", "QUEUED",
                    "attachmentId", attachmentId
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Extension intake error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Processing failed"));
        }
    }
}

