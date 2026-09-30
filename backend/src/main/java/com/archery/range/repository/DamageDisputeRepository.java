package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.DamageDispute;

import jakarta.persistence.LockModeType;

public interface DamageDisputeRepository extends JpaRepository<DamageDispute, Long> {

    List<DamageDispute> findByDebtIdOrderByIdAsc(Long debtId);

    List<DamageDispute> findByStatusOrderByIdDesc(String status);

    List<DamageDispute> findByMemberIdOrderByIdDesc(Long memberId);

    /** 一笔待缴同一时间只能有一个处理中的异议 */
    long countByDebtIdAndStatus(Long debtId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DamageDispute d where d.id = :id")
    Optional<DamageDispute> findByIdForUpdate(@Param("id") Long id);
}
