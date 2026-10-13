import { fetchPlanner } from '../api.js'
import { formatPoints } from '../format.js'
import { useApi } from '../hooks.js'
import { PositionChip } from './PlayerBadges.jsx'
import { PlayerLink } from './PlayerDialog.jsx'
import { ErrorMessage, Loading } from './Status.jsx'

const STATUS = {
  ok: { label: null, full: 'Available' },
  bye: { label: 'BYE', full: 'On bye' },
  out: { label: 'OUT', full: 'Out' },
  doubtful: { label: 'D', full: 'Doubtful' },
  questionable: { label: 'Q', full: 'Questionable' },
  played: { label: '✓', full: 'Game already played this week' },
}

/** Basketball: no games that week instead of a bye. */
const BASKETBALL_STATUS = { ...STATUS, bye: { label: '–', full: 'No games' } }

function Cell({ cell, basketball }) {
  const { label, full } = (basketball ? BASKETBALL_STATUS : STATUS)[cell.status]
  const text = label ?? formatPoints(cell.projected)
  const value = basketball ? 'Lock-In value' : 'projected'
  const description = `${full}${cell.opponent ? `, ${cell.opponent}` : ''}${
    cell.projected != null && cell.status === 'ok' ? `, ${value} ${formatPoints(cell.projected)}` : ''
  }`
  return (
    <td className={`planner-cell ${cell.status}`} title={description}>
      <span aria-hidden="true">
        {text}
        {basketball && cell.games > 0 && <span className="planner-games">{cell.games}g</span>}
      </span>
      <span className="visually-hidden">{description}</span>
    </td>
  )
}

export default function PlannerView({ leagueId, userId, basketball }) {
  const planner = useApi((signal) => fetchPlanner(leagueId, userId, signal), `${leagueId}|${userId}`)

  if (planner.loading) return <Loading label="Mapping out the rest of your season…" />
  if (planner.error) return <ErrorMessage error={planner.error} onRetry={planner.retry} />
  if (!planner.data) return null

  const { weeks, players, shortages, notes } = planner.data
  const firstBench = players.findIndex((p) => !p.starter)

  return (
    <section className="planner">
      {shortages.length > 0 ? (
        <div className="card shortages" role="note">
          <h3>{basketball ? 'Weeks to watch' : "Weeks you'll be short"}</h3>
          <ul>
            {shortages.map((s) => (
              <li key={s.week}>{s.message}</li>
            ))}
          </ul>
          <p className="muted small">
            {basketball
              ? 'In light weeks, players with more games give you more chances at a score worth locking.'
              : 'Pick up a player at those positions before then, or plan a trade.'}
          </p>
        </div>
      ) : (
        <p className="hint muted">You can fill every starting slot in every remaining week.</p>
      )}

      <div className="card table-card">
        <div className="table-scroll">
          <table className="planner-table">
            <caption className="visually-hidden">Player availability and projected points by week</caption>
            <thead>
              <tr>
                <th scope="col" className="planner-player-col">
                  Player
                </th>
                {weeks.map((w) => (
                  <th scope="col" key={w} className={shortages.some((s) => s.week === w) ? 'short' : undefined}>
                    {w}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {players.map((row, i) => (
                <tr key={row.player.playerId} className={i === firstBench ? 'bench-start' : undefined}>
                  <th scope="row" className="planner-player-col">
                    <span className="planner-player">
                      <PositionChip position={row.player.position} />
                      <PlayerLink player={row.player} />
                    </span>
                  </th>
                  {row.cells.map((cell) => (
                    <Cell key={cell.week} cell={cell} basketball={basketball} />
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {basketball ? (
          <p className="muted small table-legend">
            Numbers are each week's Lock-In value in your scoring, with the number of games (3g). – = no games, OUT =
            out or on IR, Q/D = questionable or doubtful this week, ✓ = no games left this week. Starters are listed
            first.
          </p>
        ) : (
          <p className="muted small table-legend">
            Numbers are projected points in your scoring. BYE = on bye, OUT = out or on IR, Q/D = questionable or
            doubtful this week, ✓ = already played. Starters are listed first.
          </p>
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
