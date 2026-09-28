import { useState } from 'react'
import { fetchLeagueTeams, fetchTrade } from '../api.js'
import { useApi } from '../hooks.js'
import { InjuryTag, PositionChip } from './PlayerBadges.jsx'
import { ErrorMessage, Loading } from './Status.jsx'

const MAX_PER_SIDE = 5

const STRENGTH_LABELS = {
  FAIR: 'Fair trade',
  SLIGHT: 'Slight edge',
  CLEAR: 'Clear edge',
}

function teamName(team) {
  return team.owner.teamName ?? team.owner.displayName ?? `Team ${team.rosterId}`
}

function formatPoints(points) {
  return points == null ? '–' : (Math.round(points * 10) / 10).toFixed(1)
}

function toggleIn(ids, playerId) {
  if (ids.includes(playerId)) return ids.filter((id) => id !== playerId)
  return ids.length < MAX_PER_SIDE ? [...ids, playerId] : ids
}

function PickList({ players, selectedIds, onToggle }) {
  if (players.length === 0) return <p className="muted small">No players on this roster.</p>
  return (
    <ul className="player-list pick-list">
      {players.map((player) => {
        const selected = selectedIds.includes(player.playerId)
        return (
          <li key={player.playerId}>
            <button
              type="button"
              className={`player-row selectable${selected ? ' selected' : ''}`}
              aria-pressed={selected}
              onClick={() => onToggle(player.playerId)}
            >
              <PositionChip position={player.position} />
              <span className="player-name">
                {player.name ?? <span className="muted">Unknown player ({player.playerId})</span>}
                <InjuryTag status={player.injuryStatus} />
              </span>
              <span className="player-team muted">{player.team ?? 'FA'}</span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}

function verdictHeadline(verdict) {
  if (verdict.winner == null) return 'Neither side adds value'
  if (verdict.strength === 'FAIR') return verdict.winner === 'YOU' ? 'Fair trade, slightly in your favor' : 'Fair trade, slightly in their favor'
  return verdict.winner === 'YOU' ? 'You win this trade' : 'You lose this trade'
}

function scheduleLabel(percent) {
  if (percent == null) return '–'
  if (Math.abs(percent) < 3) return 'Average'
  return `${Math.abs(percent).toFixed(0)}% ${percent > 0 ? 'easier' : 'tougher'}`
}

function TradeSide({ title, side, winning }) {
  return (
    <div className={`trade-side${winning ? ' recommended' : ''}`}>
      <div className="trade-side-header">
        <h4>{title}</h4>
        <span className="trade-total">
          {formatPoints(side.totalValue)} <span className="muted">value</span>
        </span>
      </div>
      {side.players.map((p) => (
        <div key={p.player.playerId} className="trade-player">
          <div className="comparison-name">
            <PositionChip position={p.player.position} />
            <span>
              <strong>{p.player.name ?? `Player ${p.player.playerId}`}</strong>
              <InjuryTag status={p.player.injuryStatus} />
              <span className="muted"> {p.player.team ?? 'FA'}</span>
            </span>
          </div>
          {p.note && <p className="unavailable">{p.note}</p>}
          <dl className="comparison-stats trade-stats">
            <div>
              <dt>Rest of season</dt>
              <dd>
                {formatPoints(p.restOfSeasonPoints)}
                <span className="muted small"> / {p.remainingGames === 1 ? '1 game' : `${p.remainingGames} games`}</span>
              </dd>
            </div>
            <div>
              <dt>Replacement {p.player.position}</dt>
              <dd>{formatPoints(p.replacementPoints)}</dd>
            </div>
            <div>
              <dt>Schedule</dt>
              <dd>{scheduleLabel(p.scheduleStrengthPercent)}</dd>
            </div>
            <div>
              <dt>Value</dt>
              <dd className="score">{formatPoints(p.value)}</dd>
            </div>
          </dl>
        </div>
      ))}
    </div>
  )
}

function TradeResult({ leagueId, giveIds, getIds }) {
  const trade = useApi(
    (signal) => fetchTrade(leagueId, giveIds, getIds, signal),
    `${leagueId}|${giveIds.join(',')}|${getIds.join(',')}`,
  )
  if (trade.loading) return <Loading label="Analyzing trade… the first run loads the season's projections." />
  if (trade.error) return <ErrorMessage error={trade.error} />
  if (!trade.data) return null

  const { verdict, give, get, reasons, notes, fromWeek, throughWeek } = trade.data
  return (
    <section className="card start-sit trade-result" aria-live="polite">
      <div className="start-sit-header">
        <div>
          <span className="muted">
            Rest of season · weeks {fromWeek}–{throughWeek}
          </span>
          <h3>{verdictHeadline(verdict)}</h3>
        </div>
        {verdict.winner && (
          <span className={`tag strength-${verdict.strength}`}>
            {STRENGTH_LABELS[verdict.strength]} · {verdict.marginPercent.toFixed(0)}%
          </span>
        )}
      </div>
      <div className="comparison">
        <TradeSide title="You give" side={give} winning={verdict.winner === 'THEM'} />
        <TradeSide title="You get" side={get} winning={verdict.winner === 'YOU'} />
      </div>
      <p className="muted small value-explainer">
        Value = rest-of-season points above a replacement-level player at the same position, in your league's
        scoring.
      </p>
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
    </section>
  )
}

export default function TradeView({ roster }) {
  const teams = useApi((signal) => fetchLeagueTeams(roster.leagueId, signal), roster.leagueId)
  const [giveIds, setGiveIds] = useState([])
  const [getIds, setGetIds] = useState([])
  const [partnerId, setPartnerId] = useState('')
  const [submitted, setSubmitted] = useState(null)

  const myPlayers = [
    ...roster.starters.map((s) => s.player).filter(Boolean),
    ...roster.bench,
    ...roster.reserve,
    ...roster.taxi,
  ]
  const partners = (teams.data ?? []).filter((t) => t.rosterId !== roster.rosterId)
  const partner = partners.find((t) => String(t.rosterId) === partnerId)

  // Any change to the trade hides the previous result until it is analyzed again
  function updateGive(playerId) {
    setGiveIds((ids) => toggleIn(ids, playerId))
    setSubmitted(null)
  }
  function updateGet(playerId) {
    setGetIds((ids) => toggleIn(ids, playerId))
    setSubmitted(null)
  }
  function choosePartner(rosterId) {
    setPartnerId(rosterId)
    setGetIds([])
    setSubmitted(null)
  }

  const canAnalyze = giveIds.length > 0 && getIds.length > 0

  return (
    <>
      <div className="trade-builder">
        <section className="card roster-section">
          <h3>
            You give <span className="muted count">{giveIds.length}/{MAX_PER_SIDE}</span>
          </h3>
          <PickList players={myPlayers} selectedIds={giveIds} onToggle={updateGive} />
        </section>
        <section className="card roster-section">
          <h3>
            You get <span className="muted count">{getIds.length}/{MAX_PER_SIDE}</span>
          </h3>
          {teams.loading && <Loading label="Loading league teams…" />}
          {teams.error && <ErrorMessage error={teams.error} />}
          {teams.data && (
            <>
              <label className="visually-hidden" htmlFor="trade-partner">
                Trade partner
              </label>
              <select
                id="trade-partner"
                className="partner-select"
                value={partnerId}
                onChange={(e) => choosePartner(e.target.value)}
              >
                <option value="">Choose a team…</option>
                {partners.map((team) => (
                  <option key={team.rosterId} value={team.rosterId}>
                    {teamName(team)}
                  </option>
                ))}
              </select>
              {partner && <PickList players={partner.players} selectedIds={getIds} onToggle={updateGet} />}
            </>
          )}
        </section>
      </div>
      <div className="trade-actions">
        <button type="button" disabled={!canAnalyze} onClick={() => setSubmitted({ give: giveIds, get: getIds })}>
          Analyze trade
        </button>
        {!canAnalyze && <span className="muted small">Pick at least one player on each side.</span>}
      </div>
      {submitted && <TradeResult leagueId={roster.leagueId} giveIds={submitted.give} getIds={submitted.get} />}
    </>
  )
}
