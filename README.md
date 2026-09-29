# Fantasy App

Weekly decision tools for [Sleeper](https://sleeper.com) fantasy football leagues. Enter a Sleeper
username, pick a league, and:

- **Lineup**: the lineup with the most expected points this week, with the swaps that get you there (benching
  players who are out or on bye), plus **Start/Sit** for comparing any two players.
- **Matchup**: this week's projected score against your opponent, live during games, with your win
  probability now and with your best lineup.
- **Trade**: analyze any trade by rest-of-season value above a replacement-level player (so positional
  scarcity counts), or let the **trade finder** suggest deals that improve both teams' lineups.
- **League**: standings, power rankings by projected lineup strength, and playoff odds from 10,000
  simulations of the remaining schedule.
- **Planner**: your roster week by week through the playoffs, flagging byes, injuries and weeks you can't fill
  a starting slot.
- **Player details**: any player's game log and upcoming schedule with matchup difficulty.

Every number uses the league's own scoring settings (PPR or half, 6-point passing TDs, bonuses). No login:
the app only reads public Sleeper data.

## Stack

| Layer | Technology |
| --- | --- |
| Frontend | React 19, Vite |
| Backend | Spring Boot 4 (Java 21) |
| Database | H2 (dev), PostgreSQL (prod), schema managed by Flyway |
| Data | Sleeper API; projections by Rotowire via Sleeper |
| Hosting | Render (Docker), Neon (Postgres) |

In production the backend serves the built frontend, so the site and API share one origin.

## Run locally

Requires Java 21 and Node 24.

```bash
backend\run-dev.cmd
```

```bash
npm --prefix frontend run dev
```

Then open http://localhost:5173. The backend runs on port 8080 with a file-based H2 database in
`backend/data/` that caches Sleeper's player list (refreshed at most once a day). Delete that folder to
reset it.

Tests:

```bash
cd backend && ./mvnw test
```

## Deploy (Render + Neon, free tiers)

1. **Database**: create a project at [neon.com](https://neon.com). From its connection details, note the
   host, database, user and password.
2. **App**: in the [Render dashboard](https://dashboard.render.com), choose **New > Blueprint** and select
   this repository. Render reads [`render.yaml`](render.yaml) and asks for:
   - `DATABASE_URL`: `jdbc:postgresql://<host>/<database>?sslmode=require`
   - `DATABASE_USERNAME` and `DATABASE_PASSWORD`
3. Render builds the [`Dockerfile`](Dockerfile) and deploys. Pushes to `main` redeploy automatically.

On first start, Flyway creates the tables and the app downloads Sleeper's player list. Render's free
plan sleeps after 15 minutes without traffic; the first request afterwards takes about a minute.

## How the analysis works

- **Start/Sit** score = 60% projected points + 40% average of the last 3 games, with form adjusted up to
  ±15% for the opponent's points allowed to that position. Out, IR and bye players can't start;
  Questionable −10%, Doubtful −50%.
- **Trade** value = rest-of-season points (70% summed weekly projections, 30% recent form carried forward
  and adjusted for schedule) minus a replacement-level player at the same position, derived from the
  league's team count and starting slots.
- **Lineup** fills dedicated slots before flex slots with the best available player, then improves the result
  where overlapping flex slots matter. Players whose games have started stay locked.
- **Win probability** treats each player's score as normal with a standard deviation of half their expected
  points; players whose games are final count their actual points.
- **Playoff odds** simulate each remaining regular-season week with Sleeper's real pairings (and the league
  median game when played), drawing each team's score around its best projected lineup. Standings sort by
  wins, then points for.
- **Trade ideas** try every one-for-one, two-for-one and one-for-two deal and keep those that raise both
  teams' expected lineups while giving the partner at least 70% of the trade value they send.

Weekly stats, projections and the schedule come from Sleeper's undocumented `api.sleeper.com` host. They
are cached, and if a source is unavailable the analysis uses the rest and says so.
