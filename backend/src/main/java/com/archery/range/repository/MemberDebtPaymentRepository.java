package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.MemberDebtPayment;

public interface MemberDebtPaymentRepository extends JpaRepository<MemberDebtPayment, Long> {

    List<MemberDebtPayment> findByDebtIdOrderByIdAsc(Long debtId);

    List<MemberDebtPayment> findByMemberIdOrderByIdDesc(Long memberId);
}
