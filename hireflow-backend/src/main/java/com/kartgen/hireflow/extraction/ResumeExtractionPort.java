package com.kartgen.hireflow.extraction;

import com.kartgen.hireflow.extraction.dto.ExtractedResumeSchema;

import java.util.UUID;

/**
 * Pluggable extraction port. Implementations must be deterministic for the same
 * input, or explicitly version their parser.
 */
public interface ResumeExtractionPort {

    ExtractedResumeSchema extract(String rawResumeText, String fileName, String senderEmailHint);

    /**
     * Extraction with tenant context for AI usage accounting. Local extractors can
     * ignore the extra identifiers.
     */
    default ExtractedResumeSchema extract(String rawResumeText, String fileName,
                                          String senderEmailHint, UUID organizationId,
                                          UUID resumeFileId) {
        return extract(rawResumeText, fileName, senderEmailHint);
    }
}
