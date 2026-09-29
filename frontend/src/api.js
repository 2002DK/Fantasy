/**
 * Backend API calls. Errors come back as RFC 9457 problem details; their
 * `detail` text is shown to the user as-is.
 */
async function getJson(path, signal) {
  const res = await fetch(path, { signal })
  if (!res.ok) {
    let detail
    try {
      detail = (await res.json()).detail
    } catch {
      // Non-JSON error body (e.g. proxy error while the backend is down)
    }
    throw new Error(detail || `Request failed (HTTP ${res.status}). Is the backend running?`)
  }
  return res.json()
}

export function fetchLeagues(username, season, signal) {
  const query = season ? `?season=${encodeURIComponent(season)}` : ''
  return getJson(`/api/users/${encodeURIComponent(username)}/leagues${query}`, signal)
}

export function fetchStartSit(leagueId, playerA, playerB, signal) {
  const query = new URLSearchParams({ playerA, playerB })
  return getJson(`/api/leagues/${encodeURIComponent(leagueId)}/start-sit?${query}`, signal)
}

export function fetchLeagueTeams(leagueId, signal) {
  return getJson(`/api/leagues/${encodeURIComponent(leagueId)}/rosters`, signal)
}

export function fetchTrade(leagueId, giveIds, getIds, signal) {
  const query = new URLSearchParams({ give: giveIds.join(','), get: getIds.join(',') })
  return getJson(`/api/leagues/${encodeURIComponent(leagueId)}/trade?${query}`, signal)
}

const leaguePath = (leagueId) => `/api/leagues/${encodeURIComponent(leagueId)}`
const userPath = (leagueId, userId) => `${leaguePath(leagueId)}/users/${encodeURIComponent(userId)}`

export function fetchLineup(leagueId, userId, signal) {
  return getJson(`${userPath(leagueId, userId)}/lineup`, signal)
}

export function fetchMatchup(leagueId, userId, signal) {
  return getJson(`${userPath(leagueId, userId)}/matchup`, signal)
}

export function fetchPlanner(leagueId, userId, signal) {
  return getJson(`${userPath(leagueId, userId)}/planner`, signal)
}

export function fetchTradeIdeas(leagueId, userId, signal) {
  return getJson(`${userPath(leagueId, userId)}/trade-ideas`, signal)
}

export function fetchStandings(leagueId, userId, signal) {
  return getJson(`${leaguePath(leagueId)}/standings?userId=${encodeURIComponent(userId)}`, signal)
}

export function fetchPlayer(leagueId, playerId, userId, signal) {
  return getJson(
    `${leaguePath(leagueId)}/players/${encodeURIComponent(playerId)}?userId=${encodeURIComponent(userId)}`,
    signal,
  )
}

export function fetchRoster(leagueId, userId, signal) {
  return getJson(
    `/api/leagues/${encodeURIComponent(leagueId)}/users/${encodeURIComponent(userId)}/roster`,
    signal,
  )
}
