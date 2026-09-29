import { useState } from 'react'
import { useDocumentTitle } from '../hooks.js'

const LAST_USERNAME_KEY = 'fantasy.lastUsername'

function readLastUsername() {
  try {
    return localStorage.getItem(LAST_USERNAME_KEY) ?? ''
  } catch {
    return ''
  }
}

export default function UsernameForm({ onSubmit }) {
  const [username, setUsername] = useState(readLastUsername)
  const trimmed = username.trim()
  useDocumentTitle(null)

  function handleSubmit(event) {
    event.preventDefault()
    if (!trimmed) return
    try {
      localStorage.setItem(LAST_USERNAME_KEY, trimmed)
    } catch {
      // Storage unavailable (private mode); remembering the name is optional
    }
    onSubmit(trimmed)
  }

  return (
    <>
      <section className="card intro">
        <h2>Smarter weekly decisions for your Sleeper league</h2>
        <p className="muted">
          Enter your Sleeper username to load your leagues. No login needed: the app only reads public league
          data.
        </p>
        <form className="username-form" onSubmit={handleSubmit}>
          <label htmlFor="username" className="visually-hidden">
            Sleeper username
          </label>
          <input
            id="username"
            type="text"
            placeholder="Sleeper username"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck="false"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
          <button type="submit" disabled={!trimmed}>
            Find my leagues
          </button>
        </form>
      </section>
      <ul className="features">
        <li className="card feature">
          <h3>Start/Sit</h3>
          <p className="muted">
            Pick two players and get a recommendation for this week, from projections, recent form and how many
            points each opponent allows.
          </p>
        </li>
        <li className="card feature">
          <h3>Trade analyzer</h3>
          <p className="muted">
            See who wins a trade by rest-of-season value above a replacement-level player, so positional scarcity
            counts.
          </p>
        </li>
        <li className="card feature">
          <h3>Your league's scoring</h3>
          <p className="muted">
            Every number uses your league's own settings: PPR or half, 6-point passing TDs, bonuses and all.
          </p>
        </li>
      </ul>
    </>
  )
}
