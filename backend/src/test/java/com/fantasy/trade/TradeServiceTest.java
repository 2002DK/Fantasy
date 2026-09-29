package com.fantasy.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.NflState;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.stats.WeeklyDataService;
import com.fantasy.trade.TradeResponse.Strength;
import com.fantasy.trade.TradeResponse.TradePlayer;
import com.fantasy.trade.TradeResponse.Winner;

/**
 * Worked example: 2 teams each starting one RB, 0.1 points per rushing yard, week 3 of a
 * season that ends in week 4 (playoffs start week 4 with 2 teams = 1 round).
 * Replacement RB = 3rd best rest-of-season projection = Beta's 8.0.
 *
 * Alpha (PHI): 2 games, projections 10 + 10 = 20; form 13/game over 2 games, neutral
 *   schedule (NYJ 1.5x, DAL 0.5x, average 1.0): 0.7*20 + 0.3*26 = 21.8; value 13.8
 * Beta (SF): bye in week 4, 1 game; projects 8; form 6 * 1 game * 0.85 (DAL, capped) = 5.1:
 *   0.7*8 + 0.3*5.1 = 7.13; below replacement, value 0
 * Cee (KC): week 3 game already kicked off, so week 4 only; projects 9, no form: value 1.0
 */
@ExtendWith(MockitoExtension.class)
class TradeServiceTest {

    private static final String LEAGUE_ID = "L1";
    private static final PlayerSummary ALPHA = new PlayerSummary("A", "Alpha Back", "RB", "PHI", null);
    private static final PlayerSummary BETA = new PlayerSummary("B", "Beta Back", "RB", "SF", null);
    private static final PlayerSummary CEE = new PlayerSummary("C", "Cee Back", "RB", "KC", null);
    private static final PlayerSummary DEE = new PlayerSummary("D", "Dee Back", "RB", "LV", null);

    @Mock
    private SleeperClient sleeperClient;

    @Mock
    private WeeklyDataService weeklyData;

    @Mock
    private PlayerService playerService;

    private TradeService service;
    private Map<String, PlayerSummary> players;

    @BeforeEach
    void setUp() {
        service = new TradeService(sleeperClient, playerService, new SeasonValuer(weeklyData, playerService),
                new SeasonCalendar(sleeperClient));
        givenLeague(List.of("RB", "BN"), new SleeperLeague.Settings(4, 2, 0));
        lenient().when(sleeperClient.getNflState()).thenReturn(new NflState("2026", 3, "regular"));
        givenPlayers(ALPHA, BETA, CEE, DEE);
        stubPlayerService();
        lenient().when(weeklyData.schedule("2026")).thenReturn(List.of(
                new SleeperGame("g1", 3, "PHI", "NYJ", "pre_game"),
                new SleeperGame("g2", 3, "SF", "DAL", "pre_game"),
                new SleeperGame("g3", 3, "KC", "LV", "complete"),
                new SleeperGame("g4", 4, "PHI", "DAL", "pre_game"),
                new SleeperGame("g5", 4, "KC", "NYJ", "pre_game")));
        lenient().when(weeklyData.projections("2026", 3, 4)).thenReturn(Map.of(
                3, List.of(rushing("A", 3, 100), rushing("B", 3, 80), rushing("C", 3, 90), rushing("D", 3, 50)),
                4, List.of(rushing("A", 4, 100), rushing("C", 4, 90), rushing("D", 4, 50))));
        lenient().when(weeklyData.stats("2026", 2)).thenReturn(List.of(
                rushing("A", 2, 120), rushing("B", 2, 60), defense("NYJ", 2, 30), defense("DAL", 2, 10)));
        lenient().when(weeklyData.stats("2026", 1)).thenReturn(List.of(
                rushing("A", 1, 140), defense("NYJ", 1, 30), defense("DAL", 1, 10)));
    }

