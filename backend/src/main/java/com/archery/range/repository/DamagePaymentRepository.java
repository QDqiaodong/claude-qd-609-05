package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.DamagePayment;

public interface DamagePaymentRepository extends JpaRepository<DamagePayment, Long> {

    List<DamagePayment> findByDebtIdOrderByIdAsc(Long debtId);

    List<DamagePayment> findBySettlementIdOrderByIdAsc(Long settlementId);

    List<DamagePayment> findByMemberIdOrderByIdDesc(Long memberId);
}
