export function Loading({ label }) {
  return (
    <div className="status" role="status">
      <span className="spinner" aria-hidden="true" />
      {label}
    </div>
  )
}

/** An error with a "Try again" button when the request can be retried, plus any extra action. */
export function ErrorMessage({ error, onRetry, action }) {
  return (
    <div className="status error" role="alert">
      <p>{error.message}</p>
      {(onRetry || action) && (
        <div className="status-actions">
          {onRetry && (
            <button type="button" onClick={onRetry}>
              Try again
            </button>
          )}
          {action}
        </div>
      )}
    </div>
  )
}

/**
 * Grey placeholder blocks in the shape of the content being loaded, so the page
 * does not jump when data arrives. Announced to screen readers as loading.
 */
export function Skeleton({ label, children }) {
  return (
    <div role="status" aria-label={label}>
      <div aria-hidden="true">{children}</div>
    </div>
  )
}

export function SkeletonBlock({ width = '100%', height = 16, round = false }) {
  return <span className="skeleton" style={{ width, height, borderRadius: round ? '50%' : undefined }} />
}

export function LeagueListSkeleton() {
  return (
    <Skeleton label="Loading leagues">
      <div className="card user-header">
        <SkeletonBlock width={48} height={48} round />
        <div className="user-name skeleton-stack">
          <SkeletonBlock width="40%" height={20} />
          <SkeletonBlock width="25%" height={14} />
        </div>
      </div>
      <ul className="league-list">
        {[0, 1, 2, 3].map((i) => (
          <li key={i} className="card league-card">
            <SkeletonBlock width={36} height={36} round />
            <SkeletonBlock width={`${50 - i * 6}%`} height={16} />
          </li>
        ))}
      </ul>
    </Skeleton>
  )
}

export function RosterSkeleton() {
  return (
    <Skeleton label="Loading roster">
      <div className="card team-header">
        <SkeletonBlock width={48} height={48} round />
        <div className="skeleton-stack" style={{ flex: 1 }}>
          <SkeletonBlock width="45%" height={20} />
          <SkeletonBlock width="30%" height={14} />
        </div>
      </div>
      <div className="card roster-section">
        {[0, 1, 2, 3, 4, 5].map((i) => (
          <div key={i} className="player-row">
            <SkeletonBlock width={40} height={20} />
            <SkeletonBlock width={`${60 - (i % 3) * 10}%`} height={16} />
          </div>
        ))}
      </div>
    </Skeleton>
  )
}

/** A collapsible explanation of how a result was calculated. */
export function HowItWorks({ children }) {
  return (
    <details className="how-it-works">
      <summary>How this works</summary>
      <div className="how-it-works-body">{children}</div>
    </details>
  )
}
