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

function Cell({ cell }) {
  const { label, full } = STATUS[cell.status]
  const text = label ?? formatPoints(cell.projected)
  const description = `${full}${cell.opponent ? `, ${cell.opponent}` : ''}${
    cell.projected != null && cell.status === 'ok' ? `, projected ${formatPoints(cell.projected)}` : ''
  }`
  return (
    <td className={`planner-cell ${cell.status}`} title={description}>
      <span aria-hidden="true">{text}</span>
      <span className="visually-hidden">{description}</span>
    </td>
  )
}

export default function PlannerView({ leagueId, userId }) {
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
          <h3>Weeks you'll be short</h3>
          <ul>
            {shortages.map((s) => (
              <li key={s.week}>{s.message}</li>
            ))}
          </ul>
          <p className="muted small">Pick up a player at those positions before then, or plan a trade.</p>
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
                    <Cell key={cell.week} cell={cell} />
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="muted small table-legend">
          Numbers are projected points in your scoring. BYE = on bye, OUT = out or on IR, Q/D = questionable or doubtful
          this week, ✓ = already played. Starters are listed first.
        </p>
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
