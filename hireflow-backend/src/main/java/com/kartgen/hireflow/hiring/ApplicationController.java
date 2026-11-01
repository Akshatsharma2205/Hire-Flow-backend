package com.kartgen.hireflow.hiring;

import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.CandidateRepository;
import com.kartgen.hireflow.jobs.Job;
import com.kartgen.hireflow.jobs.JobRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;
    private final ApplicationRepository applicationRepo;
    private final ApplicationStageHistoryRepository historyRepo;
    private final CandidateRepository candidateRepo;
    private final JobRepository jobRepo;

    public record CreateApplicationRequest(
            @NotNull UUID candidateId,
            @NotNull UUID jobId,
            UUID resumeVersionId,
            String sourceType
    ) {}

    public record StageUpdateRequest(@NotBlank String stage, String note) {}

    public record ApplicationDto(
            UUID id, UUID candidateId, String candidateName, UUID jobId, String jobTitle,
            int attemptNumber, String currentStage, String status,
            Instant createdAt, Instant updatedAt
    ) {}

    public record StageHistoryDto(
            UUID id, String fromStage, String toStage, UUID changedBy, Instant changedAt, String note
    ) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','MANAGER')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateApplicationRequest req) {
        UUID orgId = TenantContext.getOrganizationId();
        Application application = applicationService.applyToJob(
                orgId, req.candidateId(), req.jobId(), req.resumeVersionId(),
                req.sourceType() != null ? req.sourceType() : "MANUAL");
        return ResponseEntity.ok(toDto(orgId, application));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) UUID jobId,
                                  @RequestParam(required = false) UUID candidateId) {
        UUID orgId = TenantContext.getOrganizationId();
        List<ApplicationDto> dtos = applicationService.listForOrg(orgId, jobId, candidateId)
                .stream().map(a -> toDto(orgId, a)).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        Application application = applicationRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Application not found: " + id));
        return ResponseEntity.ok(toDto(orgId, application));
    }

    @PatchMapping("/{id}/stage")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','MANAGER')")
    public ResponseEntity<?> updateStage(@PathVariable UUID id,
                                         @Valid @RequestBody StageUpdateRequest req) {
        UUID orgId = TenantContext.getOrganizationId();
        Application updated = applicationService.updateStage(
                orgId, id, req.stage(), TenantContext.getUserId(), req.note());
        return ResponseEntity.ok(toDto(orgId, updated));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> history(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        applicationRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Application not found: " + id));

        List<StageHistoryDto> history = historyRepo.findByApplicationIdOrderByChangedAtDesc(id)
                .stream().map(h -> new StageHistoryDto(
                        h.getId(), h.getFromStage(), h.getToStage(),
                        h.getChangedBy(), h.getChangedAt(), h.getNote()))
                .toList();
        return ResponseEntity.ok(history);
    }

    @GetMapping("/stages")
    public ResponseEntity<?> stages() {
        return ResponseEntity.ok(Map.of("stages", ApplicationService.STAGES));
    }

    private ApplicationDto toDto(UUID orgId, Application application) {
        String candidateName = candidateRepo.findByIdAndOrganizationId(
                        application.getCandidateId(), orgId)
                .map(Candidate::getDisplayName).orElse("Unknown");
        String jobTitle = jobRepo.findByIdAndOrganizationId(application.getJobId(), orgId)
                .map(Job::getTitle).orElse("Unknown");
        return new ApplicationDto(
                application.getId(), application.getCandidateId(), candidateName,
                application.getJobId(), jobTitle, application.getAttemptNumber(),
                application.getCurrentStage(), application.getStatus(),
                application.getCreatedAt(), application.getUpdatedAt());
    }
}
