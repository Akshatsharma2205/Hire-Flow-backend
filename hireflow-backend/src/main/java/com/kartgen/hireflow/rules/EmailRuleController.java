package com.kartgen.hireflow.rules;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.common.TenantContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class EmailRuleController {

    private final EmailRuleRepository ruleRepo;
    private final EmailRuleEngineService engineService;
    private final ObjectMapper objectMapper;

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','HIRING_MANAGER','MANAGER')")
    public ResponseEntity<List<EmailRuleDto>> listRules() {
        UUID orgId = TenantContext.getOrganizationId();
        List<EmailRuleDto> dtos = ruleRepo.findByOrganizationIdOrderByPriorityAsc(orgId)
                .stream().map(this::toDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','HIRING_MANAGER','MANAGER')")
    public ResponseEntity<EmailRuleDto> getRule(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        EmailRule rule = ruleRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Rule not found: " + id));
        return ResponseEntity.ok(toDto(rule));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> createRule(@Valid @RequestBody CreateEmailRuleRequest req) {
        UUID orgId = TenantContext.getOrganizationId();

        // Extract condition fields from nested conditions map if present
        resolveConditions(req);

        if (!req.hasAnyCondition()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Rule must contain at least one condition (keywords, sender patterns, folder, mailbox, or attachments)"));
        }

        EmailRule rule = EmailRule.builder()
                .organizationId(orgId)
                .name(req.getName().trim())
                .description(req.getDescription())
                .priority(req.getPriority() != null ? req.getPriority() : 100)
                .enabled(req.getEnabled() != null ? req.getEnabled() : true)
                .connectionId(req.getConnectionId())
                .folderFilter(req.getFolderFilter())
                .senderIncludeJson(engineService.toJsonList(req.getSenderInclude()))
                .senderExcludeJson(engineService.toJsonList(req.getSenderExclude()))
                .subjectKeywordsJson(engineService.toJsonList(req.getSubjectKeywords()))
                .subjectExcludeJson(engineService.toJsonList(req.getSubjectExcludeKeywords()))
                .bodyKeywordsJson(engineService.toJsonList(req.getBodyKeywords()))
                .bodyExcludeJson(engineService.toJsonList(req.getBodyExcludeKeywords()))
                .requireAttachment(req.getRequireAttachment() != null ? req.getRequireAttachment() : true)
                .allowedMimesJson(engineService.toJsonList(req.getAllowedMimes()))
                .minAttachmentSizeBytes(req.getMinAttachmentSizeBytes())
                .maxAttachmentSizeBytes(req.getMaxAttachmentSizeBytes())
                .targetJobId(req.getTargetJobId())
                .duplicatePolicy(req.getDuplicatePolicy() != null ? req.getDuplicatePolicy() : "UPDATE_PROFILE_ONLY")
                .actionOnMatch(req.getActionOnMatch() != null ? req.getActionOnMatch() : "PROCESS")
                .createdBy(TenantContext.getUserId())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        ruleRepo.save(rule);
        log.info("Created rule id={} name='{}' org={}", rule.getId(), rule.getName(), orgId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(rule));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> updateRule(@PathVariable UUID id, @RequestBody UpdateEmailRuleRequest req) {
        UUID orgId = TenantContext.getOrganizationId();
        EmailRule rule = ruleRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Rule not found: " + id));

        resolveUpdateConditions(req);

        if (req.getName() != null && !req.getName().isBlank()) rule.setName(req.getName().trim());
        if (req.getDescription() != null) rule.setDescription(req.getDescription());
        if (req.getPriority() != null) rule.setPriority(req.getPriority());
        if (req.getEnabled() != null) rule.setEnabled(req.getEnabled());
        if (req.getConnectionId() != null) rule.setConnectionId(req.getConnectionId());
        if (req.getFolderFilter() != null) rule.setFolderFilter(req.getFolderFilter());

        if (req.getSenderInclude() != null) rule.setSenderIncludeJson(engineService.toJsonList(req.getSenderInclude()));
        if (req.getSenderExclude() != null) rule.setSenderExcludeJson(engineService.toJsonList(req.getSenderExclude()));
        if (req.getSubjectKeywords() != null) rule.setSubjectKeywordsJson(engineService.toJsonList(req.getSubjectKeywords()));
        if (req.getSubjectExcludeKeywords() != null) rule.setSubjectExcludeJson(engineService.toJsonList(req.getSubjectExcludeKeywords()));
        if (req.getBodyKeywords() != null) rule.setBodyKeywordsJson(engineService.toJsonList(req.getBodyKeywords()));
        if (req.getBodyExcludeKeywords() != null) rule.setBodyExcludeJson(engineService.toJsonList(req.getBodyExcludeKeywords()));

        if (req.getRequireAttachment() != null) rule.setRequireAttachment(req.getRequireAttachment());
        if (req.getAllowedMimes() != null) rule.setAllowedMimesJson(engineService.toJsonList(req.getAllowedMimes()));
        if (req.getMinAttachmentSizeBytes() != null) rule.setMinAttachmentSizeBytes(req.getMinAttachmentSizeBytes());
        if (req.getMaxAttachmentSizeBytes() != null) rule.setMaxAttachmentSizeBytes(req.getMaxAttachmentSizeBytes());

        if (req.getTargetJobId() != null) rule.setTargetJobId(req.getTargetJobId());
        if (req.getDuplicatePolicy() != null) rule.setDuplicatePolicy(req.getDuplicatePolicy());
        if (req.getActionOnMatch() != null) rule.setActionOnMatch(req.getActionOnMatch());

        rule.setUpdatedAt(Instant.now());
        ruleRepo.save(rule);
        log.info("Updated rule id={} org={}", rule.getId(), orgId);
        return ResponseEntity.ok(toDto(rule));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> toggleStatus(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        UUID orgId = TenantContext.getOrganizationId();
        EmailRule rule = ruleRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Rule not found: " + id));

        Boolean enabled = null;
        if (body.containsKey("enabled") && body.get("enabled") instanceof Boolean b) {
            enabled = b;
        } else if (body.containsKey("status")) {
            enabled = "ACTIVE".equalsIgnoreCase(Objects.toString(body.get("status")));
        }
        if (enabled == null) {
            enabled = !Boolean.TRUE.equals(rule.getEnabled());
        }

        rule.setEnabled(enabled);
        rule.setUpdatedAt(Instant.now());
        ruleRepo.save(rule);
        log.info("Toggled rule id={} enabled={} org={}", rule.getId(), enabled, orgId);
        return ResponseEntity.ok(toDto(rule));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> deleteRule(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        EmailRule rule = ruleRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Rule not found: " + id));
        ruleRepo.delete(rule);
        log.info("Deleted rule id={} org={}", id, orgId);
        return ResponseEntity.ok(Map.of("status", "DELETED", "id", id));
    }

    @PostMapping("/reorder")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> reorderRules(@RequestBody Map<String, Object> body) {
        UUID orgId = TenantContext.getOrganizationId();
        Object ruleIdsObj = body.get("ruleIds");
        if (!(ruleIdsObj instanceof List<?> rawList)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ruleIds list is required"));
        }

        List<EmailRule> orgRules = ruleRepo.findByOrganizationIdOrderByPriorityAsc(orgId);
        Map<UUID, EmailRule> ruleMap = new HashMap<>();
        for (EmailRule r : orgRules) {
            ruleMap.put(r.getId(), r);
        }

        int priority = 1;
        for (Object item : rawList) {
            UUID ruleId = null;
            if (item instanceof String s) {
                try { ruleId = UUID.fromString(s); } catch (Exception ignored) {}
            } else if (item instanceof Map<?, ?> m && m.get("id") != null) {
                try { ruleId = UUID.fromString(m.get("id").toString()); } catch (Exception ignored) {}
            }
            if (ruleId != null && ruleMap.containsKey(ruleId)) {
                EmailRule rule = ruleMap.get(ruleId);
                rule.setPriority(priority++);
                rule.setUpdatedAt(Instant.now());
                ruleRepo.save(rule);
            }
        }

        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Rules reordered successfully"));
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','HIRING_MANAGER','MANAGER')")
    public ResponseEntity<RulePreviewResponse> previewRule(@RequestBody RulePreviewRequest req) {
        UUID orgId = TenantContext.getOrganizationId();
        RulePreviewResponse response = engineService.preview(orgId, req);
        return ResponseEntity.ok(response);
    }

    private void resolveConditions(CreateEmailRuleRequest req) {
        if (req.getConditions() != null && !req.getConditions().isEmpty()) {
            Map<String, Object> cond = req.getConditions();
            if (req.getSubjectKeywords() == null && cond.containsKey("subjectKeywords")) {
                req.setSubjectKeywords(toStringList(cond.get("subjectKeywords")));
            }
            if (req.getSenderInclude() == null) {
                if (cond.containsKey("senderInclude")) {
                    req.setSenderInclude(toStringList(cond.get("senderInclude")));
                } else if (cond.containsKey("senderPatterns")) {
                    req.setSenderInclude(toStringList(cond.get("senderPatterns")));
                }
            }
            if (req.getSenderExclude() == null && cond.containsKey("senderExclude")) {
                req.setSenderExclude(toStringList(cond.get("senderExclude")));
            }
            if (req.getBodyKeywords() == null && cond.containsKey("bodyKeywords")) {
                req.setBodyKeywords(toStringList(cond.get("bodyKeywords")));
            }
            if (req.getAllowedMimes() == null && cond.containsKey("allowedMimes")) {
                req.setAllowedMimes(toStringList(cond.get("allowedMimes")));
            }
            if (req.getFolderFilter() == null && cond.containsKey("folderFilter")) {
                req.setFolderFilter(Objects.toString(cond.get("folderFilter"), null));
            }
        }
    }

    private void resolveUpdateConditions(UpdateEmailRuleRequest req) {
        if (req.getConditions() != null && !req.getConditions().isEmpty()) {
            Map<String, Object> cond = req.getConditions();
            if (req.getSubjectKeywords() == null && cond.containsKey("subjectKeywords")) {
                req.setSubjectKeywords(toStringList(cond.get("subjectKeywords")));
            }
            if (req.getSenderInclude() == null) {
                if (cond.containsKey("senderInclude")) {
                    req.setSenderInclude(toStringList(cond.get("senderInclude")));
                } else if (cond.containsKey("senderPatterns")) {
                    req.setSenderInclude(toStringList(cond.get("senderPatterns")));
                }
            }
            if (req.getSenderExclude() == null && cond.containsKey("senderExclude")) {
                req.setSenderExclude(toStringList(cond.get("senderExclude")));
            }
            if (req.getBodyKeywords() == null && cond.containsKey("bodyKeywords")) {
                req.setBodyKeywords(toStringList(cond.get("bodyKeywords")));
            }
            if (req.getAllowedMimes() == null && cond.containsKey("allowedMimes")) {
                req.setAllowedMimes(toStringList(cond.get("allowedMimes")));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object obj) {
        if (obj instanceof List<?> l) {
            return l.stream().map(Objects::toString).toList();
        }
        if (obj instanceof String s) {
            return List.of(s);
        }
        return List.of();
    }

    private EmailRuleDto toDto(EmailRule r) {
        List<String> senderInclude = engineService.parseJsonList(r.getSenderIncludeJson());
        List<String> senderExclude = engineService.parseJsonList(r.getSenderExcludeJson());
        List<String> subjectKeywords = engineService.parseJsonList(r.getSubjectKeywordsJson());
        List<String> subjectExclude = engineService.parseJsonList(r.getSubjectExcludeJson());
        List<String> bodyKeywords = engineService.parseJsonList(r.getBodyKeywordsJson());
        List<String> bodyExclude = engineService.parseJsonList(r.getBodyExcludeJson());
        List<String> allowedMimes = engineService.parseJsonList(r.getAllowedMimesJson());

        Map<String, Object> conditions = new LinkedHashMap<>();
        if (!senderInclude.isEmpty()) conditions.put("senderInclude", senderInclude);
        if (!senderExclude.isEmpty()) conditions.put("senderExclude", senderExclude);
        if (!subjectKeywords.isEmpty()) conditions.put("subjectKeywords", subjectKeywords);
        if (!subjectExclude.isEmpty()) conditions.put("subjectExcludeKeywords", subjectExclude);
        if (!bodyKeywords.isEmpty()) conditions.put("bodyKeywords", bodyKeywords);
        if (!bodyExclude.isEmpty()) conditions.put("bodyExcludeKeywords", bodyExclude);
        if (!allowedMimes.isEmpty()) conditions.put("allowedMimes", allowedMimes);
        if (r.getConnectionId() != null) conditions.put("connectionId", r.getConnectionId());
        if (r.getFolderFilter() != null) conditions.put("folderFilter", r.getFolderFilter());
        conditions.put("requireAttachment", r.getRequireAttachment());

        return EmailRuleDto.builder()
                .id(r.getId())
                .organizationId(r.getOrganizationId())
                .name(r.getName())
                .description(r.getDescription())
                .priority(r.getPriority())
                .enabled(r.getEnabled())
                .connectionId(r.getConnectionId())
                .folderFilter(r.getFolderFilter())
                .senderInclude(senderInclude)
                .senderExclude(senderExclude)
                .subjectKeywords(subjectKeywords)
                .subjectExcludeKeywords(subjectExclude)
                .bodyKeywords(bodyKeywords)
                .bodyExcludeKeywords(bodyExclude)
                .requireAttachment(r.getRequireAttachment())
                .allowedMimes(allowedMimes)
                .minAttachmentSizeBytes(r.getMinAttachmentSizeBytes())
                .maxAttachmentSizeBytes(r.getMaxAttachmentSizeBytes())
                .targetJobId(r.getTargetJobId())
                .duplicatePolicy(r.getDuplicatePolicy())
                .actionOnMatch(r.getActionOnMatch())
                .conditions(conditions)
                .createdBy(r.getCreatedBy())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
