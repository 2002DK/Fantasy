import { useEffect, useRef } from 'react'
import { fetchLeagues, fetchRoster } from './api.js'
import LeagueList from './components/LeagueList.jsx'
import Logo from './components/Logo.jsx'
import RosterView from './components/RosterView.jsx'
import UsernameForm from './components/UsernameForm.jsx'
import { useApi, useUrlState } from './hooks.js'

function App() {
  const [{ username, season, leagueId, tool }, navigate] = useUrlState()
  const mainRef = useRef(null)

  const leagues = useApi(
    (signal) => fetchLeagues(username, season, signal),
    username ? `${username}|${season ?? ''}` : null,
  )
  const userId = leagues.data?.user.userId
  const roster = useApi(
    (signal) => fetchRoster(leagueId, userId, signal),
    leagueId && userId ? `${leagueId}|${userId}` : null,
  )

  /**
   * Moving to a new view replaces the element that had focus, so focus moves to
   * the main region once the new view has rendered; screen readers then start
   * reading it. (Not on tab switches, where focus stays on the tab.)
   */
  const focusMainAfterRender = useRef(false)
  function goTo(next) {
    focusMainAfterRender.current = true
    navigate(next)
  }
  useEffect(() => {
    if (focusMainAfterRender.current) {
      focusMainAfterRender.current = false
      mainRef.current?.focus()
    }
  }, [username, season, leagueId])

  let content
  if (!username) {
    content = <UsernameForm onSubmit={(name) => goTo({ username: name })} />
  } else if (leagueId && !leagues.error) {
    content = (
      <RosterView
        key={leagueId}
        roster={leagues.loading ? leagues : roster}
        userId={userId}
        tool={tool}
        onToolChange={(next) => navigate({ username, season, leagueId, tool: next === 'lineup' ? null : next })}
        onBack={() => goTo({ username, season })}
      />
    )
  } else {
    content = (
      <LeagueList
        leagues={leagues}
        onSelectLeague={(id) => goTo({ username, season, leagueId: id })}
        onSeasonChange={(year) => navigate({ username, season: year })}
        onChangeUser={() => goTo({})}
      />
    )
  }

  return (
    <div className="app">
      <header className="app-header">
        <button type="button" className="brand" onClick={() => goTo({})}>
          <Logo size={28} />
          Fantasy App
        </button>
        <span className="muted tagline">Start/sit and trade help for Sleeper leagues</span>
      </header>
      <main ref={mainRef} tabIndex={-1}>
        {content}
      </main>
      <footer className="app-footer muted">
        Data from Sleeper. Projections by Rotowire via Sleeper. Not affiliated with Sleeper.
      </footer>
    </div>
  )
}

export default App
