import com.intellij.notification.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDialog;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.concurrency.EdtExecutorService;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.SystemIndependent;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@State(name="TimeTracker", storages = {@Storage(value = StoragePathMacros.WORKSPACE_FILE)})
public final class TimeTrackerComponent implements ProjectComponent, PersistentStateComponent<TimeTrackerPersistentState>, Disposable {

    private static final Logger LOG = Logger.getLogger(TimeTrackerComponent.class.getName());
    private static final boolean DEBUG_LIFECYCLE = false;
    private static final NotificationGroup IDLE_NOTIFICATION_GROUP = new NotificationGroup("Time Tracker - Idle time", NotificationDisplayType.BALLOON, true, null, EmptyIcon.ICON_0);

    @Nullable
    private final Project _project;

    @Nullable
    private TimeTrackerWidget widget;


    private long totalTimeMs = 0;
    private Status status = Status.STOPPED;
    private long statusStartedMs = System.currentTimeMillis();
    private long lastTickMs = System.currentTimeMillis();
    private long lastActivityMs = System.currentTimeMillis();

    private long idleThresholdMs;
    private boolean stopWhenIdleRatherThanPausing;
    private int autoCountIdleSeconds;
    private boolean pauseOtherTrackerInstances;
    private boolean autoStart;

    private long naggedAbout = 0;

    static final long RESET_TIME_TO_ZERO = Long.MIN_VALUE;

    @Nullable
    private ScheduledFuture<?> ticker;

    private static final long TICK_DELAY = 1;
    private static final TimeUnit TICK_DELAY_UNIT = TimeUnit.SECONDS;
    private static final long TICK_JUMP_DETECTION_THRESHOLD_MS = TICK_DELAY_UNIT.toMillis(TICK_DELAY * 20);

    private DocumentListener autoStartDocumentListener = null;
    private final FileDocumentManagerListener saveDocumentListener = new FileDocumentManagerListener() {
        @Override
        public void beforeAllDocumentsSaving() {
            saveTime();
        }

        @Override
        public void beforeDocumentSaving(@NotNull Document document) {
            saveTime();
        }

        // Default methods in 2018.3, but would probably crash in earlier versions
        @Override
        public void beforeFileContentReload(@NotNull VirtualFile file, @NotNull Document document) { }

        @Override
        public void fileWithNoDocumentChanged(@NotNull VirtualFile file) { }

        @Override
        public void fileContentReloaded(@NotNull VirtualFile file, @NotNull Document document) { }

        @Override
        public void fileContentLoaded(@NotNull VirtualFile file, @NotNull Document document) { }

        @Override
        public void unsavedDocumentsDropped() { }
    };

    private synchronized void saveTime() {
        if (status == Status.RUNNING) {
            final long now = System.currentTimeMillis();
            final long msInState = Math.max(0L, now - statusStartedMs);
            statusStartedMs = now;
            addTotalTimeMs(msInState);
        }
    }

    private static final Set<TimeTrackerComponent> ALL_OPENED_TRACKERS = ContainerUtil.newConcurrentSet();

    @NotNull
    public Status getStatus() {
        return status;
    }

    public synchronized void toggleRunning() {
        switch (this.status) {
            case RUNNING:
                setStatus(Status.STOPPED);
                break;
            case STOPPED:
            case IDLE:
                setStatus(Status.RUNNING);
                break;
        }
    }

    private synchronized void tick() {
        if (status != Status.RUNNING) {
            LOG.warning("Tick when status is "+status);
            return;
        }

        final long now = System.currentTimeMillis();
        final long sinceLastTickMs = now - lastTickMs;
        final long sinceLastActivityMs = now - lastActivityMs;

        if (sinceLastTickMs > TICK_JUMP_DETECTION_THRESHOLD_MS) {
            final long lastValidTimeMs = lastTickMs + TICK_JUMP_DETECTION_THRESHOLD_MS;
            setStatus(stopWhenIdleRatherThanPausing ? Status.STOPPED : Status.IDLE, lastValidTimeMs);
        } else if (sinceLastActivityMs >= idleThresholdMs) {
            final long lastValidTimeMs = lastActivityMs + idleThresholdMs;
            setStatus(stopWhenIdleRatherThanPausing ? Status.STOPPED : Status.IDLE, lastValidTimeMs);
        }

        lastTickMs = now;
        repaintWidget(false);
    }

    private synchronized void addTotalTimeMs(long milliseconds) {
        totalTimeMs = Math.max(0L, totalTimeMs + milliseconds);
    }

    public synchronized void setStatus(@NotNull Status status) {
        setStatus(status, System.currentTimeMillis());
    }

    private void setStatus(final @NotNull Status status, final long now) {
        if (this.status == status) {
            return;
        }

        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }

        final long msInState = Math.max(0L, now - statusStartedMs);

