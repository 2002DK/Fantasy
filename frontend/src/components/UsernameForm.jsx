import { useState } from 'react'

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
    <section className="card intro">
      <h2>Connect your Sleeper account</h2>
      <p className="muted">
        Enter your Sleeper username to load your leagues. No login needed. The app only reads public
        league data.
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
  )
}
