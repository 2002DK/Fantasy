package com.fantasy.trade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fantasy.league.InvalidRequestException;
import com.fantasy.league.LeagueData;
import com.fantasy.league.SeasonCalendar;
import com.fantasy.lineup.LineupSolver;
import com.fantasy.lineup.LineupSolver.Candidate;
import com.fantasy.player.PlayerSummary;
import com.fantasy.sleeper.SleeperLeague;
import com.fantasy.stats.ScoringCalculator;
import com.fantasy.trade.TradeIdeasResponse.Idea;
import com.fantasy.trade.TradeResponse.TradePlayer;

/**
 * Suggests trades that raise both teams' expected starting lineups, so the other
 * manager has a reason to accept.
 *
 * <p>Each player is valued at their rest-of-season points averaged over the remaining
 * weeks (so byes and injuries count), and a team's strength is its best lineup under
 * those averages. Every one-for-one, two-for-one and one-for-two deal between the
 * user's tradeable players and each partner's is tried; a deal qualifies when the
 * user's lineup gains at least {@link #MIN_YOUR_GAIN} a week, the partner's at least
 * {@link #MIN_THEIR_GAIN}, and the partner gets back at least {@link #MIN_VALUE_RETURN} of the trade
 * value (rest-of-season points above replacement) they send, so they aren't fleeced.
 */
@Service
public class TradeFinderService {

    static final int MAX_IDEAS = 8;
    static final int MAX_IDEAS_PER_PARTNER = 2;
    static final int MAX_IDEAS_PER_PLAYER = 2;
    /** Expected lineup points per week the user must gain for an idea to be worth showing. */
    static final double MIN_YOUR_GAIN = 0.5;
    /** Expected lineup points per week the partner must gain, so the offer is plausible. */
    static final double MIN_THEIR_GAIN = 0.25;
    /** Share of the trade value they send that the partner must get back. */
    static final double MIN_VALUE_RETURN = 0.7;

    private final LeagueData leagueData;
    private final SeasonCalendar calendar;
    private final SeasonValuer valuer;

    public TradeFinderService(LeagueData leagueData, SeasonCalendar calendar, SeasonValuer valuer) {
        this.leagueData = leagueData;
        this.calendar = calendar;
        this.valuer = valuer;
    }

    private record Deal(LeagueData.Team partner, List<PlayerSummary> give, List<PlayerSummary> get, double yourGain,
            double theirGain) {
    }

    public TradeIdeasResponse findIdeas(String leagueId, String userId) {
        LeagueData.Snapshot snapshot = leagueData.load(leagueId);
        SleeperLeague league = snapshot.league();
        LeagueData.Team me = snapshot.teamOf(userId);
        int currentWeek = calendar.currentWeek(league);
        int lastWeek = SeasonCalendar.lastFantasyWeek(league.settings());
        if (currentWeek > lastWeek) {
            throw new InvalidRequestException("This league's season is over (it ended in week " + lastWeek + ")");
        }
        SeasonValuer.Season season = valuer.load(league, currentWeek, lastWeek);
        List<String> notes = new ArrayList<>(season.notes());
        if (league.settings() != null && league.settings().tradeDeadline() > 0
                && currentWeek > league.settings().tradeDeadline()) {
            notes.add(0, "This league's trade deadline (week %d) has passed.".formatted(league.settings().tradeDeadline()));
        }

        int weeks = lastWeek - currentWeek + 1;
        Map<String, TradePlayer> values = new HashMap<>();
        Map<Integer, List<Candidate>> rosters = new HashMap<>();
        for (LeagueData.Team team : snapshot.teams()) {
            Set<String> taxi = new HashSet<>(team.roster().taxi() != null ? team.roster().taxi() : List.of());
            List<Candidate> candidates = new ArrayList<>();
            for (PlayerSummary p : team.players()) {
                if (p == null || taxi.contains(p.playerId())) {
                    continue;
                }
                TradePlayer value = valuer.evaluate(p, season);
                values.put(p.playerId(), value);
                candidates.add(new Candidate(p, value.restOfSeasonPoints() / weeks, false));
            }
            rosters.put(team.rosterId(), candidates);
        }

        List<String> slots = LineupSolver.startingSlots(snapshot.rosterPositions());
        List<Candidate> mine = rosters.get(me.rosterId());
        double myBase = strength(slots, mine);
        List<List<Candidate>> myPackages = packages(tradeable(mine, values));

        List<Deal> deals = new ArrayList<>();
        for (LeagueData.Team partner : snapshot.teams()) {
            if (partner.rosterId() == me.rosterId()) {
                continue;
            }
            List<Candidate> theirs = rosters.get(partner.rosterId());
            double theirBase = strength(slots, theirs);
            for (List<Candidate> give : myPackages) {
                for (List<Candidate> get : packages(tradeable(theirs, values))) {
                    if (give.size() == 2 && get.size() == 2 || !fairForPartner(give, get, values)) {
                        continue;
                    }
                    double yourGain = strength(slots, swap(mine, give, get)) - myBase;
                    if (yourGain < MIN_YOUR_GAIN) {
                        continue;
                    }
                    double theirGain = strength(slots, swap(theirs, get, give)) - theirBase;
                    if (theirGain >= MIN_THEIR_GAIN) {
                        deals.add(new Deal(partner, players(give), players(get), yourGain, theirGain));
                    }
                }
            }
        }

        List<Idea> ideas = pick(deals, values);
        if (ideas.isEmpty()) {
            notes.add(0, "No trades improve both lineups right now.");
        }
        notes.add("Deals receiving more players than they send need an open roster spot or a drop.");
        return new TradeIdeasResponse(currentWeek, lastWeek, ideas, notes);
    }

