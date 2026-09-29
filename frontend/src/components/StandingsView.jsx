import { fetchStandings } from '../api.js'
import { formatPoints, formatTotal } from '../format.js'
import { useApi } from '../hooks.js'
import { ErrorMessage, HowItWorks, Loading } from './Status.jsx'

function record(team) {
  return team.ties > 0 ? `${team.wins}–${team.losses}–${team.ties}` : `${team.wins}–${team.losses}`
}

export default function StandingsView({ leagueId, userId }) {
  const standings = useApi((signal) => fetchStandings(leagueId, userId, signal), `${leagueId}|${userId}`)

  if (standings.loading) return <Loading label="Simulating the rest of the season…" />
  if (standings.error) return <ErrorMessage error={standings.error} onRetry={standings.retry} />
  if (!standings.data) return null

  const { teams, playoffTeams, regularSeasonEnd, simulations, notes } = standings.data
  const you = teams.find((t) => t.you)

  return (
    <section className="standings">
      {you && you.playoffOdds != null && (
        <div className="card standings-summary">
          <span className="muted small">Your playoff odds</span>
          <strong className="big-number">{you.playoffOdds.toFixed(0)}%</strong>
          <span className="muted small">
            {ordinalRank(you.rank)} in the standings · {ordinalRank(you.powerRank)} in power rankings · projected{' '}
            {formatPoints(you.projectedWins)} wins by week {regularSeasonEnd}
          </span>
        </div>
      )}
      <div className="card table-card">
        <div className="table-scroll">
          <table className="standings-table">
            <caption className="visually-hidden">League standings, power rankings and playoff odds</caption>
            <thead>
              <tr>
                <th scope="col">#</th>
                <th scope="col">Team</th>
                <th scope="col">Record</th>
                <th scope="col" className="num">Pts for</th>
                <th scope="col" className="num" title="Expected starting-lineup points per week, rest of season">
                  Strength
                </th>
                <th scope="col" className="num">Power</th>
                {simulations != null && <th scope="col">Playoff odds</th>}
              </tr>
            </thead>
            <tbody>
              {teams.map((t) => (
                <tr key={t.rosterId} className={`${t.you ? 'you' : ''}${t.rank === playoffTeams ? ' cutoff' : ''}`}>
                  <td>{t.rank}</td>
                  <th scope="row" className="team-cell">
                    {t.avatarUrl ? (
                      <img className="avatar tiny" src={t.avatarUrl} alt="" />
                    ) : (
                      <span className="avatar tiny placeholder" aria-hidden="true">
                        {t.teamName.charAt(0).toUpperCase()}
                      </span>
                    )}
                    <span>
                      {t.teamName}
                      {t.you && <span className="visually-hidden"> (you)</span>}
                    </span>
                  </th>
                  <td>{record(t)}</td>
                  <td className="num">{formatTotal(t.pointsFor)}</td>
                  <td className="num">{formatPoints(t.strength)}</td>
                  <td className="num">{t.powerRank}</td>
                  {simulations != null && (
                    <td>
                      <span className="odds-cell">
                        <span className="odds-bar" aria-hidden="true">
                          <span style={{ width: `${t.playoffOdds}%` }} />
                        </span>
                        <span className="odds-value">{t.playoffOdds.toFixed(0)}%</span>
                      </span>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="muted small table-legend">
          Top {playoffTeams} make the playoffs (line marks the cutoff). Power ranks teams by strength: their best
          projected lineup per week for the rest of the season.
        </p>
      </div>
      <HowItWorks>
        <p>
          <strong>Strength</strong> is each team's best possible starting lineup, averaged over the remaining weeks from
          weekly projections in your league's scoring, so byes and injuries count.
        </p>
        <p>
          <strong>Playoff odds</strong> come from simulating the remaining regular season{' '}
          {simulations != null && `${simulations.toLocaleString()} times `}
          with Sleeper's actual schedule. Each week every team's score is drawn around its expected lineup total, with
          typical week-to-week swings; league-median games count when your league plays them. Standings are sorted by
          wins, then points for.
        </p>
      </HowItWorks>
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

function ordinalRank(n) {
  const mod100 = n % 100
  if (mod100 >= 11 && mod100 <= 13) return `${n}th`
  return n + ({ 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] ?? 'th')
}
