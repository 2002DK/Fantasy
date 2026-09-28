export function Loading({ label }) {
  return (
    <div className="status" role="status">
      <span className="spinner" aria-hidden="true" />
      {label}
    </div>
  )
}

export function ErrorMessage({ error, action }) {
  return (
    <div className="status error" role="alert">
      <p>{error.message}</p>
      {action}
    </div>
  )
}
