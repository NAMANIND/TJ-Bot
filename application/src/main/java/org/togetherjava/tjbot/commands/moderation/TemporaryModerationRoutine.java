package org.togetherjava.tjbot.commands.moderation;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.jetbrains.annotations.NotNull;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.togetherjava.tjbot.db.Database;
import org.togetherjava.tjbot.db.generated.tables.TemporaryModerationActions;
import org.togetherjava.tjbot.db.generated.tables.records.TemporaryModerationActionsRecord;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class TemporaryModerationRoutine {
    private static final Logger logger = LoggerFactory.getLogger(TemporaryModerationRoutine.class);

    private final Database database;
    private final JDA jda;
    private final ScheduledExecutorService checkExpiredActionsService =
            Executors.newSingleThreadScheduledExecutor();

    /**
     * Creates a new instance.
     *
     * @param jda the JDA instance to use to send messages and retrieve information
     * @param database the database used to retrieve data about temporary moderation actions
     */
    public TemporaryModerationRoutine(@NotNull JDA jda, @NotNull Database database) {
        this.database = database;
        this.jda = jda;
    }

    private void checkExpiredActions() {
        logger.debug("Checking expired temporary moderation actions to revoke...");

        // TODO Overlapping actions

        database.write(context -> {
            Result<TemporaryModerationActionsRecord> oldRecords = context
                .selectFrom(TemporaryModerationActions.TEMPORARY_MODERATION_ACTIONS)
                .where(TemporaryModerationActions.TEMPORARY_MODERATION_ACTIONS.ACTION_EXPIRES
                    .lessOrEqual(Instant.now()))
                .fetch();

            oldRecords.forEach(recordToRevoke -> {
                revokeAction(recordToRevoke.getUserId(), recordToRevoke.getGuildId(),
                        ModerationUtils.Action.valueOf(recordToRevoke.getActionType()));
                recordToRevoke.delete();
            });
        });

        logger.debug("Finished checking expired temporary moderation actions to revoke.");
    }

    private void revokeAction(long userId, long guildId, @NotNull ModerationUtils.Action action) {
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            logger.info(
                    "Attempted to revoke a temporary moderation action but the bot is not connected to the guild '{}' anymore, skipping revoking.",
                    guildId);
            return;
        }
        if (action != ModerationUtils.Action.BAN) {
            throw new IllegalArgumentException("Unsupported action type: " + action);
        }

        // TODO Make this more modular, so that it nicely supports other action types (mute) as well
        jda.retrieveUserById(userId)
            .flatMap(user -> guild.unban(user).reason("Automatic revocation of temporary action."))
            .queue(r -> logger.info("Revoked temporary action {} against user '{}'.", action,
                    userId), unbanFailure -> handleFailure(unbanFailure, userId));
        // TODO Should use existing utility, such as UnbanCommand.unban(...) or similar - needs some
        // refactoring though
    }

    private static void handleFailure(@NotNull Throwable unbanFailure, long userId) {
        if (unbanFailure instanceof ErrorResponseException errorResponseException) {
            if (errorResponseException.getErrorResponse() == ErrorResponse.UNKNOWN_USER) {
                logger.info(
                        "Attempted to revoke a temporary moderation action but user '{}' does not exist anymore.",
                        userId);
                return;
            }

            if (errorResponseException.getErrorResponse() == ErrorResponse.UNKNOWN_BAN) {
                logger.info(
                        "Attempted to revoke a temporary moderation action but the action is not relevant for user '{}' anymore.",
                        userId);
                return;
            }
        }

        logger.warn(
                "Attempted to revoke a temporary moderation action for user '{}' but something unexpected went wrong.",
                userId, unbanFailure);
    }

    /**
     * Starts the routine, automatically checking expired temporary moderation actions on a
     * schedule.
     */
    public void start() {
        // TODO This should be registered at some sort of routine system instead (see GH issue #235
        // which adds support for routines)
        checkExpiredActionsService.scheduleWithFixedDelay(this::checkExpiredActions, 0, 5,
                TimeUnit.MINUTES);
    }
}
