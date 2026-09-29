# Fantasy App

Start/sit and trade analysis for [Sleeper](https://sleeper.com) fantasy football leagues. Enter a Sleeper
username, pick a league, and:

- **Start/Sit**: tap two players to see who to start this week, blending projections, recent form and how
  many points each opponent allows to the position.
- **Trade analyzer**: pick players to give and get to see who wins, by rest-of-season value above a
  replacement-level player, so positional scarcity counts.

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

Weekly stats, projections and the schedule come from Sleeper's undocumented `api.sleeper.com` host. They
are cached, and if a source is unavailable the analysis uses the rest and says so.
