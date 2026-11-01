package com.kartgen.hireflow.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
public class TikaMimeDetector {

    private static final Set<String> RESUME_MIMES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword"
    );

    private final Tika tika = new Tika();

    public String detectMimeType(byte[] data, String fileName) {
        try {
            return tika.detect(data, fileName);
        } catch (Exception e) {
            log.warn("Tika detection failed for {}: {}", fileName, e.getMessage());
            return "application/octet-stream";
        }
    }

    public boolean isResumeMimeType(String mimeType) {
        return mimeType != null && RESUME_MIMES.contains(mimeType);
    }
}

