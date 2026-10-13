-- Scores a user marked as locked in a basketball Lock-In league. Sleeper's API doesn't
-- say which scores are locked, so the app records them when the user does.
CREATE TABLE lock_in_mark (
    league_id  VARCHAR(32)                 NOT NULL,
    user_id    VARCHAR(32)                 NOT NULL,
    week       INTEGER                     NOT NULL,
    player_id  VARCHAR(16)                 NOT NULL,
    game_id    VARCHAR(32),
    game_date  VARCHAR(10),
    opponent   VARCHAR(8),
    points     DOUBLE PRECISION            NOT NULL,
    marked_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (league_id, user_id, week, player_id)
);
