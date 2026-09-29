import { useEffect, useRef, useState } from 'react'
import { fetchPlayer } from '../api.js'
import { formatPoints } from '../format.js'
import { useApi } from '../hooks.js'
import { InjuryTag, PositionChip } from './PlayerBadges.jsx'
import { PlayerContext, useOpenPlayer } from './playerContext.js'
import { ErrorMessage, Loading } from './Status.jsx'

/** A player's name as a button that opens their detail dialog. */
export function PlayerLink({ player, children }) {
  const openPlayer = useOpenPlayer()
  const label = player.name ?? `Player ${player.playerId}`
  return (
    <button type="button" className="player-link" onClick={() => openPlayer(player.playerId)}>
      {children ?? label}
    </button>
  )
}

/** Rank 1 = the opponent allows the most points to the position. */
function difficulty(rank, teams) {
  if (rank == null) return null
  if (rank <= Math.ceil(teams / 3)) return 'easy'
  if (rank > teams - Math.ceil(teams / 3)) return 'hard'
  return 'neutral'
}

function PlayerDetail({ leagueId, userId, playerId }) {
  const detail = useApi((signal) => fetchPlayer(leagueId, playerId, userId, signal), `${leagueId}|${playerId}`)
  if (detail.loading) return <Loading label="Loading player…" />
  if (detail.error) return <ErrorMessage error={detail.error} onRetry={detail.retry} />
  if (!detail.data) return null

  const { player, age, yearsExp, rosteredBy, onYourTeam, value, games, upcoming } = detail.data
  const maxPoints = Math.max(1, ...games.map((g) => g.points), ...upcoming.map((u) => u.projected ?? 0))

  return (
    <>
      <div className="dialog-header">
        <PositionChip position={player.position} />
        <div>
          <h2 id="player-dialog-title">
            {player.name}
            <InjuryTag status={player.injuryStatus} />
          </h2>
          <span className="muted">
            {player.team ?? 'Free agent'}
            {age != null && ` · Age ${age}`}
            {yearsExp != null && ` · ${yearsExp === 0 ? 'Rookie' : `${yearsExp} yr exp`}`}
            {' · '}
            {onYourTeam ? 'On your team' : rosteredBy ? `Rostered by ${rosteredBy}` : 'Free agent in this league'}
          </span>
        </div>
      </div>

      {value && (
        <dl className="comparison-stats detail-stats">
          <div>
            <dt>Rest of season</dt>
            <dd>
              {formatPoints(value.restOfSeasonPoints)}
              <span className="muted small"> / {value.remainingGames} games</span>
            </dd>
          </div>
          <div>
            <dt>Trade value</dt>
            <dd>{formatPoints(value.value)}</dd>
          </div>
          <div>
            <dt>Recent avg</dt>
            <dd>{formatPoints(value.recentAverage)}</dd>
          </div>
          <div>
            <dt>Schedule</dt>
            <dd>
              {value.scheduleStrengthPercent == null
                ? '–'
                : Math.abs(value.scheduleStrengthPercent) < 3
                  ? 'Average'
                  : `${Math.abs(value.scheduleStrengthPercent).toFixed(0)}% ${value.scheduleStrengthPercent > 0 ? 'easier' : 'tougher'}`}
            </dd>
          </div>
        </dl>
      )}

      <h3 className="dialog-section">This season</h3>
      {games.length === 0 ? (
        <p className="muted small">No games played yet.</p>
      ) : (
        <ul className="bar-list">
          {games.map((g) => (
            <li key={g.week}>
              <span className="bar-label">
                Wk {g.week} <span className="muted">{g.opponent}</span>
              </span>
              <span className="bar" style={{ width: `${(g.points / maxPoints) * 100}%` }} aria-hidden="true" />
              <span className="bar-value">{formatPoints(g.points)}</span>
            </li>
          ))}
        </ul>
      )}

      <h3 className="dialog-section">Upcoming</h3>
      <ul className="bar-list upcoming">
        {upcoming.map((u) => (
          <li key={u.week}>
            <span className="bar-label">
              Wk {u.week} <span className="muted">{u.opponent ?? 'Bye'}</span>
            </span>
            {u.opponent ? (
              <>
                <span
                  className={`bar projected ${difficulty(u.matchupRank, u.teams) ?? ''}`}
                  style={{ width: `${((u.projected ?? 0) / maxPoints) * 100}%` }}
                  aria-hidden="true"
                />
                <span className="bar-value">{formatPoints(u.projected)}</span>
              </>
            ) : (
              <span className="bar-value muted">–</span>
            )}
          </li>
        ))}
      </ul>
      <p className="muted small">
        Upcoming bars are projections in your league's scoring; green marks easier matchups and red tougher ones.
      </p>
    </>
  )
}

/**
 * Provides useOpenPlayer() to its children and renders the detail dialog. Uses the native
 * <dialog> element, which traps focus, closes on Escape and restores focus on close.
 */
export default function PlayerDialogProvider({ leagueId, userId, children }) {
  const [playerId, setPlayerId] = useState(null)
  const dialogRef = useRef(null)

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    if (playerId && !dialog.open) dialog.showModal()
    if (!playerId && dialog.open) dialog.close()
  }, [playerId])

  return (
    <PlayerContext.Provider value={setPlayerId}>
      {children}
      <dialog
        ref={dialogRef}
        className="player-dialog"
        aria-labelledby="player-dialog-title"
        onClose={() => setPlayerId(null)}
        onClick={(e) => e.target === dialogRef.current && setPlayerId(null)}
      >
        <button type="button" className="link dialog-close" onClick={() => setPlayerId(null)} aria-label="Close">
          ✕
        </button>
        {playerId && <PlayerDetail leagueId={leagueId} userId={userId} playerId={playerId} />}
      </dialog>
    </PlayerContext.Provider>
  )
}