        switch (this.status) {
            case RUNNING: {
                addTotalTimeMs(msInState);
                break;
            }
            case IDLE: {
                if (msToS(msInState) <= autoCountIdleSeconds) {
                    addTotalTimeMs(msInState);
                } else if (msInState > 1000) {
                    final Project project = project();
                    if (project != null) {
                        final Notification notification = IDLE_NOTIFICATION_GROUP.createNotification(
                                "Gone for <b>" + millisecondsToString(msInState) + "</b>",
                                NotificationType.INFORMATION);

                        notification.addAction(new AnAction("Count this time in") {

                            private boolean primed = true;

                            @Override
                            public void actionPerformed(@NotNull AnActionEvent e) {
                                if (primed) {
                                    addTotalTimeMs(msInState);
                                    repaintWidget(false);
                                    primed = false;
                                    getTemplatePresentation().setText("Already counted in");
                                    e.getPresentation().setText("Counted in");
                                    notification.expire();
                                }
                            }
                        });

                        Notifications.Bus.notify(notification, project);
                    }
                }
                break;
            }
        }

        this.statusStartedMs = now;
        this.lastTickMs = now;
        this.lastActivityMs = now;
        this.status = status;

        switch (status) {
            case RUNNING: {

                if (pauseOtherTrackerInstances) {
                    ALL_OPENED_TRACKERS.forEach(tracker -> {
                        if (tracker != this) {
                            tracker.otherComponentStarted();
                        }
                    });
                }

                ticker = EdtExecutorService.getScheduledExecutorInstance().scheduleWithFixedDelay(this::tick, TICK_DELAY, TICK_DELAY, TICK_DELAY_UNIT);
            }
            break;
        }

