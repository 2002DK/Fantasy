const INJURY_ABBREVIATIONS = {
  Questionable: 'Q',
  Doubtful: 'D',
  Out: 'O',
  IR: 'IR',
  PUP: 'PUP',
  Sus: 'SUS',
  NA: 'NA',
}

const INJURY_NAMES = {
  IR: 'injured reserve',
  PUP: 'physically unable to perform',
  Sus: 'suspended',
  NA: 'not active',
}

/** Shows "Q"/"O"/"IR"; screen readers hear the full status instead of the abbreviation. */
export function InjuryTag({ status }) {
  if (!status) return null
  const severity = status === 'Questionable' ? 'minor' : 'major'
  const fullName = INJURY_NAMES[status] ?? status.toLowerCase()
  return (
    <span className={`tag injury ${severity}`} title={status}>
      <span aria-hidden="true">{INJURY_ABBREVIATIONS[status] ?? status}</span>
      <span className="visually-hidden">({fullName})</span>
    </span>
  )
}

export function PositionChip({ position }) {
  return <span className={`pos pos-${position ?? 'unknown'}`}>{position ?? '?'}</span>
}
