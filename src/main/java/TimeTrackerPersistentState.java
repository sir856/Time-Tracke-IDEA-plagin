@SuppressWarnings("WeakerAccess")
public final class TimeTrackerPersistentState {

    public long totalTimeSeconds = 0;

    public long idleThresholdMs = 2 * 60 * 1000;

    public long naggedAbout = 0;

    public void setDefaultsFrom(final TimeTrackerPersistentState state) {
        this.idleThresholdMs = state.idleThresholdMs;
    }
}