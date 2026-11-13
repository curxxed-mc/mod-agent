package dev.curxxed.agent;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import dev.curxxed.agent.config.Json;

/**
 * The setup window shown when the agent is opened with arguments.
 */
public final class AgentWindow {

    private static final Color BACKGROUND_COLOR = new Color(24, 25, 29);
    private static final Color SECTION_BACKGROUND_COLOR = new Color(33, 35, 40);
    private static final Color OUTLINE_COLOR = new Color(50, 53, 60);
    private static final Color CLIENT_BACKGROUND_COLOR = new Color(25, 26, 30);
    private static final Color HOVER_COLOR = new Color(46, 49, 56);
    private static final Color TEXT_COLOR = new Color(230, 231, 236);
    private static final Color DESCRIPTION_TEXT_COLOR = new Color(150, 154, 166);
    private static final Color DARK_TEXT_COLOR = new Color(24, 25, 29);
    private static final Color ACCENT_COLOR = new Color(202, 168, 247);
    private static final Color ACCENT_HOVER_COLOR = new Color(216, 195, 250);
    private static final Color ACCENT_DISABLED_COLOR = new Color(101, 90, 123); // When a button is currently disabled
    private static final Color SUCCESS_COLOR = new Color(110, 205, 135);
    private static final Color FAIL_COLOR = new Color(245, 115, 115);
    private static final int WRAP = 380;

