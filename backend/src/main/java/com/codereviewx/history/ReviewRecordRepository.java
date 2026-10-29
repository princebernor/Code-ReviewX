package com.codereviewx.history;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query is scoped by owner, so one user can never address another user's reviews. */
public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, String> {
    Page<ReviewRecord> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable page);

    Optional<ReviewRecord> findByIdAndUserId(String id, Long userId);

    long countByUserId(Long userId);

    @Query("select r.id from ReviewRecord r where r.userId = :user order by r.createdAt desc")
    List<String> idsNewestFirst(@Param("user") Long userId);

    @Modifying
    @Query("delete from ReviewRecord r where r.id = :id and r.userId = :user")
    int deleteOwned(@Param("id") String id, @Param("user") Long userId);

    @Modifying
    @Query("delete from ReviewRecord r where r.userId = :user")
    int deleteAllOwned(@Param("user") Long userId);
}
