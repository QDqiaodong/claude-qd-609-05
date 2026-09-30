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

    List<DamageSettlement> findByEquipmentIdOrderByIdDesc(Long equipmentId);

    List<DamageSettlement> findByMemberIdOrderByIdDesc(Long memberId);

    List<DamageSettlement> findByStatusOrderByIdDesc(String status);

    List<DamageSettlement> findByStatusNotOrderByIdDesc(String status);

    boolean existsBySettleNo(String settleNo);

    /** 某件器材是否存在未结清（检查中 / 待结清 / 待核对）的清算单 */
    @Query("select count(s) > 0 from DamageSettlement s where s.equipmentId = :equipmentId"
            + " and s.status <> 'SETTLED'")
    boolean existsOpenByEquipment(@Param("equipmentId") Long equipmentId);

    /** 会员是否存在未结清清算单 —— 档案合并必须等清算结束，否则基线（租借人 / 余额）无法沿用 */
    @Query("select count(s) > 0 from DamageSettlement s where s.memberId = :memberId"
            + " and s.status <> 'SETTLED'")
    boolean existsOpenByMember(@Param("memberId") Long memberId);

    /** 悲观写锁：同一清算单的登记 / 减免 / 异议 / 结清在此串行 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DamageSettlement s where s.id = :id")
    Optional<DamageSettlement> findByIdForUpdate(@Param("id") Long id);
}