    /** The partner receives at least {@link #MIN_VALUE_RETURN} of the value they give up. */
    static boolean fairForPartner(List<Candidate> give, List<Candidate> get, Map<String, TradePlayer> values) {
        double theyReceive = give.stream().mapToDouble(c -> values.get(c.player().playerId()).value()).sum();
        double theySend = get.stream().mapToDouble(c -> values.get(c.player().playerId()).value()).sum();
        return theyReceive >= MIN_VALUE_RETURN * theySend;
    }

    static double strength(List<String> slots, List<Candidate> roster) {
        return LineupSolver.total(LineupSolver.solve(slots, List.of(), roster));
    }

    /** Players worth trading: value above replacement at their position. */
    private static List<Candidate> tradeable(List<Candidate> roster, Map<String, TradePlayer> values) {
        return roster.stream().filter(c -> values.get(c.player().playerId()).value() > 0).toList();
    }

    /** Every single player and every pair. */
    private static List<List<Candidate>> packages(List<Candidate> players) {
        List<List<Candidate>> packages = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            packages.add(List.of(players.get(i)));
            for (int j = i + 1; j < players.size(); j++) {
                packages.add(List.of(players.get(i), players.get(j)));
            }
        }
        return packages;
    }

    private static List<Candidate> swap(List<Candidate> roster, List<Candidate> out, List<Candidate> in) {
        Set<String> outIds = out.stream().map(c -> c.player().playerId()).collect(Collectors.toSet());
        List<Candidate> result = new ArrayList<>(roster.size() + in.size());
        for (Candidate c : roster) {
            if (!outIds.contains(c.player().playerId())) {
                result.add(c);
            }
        }
        result.addAll(in);
        return result;
    }

    private static List<PlayerSummary> players(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::player).toList();
    }

    /** Best for the user first, spread across partners and players so the list isn't one idea repeated. */
    private static List<Idea> pick(List<Deal> deals, Map<String, TradePlayer> values) {
        Map<Integer, Integer> perPartner = new HashMap<>();
        Map<String, Integer> perPlayer = new HashMap<>();
        List<Idea> ideas = new ArrayList<>();
        for (Deal d : deals.stream().sorted(Comparator.comparingDouble(Deal::yourGain).reversed()).toList()) {
            if (ideas.size() == MAX_IDEAS) {
                break;
            }
            List<PlayerSummary> involved = new ArrayList<>(d.give());
            involved.addAll(d.get());
            if (perPartner.getOrDefault(d.partner().rosterId(), 0) >= MAX_IDEAS_PER_PARTNER
                    || involved.stream().anyMatch(p -> perPlayer.getOrDefault(p.playerId(), 0) >= MAX_IDEAS_PER_PLAYER)) {
                continue;
            }
            perPartner.merge(d.partner().rosterId(), 1, Integer::sum);
            involved.forEach(p -> perPlayer.merge(p.playerId(), 1, Integer::sum));
            double yourGain = ScoringCalculator.round(d.yourGain());
            double theirGain = ScoringCalculator.round(d.theirGain());
            ideas.add(new Idea(d.partner().rosterId(), d.partner().name(),
                    d.give().stream().map(p -> values.get(p.playerId())).toList(),
                    d.get().stream().map(p -> values.get(p.playerId())).toList(),
                    yourGain, theirGain, reason(d, yourGain, theirGain)));
        }
        return ideas;
    }

    private static String reason(Deal d, double yourGain, double theirGain) {
        return "Your starting lineup gains %.1f points a week by adding %s; %s gains %.1f a week by adding %s."
                .formatted(yourGain, names(d.get()), d.partner().name(), theirGain, names(d.give()));
    }

    private static String names(List<PlayerSummary> players) {
        return players.stream()
                .map(p -> Optional.ofNullable(p.name()).orElse("Player " + p.playerId()) + " (" + p.position() + ")")
                .collect(Collectors.joining(" and "));
    }

}
