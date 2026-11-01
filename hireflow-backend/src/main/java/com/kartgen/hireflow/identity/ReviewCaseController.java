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
                                Instant createdAt) {}

    @GetMapping
    public ResponseEntity<?> list() {
        UUID orgId = TenantContext.getOrganizationId();
        List<ReviewCaseDto> dtos = reviewCaseRepo.findByOrganizationIdAndResolutionIsNull(orgId)
                .stream().map(this::toDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','HIRING_MANAGER','MANAGER')")
    public ResponseEntity<?> resolve(@PathVariable UUID id,
                                     @RequestBody Map<String, String> body) {
        UUID orgId = TenantContext.getOrganizationId();
        IdentityReviewCase rc = reviewCaseRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Review case not found: " + id));

        String resolution = body.getOrDefault("resolution", "DISMISSED").trim().toUpperCase();
        if (resolution.isBlank() || resolution.length() > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid resolution"));
        }
        if (rc.getResolution() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Review case is already resolved"));
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
                rc.getReasonCode(), rc.getDetails(), rc.getResolution(), rc.getCreatedAt());
    }
}
