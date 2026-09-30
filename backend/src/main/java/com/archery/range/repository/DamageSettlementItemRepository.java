package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.DamageSettlementItem;

public interface DamageSettlementItemRepository extends JpaRepository<DamageSettlementItem, Long> {

    List<DamageSettlementItem> findBySettlementIdOrderByItemSeqAsc(Long settlementId);

    int countBySettlementId(Long settlementId);
}
