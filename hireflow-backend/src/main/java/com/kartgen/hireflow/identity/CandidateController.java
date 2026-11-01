package com.kartgen.hireflow.identity;

import com.kartgen.hireflow.common.PageResponse;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.hiring.Application;
import com.kartgen.hireflow.hiring.ApplicationRepository;
import com.kartgen.hireflow.jobs.Job;
import com.kartgen.hireflow.jobs.JobRepository;
import com.kartgen.hireflow.mail.model.InboundAttachmentRepository;
import com.kartgen.hireflow.mail.model.InboundMessage;
import com.kartgen.hireflow.mail.model.InboundMessageRepository;
import com.kartgen.hireflow.matching.JobMatch;
import com.kartgen.hireflow.matching.JobMatchRepository;
import com.kartgen.hireflow.profile.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/candidates")
@RequiredArgsConstructor
public class CandidateController {

    private final CandidateRepository candidateRepo;
    private final CandidateSkillRepository skillRepo;
    private final SkillRepository masterSkillRepo;
    private final ResumeVersionRepository resumeVersionRepo;
    private final CandidateProfileVersionRepository profileVersionRepo;
    private final ApplicationRepository applicationRepo;
    private final JobMatchRepository matchRepo;
    private final JobRepository jobRepo;
    private final InboundMessageRepository messageRepo;
    private final InboundAttachmentRepository attachmentRepo;

    public record CandidateListDto(
            UUID id, String displayName, String primaryEmail, Integer currentExperienceMonths,
            String experienceConfidence, String currentCity, List<String> skills,
            BigDecimal matchScore, Integer resumeVersion, String sourceType,
            String currentStage, Instant lastApplicationAt
    ) {}

    public record CandidateDetailDto(
            UUID id, String displayName, String primaryEmail, String primaryPhone,
            String linkedinUrl, Integer currentExperienceMonths, String experienceConfidence,
            String currentCity, List<SkillDto> skills, String educationJson, String sourceEvidence,
            int resumeVersionCount, int applicationCount, Instant profileUpdatedAt
    ) {}

    public record SkillDto(String name, String category, BigDecimal confidence) {}

    public record ResumeVersionDto(
            UUID id, int versionNumber, Instant receivedAt, String parseStatus,
            String validationStatus, String parserVersion, String viewUrl
    ) {}

