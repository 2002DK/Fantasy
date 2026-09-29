/** App mark: a football on a rounded square. Matches public/favicon.svg. */
export default function Logo({ size = 32 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="8" fill="var(--accent)" />
      <g transform="rotate(-35 16 16)">
        <ellipse cx="16" cy="16" rx="11" ry="6.5" fill="var(--accent-text)" />
        <path d="M11 16h10" stroke="var(--accent)" strokeWidth="1.6" strokeLinecap="round" />
        <path d="M13 14.4v3.2M16 14.4v3.2M19 14.4v3.2" stroke="var(--accent)" strokeWidth="1.4" strokeLinecap="round" />
      </g>
    </svg>
  )
}