        repaintWidget(false);
    }

    public synchronized int getTotalTimeSeconds() {
        long resultMs = this.totalTimeMs;
        if (this.status == Status.RUNNING) {
            final long now = System.currentTimeMillis();
            resultMs += Math.max(0L, now - statusStartedMs);
        }

        return (int) msToS(resultMs);
    }

    public long getIdleThresholdMs() {
        return idleThresholdMs;
    }

    public synchronized void setIdleThresholdMs(long idleThresholdMs) {
        this.idleThresholdMs = idleThresholdMs;
    }

    public boolean isStopWhenIdleRatherThanPausing() {
        return stopWhenIdleRatherThanPausing;
    }

    public void setStopWhenIdleRatherThanPausing(boolean stopWhenIdleRatherThanPausing) {
        this.stopWhenIdleRatherThanPausing = stopWhenIdleRatherThanPausing;
    }

    public TimeTrackerComponent(@Nullable Project project) {
        this._project = project;
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "Instantiated "+this);
    }

    @Nullable
    private Project project() {
        final Project project = _project;
        if (project == null || project.isDisposed()) {
            return null;
        }
        return project;
    }

    private void repaintWidget(boolean relayout) {
        final TimeTrackerWidget widget = this.widget;
        if (widget != null) {
            UIUtil.invokeLaterIfNeeded(() -> {
                widget.repaint();
                if (relayout) {
                    widget.revalidate();
                }
            });
        }
    }

    @Override
    public void noStateLoaded() {
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "noStateLoaded() "+this);
        loadState(TimeTrackerDefaultSettingsComponent.instance().getState());
    }

    public void loadStateDefaults(@NotNull TimeTrackerPersistentState defaults) {
        final TimeTrackerPersistentState modifiedState = getState();
        modifiedState.setDefaultsFrom(defaults);
        loadState(modifiedState);
    }

    @Override
    public void loadState(@NotNull TimeTrackerPersistentState state) {
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "loadState() "+this);
        ApplicationManager.getApplication().invokeLater(() -> {
            synchronized (this) {
                this.totalTimeMs = state.totalTimeSeconds * 1000L;
                setIdleThresholdMs(state.idleThresholdMs);
                setAutoCountIdleSeconds(state.autoCountIdleSeconds);
                setPauseOtherTrackerInstances(state.pauseOtherTrackerInstances);
                setAutoStart(state.autoStart);
            }
            repaintWidget(true);
        });
    }

    @Override
    public synchronized void initComponent() {
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "initComponent() "+this);
        ALL_OPENED_TRACKERS.add(this);
        Extensions.getArea(null).getExtensionPoint(FileDocumentManagerListener.EP_NAME)
                .registerExtension(saveDocumentListener);
    }

    @Override
    public synchronized void disposeComponent() {
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "disposeComponent() "+this);
        ALL_OPENED_TRACKERS.remove(this);
        Extensions.getArea(null).getExtensionPoint(FileDocumentManagerListener.EP_NAME)
                .unregisterExtension(saveDocumentListener);

        updateAutoStartListener(false);

        setStatus(Status.STOPPED);
    }

    @Override
    @NotNull
    public String getComponentName() {
        return "TimeTrackerComponent";
    }

    @Nullable
    private StatusBar widgetStatusBar() {
        final WindowManager windowManager = WindowManager.getInstance();
        if (windowManager == null) {
            return null;
        }
        final Project project = project();
        if (project == null) {
            return null;
        }
        return windowManager.getStatusBar(project);
    }

    @Override
    public void projectOpened() {
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "projectOpened() "+this);
        UIUtil.invokeLaterIfNeeded(() -> {
            TimeTrackerWidget widget;
            synchronized (this) {
                widget = this.widget;
                if (widget == null) {
                    this.widget = widget = new TimeTrackerWidget(this);
                }
            }

            final StatusBar statusBar = widgetStatusBar();
            if (statusBar != null) {
                statusBar.addWidget(widget, "before Memory", this);
            } else {
                LOG.log(Level.SEVERE, "Can't initialize time tracking widget, status bar is null");
            }
        });
    }

    @Override
    public void projectClosed() {
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "projectClosed() "+this);
        UIUtil.invokeLaterIfNeeded(() -> {
            final StatusBar statusBar = widgetStatusBar();
            if (statusBar != null) {
                statusBar.removeWidget(TimeTrackerWidget.ID);
            }
        });
    }

    @NotNull
    @Override
    public synchronized TimeTrackerPersistentState getState() {
        if (DEBUG_LIFECYCLE) LOG.log(Level.INFO, "getState() "+this);
        final TimeTrackerPersistentState result = new TimeTrackerPersistentState();
        result.totalTimeSeconds = msToS(totalTimeMs);

        result.idleThresholdMs = idleThresholdMs;
        result.naggedAbout = naggedAbout;
        result.autoCountIdleSeconds = autoCountIdleSeconds;
        result.pauseOtherTrackerInstances = pauseOtherTrackerInstances;
        result.autoStart = autoStart;

        return result;
    }

    /** User did something, this resets the idle timer and restarts counting, if applicable. */
    public synchronized void notifyUserNotIdle() {
        final long now = System.currentTimeMillis();
        this.lastActivityMs = now;
        if (status == Status.IDLE) {
            setStatus(Status.RUNNING, now);
        }
    }

    @Override
    public String toString() {
        return "TTC("+_project+")@"+System.identityHashCode(this);
    }

    @Override
    public void dispose() {
        // Everything is implemented in disposeComponent()
    }

    public enum Status {
        RUNNING,
        IDLE,
        STOPPED
    }

    final private static long week = 604800000;
    final private static long day = 86400000;
    final private static long hour = 3600000;
    final private static long minute = 60000;

    /** Rounded conversion of milliseconds to seconds. */
    public static long msToS(long ms) {
        return (ms + 500L) / 1000L;
    }

    public static String millisecondsToString(long time) {
        String res;

        long weeks = time / week;
        time = time - week * weeks;

        long days = time / day;
        time = time - days * day;

        long hours = time / hour;
        time = time - hours * hour;

        long minutes = time / minute;
        time = time - minutes * minute;

        long seconds = msToS(time);

        if (weeks > 0) {
            res = weeks + "w " + days + "d";
        }
        else
            if (days > 0) {
                res = days + "d " + hours + "h";
            }
            else
                if (hours > 0) {
                    res = hours + "h " + minutes + "m";
                }
                else
                    if (minutes > 0) {
                        res = minutes + "m " + seconds + "s";
                    }
                    else {
                        res = seconds + "sec";
                    }


        return res;
    }

    public int getAutoCountIdleSeconds() {
        return autoCountIdleSeconds;
    }

    public synchronized void setAutoCountIdleSeconds(int autoCountIdleSeconds) {
        this.autoCountIdleSeconds = autoCountIdleSeconds;
    }

    public boolean isPauseOtherTrackerInstances() {
        return pauseOtherTrackerInstances;
    }

    public synchronized void setPauseOtherTrackerInstances(boolean pauseOtherTrackerInstances) {
        this.pauseOtherTrackerInstances = pauseOtherTrackerInstances;
    }

    private synchronized void otherComponentStarted() {
        if (status != Status.STOPPED) {
            setStatus(Status.IDLE);
        }
    }

    public synchronized void addOrResetTotalTimeMs(long milliseconds) {
        if (milliseconds == RESET_TIME_TO_ZERO) {
            totalTimeMs = 0L;
            statusStartedMs = System.currentTimeMillis();
        } else {
            addTotalTimeMs(milliseconds);
        }
        repaintWidget(false);
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public synchronized void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
        updateAutoStartListener(autoStart);
    }

    private void updateAutoStartListener(boolean enabled) {
        final EditorEventMulticaster editorEventMulticaster = EditorFactory.getInstance().getEventMulticaster();
        if (autoStartDocumentListener != null) {
            editorEventMulticaster.removeDocumentListener(autoStartDocumentListener);
            autoStartDocumentListener = null;
        }
        if (enabled) {
            editorEventMulticaster.addDocumentListener(autoStartDocumentListener = new DocumentListener() {
                @Override
                public void documentChanged(@NotNull DocumentEvent e) {
                    if (getStatus() == Status.RUNNING) return;
                    //getSelectedTextEditor() must be run from event dispatch thread
                    EventQueue.invokeLater(() -> {
                        final Project project = project();
                        if (project == null) return;

                        final Editor selectedTextEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                        if (selectedTextEditor == null) return;
                        if(e.getDocument().equals(selectedTextEditor.getDocument())) {
                            setStatus(Status.RUNNING);
                        }
                    });
                }
            });
        }
    }
}