-- Local copy of Sleeper's /players/nfl, refreshed at most once a day
CREATE TABLE player (
    id                VARCHAR(255) NOT NULL PRIMARY KEY,
    full_name         VARCHAR(255),
    position          VARCHAR(255),
    fantasy_positions VARCHAR(255),
    team              VARCHAR(255),
    injury_status     VARCHAR(255),
    active            BOOLEAN      NOT NULL,
    age               INTEGER,
    years_exp         INTEGER,
    search_rank       INTEGER
);

CREATE INDEX idx_player_position ON player (position);

-- When each cached data set was last refreshed from Sleeper
CREATE TABLE data_sync (
    name         VARCHAR(255) NOT NULL PRIMARY KEY,
    synced_at    TIMESTAMP(6) WITH TIME ZONE,
    record_count INTEGER      NOT NULL
);
