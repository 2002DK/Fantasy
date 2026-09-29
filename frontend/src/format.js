/** One decimal, rounding half up like the backend (toFixed alone turns 7.85 into "7.8"). */
export function formatPoints(points) {
  return points == null ? '–' : (Math.round(points * 10) / 10).toFixed(1)
}

/** Two decimals with thousands separators, for season totals like 1,736.02. */
export function formatTotal(points) {
  return points.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function ordinal(n) {
  const mod100 = n % 100
  if (mod100 >= 11 && mod100 <= 13) return `${n}th`
  return n + ({ 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] ?? 'th')
}

export function games(count) {
  return count === 1 ? '1 game' : `${count} games`
}

/** A league team's display name: its team name, else the owner's name. */
export function teamName(owner, rosterId) {
  return owner.teamName ?? owner.displayName ?? `Team ${rosterId}`
}
