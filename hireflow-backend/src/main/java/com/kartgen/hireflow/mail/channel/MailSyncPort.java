package com.kartgen.hireflow.mail.channel;

import com.kartgen.hireflow.mail.model.MailConnection;

/**
 * Port interface for mailbox synchronization providers (IMAP, Gmail, Microsoft Graph).
 */
public interface MailSyncPort {

    boolean supports(String provider);

    SyncResult syncConnection(MailConnection conn);
}
