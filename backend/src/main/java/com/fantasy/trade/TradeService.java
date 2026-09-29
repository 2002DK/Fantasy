package com.fantasy.trade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.league.NotFoundException;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.player.PlayerService;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperClient;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.stats.Positions;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.trade.TradeResponse.Side;
import com.fantasy.trade.TradeResponse.Strength;
import com.fantasy.trade.TradeResponse.TradePlayer;
import com.fantasy.trade.TradeResponse.Verdict;
import com.fantasy.trade.TradeResponse.Winner;

/**
 * Evaluates a trade by rest-of-season value above replacement ({@link SeasonValuer}),
 * and explains the verdict.
 */
@Service
public class TradeService {

    static final int MAX_PLAYERS_PER_SIDE = 5;
    static final double FAIR_MARGIN = 0.10;
    static final double SLIGHT_MARGIN = 0.25;
    /** Schedule strength worth calling out in the reasons, in percent. */
    static final double NOTABLE_SCHEDULE_PERCENT = 8;

    private final SleeperClient sleeperClient;
    private final PlayerService playerService;
    private final SeasonValuer valuer;
    private final SeasonCalendar calendar;

    public TradeService(SleeperClient sleeperClient, PlayerService playerService, SeasonValuer valuer,
            SeasonCalendar calendar) {
        this.sleeperClient = sleeperClient;
        this.playerService = playerService;
        this.valuer = valuer;
        this.calendar = calendar;
    }

    public TradeResponse analyze(String leagueId, List<String> giveIds, List<String> getIds) {
        validateSides(giveIds, getIds);
        SleeperLeague league = sleeperClient.getLeague(leagueId)
                .orElseThrow(() -> new NotFoundException("No Sleeper league found with ID '" + leagueId + "'"));
        int currentWeek;
        try {
            currentWeek = calendar.currentWeek(league);
        } catch (InvalidRequestException e) {
            throw new InvalidRequestException(
                    "Trades can only be analyzed for the current season; this league is from " + league.season());
        }
        int lastWeek = lastFantasyWeek(league.settings());
        if (currentWeek > lastWeek) {
            throw new InvalidRequestException("This league's season is over (it ended in week " + lastWeek + ")");
        }
        List<PlayerSummary> give = giveIds.stream().map(this::findPlayer).toList();
        List<PlayerSummary> get = getIds.stream().map(this::findPlayer).toList();

        SeasonValuer.Season season = valuer.load(league, currentWeek, lastWeek);
        Side giveSide = side(give, season);
        Side getSide = side(get, season);
        Verdict verdict = verdict(giveSide, getSide);

        List<String> notes = new ArrayList<>(season.notes());
        if (league.settings() != null && league.settings().tradeDeadline() > 0
                && currentWeek > league.settings().tradeDeadline()) {
            notes.add(0, "This league's trade deadline (week %d) has passed.".formatted(league.settings().tradeDeadline()));
        }
        return new TradeResponse(league.season(), currentWeek, lastWeek, verdict, giveSide, getSide,
                reasons(giveSide, getSide, verdict), notes);
    }

    private static void validateSides(List<String> give, List<String> get) {
        if (give.isEmpty() || get.isEmpty()) {
            throw new InvalidRequestException("Pick at least one player on each side of the trade");
        }
        if (give.size() > MAX_PLAYERS_PER_SIDE || get.size() > MAX_PLAYERS_PER_SIDE) {
            throw new InvalidRequestException("A trade can include at most " + MAX_PLAYERS_PER_SIDE + " players per side");
        }
        Set<String> seen = new HashSet<>();
        for (String id : give) {
            if (!seen.add(id)) {
                throw new InvalidRequestException("A player is listed twice in the trade");
            }
        }
        for (String id : get) {
            if (!seen.add(id)) {
                throw new InvalidRequestException("A player is listed twice in the trade");
            }
        }
    }

    /** Kept for tests; see {@link SeasonCalendar#lastFantasyWeek}. */
    static int lastFantasyWeek(SleeperLeague.Settings settings) {
        return SeasonCalendar.lastFantasyWeek(settings);
    }

    private PlayerSummary findPlayer(String playerId) {
        return playerService.findSummary(playerId)
                .orElseThrow(() -> new NotFoundException("No player found with ID '" + playerId + "'"));
    }

    private Side side(List<PlayerSummary> players, SeasonValuer.Season season) {
        List<TradePlayer> evaluated = players.stream().map(p -> valuer.evaluate(p, season)).toList();
        return new Side(evaluated,
                ScoringCalculator.round(evaluated.stream().mapToDouble(TradePlayer::restOfSeasonPoints).sum()),
                ScoringCalculator.round(evaluated.stream().mapToDouble(TradePlayer::value).sum()));
    }

