package com.codereviewx.progress;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgressRepository extends JpaRepository<ProgressRecord, Long> {}
