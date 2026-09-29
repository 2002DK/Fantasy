import { useCallback, useEffect, useState } from 'react'

/**
 * Runs `load(signal)` whenever `key` changes and tracks its result. A null key
 * means "nothing to load". Results are tagged with the key (and attempt) that
 * produced them, so a slow earlier request can never overwrite a newer one.
 * `retry()` runs the same load again, e.g. after a network error.
 */
export function useApi(load, key) {
  const [result, setResult] = useState({ tag: null, data: null, error: null })
  const [attempt, setAttempt] = useState(0)
  const tag = key == null ? null : `${key}#${attempt}`

  useEffect(() => {
    if (tag == null) return
    const controller = new AbortController()
    load(controller.signal)
      .then((data) => setResult({ tag, data, error: null }))
      .catch((error) => {
        if (!controller.signal.aborted) setResult({ tag, data: null, error })
      })
    return () => controller.abort()
    // `load` is recreated every render; `tag` captures everything it depends on
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tag])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])
  const current = result.tag === tag
  return {
    data: current ? result.data : null,
    error: current ? result.error : null,
    loading: tag != null && !current,
    retry,
  }
}

/** Sets the browser tab title, e.g. "The Megalabowl · Fantasy App". */
export function useDocumentTitle(title) {
  useEffect(() => {
    document.title = title ? `${title} · Fantasy App` : 'Fantasy App'
  }, [title])
}

function readParams() {
  const params = new URLSearchParams(window.location.search)
  return {
    username: params.get('u') || null,
    season: params.get('season') || null,
    leagueId: params.get('league') || null,
    tool: params.get('tool') || null,
  }
}

/**
 * App navigation state kept in the URL query (?u=&season=&league=&tool=) so refresh,
 * the back button and shared links all land on the same view.
 */
export function useUrlState() {
  const [state, setState] = useState(readParams)

  useEffect(() => {
    const onPopState = () => setState(readParams())
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  const navigate = useCallback((next) => {
    const params = new URLSearchParams()
    if (next.username) params.set('u', next.username)
    if (next.season) params.set('season', next.season)
    if (next.leagueId) params.set('league', next.leagueId)
    if (next.tool) params.set('tool', next.tool)
    const query = params.toString()
    window.history.pushState(null, '', query ? `?${query}` : window.location.pathname)
    setState({ username: null, season: null, leagueId: null, tool: null, ...next })
  }, [])

  return [state, navigate]
}
