import { fetchLeagues, fetchRoster } from './api.js'
import LeagueList from './components/LeagueList.jsx'
import RosterView from './components/RosterView.jsx'
import UsernameForm from './components/UsernameForm.jsx'
import { useApi, useUrlState } from './hooks.js'

function App() {
  const [{ username, season, leagueId }, navigate] = useUrlState()

  const leagues = useApi(
    (signal) => fetchLeagues(username, season, signal),
    username ? `${username}|${season ?? ''}` : null,
  )
  const userId = leagues.data?.user.userId
  const roster = useApi(
    (signal) => fetchRoster(leagueId, userId, signal),
    leagueId && userId ? `${leagueId}|${userId}` : null,
  )

  let content
  if (!username) {
    content = <UsernameForm onSubmit={(name) => navigate({ username: name })} />
  } else if (leagueId && !leagues.error) {
    content = (
      <RosterView
        roster={leagues.loading ? leagues : roster}
        onBack={() => navigate({ username, season })}
      />
    )
  } else {
    content = (
      <LeagueList
        leagues={leagues}
        onSelectLeague={(id) => navigate({ username, season, leagueId: id })}
        onSeasonChange={(year) => navigate({ username, season: year })}
        onChangeUser={() => navigate({})}
      />
    )
  }

  return (
    <div className="app">
      <header className="app-header">
        <button type="button" className="brand" onClick={() => navigate({})}>
          Fantasy App
        </button>
        <span className="muted">Start/sit and trade help for Sleeper leagues</span>
      </header>
      <main>{content}</main>
    </div>
  )
}

export default App
