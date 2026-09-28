package com.fantasy.startsit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.NflState;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperGame;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.sleeper.SleeperWeeklyEntry;
import com.fantasy.startsit.StartSitResponse.Confidence;
import com.fantasy.startsit.StartSitResponse.Matchup;
import com.fantasy.startsit.StartSitResponse.PlayerAnalysis;
import com.fantasy.startsit.StartSitResponse.RecentGame;

/**
 * Worked example used throughout, scored at 0.1 per rushing yard:
 * Alpha (PHI) projects 10.0, averaged 14.0, faces NYJ who allow 30 vs a 20 average (+15% cap):
 *   0.6 * 10 + 0.4 * 14 * 1.15 = 12.44
 * Beta (SF) projects 6.0, averaged 6.0, faces DAL who allow 10 (-15% cap):
 *   0.6 * 6 + 0.4 * 6 * 0.85 = 5.64
 */
@ExtendWith(MockitoExtension.class)
class StartSitServiceTest {

    private static final String LEAGUE_ID = "L1";
    private static final PlayerSummary ALPHA = new PlayerSummary("A", "Alpha Back", "RB", "PHI", null);
    private static final PlayerSummary BETA = new PlayerSummary("B", "Beta Back", "RB", "SF", null);

    @Mock
    private SleeperClient sleeperClient;

    @Mock
    private WeeklyDataService weeklyData;

    @Mock
    private PlayerService playerService;

    private StartSitService service;

    @BeforeEach
    void setUp() {
        service = new StartSitService(sleeperClient, weeklyData, playerService);
        lenient().when(sleeperClient.getLeague(LEAGUE_ID)).thenReturn(Optional.of(new SleeperLeague(
                LEAGUE_ID, "Dynasty", "2026", "in_season", 12, null, List.of(), Map.of("rush_yd", 0.1))));
        lenient().when(sleeperClient.getNflState()).thenReturn(new NflState("2026", 3, "regular"));
        givenPlayers(ALPHA, BETA);
        lenient().when(weeklyData.schedule("2026")).thenReturn(List.of(
                new SleeperGame("g1", 3, "PHI", "NYJ", "pre_game"),
                new SleeperGame("g2", 3, "DAL", "SF", "pre_game")));
        lenient().when(weeklyData.projections("2026", 3)).thenReturn(List.of(
                rushing("A", "PHI", "NYJ", 3, 100), rushing("B", "SF", "DAL", 3, 60)));
        lenient().when(weeklyData.stats("2026", 2)).thenReturn(List.of(
                rushing("A", "PHI", "WAS", 2, 150), rushing("B", "SF", "LAR", 2, 50),
                defense("NYJ", 2, 32), defense("DAL", 2, 12)));
        lenient().when(weeklyData.stats("2026", 1)).thenReturn(List.of(
                rushing("A", "PHI", "DAL", 1, 130), rushing("B", "SF", "SEA", 1, 70),
                defense("NYJ", 1, 28), defense("DAL", 1, 8)));
    }

    @Test
    void recommendsHigherBlendedScoreForCurrentWeek() {
        StartSitResponse response = service.compare(LEAGUE_ID, "A", "B", null);

        assertThat(response.week()).isEqualTo(3);
        PlayerAnalysis alpha = response.players().getFirst();
        assertThat(alpha.projectedPoints()).isEqualTo(10.0);
        assertThat(alpha.recentGames()).containsExactly(new RecentGame(2, "WAS", 15.0), new RecentGame(1, "DAL", 13.0));
        assertThat(alpha.recentAverage()).isEqualTo(14.0);
        assertThat(alpha.matchup()).isEqualTo(new Matchup("NYJ", true, 1, 2, 30.0, 20.0));
        assertThat(alpha.score()).isEqualTo(12.44);
        assertThat(response.players().get(1).score()).isEqualTo(5.64);
        assertThat(response.recommendation().playerId()).isEqualTo("A");
        assertThat(response.recommendation().confidence()).isEqualTo(Confidence.CLEAR);
        assertThat(response.recommendation().marginPercent()).isEqualTo(54.66);
        assertThat(response.reasons()).contains(
                "Alpha Back projects 10.0 points to Beta Back's 6.0 in your league's scoring.",
                "Alpha Back has averaged 14.0 over 2 games to Beta Back's 6.0 over 2 games.",
                "Alpha Back faces NYJ, who allow the most points to running backs (30.0 per game).",
                "Beta Back faces DAL, who allow the fewest points to running backs (10.0 per game).");
        assertThat(response.notes()).containsExactly(
                "Matchup rankings use only 2 weeks of games so far, so treat them as rough.");
    }

