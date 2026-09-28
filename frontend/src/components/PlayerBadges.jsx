const INJURY_ABBREVIATIONS = {
  Questionable: 'Q',
  Doubtful: 'D',
  Out: 'O',
  IR: 'IR',
  PUP: 'PUP',
  Sus: 'SUS',
  NA: 'NA',
}

export function InjuryTag({ status }) {
  if (!status) return null
  const severity = status === 'Questionable' ? 'minor' : 'major'
  return (
    <span className={`tag injury ${severity}`} title={status}>
      {INJURY_ABBREVIATIONS[status] ?? status}
    </span>
  )
}

export function PositionChip({ position }) {
  return <span className={`pos pos-${position ?? 'unknown'}`}>{position ?? '?'}</span>
}
