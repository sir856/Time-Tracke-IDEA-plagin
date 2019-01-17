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
    }
}