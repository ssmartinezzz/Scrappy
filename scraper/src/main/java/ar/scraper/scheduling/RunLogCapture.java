package ar.scraper.scheduling;

/** Records the lines a logger emits while a cron run is in flight. */
public interface RunLogCapture {

    Handle start(String loggerName);

    interface Handle {
        String lines();

        void close();
    }

    RunLogCapture NONE = name -> new Handle() {
        @Override public String lines() { return ""; }
        @Override public void close() { }
    };
}
