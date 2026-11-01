package com.kartgen.hireflow.jobs;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.matching.JobMatch;
import com.kartgen.hireflow.matching.JobMatchRepository;
import com.kartgen.hireflow.matching.MatchService;
import com.kartgen.hireflow.ops.ProcessingJob;
import com.kartgen.hireflow.ops.ProcessingJobRepository;
import com.kartgen.hireflow.profile.Skill;
import com.kartgen.hireflow.profile.SkillRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepo;
    private final JobVersionRepository jobVersionRepo;
    private final JobSkillRequirementRepository skillReqRepo;
    private final JobMatchRepository matchRepo;
    private final SkillRepository skillRepo;
    private final JDAnalyzer jdAnalyzer;
    private final ProcessingJobRepository processingJobRepo;

    public record CreateJobRequest(
            @NotBlank String title,
            String department,
            String location,
            @JsonAlias({"rawJdText", "jd", "description"}) String jdText,
            Integer minExperienceMonths,
            Integer maxExperienceMonths
    ) {}

    public record CreateJobVersionRequest(
            @NotBlank String jdText,
            Integer minExperienceMonths,
            Integer maxExperienceMonths
    ) {}

    @Transactional
    public Job createJob(UUID orgId, CreateJobRequest req) {
        Job job = Job.builder()
                .organizationId(orgId)
                .title(req.title().trim())
                .department(req.department())
                .location(req.location())
                .status("DRAFT")
                .createdBy(TenantContext.getUserId())
                .build();
        jobRepo.save(job);

        if (req.jdText() != null && !req.jdText().isBlank()) {
            createVersionInternal(orgId, job, req.jdText(), req.minExperienceMonths(),
                    req.maxExperienceMonths());
        }

        log.info("Created job id={} title='{}' org={}", job.getId(), job.getTitle(), orgId);
        return job;
    }

    @Transactional
    public JobVersion createJobVersion(UUID orgId, UUID jobId, CreateJobVersionRequest req) {
        Job job = jobRepo.findByIdAndOrganizationId(jobId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        return createVersionInternal(orgId, job, req.jdText(), req.minExperienceMonths(),
                req.maxExperienceMonths());
    }

    private JobVersion createVersionInternal(UUID orgId, Job job, String jdText,
                                              Integer minExpOverride, Integer maxExpOverride) {
        int nextVersion = jobVersionRepo.findMaxVersionNumber(job.getId()) + 1;
        String jdHash = HashUtil.sha256Hex(jdText.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<Skill> allSkills = skillRepo.findByActiveTrue();
        JDAnalyzer.AnalyzedJD analyzed = jdAnalyzer.analyze(jdText, allSkills);

        Integer minExp = minExpOverride != null ? minExpOverride : analyzed.minExperienceMonths();
        Integer maxExp = maxExpOverride != null ? maxExpOverride : analyzed.maxExperienceMonths();
        if (minExp != null && maxExp != null && minExp > maxExp) {
            throw new IllegalArgumentException("minExperienceMonths cannot be greater than maxExperienceMonths");
        }

        JobVersion jv = JobVersion.builder()
                .organizationId(orgId)
                .jobId(job.getId())
                .versionNumber(nextVersion)
                .jdText(jdText)
                .jdHash(jdHash)
                .minExperienceMonths(minExp)
                .maxExperienceMonths(maxExp)
                .build();
        jobVersionRepo.save(jv);

        analyzed.mustSkills().forEach(skillId -> skillReqRepo.save(
                JobSkillRequirement.builder()
                        .jobVersionId(jv.getId()).skillId(skillId).priority("MUST").build()));
        analyzed.preferredSkills().forEach(skillId -> skillReqRepo.save(
                JobSkillRequirement.builder()
                        .jobVersionId(jv.getId()).skillId(skillId).priority("PREFERRED").build()));

        job.setCurrentVersionId(jv.getId());
        job.setStatus("OPEN");
        jobRepo.save(job);

        enqueueRecompute(orgId, jv.getId());
        log.info("Created job version {} for job={}", nextVersion, job.getId());
        return jv;
    }

    public Job getJob(UUID orgId, UUID jobId) {
        return jobRepo.findByIdAndOrganizationId(jobId, orgId)
                .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId));
    }

    public List<Job> listJobs(UUID orgId) {
        return jobRepo.findByOrganizationId(orgId);
    }

    public Page<JobMatch> getJobMatches(UUID orgId, UUID jobId, Pageable pageable) {
        Job job = getJob(orgId, jobId);
        if (job.getCurrentVersionId() == null) return Page.empty(pageable);
        return matchRepo.findByJobVersionIdOrderByScoreDesc(job.getCurrentVersionId(), pageable);
    }

    @Transactional
    public Job updateStatus(UUID orgId, UUID jobId, String status) {
        Job job = getJob(orgId, jobId);
        if (!List.of("DRAFT", "OPEN", "CLOSED").contains(status)) {
            throw new IllegalArgumentException("Invalid job status: " + status);
        }
        job.setStatus(status);
        return jobRepo.save(job);
    }

    private void enqueueRecompute(UUID orgId, UUID jobVersionId) {
        processingJobRepo.save(ProcessingJob.builder()
                .organizationId(orgId)
                .jobType("RECOMPUTE_MATCHES")
                .referenceId(jobVersionId)
                .status("PENDING")
                .build());
    }
}
