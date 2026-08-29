package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.jetbrains.annotations.NotNull;

import javax.swing.UIManager;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Font;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Adapts VS Code's webview bridge to IntelliJ's embedded JCEF browser.
 *
 * The React bundle is the same bundle used by dsh-ide. Instead of
 * {@code acquireVsCodeApi} being supplied by VS Code, a JBCefJSQuery carries
 * actions into the project service and host state is delivered with the normal
 * {@code window.postMessage} event expected by {@code webview/src/bridge.ts}.
 */
public final class DshBridge implements Disposable {
    private static final Logger LOG = Logger.getInstance(DshBridge.class);

    private final JBCefBrowser browser;
    private final JBCefJSQuery actionQuery;
    private final Consumer<JsonElement> actionConsumer;
    private final PropertyChangeListener lookAndFeelListener;
    private volatile JsonElement lastState;
    private volatile boolean disposed;

    /** Check JCEF availability before constructing a DshBridge. */
    public static boolean isAvailable() {
        try {
            return JBCefApp.isSupported();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public DshBridge(@NotNull Consumer<JsonElement> actionConsumer) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "JCEF is not available in this IDE. "
                    + "Please use a JetBrains IDE with bundled JCEF (IntelliJ IDEA, PyCharm, etc.), "
                    + "or check Help → Find Action → 'Choose Boot Java Runtime' to switch to a JRE with JCEF.");
        }
        this.actionConsumer = actionConsumer;
        this.browser = new JBCefBrowser();
        this.actionQuery = JBCefJSQuery.create(browser);
        this.lookAndFeelListener = ignored -> updateThemeLater();
        UIManager.addPropertyChangeListener(lookAndFeelListener);
        this.actionQuery.addHandler(request -> {
            try {
                JsonElement action = JsonParser.parseString(request);
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        actionConsumer.accept(action);
                    } catch (RuntimeException error) {
                        LOG.warn("DSH webview action failed", error);
                    }
                });
            } catch (RuntimeException error) {
                LOG.warn("Ignoring malformed action from the DSH webview", error);
            }
            return null;
        });
    }

    public JComponent getComponent() {
        return browser.getComponent();
    }

    public void load() {
        String css = readResource("/webview/main.css");
        String adapterCss = readResource("/webview/intellij.css");
        String script = readResource("/webview/main.js");
        String injectedPost = actionQuery.inject("JSON.stringify(message)");
        String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<title>DSH</title><style>" + css + adapterCss + "</style>"
                + "<style id=\"dsh-jetbrains-theme\">" + themeCss() + "</style>"
                + "</head><body><div id=\"root\"></div><script>"
                + "window.__dshState=undefined;"
                + "window.acquireVsCodeApi=function(){return {"
                + "postMessage:function(message){" + injectedPost + "},"
                + "getState:function(){return window.__dshState;},"
                + "setState:function(state){window.__dshState=state;}"
                + "};};"
                + "</script><script>" + script + "</script></body></html>";
        browser.loadHTML(html);
        browser.getComponent().setBackground(themeColor("Panel.background", new Color(0x1E1F22)));
    }

    /** Send a VS Code-compatible host message to the React bridge. */
    public void postMessage(JsonElement message) {
        lastState = message;
        String json = message == null ? "null" : message.toString();
        String escaped = escapeJavaScriptString(json);
        String script = "(function(){var value=JSON.parse('" + escaped + "');"
                + "window.__dshState=value.state||value;window.postMessage(value,'*');})();";
        browser.getCefBrowser().executeJavaScript(script, browser.getCefBrowser().getURL(), 0);
    }

    public JsonElement getLastState() {
        return lastState;
    }

    private static String readResource(String path) {
        try (InputStream stream = DshBridge.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing DSH resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read DSH resource " + path, error);
        }
    }

    private static String escapeJavaScriptString(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    private void updateThemeLater() {
        if (disposed) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed) return;
            Color background = themeColor("Panel.background", new Color(0x1E1F22));
            browser.getComponent().setBackground(background);
            String css = escapeJavaScriptString(themeCss());
            String script = "(function(){var style=document.getElementById('dsh-jetbrains-theme');"
                    + "if(style){style.textContent='" + css + "';}})();";
            browser.getCefBrowser().executeJavaScript(script, browser.getCefBrowser().getURL(), 0);
        });
    }

    /** Translate the active JetBrains look-and-feel into the variables used by dsh-ide. */
    private static String themeCss() {
        Color background = themeColor("Editor.background", themeColor("Panel.background", new Color(0x1E1F22)));
        boolean dark = luminance(background) < 0.48;
        Color foreground = themeColor("Label.foreground", dark ? new Color(0xDFE1E5) : new Color(0x1F2329));
        Color muted = themeColor("Label.disabledForeground", dark ? new Color(0x9DA3AD) : new Color(0x6C707E));
        Color panel = themeColor("Panel.background", background);
        Color surface = themeColor("PopupMenu.background", themeColor("TextField.background", panel));
        Color input = themeColor("TextField.background", dark ? new Color(0x2B2D31) : Color.WHITE);
        Color inputForeground = themeColor("TextField.foreground", foreground);
        Color border = themeColor("Component.borderColor", themeColor("Separator.separatorColor",
                dark ? new Color(0x454851) : new Color(0xC9CCD6)));
        Color primary = themeColor("Button.default.startBackground",
                themeColor("Focus.color", dark ? new Color(0x3574F0) : new Color(0x315EFB)));
        Color primaryForeground = themeColor("Button.default.foreground", Color.WHITE);
        Color hover = themeColor("List.hoverBackground", dark ? new Color(0x33363D) : new Color(0xE8EAED));
        Color selection = themeColor("List.selectionBackground", primary);
        Color selectionForeground = themeColor("List.selectionForeground", primaryForeground);
        Color link = themeColor("Link.activeForeground", dark ? new Color(0x6EA8FE) : new Color(0x2F65CA));
        Color linkHover = themeColor("Link.hoverForeground", link.brighter());
        Color error = themeColor("Component.errorFocusColor", dark ? new Color(0xFF7B72) : new Color(0xC7222D));
        Color warning = themeColor("Component.warningFocusColor", dark ? new Color(0xDDBA7D) : new Color(0xA66B00));
        Color success = themeColor("Component.successFocusColor", dark ? new Color(0x73BD79) : new Color(0x2E7D32));
        Color errorBackground = themeColor("ValidationTooltip.errorBackground",
                dark ? new Color(0x3D2022) : new Color(0xFCE8E8));
        Color scroll = themeColor("ScrollBar.thumbColor", dark ? new Color(0x5C606A) : new Color(0xA8ABB4));
        Color scrollHover = themeColor("ScrollBar.hoverThumbColor", dark ? new Color(0x777D89) : new Color(0x858993));
        Color dropdown = themeColor("ComboBox.background", input);
        Color dropdownForeground = themeColor("ComboBox.foreground", inputForeground);
        Color menu = themeColor("PopupMenu.background", surface);
        Color menuForeground = themeColor("PopupMenu.foreground", foreground);
        Color code = themeColor("TextArea.background", input);
        Font labelFont = UIManager.getFont("Label.font");
        String family = labelFont == null ? "-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif"
                : cssFontFamily(labelFont.getFamily());
        int fontSize = labelFont == null ? 13 : Math.max(11, labelFont.getSize());

        StringBuilder css = new StringBuilder(2400).append(":root{")
                .append("color-scheme:").append(dark ? "dark" : "light").append(';');
        variable(css, "foreground", foreground);
        variable(css, "descriptionForeground", muted);
        variable(css, "sideBar-background", panel);
        variable(css, "editor-background", background);
        variable(css, "editor-foreground", foreground);
        variable(css, "editorWidget-background", surface);
        variable(css, "editorWidget-border", border);
        variable(css, "input-background", input);
        variable(css, "input-foreground", inputForeground);
        variable(css, "input-border", border);
        variable(css, "dropdown-background", dropdown);
        variable(css, "dropdown-foreground", dropdownForeground);
        variable(css, "dropdown-border", border);
        variable(css, "button-background", primary);
        variable(css, "button-foreground", primaryForeground);
        variable(css, "button-hoverBackground", blend(primary, foreground, 0.14));
        variable(css, "button-secondaryBackground", hover);
        variable(css, "toolbar-hoverBackground", hover);
        variable(css, "list-hoverBackground", hover);
        variable(css, "menu-background", menu);
        variable(css, "menu-foreground", menuForeground);
        variable(css, "menu-selectionBackground", selection);
        variable(css, "menu-selectionForeground", selectionForeground);
        variable(css, "panel-border", border);
        variable(css, "focusBorder", primary);
        variable(css, "textBlockQuote-background", input);
        variable(css, "textBlockQuote-border", border);
        variable(css, "textCodeBlock-background", code);
        variable(css, "textLink-foreground", link);
        variable(css, "textLink-activeForeground", linkHover);
        variable(css, "errorForeground", error);
        variable(css, "inputValidation-errorForeground", error);
        variable(css, "inputValidation-errorBorder", error);
        variable(css, "inputValidation-errorBackground", errorBackground);
        variable(css, "badge-background", primary);
        variable(css, "badge-foreground", primaryForeground);
        variable(css, "progressBar-background", primary);
        variable(css, "scrollbarSlider-background", scroll);
        variable(css, "scrollbarSlider-hoverBackground", scrollHover);
        variable(css, "testing-iconPassed", success);
        variable(css, "testing-iconFailed", error);
        variable(css, "editorWarning-foreground", warning);
        variable(css, "charts-green", success);
        variable(css, "charts-yellow", warning);
        variable(css, "charts-orange", dark ? new Color(0xD1864A) : new Color(0xC45D01));
        variable(css, "charts-blue", link);
        variable(css, "charts-purple", dark ? new Color(0xB392F0) : new Color(0x7E57C2));
        variable(css, "gitDecoration-addedResourceForeground", success);
        variable(css, "gitDecoration-deletedResourceForeground", error);
        variable(css, "gitDecoration-modifiedResourceForeground", link);
        variable(css, "gitDecoration-renamedResourceForeground", warning);
        variable(css, "symbolIcon-functionForeground", link);
        variable(css, "symbolIcon-keywordForeground", dark ? new Color(0xB392F0) : new Color(0x7E57C2));
        css.append("--vscode-font-family:").append(family).append(';')
                .append("--vscode-font-size:").append(fontSize).append("px;")
                .append("--vscode-editor-font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;")
                .append('}');
        return css.toString();
    }

    private static Color themeColor(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }

    private static void variable(StringBuilder css, String name, Color value) {
        css.append("--vscode-").append(name).append(':').append(cssColor(value)).append(';');
    }

    private static String cssColor(Color color) {
        if (color.getAlpha() == 255) {
            return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        }
        return "rgba(" + color.getRed() + ',' + color.getGreen() + ',' + color.getBlue() + ','
                + String.format(java.util.Locale.ROOT, "%.3f", color.getAlpha() / 255.0) + ')';
    }

    private static String cssFontFamily(String family) {
        return "\"" + family.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif";
    }

    private static double luminance(Color color) {
        return (0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue()) / 255.0;
    }

    private static Color blend(Color base, Color overlay, double amount) {
        double keep = 1.0 - amount;
        return new Color(
                (int) Math.round(base.getRed() * keep + overlay.getRed() * amount),
                (int) Math.round(base.getGreen() * keep + overlay.getGreen() * amount),
                (int) Math.round(base.getBlue() * keep + overlay.getBlue() * amount));
    }

    @Override
    public void dispose() {
        disposed = true;
        UIManager.removePropertyChangeListener(lookAndFeelListener);
        Disposer.dispose(actionQuery);
        Disposer.dispose(browser);
    }
}