    @Test
    void valuesPlayersAboveReplacementOverRemainingGames() {
        TradeResponse response = service.analyze(LEAGUE_ID, List.of("A"), List.of("B", "C"));

        assertThat(response.fromWeek()).isEqualTo(3);
        assertThat(response.throughWeek()).isEqualTo(4);
        TradePlayer alpha = response.give().players().getFirst();
        assertThat(alpha.remainingGames()).isEqualTo(2);
        assertThat(alpha.projectedPoints()).isEqualTo(20.0);
        assertThat(alpha.recentAverage()).isEqualTo(13.0);
        assertThat(alpha.scheduleStrengthPercent()).isEqualTo(0.0);
        assertThat(alpha.restOfSeasonPoints()).isEqualTo(21.8);
        assertThat(alpha.replacementPoints()).isEqualTo(8.0);
        assertThat(alpha.value()).isEqualTo(13.8);

        TradePlayer beta = response.get().players().getFirst();
        assertThat(beta.remainingGames()).isEqualTo(1);
        assertThat(beta.scheduleStrengthPercent()).isEqualTo(-50.0);
        assertThat(beta.restOfSeasonPoints()).isEqualTo(7.13);
        assertThat(beta.value()).isZero();

        TradePlayer cee = response.get().players().get(1);
        assertThat(cee.remainingGames()).isEqualTo(1);
        assertThat(cee.projectedPoints()).isEqualTo(9.0);
        assertThat(cee.recentAverage()).isNull();
        assertThat(cee.value()).isEqualTo(1.0);

        assertThat(response.give().totalValue()).isEqualTo(13.8);
        assertThat(response.get().totalValue()).isEqualTo(1.0);
        assertThat(response.verdict().winner()).isEqualTo(Winner.THEM);
        assertThat(response.verdict().strength()).isEqualTo(Strength.CLEAR);
        assertThat(response.verdict().marginPercent()).isEqualTo(92.75);
        assertThat(response.reasons()).contains(
                "You receive 1.0 points of rest-of-season value above replacement and give up 13.8.",
                "Alpha Back is the most valuable player in the deal: 21.8 projected points over 2 games, "
                        + "13.8 above a replacement-level RB.",
                "Beta Back has one of the tougher remaining schedules: opponents allow 50% fewer points to "
                        + "running backs than average.",
                "Beta Back projects at or below replacement level, so they add little trade value.",
                "You receive 2 players for 1, so you'll need to open a roster spot.");
        assertThat(response.reasons()).noneMatch(reason -> reason.contains("%s"));
    }

    @Test
    void longTermInjuryIgnoresRecentForm() {
        givenPlayers(new PlayerSummary("A", "Alpha Back", "RB", "PHI", "IR"), BETA, CEE, DEE);

        TradeResponse response = service.analyze(LEAGUE_ID, List.of("A"), List.of("C"));

        TradePlayer alpha = response.give().players().getFirst();
        assertThat(alpha.restOfSeasonPoints()).isEqualTo(20.0);
        assertThat(alpha.note()).isEqualTo("IR");
        assertThat(response.reasons()).contains(
                "Alpha Back is on IR, so only projected games after a return count and recent form is ignored.");
    }

    @Test
    void positionWithoutStartingSlotHasNoValue() {
        PlayerSummary kicker = new PlayerSummary("K1", "Kay Kicker", "K", "PHI", null);
        givenPlayers(ALPHA, BETA, CEE, DEE, kicker);

        TradeResponse response = service.analyze(LEAGUE_ID, List.of("K1"), List.of("C"));

        assertThat(response.give().players().getFirst().value()).isZero();
        assertThat(response.give().players().getFirst().note()).isEqualTo("No starting slot for K");
        assertThat(response.reasons()).contains("Kay Kicker has no starting slot in this league, so they add no trade value.");
    }

    @Test
    void missingProjectionsFallBackToFormWithoutScarcity() {
        given(weeklyData.projections(eq("2026"), anyInt(), anyInt())).willThrow(new ResourceAccessException("timeout"));

        TradeResponse response = service.analyze(LEAGUE_ID, List.of("A"), List.of("B"));

        TradePlayer alpha = response.give().players().getFirst();
        assertThat(alpha.projectedPoints()).isNull();
        assertThat(alpha.restOfSeasonPoints()).isEqualTo(26.0);
        assertThat(alpha.replacementPoints()).isZero();
        assertThat(response.notes()).contains("Projections are unavailable right now, so values use recent form "
                + "only and positional scarcity is not applied.");
    }

    @Test
    void notesPassedTradeDeadline() {
        givenLeague(List.of("RB", "BN"), new SleeperLeague.Settings(4, 2, 2));

        TradeResponse response = service.analyze(LEAGUE_ID, List.of("A"), List.of("B"));

        assertThat(response.notes()).contains("This league's trade deadline (week 2) has passed.");
    }

