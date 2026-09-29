import { useState } from 'react'
import { fetchTradeIdeas } from '../api.js'
import { formatPoints } from '../format.js'
import { useApi } from '../hooks.js'
import { PositionChip } from './PlayerBadges.jsx'
import { PlayerLink } from './PlayerDialog.jsx'
import { ErrorMessage, Loading } from './Status.jsx'

function Players({ players }) {
  return (
    <ul className="idea-players">
      {players.map((p) => (
        <li key={p.player.playerId}>
          <PositionChip position={p.player.position} />
          <PlayerLink player={p.player} />
          <span className="muted small"> {formatPoints(p.value)} value</span>
        </li>
      ))}
    </ul>
  )
}

function IdeaList({ leagueId, userId, onAnalyze }) {
  const ideas = useApi((signal) => fetchTradeIdeas(leagueId, userId, signal), `${leagueId}|${userId}`)
  if (ideas.loading) return <Loading label="Searching every team for trades that help both sides…" />
  if (ideas.error) return <ErrorMessage error={ideas.error} onRetry={ideas.retry} />
  if (!ideas.data) return null

  return (
    <>
      {ideas.data.ideas.length > 0 && (
        <ul className="ideas">
          {ideas.data.ideas.map((idea, i) => (
            <li key={i} className="idea">
              <div className="idea-header">
                <strong>With {idea.partnerName}</strong>
                <span className="tag strength-SLIGHT">
                  You +{formatPoints(idea.yourGainPerWeek)}/wk · They +{formatPoints(idea.theirGainPerWeek)}/wk
                </span>
              </div>
              <div className="idea-sides">
                <div>
                  <span className="muted small">You give</span>
                  <Players players={idea.give} />
                </div>
                <div>
                  <span className="muted small">You get</span>
                  <Players players={idea.get} />
                </div>
              </div>
              <button type="button" className="secondary" onClick={() => onAnalyze(idea)}>
                Analyze this trade
              </button>
            </li>
          ))}
        </ul>
      )}
      <ul className="notes muted">
        {ideas.data.notes.map((n) => (
          <li key={n}>{n}</li>
        ))}
      </ul>
    </>
  )
}

/** Trade suggestions, loaded on demand because the search tries thousands of deals. */
export default function TradeIdeas({ leagueId, userId, onAnalyze }) {
  const [open, setOpen] = useState(false)
  return (
    <section className="card trade-ideas">
      <div className="start-sit-header">
        <div>
          <h3>Trade ideas</h3>
          <span className="muted small">
            Deals that raise both teams' expected starting lineups, with fair value for the other side.
          </span>
        </div>
        {!open && (
          <button type="button" onClick={() => setOpen(true)}>
            Find trades
          </button>
        )}
      </div>
      {open && <IdeaList leagueId={leagueId} userId={userId} onAnalyze={onAnalyze} />}
    </section>
  )
}