    @Test
    void notesWhenAPlayersGameHasKickedOff() {
        given(weeklyData.schedule("2026")).willReturn(List.of(
                new SleeperGame("g1", 3, "PHI", "NYJ", "complete"),
                new SleeperGame("g2", 3, "DAL", "SF", "pre_game")));

        StartSitResponse response = service.compare(LEAGUE_ID, "A", "B", 3);

        assertThat(response.notes()).contains(
                "Alpha Back's week 3 game has already kicked off, so that lineup spot is locked.");
        assertThat(response.notes()).noneMatch(note -> note.startsWith("Beta Back"));
    }

    @Test
    void outPlayerLosesToAvailablePlayer() {
        givenPlayers(new PlayerSummary("A", "Alpha Back", "RB", "PHI", "Out"), BETA);

        StartSitResponse response = service.compare(LEAGUE_ID, "A", "B", 3);

        assertThat(response.players().getFirst().available()).isFalse();
        assertThat(response.players().getFirst().score()).isZero();
        assertThat(response.recommendation().playerId()).isEqualTo("B");
        assertThat(response.recommendation().confidence()).isEqualTo(Confidence.CLEAR);
        assertThat(response.reasons()).containsExactly("Alpha Back is listed as Out.");
    }

    @Test
    void playerWhoseTeamHasNoGameIsOnBye() {
        givenPlayers(ALPHA, new PlayerSummary("B", "Beta Back", "RB", "KC", null));

        StartSitResponse response = service.compare(LEAGUE_ID, "A", "B", 3);

        assertThat(response.players().get(1).availabilityNote()).isEqualTo("On bye");
        assertThat(response.recommendation().playerId()).isEqualTo("A");
    }

    @Test
    void questionablePlayerIsDiscountedTenPercent() {
        givenPlayers(new PlayerSummary("A", "Alpha Back", "RB", "PHI", "Questionable"), BETA);

        StartSitResponse response = service.compare(LEAGUE_ID, "A", "B", 3);

        PlayerAnalysis alpha = response.players().getFirst();
        assertThat(alpha.available()).isTrue();
        assertThat(alpha.score()).isEqualTo(11.2); // 12.44 * 0.9 = 11.196
        assertThat(response.reasons()).contains("Alpha Back is questionable, so their score is discounted.");
    }

    @Test
    void missingProjectionsFallBackToMatchupAdjustedForm() {
        given(weeklyData.projections("2026", 3)).willThrow(new ResourceAccessException("timeout"));

        StartSitResponse response = service.compare(LEAGUE_ID, "A", "B", 3);

        assertThat(response.players()).extracting(PlayerAnalysis::score).containsExactly(16.1, 5.1);
        assertThat(response.recommendation().playerId()).isEqualTo("A");
        assertThat(response.notes()).contains(
                "Projections are unavailable right now; this uses recent form and matchup only.");
    }

