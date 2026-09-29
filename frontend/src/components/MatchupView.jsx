import { fetchMatchup } from '../api.js'
import { formatPoints } from '../format.js'
import { useApi } from '../hooks.js'
import { InjuryTag, PositionChip } from './PlayerBadges.jsx'
import { PlayerLink } from './PlayerDialog.jsx'
import { ErrorMessage, Loading } from './Status.jsx'

const STATUS_LABELS = { final: 'Final', live: 'Live', upcoming: '', bye: 'Bye', empty: '' }

function Starters({ side }) {
  return (
    <ul className="player-list matchup-starters">
      {side.starters.map((s, i) => (
        <li key={i} className="player-row">
          <span className="slot">{s.slot.replace('_', ' ')}</span>
          {s.player ? (
            <>
              <PositionChip position={s.player.position} />
              <span className="player-name">
                <PlayerLink player={s.player} />
                <InjuryTag status={s.player.injuryStatus} />
                <span className="muted small"> {s.opponent ?? ''}</span>
              </span>
              <span className={`game-status ${s.status}`}>{STATUS_LABELS[s.status]}</span>
              <span className="matchup-points">
                {s.status === 'upcoming' ? formatPoints(s.expectedPoints) : formatPoints(s.actualPoints)}
              </span>
            </>
          ) : (
            <span className="player-name muted">Empty</span>
          )}
        </li>
      ))}
    </ul>
  )
}

export default function MatchupView({ leagueId, userId, onOpenLineup }) {
  const matchup = useApi((signal) => fetchMatchup(leagueId, userId, signal), `${leagueId}|${userId}`)

  if (matchup.loading) return <Loading label="Loading this week's matchup…" />
  if (matchup.error) return <ErrorMessage error={matchup.error} onRetry={matchup.retry} />
  if (!matchup.data) return null

  const { week, you, opponent, winProbability, optimizedPoints, optimizedWinProbability, notes } = matchup.data
  const leading = opponent && you.projectedPoints >= opponent.projectedPoints
  // "0.0 scored" is noise until a game has kicked off
  const started = [you, opponent].some((side) => side?.starters.some((s) => s.status === 'live' || s.status === 'final'))

  return (
    <section className="matchup">
      <div className="card scoreboard">
        <span className="muted small">Week {week}</span>
        <div className="scoreboard-teams">
          <div className="scoreboard-team">
            <span className="scoreboard-name">{you.teamName}</span>
            <span className={`scoreboard-score${leading ? ' ahead' : ''}`}>{formatPoints(you.projectedPoints)}</span>
            {started && <span className="muted small">{formatPoints(you.actualPoints)} scored</span>}
          </div>
          <span className="scoreboard-vs muted">vs</span>
          <div className="scoreboard-team">
            <span className="scoreboard-name">{opponent?.teamName ?? 'No opponent'}</span>
            <span className={`scoreboard-score${opponent && !leading ? ' ahead' : ''}`}>
              {opponent ? formatPoints(opponent.projectedPoints) : '–'}
            </span>
            {opponent && started && (
              <span className="muted small">{formatPoints(opponent.actualPoints)} scored</span>
            )}
          </div>
        </div>
        {winProbability != null && (
          <div className="win-probability">
            <div className="win-bar" role="img" aria-label={`${winProbability.toFixed(0)}% chance to win`}>
              <span style={{ width: `${winProbability}%` }} />
            </div>
            <span>
              <strong>{winProbability.toFixed(0)}%</strong> to win
            </span>
          </div>
        )}
        {optimizedPoints != null && (
          <p className="optimize-callout">
            With your best lineup you'd project <strong>{formatPoints(optimizedPoints)}</strong> and win{' '}
            <strong>{optimizedWinProbability.toFixed(0)}%</strong> of the time.{' '}
            <button type="button" className="link" onClick={onOpenLineup}>
              See lineup changes
            </button>
          </p>
        )}
      </div>

      <div className="comparison matchup-sides">
        <section className="card">
          <h3>{you.teamName}</h3>
          <Starters side={you} />
        </section>
        {opponent && (
          <section className="card">
            <h3>{opponent.teamName}</h3>
            <Starters side={opponent} />
          </section>
        )}
      </div>
      {notes.length > 0 && (
        <ul className="notes muted">
          {notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      )}
    </section>
  )
}
