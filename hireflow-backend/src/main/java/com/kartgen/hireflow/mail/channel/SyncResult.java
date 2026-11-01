package com.kartgen.hireflow.mail.channel;

public record SyncResult(
        String status,
        int messagesScanned,
        int attachmentsIngested,
        String summary,
        String errorMessage
) {}

