import { useState } from 'react'
import { InjuryTag, PositionChip } from './PlayerBadges.jsx'
import StartSitPanel from './StartSitPanel.jsx'
import TradeView from './TradeView.jsx'
import { ErrorMessage, Loading } from './Status.jsx'

function PlayerCells({ player }) {
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

/** A roster row that toggles the player in or out of the start/sit comparison. */
function SelectableRow({ player, selected, onToggle, children }) {
  return (
    <li>
      <button
        type="button"
        className={`player-row selectable${selected ? ' selected' : ''}`}
        aria-pressed={selected}
        onClick={() => onToggle(player.playerId)}
      >
        {children}
      </button>
    </li>
  )
}

function PlayerSection({ title, players, selection }) {
  if (players.length === 0) return null
  return (
    <section className="card roster-section">
      <h3>
        {title} <span className="muted count">{players.length}</span>
      </h3>
      <ul className="player-list">
        {players.map((player) => (
          <SelectableRow key={player.playerId} player={player} {...selection(player.playerId)}>
            <PositionChip position={player.position} />
            <PlayerCells player={player} />
          </SelectableRow>
        ))}
      </ul>
    </section>
  )
}

function formatPoints(points) {
  return points.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const TOOLS = [
  { id: 'start-sit', label: 'Start/Sit' },
  { id: 'trade', label: 'Trade' },
]

/** The roster with tappable rows: picking two players shows the start/sit comparison. */
function StartSitTool({ data }) {
  const [selectedIds, setSelectedIds] = useState([])

  /** Up to two players; picking a third replaces the most recent pick. */
  function toggle(playerId) {
    setSelectedIds((ids) =>
      ids.includes(playerId) ? ids.filter((id) => id !== playerId) : [ids[0], playerId].filter(Boolean).slice(-2),
    )
  }
  const selection = (playerId) => ({ selected: selectedIds.includes(playerId), onToggle: toggle })

  return (
    <>
      {selectedIds.length === 2 ? (
        <StartSitPanel leagueId={data.leagueId} playerIds={selectedIds} onClear={() => setSelectedIds([])} />
      ) : (
        <p className="hint muted">
          {selectedIds.length === 0
            ? 'Tap two players to compare who to start this week.'
            : 'Pick one more player to compare.'}
        </p>
      )}
      <section className="card roster-section">
        <h3>Starters</h3>
        <ul className="player-list">
          {data.starters.map((starter, i) =>
            starter.player ? (
              <SelectableRow key={i} player={starter.player} {...selection(starter.player.playerId)}>
                <span className="slot">{starter.slot.replace('_', ' ')}</span>
                <PlayerCells player={starter.player} />
                <PositionChip position={starter.player.position} />
              </SelectableRow>
            ) : (
              <li key={i} className="player-row">
                <span className="slot">{starter.slot.replace('_', ' ')}</span>
                <span className="player-name muted">Empty</span>
              </li>
            ),
          )}
        </ul>
      </section>
      <PlayerSection title="Bench" players={data.bench} selection={selection} />
      <PlayerSection title="Injured reserve" players={data.reserve} selection={selection} />
      <PlayerSection title="Taxi squad" players={data.taxi} selection={selection} />
    </>
  )
}

export default function RosterView({ roster, tool, onToolChange, onBack }) {
  const { data, error, loading } = roster
  const backButton = (
    <button type="button" className="link back" onClick={onBack}>
      ← All leagues
    </button>
  )

  if (loading) return <Loading label="Loading roster…" />
  if (error) return <ErrorMessage error={error} action={backButton} />
  if (!data) return null

  const activeTool = TOOLS.some((t) => t.id === tool) ? tool : 'start-sit'
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
          <div className="tabs" role="tablist" aria-label="Tools">
            {TOOLS.map((t) => (
              <button
                key={t.id}
                type="button"
                role="tab"
                aria-selected={activeTool === t.id}
                className={`tab${activeTool === t.id ? ' active' : ''}`}
                onClick={() => onToolChange(t.id)}
              >
                {t.label}
              </button>
            ))}
          </div>
          <div role="tabpanel">
            {activeTool === 'trade' ? <TradeView roster={data} /> : <StartSitTool data={data} />}
          </div>
        </>
      )}
    </section>
  )
}