    public record ApplicationDto(
            UUID id, String jobTitle, String currentStage, String status,
            int attemptNumber, Instant createdAt, Instant updatedAt
    ) {}

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID jobId,
            @RequestParam(required = false) Integer minExpMonths,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) List<String> skills
    ) {
        UUID orgId = TenantContext.getOrganizationId();
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));

        UUID jobVersionId = null;
        Map<UUID, BigDecimal> matchScores = new HashMap<>();
        if (jobId != null) {
            Job job = jobRepo.findByIdAndOrganizationId(jobId, orgId).orElse(null);
            if (job == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Job not found for this organization"));
            }
            jobVersionId = job.getCurrentVersionId();
            if (jobVersionId != null) {
                matchRepo.findByJobVersionIdOrderByScoreDesc(jobVersionId, Pageable.unpaged())
                        .forEach(m -> matchScores.put(m.getCandidateId(), m.getScore()));
            }
        }

        List<String> requestedSkills = new ArrayList<>();
        if (skills != null) requestedSkills.addAll(skills);
        if (skill != null && !skill.isBlank()) {
            Arrays.stream(skill.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .forEach(requestedSkills::add);
        }
        List<UUID> skillIds = resolveSkillIds(requestedSkills);

        String cleanSearch = null;
        if (search != null && !search.isBlank()) {
            try {
                cleanSearch = java.net.URLDecoder.decode(search, java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                cleanSearch = search.trim();
            }
        }

        Page<Candidate> candidates = candidateRepo.search(
                orgId,
                cleanSearch,
                jobVersionId, minExpMonths,
                location != null && !location.isBlank() ? location.trim() : null,
                sourceType != null && !sourceType.isBlank() ? sourceType.trim() : null,
                skillIds.isEmpty() ? List.of(new UUID(0, 0)) : skillIds,
                skillIds.size(),
                pageable);

        Map<UUID, Skill> masterSkills = masterSkillRepo.findByActiveTrue().stream()
                .collect(Collectors.toMap(Skill::getId, s -> s));

        Set<UUID> rvIds = candidates.getContent().stream()
                .map(Candidate::getCurrentResumeVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ResumeVersion> rvById = rvIds.isEmpty() ? Map.of()
                : resumeVersionRepo.findByOrganizationIdAndIdIn(orgId, rvIds).stream()
                        .collect(Collectors.toMap(ResumeVersion::getId, rv -> rv));

        Map<UUID, String> sourceTypes = resolveSourceTypes(orgId, candidates.getContent(), rvById);
        Set<UUID> candidateIds = candidates.getContent().stream().map(Candidate::getId).collect(Collectors.toSet());
        Map<UUID, Application> latestApplications = latestApplications(orgId, candidateIds);

        Set<UUID> profileVersionIds = candidates.getContent().stream()
                .map(Candidate::getCurrentProfileVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, List<CandidateSkill>> skillsByProfile = profileVersionIds.isEmpty() ? Map.of()
                : skillRepo.findByProfileVersionIdIn(profileVersionIds).stream()
                        .collect(Collectors.groupingBy(CandidateSkill::getProfileVersionId));

        Page<CandidateListDto> dtoPage = candidates.map(c -> {
            List<CandidateSkill> pSkills = c.getCurrentProfileVersionId() != null
                    ? skillsByProfile.getOrDefault(c.getCurrentProfileVersionId(), List.of())
                    : List.of();
            List<String> labelSkills = pSkills.stream()
                    .map(cs -> masterSkills.get(cs.getSkillId()))
                    .filter(Objects::nonNull)
                    .map(Skill::getCanonicalName)
                    .sorted()
                    .toList();

            ResumeVersion rv = c.getCurrentResumeVersionId() != null ? rvById.get(c.getCurrentResumeVersionId()) : null;
            Integer latestVersion = rv != null ? rv.getVersionNumber() : null;

            Application app = latestApplications.get(c.getId());
            return new CandidateListDto(
                    c.getId(), c.getDisplayName(), c.getPrimaryEmail(),
                    c.getCurrentExperienceMonths(), c.getExperienceConfidence(),
                    c.getCurrentCity(), labelSkills,
                    matchScores.get(c.getId()), latestVersion,
                    sourceTypes.get(c.getId()),
                    app != null ? app.getCurrentStage() : null,
                    c.getLastApplicationAt()
            );
        });

        return ResponseEntity.ok(PageResponse.of(dtoPage));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        Candidate c = candidateRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Candidate not found: " + id));

        Map<UUID, Skill> masterSkills = masterSkillRepo.findByActiveTrue().stream()
                .collect(Collectors.toMap(Skill::getId, s -> s));

        List<SkillDto> skillDtos = skillsForProfile(id, c.getCurrentProfileVersionId(), masterSkills);

        String educationJson = null;
        String evidence = null;
        if (c.getCurrentProfileVersionId() != null) {
            var pv = profileVersionRepo.findByIdAndOrganizationId(c.getCurrentProfileVersionId(), orgId)
                    .orElse(null);
            if (pv != null) {
                educationJson = pv.getEducationJson();
                evidence = pv.getSourceEvidenceJson();
            }
        }

        int rvCount = resumeVersionRepo.findByCandidateIdOrderByVersionNumberDesc(id).size();
        int appCount = applicationRepo.findByOrganizationIdAndCandidateId(orgId, id).size();

        return ResponseEntity.ok(new CandidateDetailDto(
                c.getId(), c.getDisplayName(), c.getPrimaryEmail(), c.getPrimaryPhone(),
                c.getLinkedinUrl(), c.getCurrentExperienceMonths(), c.getExperienceConfidence(),
                c.getCurrentCity(), skillDtos, educationJson, evidence, rvCount, appCount,
                c.getProfileUpdatedAt()
        ));
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<?> versions(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        candidateRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Candidate not found: " + id));

        List<ResumeVersionDto> dtos = resumeVersionRepo.findByCandidateIdOrderByVersionNumberDesc(id)
                .stream().map(rv -> new ResumeVersionDto(
                        rv.getId(), rv.getVersionNumber(), rv.getReceivedAt(),
                        rv.getParseStatus(), rv.getValidationStatus(), rv.getParserVersion(),
                        null
                )).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}/applications")
    public ResponseEntity<?> applications(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        candidateRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Candidate not found: " + id));

        List<ApplicationDto> dtos = applicationRepo.findByOrganizationIdAndCandidateId(orgId, id).stream()
                .map(a -> {
                    String jobTitle = jobRepo.findByIdAndOrganizationId(a.getJobId(), orgId)
                            .map(Job::getTitle).orElse("Unknown");
                    return new ApplicationDto(
                            a.getId(), jobTitle, a.getCurrentStage(), a.getStatus(),
                            a.getAttemptNumber(), a.getCreatedAt(), a.getUpdatedAt());
                }).toList();
        return ResponseEntity.ok(dtos);
    }

    private List<UUID> resolveSkillIds(List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        Map<String, UUID> canonical = masterSkillRepo.findByActiveTrue().stream()
                .collect(Collectors.toMap(s -> s.getCanonicalName().toLowerCase(Locale.ROOT), Skill::getId));
        return names.stream()
                .map(n -> canonical.get(n.trim().toLowerCase(Locale.ROOT)))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<SkillDto> skillsForProfile(UUID candidateId, UUID profileVersionId,
                                            Map<UUID, Skill> masterSkills) {
        if (profileVersionId == null) return List.of();
        return skillRepo.findByCandidateIdAndProfileVersionId(candidateId, profileVersionId).stream()
                .map(cs -> {
                    Skill s = masterSkills.get(cs.getSkillId());
                    return new SkillDto(s != null ? s.getCanonicalName() : "Unknown",
                            s != null ? s.getCategory() : null, cs.getConfidence());
                })
                .sorted(Comparator.comparing(SkillDto::name))
                .toList();
    }

    /**
     * Resolves the real intake channel for each candidate's current resume by
     * walking resume_version → inbound_attachment → inbound_message in batched queries.
     * Every hop is tenant-scoped so a crafted attachment id cannot leak another org's data.
     */
    private Map<UUID, String> resolveSourceTypes(UUID orgId, List<Candidate> candidates, Map<UUID, ResumeVersion> rvById) {
        if (candidates == null || candidates.isEmpty() || rvById == null || rvById.isEmpty()) return Map.of();

        Map<UUID, ResumeVersion> candidateToRv = new HashMap<>();
        for (Candidate c : candidates) {
            if (c.getCurrentResumeVersionId() != null) {
                ResumeVersion rv = rvById.get(c.getCurrentResumeVersionId());
                if (rv != null) candidateToRv.put(c.getId(), rv);
            }
        }

        Set<UUID> attachmentIds = candidateToRv.values().stream()
                .map(ResumeVersion::getSourceAttachmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (attachmentIds.isEmpty()) return Map.of();

        Map<UUID, com.kartgen.hireflow.mail.model.InboundAttachment> attachmentById =
                attachmentRepo.findByOrganizationIdAndIdIn(orgId, attachmentIds).stream()
                        .collect(Collectors.toMap(com.kartgen.hireflow.mail.model.InboundAttachment::getId, a -> a));

        Set<UUID> messageIds = attachmentById.values().stream()
                .map(com.kartgen.hireflow.mail.model.InboundAttachment::getMessageId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (messageIds.isEmpty()) return Map.of();

        Map<UUID, InboundMessage> messageById = messageRepo.findByOrganizationIdAndIdIn(orgId, messageIds).stream()
                .collect(Collectors.toMap(InboundMessage::getId, m -> m));

        Map<UUID, String> result = new HashMap<>();
        for (Candidate c : candidates) {
            ResumeVersion rv = candidateToRv.get(c.getId());
            if (rv != null && rv.getSourceAttachmentId() != null) {
                var att = attachmentById.get(rv.getSourceAttachmentId());
                if (att != null && att.getMessageId() != null) {
                    var msg = messageById.get(att.getMessageId());
                    if (msg != null && msg.getSourceType() != null) {
                        result.put(c.getId(), msg.getSourceType());
                    }
                }
            }
        }
        return result;
    }

    private Map<UUID, Application> latestApplications(UUID orgId, Set<UUID> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) return Map.of();
        List<Application> apps = applicationRepo.findByOrganizationIdAndCandidateIdIn(orgId, candidateIds);
        Map<UUID, Application> result = new HashMap<>();
        for (Application app : apps) {
            result.compute(app.getCandidateId(), (k, existing) -> {
                if (existing == null) return app;
                return app.getCreatedAt().isAfter(existing.getCreatedAt()) ? app : existing;
            });
        }
        return result;
    }
}
