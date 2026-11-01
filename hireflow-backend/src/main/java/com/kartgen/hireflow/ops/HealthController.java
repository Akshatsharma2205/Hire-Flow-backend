package com.kartgen.hireflow.ops;

import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.document.ResumeFileRepository;
import com.kartgen.hireflow.identity.CandidateRepository;
import com.kartgen.hireflow.mail.model.MailConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Operational health & metrics endpoint for the test console KPI bar. Every
 * metric is scoped to the caller's organization.
 */
@RestController
@RequestMapping("/api/v1/operations")
@RequiredArgsConstructor
public class HealthController {

    private final ProcessingJobRepository processingJobRepo;
    private final ResumeFileRepository resumeFileRepo;
    private final CandidateRepository candidateRepo;
    private final MailConnectionRepository mailConnectionRepo;
    private final AiUsageEventRepository aiUsageRepo;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        UUID orgId = TenantContext.getOrganizationId();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("queueDepth", processingJobRepo.countByOrganizationIdAndStatus(orgId, "PENDING"));
        metrics.put("runningCount", processingJobRepo.countByOrganizationIdAndStatus(orgId, "RUNNING"));
        metrics.put("processedCount", processingJobRepo.countByOrganizationIdAndStatus(orgId, "COMPLETED"));
        metrics.put("failedCount", processingJobRepo.countByOrganizationIdAndStatus(orgId, "FAILED"));
        metrics.put("totalResumeFiles", resumeFileRepo.countByOrganizationId(orgId));
        metrics.put("totalCandidates", candidateRepo.countByOrganizationId(orgId));
        metrics.put("activeMailConnections",
                mailConnectionRepo.countByOrganizationIdAndStatus(orgId, "ACTIVE"));
        metrics.put("aiTokenSpend", aiUsageRepo.totalTokensByOrg(orgId));

        return ResponseEntity.ok(metrics);
    }
}
