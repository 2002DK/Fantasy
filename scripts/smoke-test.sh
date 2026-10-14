#!/usr/bin/env bash
# Calls every API endpoint for one league and user and reports status and time.
#
#   scripts/smoke-test.sh <base-url> <sleeper-username> <league-id>
#
# Example: scripts/smoke-test.sh https://fantasy-app-p6xn.onrender.com 2ksports 1403186749361901568
# The first request may take a minute while a sleeping Render instance starts.
set -u

base=${1:?base URL}
username=${2:?Sleeper username}
league=${3:?league ID}

user_id=$(curl -s -m 120 "$base/api/users/$username/leagues" | sed -n 's/.*"userId":"\([0-9]*\)".*/\1/p')
if [ -z "$user_id" ]; then
  echo "Couldn't look up '$username' at $base" >&2
  exit 1
fi
sport=$(curl -s -m 60 "$base/api/leagues/$league/users/$user_id/roster" | sed -n 's/.*"sport":"\([a-z]*\)".*/\1/p')
player=$(curl -s -m 60 "$base/api/leagues/$league/users/$user_id/roster" | sed -n 's/.*"bench":\[{[^}]*"playerId":"\([0-9A-Z]*\)".*/\1/p')
echo "user $user_id, league $league ($sport)"

paths=(
  "users/$username/leagues"
  "leagues/$league/users/$user_id/roster"
  "leagues/$league/rosters"
  "leagues/$league/users/$user_id/lineup"
  "leagues/$league/users/$user_id/matchup"
  "leagues/$league/standings?userId=$user_id"
  "leagues/$league/users/$user_id/planner"
  "leagues/$league/users/$user_id/trade-ideas"
)
if [ -n "$player" ]; then
  paths+=("leagues/$league/players/$player?userId=$user_id")
fi
if [ "$sport" = "nba" ]; then
  paths+=("leagues/$league/users/$user_id/lock-in")
fi

failures=0
for path in "${paths[@]}"; do
  result=$(curl -s -m 120 -o /dev/null -w "%{http_code} %{time_total}" "$base/api/$path")
  code=${result% *}
  printf '%-4s %7.2fs  %s\n' "$code" "${result#* }" "$path"
  if [ "$code" != "200" ]; then
    failures=$((failures + 1))
  fi
done
echo "$failures failed"
[ "$failures" -eq 0 ]
