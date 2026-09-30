package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.DamageLog;

public interface DamageLogRepository extends JpaRepository<DamageLog, Long> {

    List<DamageLog> findBySettlementIdOrderByIdAsc(Long settlementId);
}
