import { ErrorMessage, Loading } from './Status.jsx'

const INJURY_ABBREVIATIONS = {
  Questionable: 'Q',
  Doubtful: 'D',
  Out: 'O',
  IR: 'IR',
  PUP: 'PUP',
  Sus: 'SUS',
  NA: 'NA',
}

function InjuryTag({ status }) {
  if (!status) return null
  const severity = status === 'Questionable' ? 'minor' : 'major'
  return (
    <span className={`tag injury ${severity}`} title={status}>
      {INJURY_ABBREVIATIONS[status] ?? status}
    </span>
  )
}

function PositionChip({ position }) {
  return <span className={`pos pos-${position ?? 'unknown'}`}>{position ?? '?'}</span>
}

function PlayerCells({ player }) {
  if (!player) {
    return <span className="player-name muted">Empty</span>
  }
  return (
    <>
      <span className="player-name">
        {player.name ?? <span className="muted">Unknown player ({player.playerId})</span>}
        <InjuryTag status={player.injuryStatus} />
      </span>
      <span className="player-team muted">{player.team ?? 'FA'}</span>
    </>
  )
}

function PlayerSection({ title, players }) {
  if (players.length === 0) return null
  return (
    <section className="card roster-section">
      <h3>
        {title} <span className="muted count">{players.length}</span>
      </h3>
      <ul className="player-list">
        {players.map((player) => (
          <li key={player.playerId} className="player-row">
            <PositionChip position={player.position} />
            <PlayerCells player={player} />
          </li>
        ))}
      </ul>
    </section>
  )
}

function formatPoints(points) {
  return points.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export default function RosterView({ roster, onBack }) {
  const { data, error, loading } = roster
  const backButton = (
    <button type="button" className="link back" onClick={onBack}>
      ← All leagues
    </button>
  )

  if (loading) return <Loading label="Loading roster…" />
  if (error) return <ErrorMessage error={error} action={backButton} />
  if (!data) return null

  const { owner, record } = data
  const hasPlayers = data.starters.some((s) => s.player) || data.bench.length > 0 || data.reserve.length > 0
  const recordText = record.ties > 0 ? `${record.wins}–${record.losses}–${record.ties}` : `${record.wins}–${record.losses}`

  return (
    <section>
      {backButton}
      <div className="card team-header">
        {owner.avatarUrl && <img className="avatar" src={owner.avatarUrl} alt="" />}
        <div>
          <h2>{owner.teamName ?? `Team ${owner.displayName ?? data.rosterId}`}</h2>
          <span className="muted">
            {data.leagueName} · {owner.displayName}
          </span>
        </div>
        <dl className="team-stats">
          <div>
            <dt>Record</dt>
            <dd>{recordText}</dd>
          </div>
          <div>
            <dt>Points for</dt>
            <dd>{formatPoints(record.pointsFor)}</dd>
          </div>
          <div>
            <dt>Points against</dt>
            <dd>{formatPoints(record.pointsAgainst)}</dd>
          </div>
        </dl>
      </div>

      {!hasPlayers ? (
        <p className="status">No players on this roster yet. The league may not have drafted.</p>
      ) : (
        <>
          <section className="card roster-section">
            <h3>Starters</h3>
            <ul className="player-list">
              {data.starters.map((starter, i) => (
                <li key={i} className="player-row">
                  <span className="slot">{starter.slot.replace('_', ' ')}</span>
                  <PlayerCells player={starter.player} />
                  {starter.player && <PositionChip position={starter.player.position} />}
                </li>
              ))}
            </ul>
          </section>
          <PlayerSection title="Bench" players={data.bench} />
          <PlayerSection title="Injured reserve" players={data.reserve} />
          <PlayerSection title="Taxi squad" players={data.taxi} />
        </>
      )}
    </section>
  )
}
