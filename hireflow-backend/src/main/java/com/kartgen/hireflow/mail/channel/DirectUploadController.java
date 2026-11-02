package com.kartgen.hireflow.mail.channel;

import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.mail.service.MessageIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class DirectUploadController {

    private final MessageIngestionService ingestionService;

    /**
     * Channel 5: Direct & Bulk PDF/DOCX/ZIP upload for HR drag-and-drop.
     */
    @PostMapping("/direct")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER')")
    public ResponseEntity<?> directUpload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sourceType", defaultValue = "UPLOAD") String sourceType,
            @RequestParam(value = "jobId", required = false) UUID jobId
    ) {
        UUID orgId = TenantContext.getOrganizationId();
        for (MultipartFile file : files) {
            if (file.getSize() > 25L * 1024 * 1024) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)
                        .body(Map.of("error", "File exceeds maximum 25MB limit: " + file.getOriginalFilename()));
            }
        }
        try {
            List<UUID> accepted = ingestionService.ingestDirectUpload(orgId, files, sourceType, jobId);
            return ResponseEntity.accepted().body(Map.of(
                    "status", "QUEUED",
                    "acceptedCount", accepted.size(),
                    "attachmentIds", accepted
            ));
        } catch (Exception e) {
            log.error("Direct upload error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Upload processing failed"));
        }
    }
}

