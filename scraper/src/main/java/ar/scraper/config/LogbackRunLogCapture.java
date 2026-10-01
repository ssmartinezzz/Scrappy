package ar.scraper.config;

import ar.scraper.scheduling.RunLogCapture;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class LogbackRunLogCapture implements RunLogCapture {

    @Override
    public Handle start(String loggerName) {
        if (!(LoggerFactory.getLogger(loggerName) instanceof Logger logger)) return NONE.start(loggerName);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new Handle() {
            @Override
            public String lines() {
                StringBuilder sb = new StringBuilder();
                for (ILoggingEvent evt : appender.list) {
                    sb.append(evt.getFormattedMessage()).append('\n');
                }
                return sb.toString();
            }

            @Override
            public void close() {
                logger.detachAppender(appender);
                appender.stop();
            }
        };
    }
}
