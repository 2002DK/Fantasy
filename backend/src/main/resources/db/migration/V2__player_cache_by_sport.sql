-- Player IDs are only unique within a sport (most basketball IDs are also football
-- IDs), so the cache is keyed by (sport, id). The table only caches Sleeper data, so it
-- is rebuilt rather than altered, and the sync records are cleared so every sport is
-- downloaded again on the next start.
DROP TABLE player;

CREATE TABLE player (
    sport             VARCHAR(10)  NOT NULL,
    id                VARCHAR(255) NOT NULL,
    full_name         VARCHAR(255),
    position          VARCHAR(255),
    fantasy_positions VARCHAR(255),
    team              VARCHAR(255),
    injury_status     VARCHAR(255),
    active            BOOLEAN      NOT NULL,
    age               INTEGER,
    years_exp         INTEGER,
    search_rank       INTEGER,
    PRIMARY KEY (sport, id)
);

CREATE INDEX idx_player_sport_position ON player (sport, position);

DELETE FROM data_sync;