    @Test
    void rejectsInvalidTrades() {
        assertThatThrownBy(() -> service.analyze(LEAGUE_ID, List.of(), List.of("B")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Pick at least one player on each side of the trade");
        assertThatThrownBy(() -> service.analyze(LEAGUE_ID, List.of("A"), List.of("A")))
                .hasMessage("A player is listed twice in the trade");
        assertThatThrownBy(() -> service.analyze(LEAGUE_ID, List.of("1", "2", "3", "4", "5", "6"), List.of("B")))
                .hasMessage("A trade can include at most 5 players per side");
    }

    @Test
    void rejectsPastSeasonAndFinishedSeason() {
        given(sleeperClient.getNflState()).willReturn(new NflState("2027", 1, "regular"));
        assertThatThrownBy(() -> service.analyze(LEAGUE_ID, List.of("A"), List.of("B")))
                .hasMessage("Trades can only be analyzed for the current season; this league is from 2026");

        given(sleeperClient.getNflState()).willReturn(new NflState("2026", 5, "regular"));
        assertThatThrownBy(() -> service.analyze(LEAGUE_ID, List.of("A"), List.of("B")))
                .hasMessage("This league's season is over (it ended in week 4)");
    }

    @Test
    void lastFantasyWeekFollowsPlayoffSettings() {
        assertThat(TradeService.lastFantasyWeek(null)).isEqualTo(17);
        assertThat(TradeService.lastFantasyWeek(new SleeperLeague.Settings(0, 6, 0))).isEqualTo(17);
        assertThat(TradeService.lastFantasyWeek(new SleeperLeague.Settings(15, 6, 0))).isEqualTo(17);
        assertThat(TradeService.lastFantasyWeek(new SleeperLeague.Settings(15, 4, 0))).isEqualTo(16);
        assertThat(TradeService.lastFantasyWeek(new SleeperLeague.Settings(16, 8, 0))).isEqualTo(18);
        assertThat(TradeService.lastFantasyWeek(new SleeperLeague.Settings(17, 6, 0))).isEqualTo(18);
    }

    @Test
    void verdictStrengthThresholds() {
        assertThat(TradeService.verdict(side(100), side(95)).strength()).isEqualTo(Strength.FAIR);
        assertThat(TradeService.verdict(side(100), side(95)).winner()).isEqualTo(Winner.THEM);
        assertThat(TradeService.verdict(side(80), side(100)).strength()).isEqualTo(Strength.SLIGHT);
        assertThat(TradeService.verdict(side(80), side(100)).winner()).isEqualTo(Winner.YOU);
        assertThat(TradeService.verdict(side(50), side(100)).strength()).isEqualTo(Strength.CLEAR);
        assertThat(TradeService.verdict(side(0), side(0)).winner()).isNull();
    }

    private void givenLeague(List<String> rosterPositions, SleeperLeague.Settings settings) {
        lenient().when(sleeperClient.getLeague(LEAGUE_ID)).thenReturn(Optional.of(new SleeperLeague(
                LEAGUE_ID, "Dynasty", "2026", "in_season", 2, null, rosterPositions, Map.of("rush_yd", 0.1),
                settings)));
    }

    /** Replaces the players the PlayerService stubs (registered once in setUp) resolve. */
    private void givenPlayers(PlayerSummary... summaries) {
        players = java.util.Arrays.stream(summaries).collect(Collectors.toMap(PlayerSummary::playerId, p -> p));
    }

    private void stubPlayerService() {
        lenient().when(playerService.findSummary(any())).thenAnswer(inv -> Optional.ofNullable(players.get(inv.getArgument(0))));
        lenient().when(playerService.findSummaries(any())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            return ids.stream().filter(players::containsKey)
                    .collect(Collectors.toMap(Function.identity(), players::get));
        });
    }

    private static TradeResponse.Side side(double value) {
        return new TradeResponse.Side(List.of(), value, value);
    }

    private static SleeperWeeklyEntry rushing(String id, int week, double yards) {
        return new SleeperWeeklyEntry(id, null, null, week, Map.of("gp", 1, "rush_yd", yards));
    }

    private static SleeperWeeklyEntry defense(String team, int week, double allowedToRbs) {
        return new SleeperWeeklyEntry(team, team, "X", week,
                Map.of("gp", 1, "fan_pts_allow", 100, "fan_pts_allow_rb", allowedToRbs));
    }
}
