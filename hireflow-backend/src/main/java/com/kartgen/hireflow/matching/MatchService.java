package com.kartgen.hireflow.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.CandidateRepository;
import com.kartgen.hireflow.jobs.JobSkillRequirement;
import com.kartgen.hireflow.jobs.JobSkillRequirementRepository;
import com.kartgen.hireflow.jobs.JobVersion;
import com.kartgen.hireflow.jobs.JobVersionRepository;
import com.kartgen.hireflow.profile.CandidateProfileVersion;
import com.kartgen.hireflow.profile.CandidateProfileVersionRepository;
import com.kartgen.hireflow.profile.CandidateSkill;
import com.kartgen.hireflow.profile.CandidateSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    private final JobMatchRepository matchRepo;
    private final JobSkillRequirementRepository skillReqRepo;
    private final CandidateSkillRepository candidateSkillRepo;
    private final CandidateRepository candidateRepo;
    private final CandidateProfileVersionRepository profileVersionRepo;
    private final JobVersionRepository jobVersionRepo;
    private final ObjectMapper objectMapper;

    private static final String SCORER_VERSION = "v2";

    @Transactional
    public JobMatch computeMatch(UUID orgId, JobVersion jv, Candidate candidate,
                                 CandidateProfileVersion profile) {
        return computeMatchInternal(orgId, jv, candidate, profile, skillReqRepo.findByJobVersionId(jv.getId()));
    }

    private JobMatch computeMatchInternal(UUID orgId, JobVersion jv, Candidate candidate,
                                          CandidateProfileVersion profile,
                                          List<JobSkillRequirement> requirements) {
        if (!orgId.equals(jv.getOrganizationId())) {
            throw new IllegalArgumentException("Job version does not belong to this organization");
        }

        List<CandidateSkill> candidateSkills = candidateSkillRepo
                .findByCandidateIdAndProfileVersionId(candidate.getId(), profile.getId());
        Set<UUID> candidateSkillIds = candidateSkills.stream()
                .map(CandidateSkill::getSkillId).collect(Collectors.toSet());

        List<JobSkillRequirement> must = requirements.stream()
                .filter(r -> "MUST".equals(r.getPriority())).toList();
        List<JobSkillRequirement> preferred = requirements.stream()
                .filter(r -> "PREFERRED".equals(r.getPriority())).toList();

        WeightedCoverage mustCoverage = coverage(must, candidateSkillIds);
        WeightedCoverage preferredCoverage = coverage(preferred, candidateSkillIds);

        double mustMax = 60.0;
        double prefMax = 20.0;
        if (mustCoverage.totalWeight() > 0 && preferredCoverage.totalWeight() == 0) {
            mustMax = 80.0;
            prefMax = 0.0;
        } else if (mustCoverage.totalWeight() == 0 && preferredCoverage.totalWeight() > 0) {
            mustMax = 0.0;
            prefMax = 80.0;
        } else if (mustCoverage.totalWeight() == 0 && preferredCoverage.totalWeight() == 0) {
            mustMax = 0.0;
            prefMax = 0.0;
        }

        double mustScore = mustCoverage.totalWeight() == 0 ? 0
                : mustCoverage.matchedWeight() / mustCoverage.totalWeight() * mustMax;
        double preferredScore = preferredCoverage.totalWeight() == 0 ? 0
                : preferredCoverage.matchedWeight() / preferredCoverage.totalWeight() * prefMax;

        ExperienceFit exp = experienceFit(candidate.getCurrentExperienceMonths(), jv);
        double expScore = (mustMax == 0 && prefMax == 0) ? (exp.score() * 5.0) : exp.score();
        double totalScore = Math.max(0, Math.min(100.0, mustScore + preferredScore + expScore));

        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("mustSkillsMatched", mustCoverage.matchedSkillIds().size());
        explanation.put("mustSkillsTotal", must.size());
        explanation.put("preferredSkillsMatched", preferredCoverage.matchedSkillIds().size());
        explanation.put("preferredSkillsTotal", preferred.size());
        explanation.put("matchedMustSkillIds", mustCoverage.matchedSkillIds());
        explanation.put("missingMustSkillIds", mustCoverage.missingSkillIds());
        explanation.put("experienceFit", exp.label());
        explanation.put("candidateExpMonths", candidate.getCurrentExperienceMonths());
        explanation.put("minExpMonths", jv.getMinExperienceMonths());
        explanation.put("maxExpMonths", jv.getMaxExperienceMonths());
        explanation.put("mustScore", round(mustScore));
        explanation.put("preferredScore", round(preferredScore));
        explanation.put("expScore", round(exp.score()));

        JobMatch match = matchRepo
                .findByJobVersionIdAndCandidateIdAndScorerVersion(jv.getId(), candidate.getId(), SCORER_VERSION)
                .orElseGet(() -> JobMatch.builder()
                        .organizationId(orgId)
                        .jobVersionId(jv.getId())
                        .candidateId(candidate.getId())
                        .scorerVersion(SCORER_VERSION)
                        .build());

        match.setProfileVersionId(profile.getId());
        match.setScore(BigDecimal.valueOf(totalScore).setScale(3, RoundingMode.HALF_UP));
        match.setExplanationJson(serializeSafe(explanation));
        match.setStatus("READY");
        match.setComputedAt(Instant.now());
        matchRepo.save(match);

        log.debug("Computed match score={} candidate={} jobVersion={}", totalScore, candidate.getId(), jv.getId());
        return match;
    }

    @Transactional
    public void recomputeAllMatches(UUID orgId, UUID jobVersionId) {
        JobVersion jv = jobVersionRepo.findById(jobVersionId)
                .filter(v -> orgId.equals(v.getOrganizationId()))
                .orElseThrow(() -> new IllegalArgumentException("Job version not found: " + jobVersionId));

        matchRepo.deleteByJobVersionId(jv.getId());
        matchRepo.flush();

        List<Candidate> candidates = candidateRepo.findByOrganizationIdAndStatus(orgId, "ACTIVE");
        List<JobSkillRequirement> requirements = skillReqRepo.findByJobVersionId(jv.getId());
        int computed = 0;
        int skipped = 0;
        for (Candidate candidate : candidates) {
            CandidateProfileVersion profile = candidate.getCurrentProfileVersionId() != null
                    ? profileVersionRepo.findById(candidate.getCurrentProfileVersionId()).orElse(null)
                    : profileVersionRepo.findTopByCandidateIdOrderByProfileVersionDesc(candidate.getId()).orElse(null);
            if (profile == null) {
                skipped++;
                continue;
            }
            try {
                computeMatchInternal(orgId, jv, candidate, profile, requirements);
                computed++;
            } catch (Exception e) {
                skipped++;
                log.warn("Match computation failed for candidate={} jobVersion={}: {}",
                        candidate.getId(), jobVersionId, e.getMessage());
            }
        }
        log.info("Recomputed {} matches for jobVersion={} ({} skipped)", computed, jobVersionId, skipped);
    }

    private record WeightedCoverage(double matchedWeight, double totalWeight,
                                    List<UUID> matchedSkillIds, List<UUID> missingSkillIds) {}

    private WeightedCoverage coverage(List<JobSkillRequirement> requirements, Set<UUID> candidateSkillIds) {
        double total = 0;
        double matched = 0;
        List<UUID> matchedIds = new ArrayList<>();
        List<UUID> missingIds = new ArrayList<>();
        for (JobSkillRequirement req : requirements) {
            double weight = req.getWeight() != null ? req.getWeight().doubleValue() : 1.0;
            total += weight;
            if (candidateSkillIds.contains(req.getSkillId())) {
                matched += weight;
                matchedIds.add(req.getSkillId());
            } else {
                missingIds.add(req.getSkillId());
            }
        }
        return new WeightedCoverage(matched, total, matchedIds, missingIds);
    }

    private record ExperienceFit(double score, String label) {}

    private ExperienceFit experienceFit(Integer candidateExp, JobVersion jv) {
        if (candidateExp == null || jv.getMinExperienceMonths() == null) {
            return new ExperienceFit(0, "UNKNOWN");
        }
        int min = jv.getMinExperienceMonths();
        int max = jv.getMaxExperienceMonths() != null ? jv.getMaxExperienceMonths() : Integer.MAX_VALUE;
        if (candidateExp >= min && candidateExp <= max) {
            return new ExperienceFit(20, "IN_RANGE");
        }
        if (candidateExp > max) {
            return new ExperienceFit(15, "OVER");
        }
        if (min <= 0) {
            return new ExperienceFit(20, "IN_RANGE");
        }
        double ratio = Math.min(1.0, (double) candidateExp / min);
        return new ExperienceFit(ratio * 20.0, "UNDER");
    }

    private double round(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String serializeSafe(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
