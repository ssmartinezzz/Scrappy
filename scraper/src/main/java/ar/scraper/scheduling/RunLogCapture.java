package ar.scraper.scheduling;

/** Records the lines a logger emits while a cron run is in flight. */
public interface RunLogCapture {

    Handle start(String loggerName);

    interface Handle {
        /** Lines captured so far, one per event, newline-terminated. */
        String lines();

        /** Stops capturing; {@link #lines()} stays readable. */
        void close();
    }

    RunLogCapture NONE = name -> new Handle() {
        @Override public String lines() { return ""; }
        @Override public void close() { }
    };
}
