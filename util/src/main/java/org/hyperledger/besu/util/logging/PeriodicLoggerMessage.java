package org.hyperledger.besu.util.logging;

import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.util.function.Function;

public class PeriodicLoggerMessage {
    public enum Level {
        INFO,
        DEBUG
    }

    private final Logger logger;
    private final Level level;
    private final LocalDateTime startTime;
    private final Function<Void, String> messageGenerator;
    private final long interval;

    public PeriodicLoggerMessage(final Logger logger, final Level level, final LocalDateTime startTime, final Function<Void, String> messageGenerator, final long interval) {
        this.logger = logger;
        this.level = level;
        this.startTime = startTime;
        this.messageGenerator = messageGenerator;
        this.interval = interval;
    }

    public Logger getLogger() {
        return logger;
    }

    public Level getLevel() {
        return level;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public Function<Void, String> getMessageGenerator() {
        return messageGenerator;
    }

    public long getInterval() {
        return interval;
    }
}
