package com.portfolio.ticket.external;

import java.util.List;

public record SyncResult(int totalSaved, List<SourceSyncResult> sources) {
}
