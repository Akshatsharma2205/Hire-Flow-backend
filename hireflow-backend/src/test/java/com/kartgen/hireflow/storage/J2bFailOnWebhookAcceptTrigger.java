package com.kartgen.hireflow.storage;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * J-2B test-only H2 trigger that forces the webhook_deliveries outcome UPDATE
 * (the last database write before commit in
 * {@code WebhookIngestionService.ingestVerifiedDelivery}) to fail. Used by
 * Test C to simulate a failure at the latest feasible point before commit
 * without mocking any Spring bean.
 */
public class J2bFailOnWebhookAcceptTrigger implements Trigger {

    /** When true, the BEFORE UPDATE trigger throws and aborts the statement. */
    public static volatile boolean fail = false;

    @Override
    public void init(Connection conn, String schemaName, String triggerName,
                     String tableName, boolean before, int type) {
        // no-op
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        if (fail) {
            throw new SQLException("J-2B forced failure at final ledger update (webhook_deliveries)");
        }
    }

    @Override
    public void close() {
        // no-op
    }

    @Override
    public void remove() {
        // no-op
    }
}
