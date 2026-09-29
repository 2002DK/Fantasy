import { useRef, useState } from 'react'
import { formatTotal, teamName } from '../format.js'
import { useDocumentTitle } from '../hooks.js'
import { InjuryTag, PositionChip } from './PlayerBadges.jsx'
import StartSitPanel from './StartSitPanel.jsx'
import { ErrorMessage, RosterSkeleton } from './Status.jsx'
import TradeView from './TradeView.jsx'

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

const TOOLS = [
  { id: 'start-sit', label: 'Start/Sit' },
  { id: 'trade', label: 'Trade' },
]

/**
 * WAI-ARIA tabs: only the active tab is in the Tab order; arrow keys, Home and End
 * move between tabs and activate them.
 */
function ToolTabs({ activeTool, onToolChange }) {
  const tabRefs = useRef({})

  function handleKeyDown(event) {
    const index = TOOLS.findIndex((t) => t.id === activeTool)
    const nextIndex = {
      ArrowRight: (index + 1) % TOOLS.length,
      ArrowLeft: (index - 1 + TOOLS.length) % TOOLS.length,
      Home: 0,
      End: TOOLS.length - 1,
    }[event.key]
    if (nextIndex === undefined) return
    event.preventDefault()
    const next = TOOLS[nextIndex].id
    onToolChange(next)
    tabRefs.current[next]?.focus()
  }

  return (
    <div className="tabs" role="tablist" aria-label="Tools" onKeyDown={handleKeyDown}>
      {TOOLS.map((t) => (
        <button
          key={t.id}
          ref={(el) => (tabRefs.current[t.id] = el)}
          id={`tab-${t.id}`}
          type="button"
          role="tab"
          aria-selected={activeTool === t.id}
          aria-controls={`panel-${t.id}`}
          tabIndex={activeTool === t.id ? 0 : -1}
          className={`tab${activeTool === t.id ? ' active' : ''}`}
          onClick={() => onToolChange(t.id)}
        >
          {t.label}
        </button>
      ))}
    </div>
  )
}

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
        <p className="hint muted" aria-live="polite">
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
                {/* The slot only adds information when it differs from the position, e.g. FLEX */}
                <span className="slot">
                  {starter.slot !== starter.player.position && starter.slot.replace('_', ' ')}
                </span>
                <PositionChip position={starter.player.position} />
                <PlayerCells player={starter.player} />
              </SelectableRow>
            ) : (
              <li key={i} className="player-row empty-slot">
                <span className="slot">{starter.slot.replace('_', ' ')}</span>
                <span className="pos pos-empty" aria-hidden="true">–</span>
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
  const { data, error, loading, retry } = roster
  useDocumentTitle(data ? data.leagueName : null)
  const backButton = (
    <button type="button" className="link back" onClick={onBack}>
      ← All leagues
    </button>
  )

  if (loading) return <RosterSkeleton />
  if (error) {
    const notFound = error.message.startsWith('No Sleeper league') || error.message.includes('has no roster')
    return <ErrorMessage error={error} onRetry={notFound ? undefined : retry} action={backButton} />
  }
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
          <h2>{teamName(owner, data.rosterId)}</h2>
          <span className="muted">
            {data.leagueName}
            {/* Without a team name the heading already is the owner's name */}
            {owner.teamName && owner.displayName && ` · ${owner.displayName}`}
          </span>
        </div>
        <dl className="team-stats">
          <div>
            <dt>Record</dt>
            <dd>{recordText}</dd>
          </div>
          <div>
            <dt>Points for</dt>
            <dd>{formatTotal(record.pointsFor)}</dd>
          </div>
          <div>
            <dt>Points against</dt>
            <dd>{formatTotal(record.pointsAgainst)}</dd>
          </div>
        </dl>
      </div>

      {!hasPlayers ? (
        <p className="status">No players on this roster yet. The league may not have drafted.</p>
      ) : (
        <>
          <ToolTabs activeTool={activeTool} onToolChange={onToolChange} />
          <div role="tabpanel" id={`panel-${activeTool}`} aria-labelledby={`tab-${activeTool}`}>
            {activeTool === 'trade' ? <TradeView roster={data} /> : <StartSitTool data={data} />}
          </div>
        </>
      )}
    </section>
  )
}
