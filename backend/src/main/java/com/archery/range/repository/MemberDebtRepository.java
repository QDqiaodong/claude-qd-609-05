package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.MemberDebt;

import jakarta.persistence.LockModeType;

public interface MemberDebtRepository extends JpaRepository<MemberDebt, Long> {

    List<MemberDebt> findByMemberIdOrderByIdDesc(Long memberId);

    List<MemberDebt> findByStatusOrderByIdDesc(String status);

    List<MemberDebt> findAllByOrderByIdDesc();

    boolean existsByDebtNo(String debtNo);

    /** 会员是否存在待缴费用 —— 存在则暂停租借器材资格 */
    @Query("select count(d) > 0 from MemberDebt d where d.memberId = :memberId and d.status = 'UNPAID'")
    boolean existsUnpaidByMember(@Param("memberId") Long memberId);

    @Query("select count(d) from MemberDebt d where d.status = 'UNPAID'")
    long countUnpaid();

    Optional<MemberDebt> findBySettlementId(Long settlementId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from MemberDebt d where d.id = :id")
    Optional<MemberDebt> findByIdForUpdate(@Param("id") Long id);
}
