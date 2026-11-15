package dev.curxxed.agent

import dev.curxxed.agent.config.Json
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.WindowConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The setup window shown when the agent is opened without arguments.
 */
object AgentWindow {

    private val BACKGROUND_COLOR = Color(24, 25, 29)
    private val SECTION_BACKGROUND_COLOR = Color(33, 35, 40)
    private val OUTLINE_COLOR = Color(50, 53, 60)
    private val CLIENT_BACKGROUND_COLOR = Color(25, 26, 30)
    private val HOVER_COLOR = Color(46, 49, 56)
    private val TEXT_COLOR = Color(230, 231, 236)
    private val DESCRIPTION_TEXT_COLOR = Color(150, 154, 166)
    private val DARK_TEXT_COLOR = Color(24, 25, 29)
    private val ACCENT_COLOR = Color(202, 168, 247)
    private val ACCENT_HOVER_COLOR = Color(216, 195, 250)
    private val ACCENT_DISABLED_COLOR = Color(101, 90, 123) // When a button is currently disabled
    private val SUCCESS_COLOR = Color(110, 205, 135)
    private val FAIL_COLOR = Color(245, 115, 115)
    private const val WRAP = 380

    fun show(agent: File, initialConfig: File) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (ignored: Exception) {} // Default look still works
        SwingUtilities.invokeLater { build(agent, initialConfig) }
    }

    private fun build(agent: File, initialConfig: File) {
        val window = JFrame("Mod Agent")
        window.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        val page = column()
        page.isOpaque = true
        page.background = BACKGROUND_COLOR
        page.border = BorderFactory.createEmptyBorder(20, 22, 16, 22)

        page.add(label("Mod Agent", Font.BOLD, 22f, TEXT_COLOR).left()) // Title
        page.add(Box.createVerticalStrut(2))
        page.add(label("Injects 1.8.9 coremods on Lunar, Badlion, Vanilla.", Font.PLAIN, 0f, DESCRIPTION_TEXT_COLOR).left())
        page.add(Box.createVerticalStrut(20))

        // Which mods to load.
        page.add(stepNumber(1, "Select your mods"))
        page.add(Box.createVerticalStrut(8))
        val mods = Card()
        val config = textField(JTextField(initialConfig.absolutePath, 28))
        val browse = Button("Browse...", false)
        val configRow = JPanel(BorderLayout(8, 0))
        configRow.isOpaque = false
        configRow.add(config)
        configRow.add(browse, BorderLayout.EAST)
        val configState = JLabel(" ")
        mods.add(configRow.left())
        mods.add(Box.createVerticalStrut(8))
        mods.add(configState.left())
        page.add(mods.left())
        val validate = { describeConfig(File(config.text.trim()), configState) }
        config.document.addDocumentListener(onChange(validate))
        browse.addActionListener {
            val chosen = choose(window, config.text)
            if (chosen != null) config.text = chosen.absolutePath
        }
        validate()
        page.add(Box.createVerticalStrut(20))

        // One client at a time, only instructions that matter are displayed.
        page.add(stepNumber(2, "Install into your client"))
        page.add(Box.createVerticalStrut(8))
        val clients = Card()
        val deckLayout = CardLayout()
        val deck = JPanel(deckLayout)
        deck.isOpaque = false
        deck.add(lunarPanel(window, agent, config), "Lunar")
        deck.add(badlionPanel(window, agent, config), "Badlion")
        deck.add(vanillaPanel(window, agent, config), "Vanilla")
        clients.add(segmented(deckLayout, deck, "Lunar", "Badlion", "Vanilla").left())
        clients.add(Box.createVerticalStrut(14))
        clients.add(deck.left())
        page.add(clients.left())
        page.add(Box.createVerticalStrut(12))
        page.add(footer(agent).left())
        window.contentPane = page
        window.isResizable = false
        window.pack()
        window.minimumSize = window.size
        window.setLocationRelativeTo(null)
        window.isVisible = true
    }

    private fun lunarPanel(window: JFrame, agent: File, config: JTextField): ClientPanel {
        val panel = ClientPanel(
            "Paste the argument into Lunar under Settings -> JVM Arguments. Turn on Advanced Settings if you can't see that field.")
        val copy = panel.button("Copy JVM argument", true)
        copy.addActionListener {
            val configPath = config.text // Read on the UI thread, Swing isn't thread-safe.
            panel.work(window, { copyArgument(agent, configPath, panel) + " Paste it into Lunar, then launch 1.8.9." }, copy)
        }
        return panel
    }

    private fun badlionPanel(window: JFrame, agent: File, config: JTextField): ClientPanel {
        val panel = ClientPanel(
            "Lunar starts Badlion with its own Java. This points it at a small launcher that adds the agent.")
        panel.note("Fully quit Lunar first, otherwise this will be undone when closing.")
        val install = panel.button("Set up Badlion", true)
        val undo = panel.button("Undo", false)
        val output = Paths.get(System.getProperty("user.home"), ".mod-agent", "badlion-runtime")
        install.addActionListener {
            val configPath = config.text // Read on the UI thread, Swing isn't thread-safe.
            panel.work(window, {
                val runtime = BadlionLauncherSetup.detectRuntime()
                val bin = BadlionLauncherSetup.install(runtime, output, agent, configFile(configPath),
                    emptyList(), output.resolve("launcher.log"))
                BadlionLauncherSetup.wireSettings(output, bin)
                "Done, using Java ${runtime.parent.fileName}. Launch Badlion from Lunar as usual."
            }, install, undo)
        }
        undo.addActionListener {
            panel.work(window, {
                BadlionLauncherSetup.restoreSettings(output)
                "Lunar's Badlion Java path is restored. You can delete $output."
            }, install, undo)
        }
        return panel
    }

    private fun vanillaPanel(window: JFrame, agent: File, config: JTextField): ClientPanel {
        val panel = ClientPanel(
            "Add the JVM argument to your current arguments list in the Java Arguments field supplied by your current launcher.")
        val copy = panel.button("Copy JVM argument", true)
        copy.addActionListener {
            val configPath = config.text // Read on the UI thread, Swing isn't thread-safe.
            panel.work(window, { copyArgument(agent, configPath, panel) + " Paste it into your installation's JVM arguments." }, copy)
        }
        return panel
    }

    private fun copyArgument(agent: File, configPath: String, panel: ClientPanel): String {
        val result = LunarJvmArgument.prepare(agent, configFile(configPath), LunarJvmArgument.defaultFolder())
        SwingUtilities.invokeLater {
            panel.argument.text = result.argument
            panel.argument.caretPosition = 0
            panel.argument.isVisible = true
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(result.argument), null)
        }
        return "Copied." + if (result.copiedTo == null) "" else
            " Your path has a space, which these launchers can't handle, so the files were copied to " +
                    "${result.copiedTo}. Press again after changing your mods."
    }

    private fun footer(agent: File): JPanel {
        val footer = column()
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        row.isOpaque = false
        val state = JLabel(" ")

        // Log button.
        val log = JButton("Open log")
        log.isBorderPainted = false
        log.isContentAreaFilled = false
        log.isFocusPainted = false
        log.border = BorderFactory.createEmptyBorder()
        log.foreground = ACCENT_COLOR
        log.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        log.addActionListener {
            val file = newestLog(agent)
            try {
                check(file.isFile) { "No log yet. Launch the game once first." }
                Desktop.getDesktop().open(file)
                state.text = " "
            } catch (failure: Exception) {
                setStatus(state, failure.message, FAIL_COLOR)
            }
        }
        row.add(log)
        row.add(label("  •  Mod-Agent writes mod-agent.log next to the agent JAR.", Font.PLAIN, 0f, DESCRIPTION_TEXT_COLOR))
        footer.add(row.left())
        footer.add(state.left())
        return footer
    }

    /// The log sits beside whichever agent JAR the game ran: this one, or the no-spaces copy that
    /// "Copy JVM argument" makes. If that folder isn't writable, it falls back to ~/.mod-agent.
    /// Of the logs that exist, the most recently written one belongs to the last launch.
    private fun newestLog(agent: File): File {
        val candidates = listOf(
            File(agent.parentFile, "mod-agent.log"),
            LunarJvmArgument.defaultFolder().resolve("mod-agent.log").toFile(),
            Paths.get(System.getProperty("user.home"), ".mod-agent", "mod-agent.log").toFile()
        )
        var newest = candidates[0]
        for (candidate in candidates) {
            if (candidate.isFile && (!newest.isFile || candidate.lastModified() > newest.lastModified())) newest = candidate
        }
        return newest
    }

    private class ClientPanel(description: String) : JPanel() {

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        val status = JLabel(" ")
        val argument = textField(JTextField(1))

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            actions.isOpaque = false
            argument.isEditable = false
            argument.isVisible = false
            argument.maximumSize = Dimension(Int.MAX_VALUE, argument.preferredSize.height)
            add(wrapped(description, DESCRIPTION_TEXT_COLOR, Font.PLAIN).left())
            add(Box.createVerticalStrut(12))
            add(actions.left())
            add(Box.createVerticalStrut(10))
            add(status.left())
            add(Box.createVerticalStrut(6))
            add(argument.left())
        }

        fun note(text: String) {
            add(wrapped(text, TEXT_COLOR, Font.BOLD).left(), 1)
            add(Box.createVerticalStrut(6), 1)
        }

        fun button(text: String, primary: Boolean): Button {
            if (actions.componentCount > 0) actions.add(Box.createHorizontalStrut(8))
            val button = Button(text, primary)
            actions.add(button)
            return button
        }

        /// Runs a step off the UI thread, disabling the buttons until it reports back.
        fun work(window: JFrame, step: () -> String, vararg busy: JButton) {
            for (button in busy) button.isEnabled = false
            setStatus(status, "Working...", DESCRIPTION_TEXT_COLOR)
            Thread({
                val (message, color) = try {
                    step() to SUCCESS_COLOR
                } catch (t: Throwable) {
                    (t.message ?: t.toString()) to FAIL_COLOR
                }

                SwingUtilities.invokeLater {
                    setStatus(status, message, color)
                    for (button in busy) {
                        button.isEnabled = true
                    }
                    window.pack()
                }
            }, "Mod-Agent-Setup").start()
        }
    }

    /// Shows how many mods the config loads & which ones are missing.
    private fun describeConfig(config: File, target: JLabel) {
        try {
            check(config.isFile) { "No agent-mods.json here. Browse to yours." }

            val root = Json.parse(String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8))
            val mods = (root as? Map<*, *>)?.get("mods") as? List<*>
            if (mods.isNullOrEmpty()) throw IllegalStateException("This file has no mods listed.")

            val names = ArrayList<String>()
            val missing = ArrayList<String>()
            for (mod in mods) {
                val jar = (mod as? Map<*, *>)?.get("jar") as? String ?: continue

                var file = File(jar)
                if (!file.isAbsolute) file = File(config.absoluteFile.parentFile, jar)
                (if (file.isFile) names else missing).add(file.name)
            }

            check(missing.isEmpty()) { "Missing mod JAR: " + missing.joinToString(", ") }
            setStatus(target, "Ready: ${names.size}" + (if (names.size == 1) " mod (" else " mods (") + names.joinToString(", ") + ")", SUCCESS_COLOR)
        } catch (e: Exception) {
            setStatus(target, e.message, FAIL_COLOR)
        }
    }

    private fun configFile(configPath: String): File {
        val file = File(configPath.trim())
        require(file.isFile) { "Choose your agent-mods.json in step 1 first." }
        return file
    }

    private class Card : JPanel() {

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
        }

        override fun paintComponent(graphics: Graphics) {
            val g = setRenderingHints(graphics)
            g.color = SECTION_BACKGROUND_COLOR
            g.fillRoundRect(0, 0, width - 1, height - 1, 14, 14)
            g.color = OUTLINE_COLOR
            g.drawRoundRect(0, 0, width - 1, height - 1, 14, 14)
            g.dispose()
        }
    }

    private class Button(text: String, private val primary: Boolean) : JButton(text) {

        init {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            isRolloverEnabled = true
            foreground = if (primary) DARK_TEXT_COLOR else TEXT_COLOR
            if (primary) font = font.deriveFont(Font.BOLD)
            border = BorderFactory.createEmptyBorder(7, 16, 7, 16)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        override fun paintComponent(graphics: Graphics) {
            val g = setRenderingHints(graphics)
            val hover = isEnabled && model.isRollover
            g.color = when {
                primary && !isEnabled -> ACCENT_DISABLED_COLOR
                primary -> if (hover) ACCENT_HOVER_COLOR else ACCENT_COLOR
                else -> if (hover) HOVER_COLOR else SECTION_BACKGROUND_COLOR
            }
            g.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)

            if (!primary) {
                g.color = OUTLINE_COLOR
                g.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
            }
            g.dispose()
            super.paintComponent(graphics)
        }
    }

    /// The segments of the switches/client buttons.
    private class ClientButton(text: String) : JToggleButton(text) {

        init {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            isRolloverEnabled = true
            border = BorderFactory.createEmptyBorder(6, 14, 6, 14)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = TEXT_COLOR
            addItemListener {
                foreground = if (isSelected) DARK_TEXT_COLOR else TEXT_COLOR
                font = font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN)
            }
        }

        override fun paintComponent(graphics: Graphics) {
            if (isSelected || model.isRollover) {
                val g = setRenderingHints(graphics)
                g.color = if (isSelected) ACCENT_COLOR else HOVER_COLOR
                g.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
                g.dispose()
            }
            super.paintComponent(graphics)
        }
    }

    /// For the client buttons.
    private fun segmented(layout: CardLayout, deck: JPanel, vararg names: String): JPanel {

        val track = object : JPanel(GridLayout(1, names.size, 4, 0)) {
            override fun paintComponent(graphics: Graphics) {
                val g = setRenderingHints(graphics)
                g.color = CLIENT_BACKGROUND_COLOR
                g.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
                g.dispose()
            }
        }

        track.isOpaque = false
        track.border = BorderFactory.createEmptyBorder(3, 3, 3, 3)
        val group = ButtonGroup()
        for (name in names) {
            val clientButton = ClientButton(name)
            clientButton.addActionListener { layout.show(deck, name) }
            group.add(clientButton)
            track.add(clientButton)
            if (group.selection == null) {
                clientButton.isSelected = true
            }
        }
        track.maximumSize = Dimension(Int.MAX_VALUE, track.preferredSize.height)
        return track
    }

    /// The numbered step icon/circle.
    private fun stepNumber(number: Int, title: String): JPanel {

        val badge = object : JComponent() {
            override fun paintComponent(graphics: Graphics) {
                val g = setRenderingHints(graphics)
                g.color = ACCENT_COLOR
                g.fillOval(0, 0, width - 1, height - 1)
                g.color = DARK_TEXT_COLOR
                g.font = font.deriveFont(Font.BOLD, 12f)
                val metrics = g.fontMetrics
                val text = number.toString()
                g.drawString(text, (width - metrics.stringWidth(text)) / 2, (height - metrics.height) / 2 + metrics.ascent)
                g.dispose()
            }
        }

        badge.font = UIManager.getFont("Label.font")
        badge.preferredSize = Dimension(22, 22)
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        row.isOpaque = false
        row.add(badge)
        row.add(Box.createHorizontalStrut(8))
        row.add(label(title, Font.BOLD, 15f, TEXT_COLOR))
        return row.left()
    }

    private fun label(text: String, style: Int, size: Float, color: Color): JLabel {
        val label = JLabel(text)
        val font = label.font.deriveFont(style)
        label.font = if (size > 0) font.deriveFont(size) else font
        label.foreground = color
        return label
    }

    private fun wrapped(text: String, color: Color, style: Int): JLabel {
        val label = JLabel(html(text))
        label.font = label.font.deriveFont(style)
        label.foreground = color
        return label
    }

    /// Restyle text fields for dark theme.
    private fun textField(field: JTextField): JTextField {
        field.background = BACKGROUND_COLOR
        field.foreground = TEXT_COLOR
        field.caretColor = TEXT_COLOR
        field.selectionColor = ACCENT_COLOR
        field.selectedTextColor = DARK_TEXT_COLOR
        field.border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(OUTLINE_COLOR),
            BorderFactory.createEmptyBorder(5, 7, 5, 7))
        return field
    }

    private fun setStatus(label: JLabel, text: String?, color: Color) {
        label.foreground = color
        label.text = html(text)
    }

    /// Fixed width HTML so long messages wrap.
    private fun html(text: String?): String {
        val escaped = (text ?: "Something went wrong; see the console for details.")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<html><body style='width:${WRAP}px'>$escaped</body></html>"
    }

    private fun column(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        return panel
    }

    private fun <T : JComponent> T.left(): T {
        alignmentX = Component.LEFT_ALIGNMENT
        return this
    }

    private fun setRenderingHints(graphics: Graphics): Graphics2D {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        return g
    }

    private fun onChange(action: () -> Unit): DocumentListener = object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) = action()
        override fun removeUpdate(event: DocumentEvent) = action()
        override fun changedUpdate(event: DocumentEvent) = action()
    }

    private fun choose(parent: JFrame, current: String): File? {
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        if (current.isNotEmpty()) chooser.selectedFile = File(current)
        return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}
