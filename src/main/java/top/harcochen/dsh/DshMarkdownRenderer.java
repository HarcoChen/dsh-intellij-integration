package top.harcochen.dsh;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

/** Parses whole messages so tables, lists, references and code retain their Markdown structure. */
final class DshMarkdownRenderer {
    private static final List<Extension> EXTENSIONS =
            List.of(TablesExtension.create(), StrikethroughExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = renderer(null);
    private static final Pattern LINE_ANCHOR =
            Pattern.compile("^(.+)#L([1-9]\\d{0,6})(?:-L?[1-9]\\d{0,6})?$");
    private static final Pattern LINE_SUFFIX =
            Pattern.compile("^(.+?):([1-9]\\d{0,6})(?::([1-9]\\d{0,6}))?$");
    private static final Pattern URI_SCHEME =
            Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*", Pattern.DOTALL);
    private static final Pattern WINDOWS_PATH =
            Pattern.compile("^[A-Za-z]:[/\\\\].*", Pattern.DOTALL);

    private DshMarkdownRenderer() {}

    static String render(String source) {
        return source == null || source.isEmpty() ? "" : RENDERER.render(PARSER.parse(source));
    }

    static String render(String source, BiFunction<String, String, String> codeBlockRenderer) {
        return source == null || source.isEmpty()
                ? ""
                : renderer(codeBlockRenderer).render(PARSER.parse(source));
    }

    private static HtmlRenderer renderer(BiFunction<String, String, String> codeBlockRenderer) {
        return HtmlRenderer.builder()
                .escapeHtml(true)
                .sanitizeUrls(true)
                .softbreak("<br>")
                .nodeRendererFactory(context -> new MessageNodeRenderer(context, codeBlockRenderer))
                .extensions(EXTENSIONS)
                .build();
    }

    private static Map<String, String> linkAttributes(String destination) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (DshWebviewActionSanitizer.isSafeExternalUrl(destination)) {
            attributes.put("class", "markdown-link");
            attributes.put("data-external-url", destination);
        } else {
            String path;
            try {
                // URLDecoder treats '+' as a space; a file link may contain a literal '+'.
                path = URLDecoder.decode(destination.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return Map.of();
            }
            int line = 1;
            Integer column = null;
            Matcher anchor = LINE_ANCHOR.matcher(path);
            Matcher suffix = LINE_SUFFIX.matcher(path);
            if (anchor.matches()) {
                path = anchor.group(1);
                line = Integer.parseInt(anchor.group(2));
            } else if (suffix.matches()) {
                path = suffix.group(1);
                line = Integer.parseInt(suffix.group(2));
                if (suffix.group(3) != null) column = Integer.parseInt(suffix.group(3));
            }
            if (path.isBlank()
                    || path.length() > 8_192
                    || path.startsWith("//")
                    || path.contains("#")
                    || path.contains("?")
                    || path.chars().anyMatch(Character::isISOControl)
                    || (URI_SCHEME.matcher(path).matches() && !WINDOWS_PATH.matcher(path).matches())
                    || line > 1_000_000
                    || (column != null && column > 1_000_000)) {
                return Map.of();
            }
            attributes.put("class", "file-location-link");
            attributes.put("data-file-path", path);
            attributes.put("data-file-line", Integer.toString(line));
            if (column != null) attributes.put("data-file-column", column.toString());
        }
        attributes.put("role", "link");
        attributes.put("tabindex", "0");
        return attributes;
    }

    private static final class MessageNodeRenderer implements NodeRenderer {
        private final HtmlNodeRendererContext context;
        private final HtmlWriter html;
        private final BiFunction<String, String, String> codeBlockRenderer;

        private MessageNodeRenderer(
                HtmlNodeRendererContext context,
                BiFunction<String, String, String> codeBlockRenderer) {
            this.context = context;
            this.html = context.getWriter();
            this.codeBlockRenderer = codeBlockRenderer;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return codeBlockRenderer == null
                    ? Set.of(Link.class, Image.class, TableBlock.class)
                    : Set.of(
                            Link.class,
                            Image.class,
                            TableBlock.class,
                            FencedCodeBlock.class,
                            IndentedCodeBlock.class);
        }

        @Override
        public void render(Node node) {
            if (node instanceof Link link) {
                renderLink(link, link.getDestination(), link.getTitle());
            } else if (node instanceof Image image) {
                // Message attachments own image display; Markdown images use the link bridge.
                renderLink(image, image.getDestination(), image.getTitle());
            } else if (node instanceof TableBlock) {
                html.line();
                html.tag("div", Map.of("class", "markdown-table-wrap"));
                html.tag("table");
                renderChildren(node);
                html.tag("/table");
                html.tag("/div");
                html.line();
            } else if (node instanceof FencedCodeBlock code) {
                renderCode(code.getLiteral(), code.getInfo());
            } else if (node instanceof IndentedCodeBlock code) {
                renderCode(code.getLiteral(), null);
            }
        }

        private void renderLink(Node node, String destination, String title) {
            Map<String, String> attributes = linkAttributes(destination);
            if (attributes.isEmpty()) {
                renderChildren(node);
                return;
            }
            if (title != null && !title.isEmpty()) attributes.put("title", title);
            html.tag("a", attributes);
            renderChildren(node);
            html.tag("/a");
        }

        private void renderCode(String code, String info) {
            html.line();
            html.raw(codeBlockRenderer.apply(code, info));
            html.line();
        }

        private void renderChildren(Node node) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                context.render(child);
            }
        }
    }
}
