package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.DamageSettlement;

import jakarta.persistence.LockModeType;

public interface DamageSettlementRepository extends JpaRepository<DamageSettlement, Long> {

    List<DamageSettlement> findAllByOrderByIdDesc();

    List<DamageSettlement> findByStatusOrderByIdDesc(String status);

    /** 状态白名单查询（待检查租借之外的工作台列表等） */
    List<DamageSettlement> findByStatusInOrderByIdDesc(List<String> statuses);

    List<DamageSettlement> findByEquipmentIdOrderByIdDesc(Long equipmentId);

    List<DamageSettlement> findByRenterIdOrderByIdDesc(Long renterId);

    /** 一件器材同一时间只能有一笔未结束的清算（检查中 / 待结清 / 待核对） */
    long countByEquipmentIdAndStatusIn(Long equipmentId, List<String> statuses);

    boolean existsBySettleNo(String settleNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DamageSettlement s where s.id = :id")
    Optional<DamageSettlement> findByIdForUpdate(@Param("id") Long id);
}
