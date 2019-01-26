@SuppressWarnings("WeakerAccess")
public final class TimeTrackerPersistentState {

    public long totalTimeSeconds = 0;

    public long idleThresholdMs = 2 * 60 * 1000;
    public int autoCountIdleSeconds = 30;
    public boolean pauseOtherTrackerInstances = true;
    public boolean autoStart = true;

    public long naggedAbout = 0;

    public void setDefaultsFrom(final TimeTrackerPersistentState state) {
        this.idleThresholdMs = state.idleThresholdMs;
    }
}