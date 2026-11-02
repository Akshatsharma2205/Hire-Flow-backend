package com.kartgen.hireflow.identity;

import com.kartgen.hireflow.common.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/review-cases")
@RequiredArgsConstructor
public class ReviewCaseController {

    private final IdentityReviewCaseRepository reviewCaseRepo;

    public record ReviewCaseDto(UUID id, UUID candidateId, UUID conflictingCandidateId,
                                String reasonCode, String details, String resolution,
                                UUID messageId, UUID attachmentId, UUID suggestedJobId,
                                Instant createdAt) {}

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "reasonCode", required = false) String reasonCode) {
        UUID orgId = TenantContext.getOrganizationId();
        List<IdentityReviewCase> cases;
        if ("RESOLVED".equalsIgnoreCase(status)) {
            cases = reviewCaseRepo.findAll().stream()
                    .filter(c -> orgId.equals(c.getOrganizationId()) && c.getResolution() != null)
                    .toList();
        } else if ("ALL".equalsIgnoreCase(status)) {
            cases = reviewCaseRepo.findAll().stream()
                    .filter(c -> orgId.equals(c.getOrganizationId()))
                    .toList();
        } else {
            cases = reviewCaseRepo.findByOrganizationIdAndResolutionIsNull(orgId);
        }

        if (reasonCode != null && !reasonCode.isBlank()) {
            cases = cases.stream()
                    .filter(c -> reasonCode.equalsIgnoreCase(c.getReasonCode()))
                    .toList();
        }

        List<ReviewCaseDto> dtos = cases.stream().map(this::toDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','HIRING_MANAGER','MANAGER')")
    public ResponseEntity<?> getById(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        IdentityReviewCase rc = reviewCaseRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Review case not found: " + id));
        return ResponseEntity.ok(toDto(rc));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','HIRING_MANAGER','MANAGER')")
    public ResponseEntity<?> resolve(@PathVariable UUID id,
                                     @RequestBody Map<String, String> body) {
        UUID orgId = TenantContext.getOrganizationId();
        IdentityReviewCase rc = reviewCaseRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Review case not found: " + id));

        String resolution = body.get("resolution");
        if (resolution == null || resolution.isBlank()) {
            resolution = body.get("action");
        }
        if (resolution == null || resolution.isBlank()) {
            resolution = "DISMISSED";
        }
        resolution = resolution.trim().toUpperCase();

        if (resolution.length() > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid resolution"));
        }
        if (rc.getResolution() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Review case is already resolved"));
        }

        String notes = body.get("notes");
        if (notes != null && !notes.isBlank()) {
            rc.setDetails((rc.getDetails() != null ? rc.getDetails() + " | Notes: " : "Notes: ") + notes);
        }

        rc.setResolution(resolution);
        rc.setResolvedBy(TenantContext.getUserId());
        rc.setResolvedAt(Instant.now());
        reviewCaseRepo.save(rc);

        log.info("Review case {} resolved as {} by user={}", id, resolution, rc.getResolvedBy());
        return ResponseEntity.ok(Map.of("status", "RESOLVED", "resolution", resolution));
    }

    private ReviewCaseDto toDto(IdentityReviewCase rc) {
        return new ReviewCaseDto(rc.getId(), rc.getCandidateId(), rc.getConflictingCandidateId(),
                rc.getReasonCode(), rc.getDetails(), rc.getResolution(),
                rc.getMessageId(), rc.getAttachmentId(), rc.getSuggestedJobId(),
                rc.getCreatedAt());
    }
}
