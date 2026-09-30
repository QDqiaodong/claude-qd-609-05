package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.DamageItem;

public interface DamageItemRepository extends JpaRepository<DamageItem, Long> {

    List<DamageItem> findBySettlementIdOrderByItemNoAsc(Long settlementId);

    @Modifying
    @Query("delete from DamageItem d where d.settlementId = :settlementId")
    int deleteBySettlementId(@Param("settlementId") Long settlementId);
}
