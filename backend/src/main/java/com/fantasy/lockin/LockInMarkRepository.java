package com.fantasy.lockin;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LockInMarkRepository extends JpaRepository<LockInMark, LockInMark.Key> {

    List<LockInMark> findByLeagueIdAndUserIdAndWeek(String leagueId, String userId, int week);
}
