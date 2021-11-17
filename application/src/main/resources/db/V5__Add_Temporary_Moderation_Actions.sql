CREATE TABLE temporary_moderation_actions
(
    case_id        INTEGER   NOT NULL PRIMARY KEY AUTOINCREMENT,
    user_id        BIGINT    NOT NULL,
    guild_id       BIGINT    NOT NULL UNIQUE,
    action_type    TEXT      NOT NULL,
    action_expires TIMESTAMP NOT NULL
)
