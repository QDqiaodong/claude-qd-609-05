package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.DamageDebt;

import jakarta.persistence.LockModeType;

public interface DamageDebtRepository extends JpaRepository<DamageDebt, Long> {

    List<DamageDebt> findAllByOrderByIdDesc();

    List<DamageDebt> findByStatusInOrderByIdDesc(List<String> statuses);

    List<DamageDebt> findByMemberIdAndStatusInOrderByIdDesc(Long memberId, List<String> statuses);

    List<DamageDebt> findByMemberIdOrderByIdDesc(Long memberId);

    Optional<DamageDebt> findBySettlementId(Long settlementId);

    /** 会员名下是否存在未彻底了结的待缴（待缴 / 部分缴纳 / 异议冻结）——租借资格判定用 */
    long countByMemberIdAndStatusIn(Long memberId, List<String> statuses);

    boolean existsByDebtNo(String debtNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DamageDebt d where d.id = :id")
    Optional<DamageDebt> findByIdForUpdate(@Param("id") Long id);
}