    public static void show(File agent, File initialConfig) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {} // Default look still works
        SwingUtilities.invokeLater(() -> build(agent, initialConfig));
    }

    private static void build(File agent, File initialConfig) {
        final JFrame window = new JFrame("Mod Agent");
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        final JPanel page = column();
        page.setOpaque(true);
        page.setBackground(BACKGROUND_COLOR);
        page.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));

        page.add(left(label("Mod Agent", Font.BOLD, 22f, TEXT_COLOR))); // Title
        page.add(Box.createVerticalStrut(2));
        page.add(left(label("Injects 1.8.9 coremods on Lunar, Badlion, Vanilla.", Font.PLAIN, 0f, DESCRIPTION_TEXT_COLOR)));
        page.add(Box.createVerticalStrut(20));

        // Which mods to load.
        page.add(stepNumber(1, "Select your mods"));
        page.add(Box.createVerticalStrut(8));
        final Card mods = new Card();
        final JTextField config = textField(new JTextField(initialConfig.getAbsolutePath(), 28));
        final Button browse = new Button("Browse...", false);
        final JPanel configRow = new JPanel(new BorderLayout(8, 0));
        configRow.setOpaque(false);
        configRow.add(config);
        configRow.add(browse, BorderLayout.EAST);
        final JLabel configState = new JLabel(" ");
        mods.add(left(configRow));
        mods.add(Box.createVerticalStrut(8));
        mods.add(left(configState));
        page.add(left(mods));
        final Runnable validate = () -> describeConfig(new File(config.getText().trim()), configState);
        config.getDocument().addDocumentListener(onChange(validate));
        browse.addActionListener(event -> {
            File chosen = choose(window, config.getText());
            if (chosen != null) config.setText(chosen.getAbsolutePath());
        });
        validate.run();
        page.add(Box.createVerticalStrut(20));

        // One client at a time, only instructions that matter are displayed.
        page.add(stepNumber(2, "Install into your client"));
        page.add(Box.createVerticalStrut(8));
        final Card clients = new Card();
        final CardLayout deckLayout = new CardLayout();
        final JPanel deck = new JPanel(deckLayout);
        deck.setOpaque(false);
        deck.add(lunarPanel(window, agent, config), "Lunar");
        deck.add(badlionPanel(window, agent, config), "Badlion");
        deck.add(vanillaPanel(window, agent, config), "Vanilla");
        clients.add(left(segmented(deckLayout, deck, "Lunar", "Badlion", "Vanilla")));
        clients.add(Box.createVerticalStrut(14));
        clients.add(left(deck));
        page.add(left(clients));
        page.add(Box.createVerticalStrut(12));
        page.add(left(footer(agent)));
        window.setContentPane(page);
        window.pack();
        window.setMinimumSize(window.getSize());
        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }

    private static ClientPanel lunarPanel(JFrame window, File agent, JTextField config) {
        final ClientPanel panel = new ClientPanel(
                "Paste the argument into Lunar under Settings -> JVM Arguments. Turn on Advanced Settings if you can't see that field.");
        final Button copy = panel.button("Copy JVM argument", true);
        copy.addActionListener(event -> {
            final String configPath = config.getText(); // Read on the UI thread, Swing isn't thread-safe.
            panel.work(window, () -> copyArgument(agent, configPath, panel) + " Paste it into Lunar, then launch 1.8.9.", copy);
        });
        return panel;
    }

    private static ClientPanel badlionPanel(JFrame window, File agent, JTextField config) {
        final ClientPanel panel = new ClientPanel(
                "Lunar starts Badlion with its own Java. This points it at a small launcher that adds the agent.");
        panel.note("Fully quit Lunar first, otherwise this will be undone when closing.");
        final Button install = panel.button("Set up Badlion", true);
        final Button undo = panel.button("Undo", false);
        final Path output = Paths.get(System.getProperty("user.home"), ".mod-agent", "badlion-runtime");
        install.addActionListener(event -> {
            final String configPath = config.getText(); // Read on the UI thread, Swing isn't thread-safe.
            panel.work(window, () -> {
                final Path runtime = BadlionLauncherSetup.detectRuntime();
                final Path bin = BadlionLauncherSetup.install(runtime, output, agent, configFile(configPath),
                        Collections.emptyList(), output.resolve("launcher.log"));
                BadlionLauncherSetup.wireSettings(output, bin);
                return "Done, using Java " + runtime.getParent().getFileName() + ". Launch Badlion from Lunar as usual.";
            }, install, undo);
        });
        undo.addActionListener(event -> panel.work(window, () -> {
            BadlionLauncherSetup.restoreSettings(output);
            return "Lunar's Badlion Java path is restored. You can delete " + output + ".";
        }, install, undo));
        return panel;
    }

    private static ClientPanel vanillaPanel(JFrame window, File agent, JTextField config) {
        final ClientPanel panel = new ClientPanel(
                "Add the JVM argument to your current arguments list in the Java Arguments field supplied by your current launcher.");
        final Button copy = panel.button("Copy JVM argument", true);
        copy.addActionListener(event -> {
            final String configPath = config.getText(); // Read on the UI thread, Swing isn't thread-safe.
            panel.work(window, () -> copyArgument(agent, configPath, panel) + " Paste it into your installation's JVM arguments.", copy);
        });
        return panel;
    }

    private static String copyArgument(File agent, String configPath, ClientPanel panel) throws Exception {
        final LunarJvmArgument.Result result = LunarJvmArgument.prepare(agent, configFile(configPath), LunarJvmArgument.defaultFolder());
        SwingUtilities.invokeLater(() -> {
            panel.argument.setText(result.argument);
            panel.argument.setCaretPosition(0);
            panel.argument.setVisible(true);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(result.argument), null);
        });
        return "Copied." + (result.copiedTo == null ? ""
                : " Your path has a space, which these launchers can't handle, so the files were copied to "
                + result.copiedTo + ". Press again after changing your mods.");
    }

    private static JPanel footer(File agent) {
        final JPanel footer = column();
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        final JLabel state = new JLabel(" ");

        // Log button.
        final JButton log = new JButton("Open log");
        log.setBorderPainted(false);
        log.setContentAreaFilled(false);
        log.setFocusPainted(false);
        log.setBorder(BorderFactory.createEmptyBorder());
        log.setForeground(ACCENT_COLOR);
        log.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        log.addActionListener(event -> {
            final File file = newestLog(agent);
            try {
                if (!file.isFile()) {
                    throw new IllegalStateException("No log yet. Launch the game once first.");
                }
                Desktop.getDesktop().open(file);
                state.setText(" ");
            } catch (Exception failure) {
                setStatus(state, failure.getMessage(), FAIL_COLOR);
            }
        });
        row.add(log);
        row.add(label("  •  Mod-Agent writes mod-agent.log next to the agent JAR.", Font.PLAIN, 0f, DESCRIPTION_TEXT_COLOR));
        footer.add(left(row));
        footer.add(left(state));
        return footer;
    }

    /// The log sits beside whichever agent JAR the game ran: this one, or the no-spaces copy that
    /// "Copy JVM argument" makes. If that folder isn't writable, it falls back to ~/.mod-agent.
    /// Of the logs that exist, the most recently written one belongs to the last launch.
    private static File newestLog(File agent) {
        final File[] candidates = {
                new File(agent.getParentFile(), "mod-agent.log"),
                LunarJvmArgument.defaultFolder().resolve("mod-agent.log").toFile(),
                Paths.get(System.getProperty("user.home"), ".mod-agent", "mod-agent.log").toFile()
        };
        File newest = candidates[0];
        for (File candidate : candidates) {
            if (candidate.isFile() && (!newest.isFile() || candidate.lastModified() > newest.lastModified())) newest = candidate;
        }
        return newest;
    }

    private static final class ClientPanel extends JPanel {

        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        final JLabel status = new JLabel(" ");
        final JTextField argument = textField(new JTextField(1));

        ClientPanel(String description) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            actions.setOpaque(false);
            argument.setEditable(false);
            argument.setVisible(false);
            argument.setMaximumSize(new Dimension(Integer.MAX_VALUE, argument.getPreferredSize().height));
            add(left(wrapped(description, DESCRIPTION_TEXT_COLOR, Font.PLAIN)));
            add(Box.createVerticalStrut(12));
            add(left(actions));
            add(Box.createVerticalStrut(10));
            add(left(status));
            add(Box.createVerticalStrut(6));
            add(left(argument));
        }

        @SuppressWarnings("SameParameterValue")
        void note(String text) {
            add(left(wrapped(text, TEXT_COLOR, Font.BOLD)), 1);
            add(Box.createVerticalStrut(6), 1);
        }

        Button button(String text, boolean primary) {
            if (actions.getComponentCount() > 0) actions.add(Box.createHorizontalStrut(8));
            final Button button = new Button(text, primary);
            actions.add(button);
            return button;
        }

        /// Runs a step off the UI thread, disabling the buttons until it reports back.
        void work(JFrame window, Step step, JButton... busy) {
            for (JButton button : busy) button.setEnabled(false);
            setStatus(status, "Working...", DESCRIPTION_TEXT_COLOR);
            new Thread(() -> {
                String message;
                Color color;

                try {
                    message = step.run();
                    color = SUCCESS_COLOR;
                } catch (Throwable t) {
                    message = t.getMessage() != null ? t.getMessage() : t.toString();
                    color = FAIL_COLOR;
                }

                String text = message;
                Color tone = color;
                SwingUtilities.invokeLater(() -> {
                    setStatus(status, text, tone);
                    for (JButton button : busy) {
                        button.setEnabled(true);
                    }
                    window.pack();
                });
            }, "Mod-Agent-Setup").start();
        }
    }

    private interface Step {
        String run() throws Exception;
    }

    /// Shows how many mods the config loads & which ones are missing.
    private static void describeConfig(File config, JLabel target) {
        try {
            if (!config.isFile()) {
                throw new IllegalStateException("No agent-mods.json here. Browse to yours.");
            }

            final Object root = Json.parse(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8));
            final Object mods = root instanceof Map ? ((Map<?, ?>) root).get("mods") : null;
            if (!(mods instanceof List) || ((List<?>) mods).isEmpty()) {
                throw new IllegalStateException("This file has no mods listed.");
            }

            final List<String> names = new ArrayList<>();
            final List<String> missing = new ArrayList<>();
            for (Object mod : (List<?>) mods) {
                final Object jar = mod instanceof Map ? ((Map<?, ?>) mod).get("jar") : null;
                if (!(jar instanceof String)) continue;

                File file = new File((String) jar);
                if (!file.isAbsolute()) file = new File(config.getAbsoluteFile().getParentFile(), (String) jar);
                (file.isFile() ? names : missing).add(file.getName());
            }

            if (!missing.isEmpty()) {
                throw new IllegalStateException("Missing mod JAR: " + String.join(", ", missing));
            }
            setStatus(target, "Ready: " + names.size() + (names.size() == 1 ? " mod (" : " mods (") + String.join(", ", names) + ")", SUCCESS_COLOR);
        } catch (Exception e) {
            setStatus(target, e.getMessage(), FAIL_COLOR);
        }
    }

    private static File configFile(String configPath) {
        final File file = new File(configPath.trim());
        if (!file.isFile()) {
            throw new IllegalArgumentException("Choose your agent-mods.json in step 1 first.");
        }
        return file;
    }

    private static final class Card extends JPanel {

        Card() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        }

        @Override protected void paintComponent(Graphics graphics) {
            final Graphics2D g = setRenderingHints(graphics);
            g.setColor(SECTION_BACKGROUND_COLOR);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g.setColor(OUTLINE_COLOR);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g.dispose();
        }
    }

    private static final class Button extends JButton {

        private final boolean primary;

        Button(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setForeground(primary ? DARK_TEXT_COLOR : TEXT_COLOR);
            if (primary) setFont(getFont().deriveFont(Font.BOLD));
            setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override protected void paintComponent(Graphics graphics) {
            final Graphics2D g = setRenderingHints(graphics);
            final boolean hover = isEnabled() && getModel().isRollover();
            g.setColor(primary ? (!isEnabled() ? ACCENT_DISABLED_COLOR : hover ? ACCENT_HOVER_COLOR : ACCENT_COLOR) : hover ? HOVER_COLOR : SECTION_BACKGROUND_COLOR);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);

            if (!primary) {
                g.setColor(OUTLINE_COLOR);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /// The segments of the switches/client buttons.
    private static final class ClientButton extends JToggleButton {

        ClientButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setForeground(TEXT_COLOR);
            addItemListener(event -> {
                setForeground(isSelected() ? DARK_TEXT_COLOR : TEXT_COLOR);
                setFont(getFont().deriveFont(isSelected() ? Font.BOLD : Font.PLAIN));
            });
        }

        @Override protected void paintComponent(Graphics graphics) {
            if (isSelected() || getModel().isRollover()) {
                final Graphics2D g = setRenderingHints(graphics);
                g.setColor(isSelected() ? ACCENT_COLOR : HOVER_COLOR);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    /// For the client buttons.
    private static JPanel segmented(CardLayout layout, JPanel deck, String... names) {

        final JPanel track = new JPanel(new GridLayout(1, names.length, 4, 0)) {
            @Override protected void paintComponent(Graphics graphics) {
                final Graphics2D g = setRenderingHints(graphics);
                g.setColor(CLIENT_BACKGROUND_COLOR);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g.dispose();
            }
        };

        track.setOpaque(false);
        track.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        final ButtonGroup group = new ButtonGroup();
        for (String name : names) {
            final ClientButton clientButton = new ClientButton(name);
            clientButton.addActionListener(event -> layout.show(deck, name));
            group.add(clientButton);
            track.add(clientButton);
            if (group.getSelection() == null) {
                clientButton.setSelected(true);
            }
        }
        track.setMaximumSize(new Dimension(Integer.MAX_VALUE, track.getPreferredSize().height));
        return track;
    }

    /// The numbered step icon/circle.
    private static JPanel stepNumber(int number, String title) {

        final JComponent badge = new JComponent() {
            @Override protected void paintComponent(Graphics graphics) {
                final Graphics2D g = setRenderingHints(graphics);
                g.setColor(ACCENT_COLOR);
                g.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                g.setColor(DARK_TEXT_COLOR);
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                final FontMetrics metrics = g.getFontMetrics();
                final String text = Integer.toString(number);
                g.drawString(text, (getWidth() - metrics.stringWidth(text)) / 2, (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
                g.dispose();
            }
        };

        badge.setFont(UIManager.getFont("Label.font"));
        badge.setPreferredSize(new Dimension(22, 22));
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(badge);
        row.add(Box.createHorizontalStrut(8));
        row.add(label(title, Font.BOLD, 15f, TEXT_COLOR));
        return left(row);
    }

    @SuppressWarnings("MagicConstant")
    private static JLabel label(String text, int style, float size, Color color) {
        final JLabel label = new JLabel(text);
        final Font font = label.getFont().deriveFont(style);
        label.setFont(size > 0 ? font.deriveFont(size) : font);
        label.setForeground(color);
        return label;
    }

    @SuppressWarnings("MagicConstant")
    private static JLabel wrapped(String text, Color color, int style) {
        final JLabel label = new JLabel(html(text));
        label.setFont(label.getFont().deriveFont(style));
        label.setForeground(color);
        return label;
    }

    /// Restyle text fields for dark theme.
    private static JTextField textField(JTextField field) {
        field.setBackground(BACKGROUND_COLOR);
        field.setForeground(TEXT_COLOR);
        field.setCaretColor(TEXT_COLOR);
        field.setSelectionColor(ACCENT_COLOR);
        field.setSelectedTextColor(DARK_TEXT_COLOR);
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(OUTLINE_COLOR),
                BorderFactory.createEmptyBorder(5, 7, 5, 7)));
        return field;
    }

    private static void setStatus(JLabel label, String text, Color color) {
        label.setForeground(color);
        label.setText(html(text));
    }

    /// Fixed width HTML so long messages wrap.
    private static String html(String text) {
        final String escaped = (text == null ? "Something went wrong; see the console for details." : text)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><body style='width:" + WRAP + "px'>" + escaped + "</body></html>";
    }

    private static JPanel column() {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    private static <T extends JComponent> T left(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    private static Graphics2D setRenderingHints(Graphics graphics) {
        final Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    private static DocumentListener onChange(Runnable action) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) {
                action.run();
            }
            @Override public void removeUpdate(DocumentEvent event) {
                action.run();
            }
            @Override public void changedUpdate(DocumentEvent event) {
                action.run();
            }
        };
    }

    private static File choose(JFrame parent, String current) {
        final JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (!current.isEmpty()) chooser.setSelectedFile(new File(current));
        return chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }
}