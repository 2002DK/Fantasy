import { useDocumentTitle } from '../hooks.js'
import { ErrorMessage, LeagueListSkeleton } from './Status.jsx'

const STATUS_LABELS = {
  pre_draft: 'Pre-draft',
  drafting: 'Drafting',
  in_season: 'In season',
  complete: 'Complete',
}

/** Leagues you can act on this week come first; finished ones last. */
const STATUS_ORDER = ['in_season', 'drafting', 'pre_draft', 'complete']

function byStatus(a, b) {
  const rank = (league) => {
    const index = STATUS_ORDER.indexOf(league.status)
    return index >= 0 ? index : STATUS_ORDER.length
  }
  return rank(a) - rank(b)
}

function seasonOptions(selected) {
  const thisYear = new Date().getFullYear()
  const years = Array.from({ length: 5 }, (_, i) => String(thisYear - i))
  return selected && !years.includes(selected) ? [...years, selected] : years
}

export default function LeagueList({ leagues, onSelectLeague, onSeasonChange, onChangeUser }) {
  const { data, error, loading, retry } = leagues
  useDocumentTitle(data ? `${data.user.displayName}'s leagues` : null)

  if (loading) return <LeagueListSkeleton />
  if (error) {
    // A 404 means the username is wrong, so retrying the same name will not help
    const notFound = error.message.startsWith('No Sleeper user')
    return (
      <ErrorMessage
        error={error}
        onRetry={notFound ? undefined : retry}
        action={
          <button type="button" className="secondary" onClick={onChangeUser}>
            Try a different username
          </button>
        }
      />
    )
  }
  if (!data) return null

  const { user, season } = data

  return (
    <section>
      <div className="card user-header">
        {user.avatarUrl && <img className="avatar" src={user.avatarUrl} alt="" />}
        <div className="user-name">
          <h2>{user.displayName}</h2>
          <span className="muted">@{user.username}</span>
        </div>
        <label className="season-picker">
          <span className="muted">Season</span>
          <select value={season} onChange={(e) => onSeasonChange(e.target.value)}>
            {seasonOptions(season).map((year) => (
              <option key={year} value={year}>
                {year}
              </option>
            ))}
          </select>
        </label>
        <button type="button" className="link" onClick={onChangeUser}>
          Change user
        </button>
      </div>

      {data.leagues.length === 0 ? (
        <p className="status">No NFL leagues for {season}. If the new season hasn't started, try last year.</p>
      ) : (
        <>
          <h3 className="section-title">
            Your {season} leagues <span className="muted count">{data.leagues.length}</span>
          </h3>
          <ul className="league-list">
            {data.leagues.toSorted(byStatus).map((league) => (
              <li key={league.leagueId}>
                <button type="button" className="card league-card" onClick={() => onSelectLeague(league.leagueId)}>
                  {league.avatarUrl ? (
                    <img className="avatar small" src={league.avatarUrl} alt="" />
                  ) : (
                    <span className="avatar small placeholder" aria-hidden="true">
                      {league.name.charAt(0).toUpperCase()}
                    </span>
                  )}
                  <span className="league-name">{league.name}</span>
                  <span className="muted league-teams">{league.totalRosters} teams</span>
                  <span className={`tag status-${league.status}`}>{STATUS_LABELS[league.status] ?? league.status}</span>
                  <span className="chevron" aria-hidden="true">
                    ›
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
