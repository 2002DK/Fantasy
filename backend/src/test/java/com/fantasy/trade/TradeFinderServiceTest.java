package com.fantasy.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fantasy.league.LeagueData;
import com.fantasy.league.RosterResponse;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.lineup.LineupSolver.Candidate;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperRoster;
import com.fantasy.stats.DefenseTable;
import com.fantasy.trade.TradeIdeasResponse.Idea;
import com.fantasy.trade.TradeResponse.TradePlayer;

/**
 * One remaining week, one QB and one RB slot. The user has two starting-calibre QBs
 * (20 and 18) and a weak RB (8); the partner has a weaker QB (15) and two good RBs
 * (14, 13). Sending QB2 for RB2 lifts the user from 28 to 34 (+6) and the partner
 * from 29 to 31 (+2).
 */
@ExtendWith(MockitoExtension.class)
class TradeFinderServiceTest {

    private static final PlayerSummary QB1 = p("qb1", "QB");
    private static final PlayerSummary QB2 = p("qb2", "QB");
    private static final PlayerSummary RB1 = p("rb1", "RB");
    private static final PlayerSummary QB3 = p("qb3", "QB");
    private static final PlayerSummary RB2 = p("rb2", "RB");
    private static final PlayerSummary RB3 = p("rb3", "RB");
    private static final Map<String, Double> POINTS = Map.of(
            "qb1", 20.0, "qb2", 18.0, "rb1", 8.0, "qb3", 15.0, "rb2", 14.0, "rb3", 13.0);

    @Mock
    private LeagueData leagueData;
    @Mock
    private SeasonCalendar calendar;
    @Mock
    private SeasonValuer valuer;

    @Test
    void findsTradesThatHelpBothLineups() {
        SleeperLeague league = new SleeperLeague("L1", "Dynasty", "2026", "in_season", 2, null,
                List.of("QB", "RB", "BN", "BN"), Map.of(), new SleeperLeague.Settings(4, 2, 0));
        LeagueData.Team me = team(1, "me", "Mine", QB1, QB2, RB1);
        LeagueData.Team partner = team(2, "them", "Partner", QB3, RB2, RB3);
        given(leagueData.load("L1")).willReturn(new LeagueData.Snapshot(league, List.of(me, partner)));
        given(calendar.currentWeek(league)).willReturn(4);
        given(valuer.load(any(), anyInt(), anyInt())).willReturn(new SeasonValuer.Season(Map.of(), 4, 4, null, Map.of(),
                true, List.of(), DefenseTable.from(List.of()), null, new ArrayList<>()));
        given(valuer.evaluate(any(), any())).willAnswer(inv -> {
            PlayerSummary player = inv.getArgument(0);
            double points = POINTS.get(player.playerId());
            return new TradePlayer(player, 1, points, null, 0, null, points, 0, points, null);
        });

        TradeIdeasResponse response = new TradeFinderService(leagueData, calendar, valuer).findIdeas("L1", "me");

        Idea best = response.ideas().getFirst();
        assertThat(best.partnerName()).isEqualTo("Partner");
        assertThat(best.yourGainPerWeek()).isEqualTo(6.0);
        assertThat(best.give()).extracting(t -> t.player().playerId()).contains("qb2");
        assertThat(best.get()).extracting(t -> t.player().playerId()).contains("rb2");
        assertThat(response.ideas()).allSatisfy(idea -> {
            assertThat(idea.yourGainPerWeek()).isGreaterThanOrEqualTo(TradeFinderService.MIN_YOUR_GAIN);
            assertThat(idea.theirGainPerWeek()).isGreaterThanOrEqualTo(TradeFinderService.MIN_THEIR_GAIN);
        });
        assertThat(response.ideas()).hasSizeLessThanOrEqualTo(TradeFinderService.MAX_IDEAS_PER_PARTNER);
    }

    @Test
    void rejectsDealsThatFleeceThePartner() {
        Map<String, TradePlayer> values = Map.of(
                "a", value("a", 10), "b", value("b", 100), "c", value("c", 60));
        // Partner sends 100 of value and gets 10 back: rejected
        assertThat(TradeFinderService.fairForPartner(List.of(c("a")), List.of(c("b")), values)).isFalse();
        // Partner sends 100 and gets 70 back: exactly the 70% floor
        Map<String, TradePlayer> fair = Map.of("a", value("a", 70), "b", value("b", 100));
        assertThat(TradeFinderService.fairForPartner(List.of(c("a")), List.of(c("b")), fair)).isTrue();
    }

    private static LeagueData.Team team(int rosterId, String ownerId, String name, PlayerSummary... players) {
        List<String> ids = java.util.Arrays.stream(players).map(PlayerSummary::playerId).toList();
        SleeperRoster roster = new SleeperRoster(rosterId, ownerId, null, ids, List.of(), null, null, null);
        return new LeagueData.Team(roster, new RosterResponse.Owner(ownerId, name, name, null), List.of(players));
    }

    private static PlayerSummary p(String id, String position) {
        return new PlayerSummary(id, id.toUpperCase(), position, "PHI", null);
    }

    private static TradePlayer value(String id, double value) {
        return new TradePlayer(p(id, "RB"), 1, value, null, 0, null, value, 0, value, null);
    }

    private static Candidate c(String id) {
        return new Candidate(p(id, "RB"), 0, false);
    }
}
