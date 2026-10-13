import { useState } from 'react'
import { fetchLockIn, setLocked } from '../api.js'
import { formatPoints, weekday } from '../format.js'
import { useApi } from '../hooks.js'
import { InjuryTag, PositionChip } from './PlayerBadges.jsx'
import { ErrorMessage, HowItWorks, Loading } from './Status.jsx'

const ACTION_LABELS = {
  LOCK: 'Lock it in',
  PASS: 'Pass',
  LOCKED: 'Locked',
  PLAYING: 'Playing',
  WAITING: 'Not played yet',
  LAST_GAME: 'Last game counts',
  NO_GAMES: 'No games',
}

function gameLabel(game) {
  return `${weekday(game.date)} ${game.home ? 'vs' : '@'} ${game.opponent}`
}

function StarterCard({ advice, pending, onToggle }) {
  const { slot, player, action, message, upcoming, lockedGame, canLock } = advice
  const last = upcoming.length - 1
  return (
    <li className={`card lock-advice action-${action}`}>
      <div className="lock-advice-header">
        <span className="slot">{slot.replace('_', ' ')}</span>
        <PositionChip position={player.position} />
        <span className="player-name">
          <strong>{player.name ?? `Player ${player.playerId}`}</strong>
          <InjuryTag status={player.injuryStatus} />
        </span>
        <span className={`tag action-tag action-${action}`}>{ACTION_LABELS[action]}</span>
      </div>
      <p className="lock-advice-message">{message}</p>
      {lockedGame && (
        <p className="muted small">
          From {weekday(lockedGame.date)}'s game against {lockedGame.opponent}
        </p>
      )}
      {upcoming.length > 0 && action !== 'LOCKED' && (
        <ul className="lock-in-games" aria-label={`${player.name ?? 'Player'}'s games left this week`}>
          {upcoming.map((game, i) => (
            <li key={i}>
              <span>
                {gameLabel(game)}
                <span className="muted"> · {formatPoints(game.expectedPoints)}</span>
              </span>
              <span className="muted">
                {i === last ? 'counts if nothing locked' : `lock ${formatPoints(game.lockAbove)}+`}
              </span>
            </li>
          ))}
        </ul>
      )}
      {(canLock || action === 'LOCKED') && (
        <button
          type="button"
          // Only a "lock it in" call gets the primary button; elsewhere marking a lock goes against the advice
          className={action === 'LOCK' ? undefined : 'secondary'}
          disabled={pending}
          onClick={() => onToggle(player.playerId, action !== 'LOCKED')}
        >
          {pending ? 'Saving…' : action === 'LOCKED' ? 'Unmark lock' : 'I locked this score'}
        </button>
      )}
    </li>
  )
}

/**
 * Lock-or-pass calls for each starter in a basketball Lock-In league, and toggles to
 * record the locks made in Sleeper (whose API doesn't report them).
 */
export default function LockInAdvisor({ leagueId, userId }) {
  const advice = useApi((signal) => fetchLockIn(leagueId, userId, signal), `${leagueId}|${userId}`)
  // A lock toggle returns fresh advice, which replaces the loaded data
  const [updated, setUpdated] = useState(null)
  const [pendingId, setPendingId] = useState(null)
  const [toggleError, setToggleError] = useState(null)

  if (advice.loading) return <Loading label="Checking your starters' games…" />
  if (advice.error) return <ErrorMessage error={advice.error} onRetry={advice.retry} />
  const data = updated ?? advice.data
  if (!data) return null

  async function toggle(playerId, locked) {
    setPendingId(playerId)
    setToggleError(null)
    try {
      setUpdated(await setLocked(leagueId, userId, playerId, locked))
    } catch (error) {
      setToggleError(error.message)
    } finally {
      setPendingId(null)
    }
  }

  const toLock = data.starters.filter((s) => s.action === 'LOCK').length
  const notStarted = data.starters.every((s) => s.action === 'WAITING' || s.action === 'NO_GAMES')
  const heading = notStarted
    ? 'No games played yet this week'
    : toLock === 0
      ? 'No scores to lock right now'
      : toLock === 1
        ? '1 score to lock'
        : `${toLock} scores to lock`
  return (
    <section className="lock-advisor">
      <div className="card start-sit-header">
        <div>
          <span className="muted">Week {data.week} Lock-In</span>
          <h3>{heading}</h3>
        </div>
      </div>
      {toggleError && (
        <p className="error" role="alert">
          {toggleError}
        </p>
      )}
      <ul className="lock-advice-list" aria-live="polite">
        {data.starters.map((s) => (
          <StarterCard key={s.player.playerId} advice={s} pending={pendingId === s.player.playerId} onToggle={toggle} />
        ))}
      </ul>
      {data.notes.length > 0 && (
        <ul className="notes muted">
          {data.notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      )}
      <HowItWorks>
        <p>
          In Lock-In, one game per starter counts each week. After a game you can lock in its score before the
          player's next game starts; if you never lock, their last game of the week counts, even a 0.
        </p>
        <p>
          A score is worth locking when it beats what waiting is worth: the expected best result of the games left,
          if you keep locking or passing well. That value comes from each game's projection and recent form, and
          shrinks as games run out, which is why the score to beat drops through the week.
        </p>
        <p>
          Sleeper doesn't share which scores you've locked, so tap <strong>I locked this score</strong> after locking
          in Sleeper. Marked scores count as final in your lineup and matchup.
        </p>
      </HowItWorks>
    </section>
  )
}
