package com.kartgen.hireflow.mail.channel;

import com.kartgen.hireflow.mail.model.MailConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Channel 3: OAuth2 mail sync delegator for Gmail and Microsoft Graph.
 * Implements MailSyncPort and coordinates provider-specific adapters.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthMailSyncAdapter implements MailSyncPort {

    private final GoogleMailAdapter googleAdapter;
    private final MicrosoftMailAdapter microsoftAdapter;

    @Override
    public boolean supports(String provider) {
        return "GMAIL_OAUTH".equalsIgnoreCase(provider) || "MS_GRAPH_OAUTH".equalsIgnoreCase(provider);
    }

    @Override
    public SyncResult syncConnection(MailConnection conn) {
        if ("GMAIL_OAUTH".equalsIgnoreCase(conn.getProvider())) {
            return googleAdapter.syncConnection(conn);
        }
        if ("MS_GRAPH_OAUTH".equalsIgnoreCase(conn.getProvider())) {
            return microsoftAdapter.syncConnection(conn);
        }
        log.warn("OAuthMailSyncAdapter called for unsupported provider: {}", conn.getProvider());
        return new SyncResult("ERROR", 0, 0, null, "Unsupported provider: " + conn.getProvider());
    }
}
