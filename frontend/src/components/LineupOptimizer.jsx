import { useState } from 'react'
import { fetchLineup } from '../api.js'
import { formatPoints } from '../format.js'
import { useApi } from '../hooks.js'
import { PositionChip } from './PlayerBadges.jsx'
import { PlayerLink } from './PlayerDialog.jsx'
import { ErrorMessage, Loading } from './Status.jsx'

function LineupCell({ entry }) {
  if (!entry) return <span className="muted">Empty</span>
  return (
    <span className="lineup-cell">
      <PositionChip position={entry.player.position} />
      <span className="lineup-name">
        <PlayerLink player={entry.player} />
        <span className="muted small">
          {' '}
          {entry.available ? (entry.opponent ?? '') : entry.availabilityNote}
          {entry.locked && ' · locked'}
        </span>
      </span>
      <span className="lineup-points">{formatPoints(entry.expectedPoints)}</span>
    </span>
  )
}

export default function LineupOptimizer({ leagueId, userId }) {
  const lineup = useApi((signal) => fetchLineup(leagueId, userId, signal), `${leagueId}|${userId}`)
  const [showTable, setShowTable] = useState(false)

  if (lineup.loading) return <Loading label="Checking your lineup…" />
  if (lineup.error) return <ErrorMessage error={lineup.error} onRetry={lineup.retry} />
  if (!lineup.data) return null

  const { week, slots, currentTotal, optimalTotal, gain, changes, notes } = lineup.data
  const optimal = gain < 0.05

  return (
    <section className={`card optimizer${optimal ? '' : ' has-changes'}`} aria-live="polite">
      <div className="start-sit-header">
        <div>
          <span className="muted">Week {week} lineup</span>
          <h3>{optimal ? 'Your lineup is already optimal' : `Gain ${formatPoints(gain)} expected points`}</h3>
        </div>
        <span className="lineup-totals">
          {formatPoints(currentTotal)}
          {!optimal && <> → <strong>{formatPoints(optimalTotal)}</strong></>}
        </span>
      </div>
      {changes.length > 0 && (
        <ul className="reasons">
          {changes.map((c) => (
            <li key={c}>{c}</li>
          ))}
        </ul>
      )}
      <button type="button" className="link" aria-expanded={showTable} onClick={() => setShowTable((v) => !v)}>
        {showTable ? 'Hide lineup' : optimal ? 'Show lineup' : 'Compare current and best lineup'}
      </button>
      {showTable && (
        <div className="table-scroll">
          <table className="lineup-table">
            <thead>
              <tr>
                <th scope="col">Slot</th>
                <th scope="col">Current</th>
                <th scope="col">Best</th>
              </tr>
            </thead>
            <tbody>
              {slots.map((s, i) => {
                const changed = s.current?.player.playerId !== s.optimal?.player.playerId
                return (
                  <tr key={i} className={changed ? 'changed' : undefined}>
                    <th scope="row">{s.slot.replace('_', ' ')}</th>
                    <td>
                      <LineupCell entry={s.current} />
                    </td>
                    <td>
                      <LineupCell entry={s.optimal} />
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
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
