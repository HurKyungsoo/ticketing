package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.domain.SyncState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncStateRepository extends JpaRepository<SyncState, SourceType> {
}
