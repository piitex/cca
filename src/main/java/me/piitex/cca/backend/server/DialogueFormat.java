package me.piitex.cca.backend.server;

import me.piitex.cca.model.Character;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A character's reply format, read from an example reply like {{char}}: "What they say." *What they do.*
 * <p>
 * The example gives three things: the prefix before the first piece (the name), the marks that wrap each piece,
 * and whether text outside of marks is allowed. The instruction goes into the system prompt and works everywhere
 * but a model can still drift. The grammar (GBNF) is only sent to the local llama-server, which then can't pick a
 * token that breaks the format.
 */
public final class DialogueFormat {

    // Ready made formats for the creator.
    public record Preset(String name, String example) {
    }

    public static final List<Preset> PRESETS = List.of(
            new Preset("Name, quotes and actions", "{{char}}: \"What they say.\" *What they do.*"),
            new Preset("Quotes and actions", "\"What they say.\" *What they do.*"),
            new Preset("Name, speech and actions", "{{char}}: What they say. *What they do.*"),
            new Preset("Narration and quotes", "What they do. \"What they say.\""));

    // Marks that can wrap a piece of a reply, opener then closer.
    private static final char[][] MARKS = {{'"', '"'}, {'“', '”'}, {'*', '*'}, {'(', ')'}, {'[', ']'}, {'_', '_'}, {'~', '~'}};

    // Everything up to the name placeholder and the punctuation after it, like "{{char}}: " or "[{{char}}] ".
    private static final Pattern PREFIX = Pattern.compile("^(.*?\\{\\{?char(?:acter|a)?}}?[^\\p{L}\\p{N}\"“*(\\[_~]*)");

    private final String example;
    private final String prefix;
    private final List<char[]> marks = new ArrayList<>();
    private boolean plain;

    private DialogueFormat(String example, String prefix) {
        this.example = example;
        this.prefix = prefix;
    }

    // The character's format, or null if it has none or the example has nothing to go on.
    public static DialogueFormat of(Character character) {
        String template = character.getReplyFormat().strip();
        if (template.isEmpty()) return null;

        Matcher matcher = PREFIX.matcher(template);
        String rawPrefix = matcher.find() ? matcher.group(1) : "";
        DialogueFormat format = new DialogueFormat(PromptBuilder.format(template, character), PromptBuilder.format(rawPrefix, character));
        format.readPieces(PromptBuilder.format(template.substring(rawPrefix.length()), character));
        return format.marks.isEmpty() && !format.plain ? null : format;
    }

    // Goes through the example after the prefix, noting each mark used and any text outside of them.
    private void readPieces(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (java.lang.Character.isWhitespace(c)) {
                i++;
                continue;
            }
            char[] mark = markOpenedBy(c);
            int close = mark == null ? -1 : text.indexOf(mark[1], i + 1);
            if (close > i) {
                addMark(mark);
                i = close + 1;
            } else {
                plain = true;
                i++;
                while (i < text.length() && markOpenedBy(text.charAt(i)) == null) i++;
            }
        }
        // Models mix straight and curly quotes, so either is fine.
        if (marks.stream().anyMatch(m -> m[0] == '"' || m[0] == '“')) {
            addMark(MARKS[0]);
            addMark(MARKS[1]);
        }
    }

    private void addMark(char[] mark) {
        if (!marks.contains(mark)) marks.add(mark);
    }

    private static char[] markOpenedBy(char c) {
        for (char[] mark : MARKS) {
            if (mark[0] == c) return mark;
        }
        return null;
    }

    public String instruction() {
        StringBuilder text = new StringBuilder("Write every reply in this format, with {{char}}'s own words in place of the example:\n")
                .append(example);
        if (!prefix.isBlank()) text.append("\nAlways start with \"").append(prefix.strip()).append("\".");
        if (!plain) text.append("\nDon't write anything outside of the marks the format uses.");
        return text.toString();
    }

    // Puts the prefix in front of a reply that left it off. Text that could still become the prefix while it streams is left alone.
    public String withPrefix(String reply) {
        String tag = prefix.strip();
        if (tag.isEmpty() || reply.isBlank() || reply.startsWith(tag) || tag.startsWith(reply)) return reply;
        return prefix + reply;
    }

    // For a new reply.
    public String grammar() {
        String start = prefix.isEmpty() ? "" : literal(prefix) + " ";
        return "root ::= " + start + "segment tail\n" + rules();
    }

    // For continuing a message, which already has the prefix. If it stopped inside a mark, that has to be closed first.
    public String continueGrammar(String continuing) {
        char[] open = openMark(continuing);
        if (open == null) return "root ::= tail\n" + rules();
        return "root ::= [^" + classChar(open[1]) + "\\n]* " + literal(String.valueOf(open[1])) + " tail\n" + rules();
    }

    // Each piece is one of the marks, or plain text if the example had some. A piece can't span lines so an
    // unclosed one can't swallow the rest of the reply. Pieces are split by a space or up to two new lines.
    private String rules() {
        StringBuilder rules = new StringBuilder("tail ::= (sep segment)*\n");
        List<String> kinds = new ArrayList<>();
        StringBuilder openers = new StringBuilder();
        for (int i = 0; i < marks.size(); i++) {
            char[] mark = marks.get(i);
            kinds.add("mark" + i);
            openers.append(classChar(mark[0]));
            rules.append("mark").append(i).append(" ::= ").append(literal(String.valueOf(mark[0])))
                    .append(" [^").append(classChar(mark[1])).append("\\n]+ ").append(literal(String.valueOf(mark[1]))).append('\n');
        }
        if (plain) {
            // Plain text can't slip into marks the format doesn't use either, parentheses and brackets are normal in prose.
            for (char c : new char[]{'*', '_', '~'}) {
                if (openers.indexOf(String.valueOf(c)) < 0) openers.append(c);
            }
            kinds.add("plain");
            rules.append("plain ::= [^ ").append(openers).append("\\n] [^").append(openers).append("\\n]*\n");
        }
        rules.append("segment ::= ").append(String.join(" | ", kinds)).append('\n');
        rules.append("sep ::= \" \"? (\"\\n\" \"\\n\"?)?\n");
        return rules.toString();
    }

    // The mark the text is still inside of at its end, or null if everything was closed.
    private char[] openMark(String text) {
        char[] open = null;
        for (int i = prefix.length() <= text.length() && text.startsWith(prefix) ? prefix.length() : 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (open == null) {
                for (char[] mark : marks) {
                    if (mark[0] == c) open = mark;
                }
            } else if (c == open[1]) {
                open = null;
            }
        }
        return open;
    }

    // A GBNF string, quotes and backslashes are escaped like json.
    private static String literal(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // One character inside a [...] class.
    private static String classChar(char c) {
        return switch (c) {
            case '\\', ']', '[', '^', '-', '"' -> "\\" + c;
            default -> String.valueOf(c);
        };
    }
}