    static Verdict verdict(Side give, Side get) {
        double difference = get.totalValue() - give.totalValue();
        double larger = Math.max(get.totalValue(), give.totalValue());
        if (larger <= 0) {
            return new Verdict(null, Strength.FAIR, 0, 0);
        }
        double margin = Math.abs(difference) / larger;
        Strength strength = margin < FAIR_MARGIN ? Strength.FAIR
                : margin < SLIGHT_MARGIN ? Strength.SLIGHT : Strength.CLEAR;
        Winner winner = difference >= 0 ? Winner.YOU : Winner.THEM;
        return new Verdict(winner, strength, ScoringCalculator.round(difference), ScoringCalculator.round(margin * 100));
    }

    // --- Explanation ---

    private static List<String> reasons(Side give, Side get, Verdict verdict) {
        List<String> reasons = new ArrayList<>();
        reasons.add("You receive %s points of rest-of-season value above replacement and give up %s."
                .formatted(fmt(get.totalValue()), fmt(give.totalValue())));

        List<TradePlayer> all = new ArrayList<>(give.players());
        all.addAll(get.players());
        TradePlayer mostValuable = all.stream().max(Comparator.comparingDouble(TradePlayer::value)).orElseThrow();
        if (mostValuable.value() > 0) {
            // Parenthesized: without it, .formatted binds only to the second string literal
            reasons.add(("%s is the most valuable player in the deal: %s projected points over %s, %s above a "
                    + "replacement-level %s.")
                    .formatted(name(mostValuable), fmt(mostValuable.restOfSeasonPoints()),
                            games(mostValuable.remainingGames()), fmt(mostValuable.value()),
                            mostValuable.player().position()));
        }
        TradePlayer mostPoints = all.stream().max(Comparator.comparingDouble(TradePlayer::restOfSeasonPoints)).orElseThrow();
        if (mostPoints != mostValuable && mostPoints.replacementPoints() > 0) {
            reasons.add(("%s scores the most raw points, but a replacement-level %s in this league still scores %s, "
                    + "so their edge over what's available is smaller.")
                    .formatted(name(mostPoints), mostPoints.player().position(), fmt(mostPoints.replacementPoints())));
        }
        for (TradePlayer p : all) {
            Double strength = p.scheduleStrengthPercent();
            if (strength != null && Math.abs(strength) >= NOTABLE_SCHEDULE_PERCENT) {
                reasons.add("%s has one of the %s remaining schedules: opponents allow %s%% %s points to %s than average."
                        .formatted(name(p), strength > 0 ? "easier" : "tougher", "%.0f".formatted(Math.abs(strength)),
                                strength > 0 ? "more" : "fewer", Positions.pluralName(p.player().position())));
            }
        }
        for (TradePlayer p : all) {
            if (p.note() != null && p.note().startsWith("No starting slot")) {
                reasons.add("%s has no starting slot in this league, so they add no trade value.".formatted(name(p)));
            } else if (p.note() != null) {
                reasons.add("%s is on %s, so only projected games after a return count and recent form is ignored."
                        .formatted(name(p), p.note()));
            } else if (p.value() == 0 && p.remainingGames() > 0) {
                reasons.add("%s projects at or below replacement level, so they add little trade value."
                        .formatted(name(p)));
            }
        }
        int extra = get.players().size() - give.players().size();
        if (extra > 0) {
            reasons.add("You receive %d players for %d, so you'll need to open %s."
                    .formatted(get.players().size(), give.players().size(),
                            extra == 1 ? "a roster spot" : extra + " roster spots"));
        } else if (extra < 0) {
            reasons.add("You send %d players for %d, which frees %s."
                    .formatted(give.players().size(), get.players().size(),
                            extra == -1 ? "a roster spot" : -extra + " roster spots"));
        }
        if (verdict.winner() != null && verdict.strength() == Strength.FAIR) {
            reasons.add("The values are within 10%, so this is a fair trade. Roster needs can reasonably decide it.");
        }
        return reasons;
    }

    private static String name(TradePlayer p) {
        return Optional.ofNullable(p.player().name()).orElse("Player " + p.player().playerId());
    }

    private static String fmt(double points) {
        return "%.1f".formatted(points);
    }

    private static String games(int count) {
        return count == 1 ? "1 game" : count + " games";
    }
}
