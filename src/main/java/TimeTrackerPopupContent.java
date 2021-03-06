import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.BalloonBuilder;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.io.StreamUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class TimeTrackerPopupContent extends Box {

    private static final Logger LOG = Logger.getLogger(TimeTrackerPopupContent.class.getName());

    JBPopup popup;

    TimeTrackerPopupContent(TimeTrackerComponent component) {
        super(BoxLayout.Y_AXIS);

        final int insetLR = 10;
        final int insetTB = 5;
        this.setBorder(BorderFactory.createEmptyBorder(insetTB, insetLR, insetTB, insetLR));

        final JPanel optionsPanel = new JPanel(new GridLayout(0, 2, 4, 0));
        this.add(optionsPanel);

        {
            final String[] modes = new String[]{
                    "Pause after (sec):",
                    "Stop after (sec):"
            };
            final ComboBox<String> modeComboBox = new ComboBox<>(modes);
            modeComboBox.setSelectedIndex(component.isStopWhenIdleRatherThanPausing() ? 1 : 0);
            modeComboBox.addActionListener(e -> {
                component.setStopWhenIdleRatherThanPausing(modeComboBox.getSelectedIndex() == 1);
            });
            modeComboBox.setAlignmentX(1f);
            optionsPanel.add(modeComboBox);

            final JSpinner idleThresholdSpinner = new JSpinner(new SpinnerNumberModel(TimeTrackerComponent.msToS(component.getIdleThresholdMs()), 0, Integer.MAX_VALUE, 10));
            optionsPanel.add(idleThresholdSpinner);
            idleThresholdSpinner.addChangeListener(ce ->
                    component.setIdleThresholdMs(((Number) idleThresholdSpinner.getValue()).longValue() * 1000));
        }

        {
            final Box otherButtons = Box.createHorizontalBox();
            this.add(otherButtons);

            otherButtons.add(Box.createHorizontalGlue());

            final JButton loadDefaults = new JButton("Reset to defaults");
            loadDefaults.addActionListener(e1 -> {
                component.loadStateDefaults(TimeTrackerDefaultSettingsComponent.instance().getState());
                popup.cancel();
            });
            otherButtons.add(loadDefaults);

            final JButton saveDefaults = new JButton("Save as defaults");
            saveDefaults.addActionListener(e1 -> {
                TimeTrackerDefaultSettingsComponent.instance().setDefaultsFrom(component.getState());
                popup.cancel();
            });
            otherButtons.add(saveDefaults);
        }

        {
            optionsPanel.add(new JLabel("Auto-count pauses shorter than (sec):", JLabel.RIGHT));
            final JSpinner autoCountSpinner = new JSpinner(new SpinnerNumberModel(component.getAutoCountIdleSeconds(), 0, Integer.MAX_VALUE, 10));
            optionsPanel.add(autoCountSpinner);
            autoCountSpinner.addChangeListener(ce ->
                    component.setAutoCountIdleSeconds(((Number) autoCountSpinner.getValue()).intValue()));
        }

        {
            optionsPanel.add(new JLabel("Pause other IDE windows when this one activates:", JLabel.RIGHT));
            final JCheckBox autoPauseCheckBox = new JCheckBox();
            autoPauseCheckBox.setSelected(component.isPauseOtherTrackerInstances());
            autoPauseCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
            autoPauseCheckBox.setVerticalAlignment(SwingConstants.CENTER);
            optionsPanel.add(autoPauseCheckBox);
            autoPauseCheckBox.addActionListener(al -> {
                component.setPauseOtherTrackerInstances(autoPauseCheckBox.isSelected());
            });
        }

        {
            final Box timeButtons = Box.createHorizontalBox();

            final JButton timeResetButton = new JButton("Reset time");
            timeResetButton.setToolTipText("Completely reset tracked time, including git time, if enabled");
            timeResetButton.addActionListener(e1 -> component.addOrResetTotalTimeMs(TimeTrackerComponent.RESET_TIME_TO_ZERO));
            timeButtons.add(timeResetButton);
            timeButtons.add(Box.createHorizontalGlue());

            {// +time buttons
                final int[] timesSec = {-3600, -60 * 5, -30, 30, 60 * 5, 3600};
                final String[] labels = {"-1h", "-5m", "-30s", "+30s", "+5m", "+1h"};

                for (int i = 0; i < labels.length; i++) {
                    final int timeChange = timesSec[i];
                    final JButton timeButton = new JButton(labels[i]);
                    timeButton.addActionListener(e1 -> {
                        component.addOrResetTotalTimeMs(timeChange * 1000);
                    });
                    timeButtons.add(timeButton);
                }
            }
            this.add(timeButtons);
        }

        {
            optionsPanel.add(new JLabel("Auto start on typing:", JLabel.RIGHT));
            final JCheckBox autoStartCheckBox = new JCheckBox();
            autoStartCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
            autoStartCheckBox.setVerticalAlignment(SwingConstants.CENTER);
            autoStartCheckBox.setSelected(component.isAutoStart());
            optionsPanel.add(autoStartCheckBox);
            autoStartCheckBox.addActionListener(al -> {
                component.setAutoStart(autoStartCheckBox.isSelected());
            });
        }
    }
}