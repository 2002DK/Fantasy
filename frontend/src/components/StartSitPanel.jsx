import { fetchStartSit } from '../api.js'
import { formatPoints, games, ordinal } from '../format.js'
import { useApi } from '../hooks.js'
import { InjuryTag, PositionChip } from './PlayerBadges.jsx'
import { ErrorMessage, HowItWorks, Loading } from './Status.jsx'

const CONFIDENCE_LABELS = {
  CLEAR: 'Clear call',
  LEAN: 'Lean',
  TOSS_UP: 'Toss-up',
}

/**
 * Rank 1 = the defense that allows the most points to this position. Described from
 * the nearer end of the table, matching the backend's reason text.
 */
function matchupLabel(matchup) {
  const where = matchup.home ? 'vs' : '@'
  const { rank, teams } = matchup
  if (rank == null) return `${where} ${matchup.opponent}`
  let standing
  if (rank === 1) standing = 'easiest matchup'
  else if (rank === teams) standing = 'toughest matchup'
  else if (rank <= Math.floor((teams + 1) / 2)) standing = `${ordinal(rank)} easiest`
  else standing = `${ordinal(teams - rank + 1)} toughest`
  return `${where} ${matchup.opponent} · ${standing}`
}

function PlayerColumn({ analysis, recommended }) {
  const { player, available, availabilityNote, projectedPoints, recentAverage, recentGames, matchup, score } =
    analysis
  return (
    <div className={`comparison-player${recommended ? ' recommended' : ''}`}>
      <div className="comparison-name">
        <PositionChip position={player.position} />
        <span>
          <strong>{player.name ?? `Player ${player.playerId}`}</strong>
          <InjuryTag status={player.injuryStatus} />
          <span className="muted"> {player.team ?? 'FA'}</span>
        </span>
      </div>
      {!available && <p className="unavailable">Can't start: {availabilityNote}</p>}
      <dl className="comparison-stats">
        <div>
          <dt>Projected</dt>
          <dd>{formatPoints(projectedPoints)}</dd>
        </div>
        <div>
          <dt>
            Recent avg
            {recentGames.length > 0 && (
              <span className="muted"> ({games(recentGames.length)})</span>
            )}
          </dt>
          <dd title={recentGames.map((g) => `Wk ${g.week} vs ${g.opponent}: ${g.points}`).join('\n')}>
            {formatPoints(recentAverage)}
          </dd>
        </div>
        <div className="wide">
          <dt>Matchup</dt>
          <dd>{matchup ? matchupLabel(matchup) : '–'}</dd>
        </div>
        <div>
          <dt>Score</dt>
          <dd className="score">{formatPoints(score)}</dd>
        </div>
      </dl>
    </div>
  )
}

export default function StartSitPanel({ leagueId, playerIds, onClear }) {
  const [playerA, playerB] = playerIds
  const comparison = useApi(
    (signal) => fetchStartSit(leagueId, playerA, playerB, signal),
    `${leagueId}|${playerA}|${playerB}`,
  )
  const clearButton = (
    <button type="button" className="link" onClick={onClear}>
      Clear
    </button>
  )

  if (comparison.loading) return <Loading label="Comparing players…" />
  if (comparison.error) return <ErrorMessage error={comparison.error} onRetry={comparison.retry} action={clearButton} />
  if (!comparison.data) return null

  const { week, recommendation, players, reasons, notes } = comparison.data
  const pick = recommendation && players.find((p) => p.player.playerId === recommendation.playerId)

  return (
    <section className="card start-sit" aria-live="polite">
      <div className="start-sit-header">
        <div>
          <span className="muted">Week {week} start/sit</span>
          <h3>{pick ? `Start ${pick.player.name}` : 'Not enough data to choose'}</h3>
        </div>
        {recommendation && (
          <span className={`tag confidence-${recommendation.confidence}`}>
            {CONFIDENCE_LABELS[recommendation.confidence]}
            {recommendation.confidence !== 'CLEAR' && ` · ${recommendation.marginPercent.toFixed(0)}% edge`}
          </span>
        )}
        {clearButton}
      </div>
      <div className="comparison">
        {players.map((analysis) => (
          <PlayerColumn
            key={analysis.player.playerId}
            analysis={analysis}
            recommended={analysis.player.playerId === recommendation?.playerId}
          />
        ))}
      </div>
      {reasons.length > 0 && (
        <ul className="reasons">
          {reasons.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      )}
      {notes.length > 0 && (
        <ul className="notes muted">
          {notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}
      <HowItWorks>
        <p>Each player's score blends:</p>
        <ul>
          <li>
            <strong>60% projected points</strong> for this week, from Sleeper's projections, scored with your league's
            settings.
          </li>
          <li>
            <strong>40% recent form</strong>: the average of their last 3 games, raised or lowered by up to 15% for how
            many points this week's opponent allows to their position. Projections already account for the opponent,
            so the matchup only adjusts form.
          </li>
        </ul>
        <p>
          Players who are Out, on IR or on bye can't start. Questionable players lose 10% and Doubtful players 50%.
          Scores within 5% are a toss-up and within 15% a lean.
        </p>
      </HowItWorks>
    </section>
  )
}