    @Test
    void closeScoresAreATossUp() {
        given(weeklyData.projections("2026", 3)).willReturn(List.of(
                rushing("A", "PHI", "NYJ", 3, 100), rushing("B", "SF", "DAL", 3, 100)));
        given(weeklyData.stats("2026", 2)).willReturn(List.of(
                rushing("A", "PHI", "WAS", 2, 100), rushing("B", "SF", "LAR", 2, 103)));
        given(weeklyData.stats("2026", 1)).willReturn(List.of());

        StartSitResponse response = service.compare(LEAGUE_ID, "A", "B", 3);

        assertThat(response.recommendation().confidence()).isEqualTo(Confidence.TOSS_UP);
        assertThat(response.reasons()).contains("The scores are within 5%, so either choice is reasonable.");
    }

    @Test
    void weekOneHasNoFormOrMatchupData() {
        given(weeklyData.projections("2026", 1)).willReturn(List.of(
                rushing("A", "PHI", "NYJ", 1, 100), rushing("B", "SF", "DAL", 1, 60)));
        given(weeklyData.schedule("2026")).willReturn(List.of(
                new SleeperGame("g1", 1, "PHI", "NYJ", "pre_game"), new SleeperGame("g2", 1, "DAL", "SF", "pre_game")));

        StartSitResponse response = service.compare(LEAGUE_ID, "A", "B", 1);

        assertThat(response.players()).extracting(PlayerAnalysis::score).containsExactly(10.0, 6.0);
        assertThat(response.players().getFirst().matchup().rank()).isNull();
        assertThat(response.notes()).hasSize(1);
    }

    @Test
    void samePlayerTwiceIsRejected() {
        assertThatThrownBy(() -> service.compare(LEAGUE_ID, "A", "A", 3))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void pastSeasonNeedsExplicitWeek() {
        given(sleeperClient.getNflState()).willReturn(new NflState("2027", 1, "regular"));

        assertThatThrownBy(() -> service.compare(LEAGUE_ID, "A", "B", null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("This league is from the 2026 season; choose a week to compare");
    }

    @Test
    void matchupMultiplierIsCappedAtFifteenPercent() {
        assertThat(StartSitService.matchupMultiplier(new Matchup("NYJ", true, 1, 32, 40.0, 20.0))).isEqualTo(1.15);
        assertThat(StartSitService.matchupMultiplier(new Matchup("SF", true, 32, 32, 10.0, 20.0))).isEqualTo(0.85);
        assertThat(StartSitService.matchupMultiplier(new Matchup("DAL", true, 10, 32, 22.0, 20.0))).isEqualTo(1.1);
        assertThat(StartSitService.matchupMultiplier(null)).isEqualTo(1.0);
    }

    @Test
    void standingPhraseUsesNearerEndOfTable() {
        assertThat(StartSitService.standingPhrase(1, 32)).isEqualTo("the most");
        assertThat(StartSitService.standingPhrase(4, 32)).isEqualTo("the 4th-most");
        assertThat(StartSitService.standingPhrase(30, 32)).isEqualTo("the 3rd-fewest");
        assertThat(StartSitService.standingPhrase(32, 32)).isEqualTo("the fewest");
        assertThat(StartSitService.ordinal(11)).isEqualTo("11th");
        assertThat(StartSitService.ordinal(22)).isEqualTo("22nd");
    }

    private void givenPlayers(PlayerSummary a, PlayerSummary b) {
        lenient().when(playerService.findSummary("A")).thenReturn(Optional.of(a));
        lenient().when(playerService.findSummary("B")).thenReturn(Optional.of(b));
    }

    private static SleeperWeeklyEntry rushing(String id, String team, String opponent, int week, double yards) {
        return new SleeperWeeklyEntry(id, team, opponent, week, Map.of("gp", 1, "rush_yd", yards));
    }

    private static SleeperWeeklyEntry defense(String team, int week, double allowedToRbs) {
        return new SleeperWeeklyEntry(team, team, "X", week,
                Map.of("gp", 1, "fan_pts_allow", 100, "fan_pts_allow_rb", allowedToRbs));
    }
}
