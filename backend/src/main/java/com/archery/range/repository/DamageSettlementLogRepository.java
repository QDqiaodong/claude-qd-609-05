package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.DamageSettlementLog;

public interface DamageSettlementLogRepository extends JpaRepository<DamageSettlementLog, Long> {

    List<DamageSettlementLog> findBySettlementIdOrderByIdAsc(Long settlementId);
}
