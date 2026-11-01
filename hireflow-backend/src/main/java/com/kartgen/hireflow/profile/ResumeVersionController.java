package com.kartgen.hireflow.profile;

import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.document.ResumeFile;
import com.kartgen.hireflow.document.ResumeFileRepository;
import com.kartgen.hireflow.document.StoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Generates short-lived view URLs for resume file versions.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/resume-versions")
@RequiredArgsConstructor
public class ResumeVersionController {

    private final ResumeVersionRepository resumeVersionRepo;
    private final ResumeFileRepository resumeFileRepo;
    private final StoragePort storagePort;

    /**
     * Returns a short-lived view URL for the resume PDF/DOCX linked to this version.
     */
    @GetMapping("/{id}/view-url")
    public ResponseEntity<?> viewUrl(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        ResumeVersion rv = resumeVersionRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Resume version not found: " + id));

        ResumeFile rf = resumeFileRepo.findByIdAndOrganizationId(rv.getResumeFileId(), orgId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Resume file not found: " + rv.getResumeFileId()));

        String fileName = "resume_v" + rv.getVersionNumber()
                + (rf.getMimeType() != null && rf.getMimeType().contains("pdf") ? ".pdf" : ".docx");
        String url = storagePort.generateViewUrl(rf.getS3ObjectKey(), fileName);

        return ResponseEntity.ok(Map.of("url", url));
    }
}
