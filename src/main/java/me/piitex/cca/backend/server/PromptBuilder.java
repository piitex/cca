package me.piitex.cca.backend.server;

import me.piitex.cca.model.Character;
import me.piitex.cca.model.ChatMessage;
import me.piitex.cca.model.Role;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the OpenAI style messages array: one system message (instructions, persona, scenario and any
 * lorebook entries that were triggered) followed by as much of the recent history as fits the context.
 * <p>
 * Tokens are estimated from the text length instead of calling /tokenize for every message. The estimate
 * is on the high side so history gets trimmed a little early rather than overflowing.
 */
public final class PromptBuilder {

    // What {{user}} is when the character has no user name.
    // ChatCompletion also stops on the user's name so the model can't write the user's side.
    public static final String USER_NAME = "User";

    private static final String INSTRUCTIONS = """
            You are {{char}} in an ongoing roleplay with {{user}}. Write {{char}}'s next reply only, \
            staying in character and consistent with the persona and scenario below. Never write \
            {{user}}'s dialogue or actions.""";

    // For a chat with more than one character. The model plays one of them, everyone else (the user included)
    // shows up in the history as a name followed by what they said.
    private static final String GROUP_INSTRUCTIONS = """
            You are {{char}} in an ongoing group roleplay with {{user}} and %s. Write {{char}}'s next reply only, \
            staying in character and consistent with the persona and scenario below. Messages from everyone else \
            start with their name, your own earlier replies don't. Never write dialogue or actions for {{user}} \
            or anyone but {{char}}.""";

    // Asked of a character that spoke last and is asked to go again, so the model starts a new reply
    // and doesn't carry on the old one.
    private static final String TURN_NOTE = "It's {{char}}'s turn. Write {{char}}'s next reply.";

    // For a server that doesn't continue a trailing assistant message on its own (llama.cpp does).
    private static final String CONTINUE_NOTE = """
            Continue your last message exactly where it left off. Write only the continuation, \
            don't repeat anything already written and don't start a new reply.""";

    // {{name}}, {name} or [name]. A name can't hold brackets itself, so "[{{char}}]" still finds {{char}} inside.
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}\\[\\]\\n]+)}}|\\{([^{}\\[\\]\\n]+)}|\\[([^{}\\[\\]\\n]+)]");

    // Room for the chat template's own tokens and the estimate being off.
    private static final int TEMPLATE_OVERHEAD_TOKENS = 128;

    private PromptBuilder() {
    }

    /**
     * @param history     the chat's messages, oldest first
     * @param contextSize the server's context size in tokens
     * @param replyTokens tokens to leave free for the reply
     */
    public static JSONArray build(Character character, List<ChatMessage> history, int contextSize, int replyTokens) {
        return build(character, character, List.of(), history, contextSize, replyTokens);
    }

    /**
     * For a group chat. With no others this is the same as the single character one above.
     *
     * @param speaker the character that replies
     * @param owner   whose chat it is. The user, the scenario and the user's lorebook come from them
     * @param others  the rest of the characters in the chat, not the speaker and not the owner unless the speaker isn't
     */
    public static JSONArray build(Character speaker, Character owner, List<Character> others, List<ChatMessage> history, int contextSize, int replyTokens) {
        boolean group = !others.isEmpty();
        String userPersona = owner.getUserPersona().isBlank() ? "" : "About {{user}}:\n" + owner.getUserPersona().trim();
        DialogueFormat replyFormat = DialogueFormat.of(speaker);
        String instructions = group ? GROUP_INSTRUCTIONS.formatted(nameList(others)) : INSTRUCTIONS;
        if (replyFormat != null) instructions += "\n\n" + replyFormat.instruction();
        String base = joinSections(List.of(
                instructions,
                speaker.getPersona(),
                group ? castSection(others, owner) : "",
                userPersona,
                owner.getChatScenario()));

        int budget = contextSize - replyTokens - TEMPLATE_OVERHEAD_TOKENS - estimateTokens(base);

        // Go newest first, the latest turns are the ones worth keeping.
        List<ChatMessage> kept = new ArrayList<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            int cost = estimateTokens(history.get(i).getContent());
            if (used + cost > budget && !kept.isEmpty()) break;
            kept.addFirst(history.get(i));
            used += cost;
        }

        // Lore is only triggered by what the model will actually see. If it goes over budget drop the oldest
        // turns to make room, but always keep the latest one.
        String lore = triggeredLore(speaker, owner, others, kept);
        int loreCost = estimateTokens(lore);
        while (used + loreCost > budget && kept.size() > 1) {
            used -= estimateTokens(kept.removeFirst().getContent());
        }

        String system = format(joinSections(List.of(base, lore)), speaker, owner);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", system));
        for (ChatMessage message : kept) {
            if (!group) {
                String role = message.getSender() == Role.USER ? "user" : "assistant";
                String content = format(message.getContent(), speaker, owner);
                // Models copy the chat more than the instructions, so every earlier reply shows the prefix too.
                if (replyFormat != null && message.getSender() != Role.USER) content = replyFormat.withPrefix(content);
                messages.put(new JSONObject().put("role", role).put("content", content));
                continue;
            }

            // The speaker's own replies are the assistant's. The user and the other characters are "user" turns with their name.
            String role;
            String content;
            if (message.getSender() == Role.USER) {
                role = "user";
                content = label(userName(owner), format(message.getContent(), speaker, owner));
            } else {
                Character author = message.getCharacter() == null ? owner : message.getCharacter();
                if (author.getId().equals(speaker.getId())) {
                    role = "assistant";
                    content = format(message.getContent(), speaker, owner);
                    if (replyFormat != null) content = replyFormat.withPrefix(content);
                } else {
                    role = "user";
                    content = label(nameOf(author), format(message.getContent(), author, owner));
                }
            }
            addMerged(messages, role, content);
        }
        return messages;
    }

    // Many chat templates refuse two turns in a row from the same side, and a group chat has them all the time.
    private static void addMerged(JSONArray messages, String role, String content) {
        JSONObject last = messages.getJSONObject(messages.length() - 1);
        if (last.getString("role").equals(role)) {
            last.put("content", last.getString("content") + "\n\n" + content);
        } else {
            messages.put(new JSONObject().put("role", role).put("content", content));
        }
    }

    // "Name: text", unless the text already starts that way.
    private static String label(String name, String text) {
        return text.startsWith(name + ":") ? text : name + ": " + text;
    }

    // "Bob", "Bob and Carol" or "Bob, Carol and Dan"
    private static String nameList(List<Character> characters) {
        List<String> names = characters.stream().map(PromptBuilder::nameOf).toList();
        if (names.size() == 1) return names.getFirst();
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    // Who else is there, each with their own persona. Their {{char}} is themselves, not the one replying.
    private static String castSection(List<Character> others, Character owner) {
        List<String> entries = new ArrayList<>();
        for (Character other : others) {
            String persona = format(other.getPersona(), other, owner).trim();
            entries.add(persona.isEmpty() ? nameOf(other) : nameOf(other) + ":\n" + persona);
        }
        return "Also in the chat (never write for them):\n\n" + String.join("\n\n", entries);
    }

    // The note that makes the model start a new reply when the character it plays also wrote the last message.
    public static JSONArray withTurnNote(JSONArray messages, Character speaker, Character owner) {
        JSONObject last = messages.getJSONObject(messages.length() - 1);
        if (last.getString("role").equals("assistant")) {
            messages.put(new JSONObject().put("role", "user").put("content", format(TURN_NOTE, speaker, owner)));
        }
        return messages;
    }

    // Takes off a name label the model put at the start of its own reply ("Alice: ..."), the chat already says who spoke.
    public static String withoutLabel(String reply, String name) {
        String stripped = reply.stripLeading();
        if (stripped.regionMatches(true, 0, name, 0, name.length())) {
            String rest = stripped.substring(name.length()).stripLeading();
            if (rest.startsWith(":")) return rest.substring(1).stripLeading();
        }
        return reply;
    }

    // The names a group reply must not carry on as, so one model can't write the others' side.
    public static List<String> otherNames(Character speaker, List<Character> participants) {
        List<String> names = new ArrayList<>();
        for (Character other : participants) {
            if (!other.getId().equals(speaker.getId())) names.add(nameOf(other));
        }
        return names;
    }

    private static String nameOf(Character character) {
        return character.getDisplayName().isBlank() ? "Character" : character.getDisplayName();
    }

    // Asks for the rest of the last message. Only needed when the server won't prefill it.
    public static JSONArray withContinueNote(JSONArray messages) {
        messages.put(new JSONObject().put("role", "user").put("content", CONTINUE_NOTE));
        return messages;
    }

    // What {{user}} turns into, and what ChatCompletion stops on.
    public static String userName(Character character) {
        return character.hasUser() ? character.getUserName().trim() : USER_NAME;
    }

    // Replaces placeholders written as {{name}}, {name} or [name], ignoring case. Anything that isn't one is left alone.
    public static String format(String text, Character character) {
        return format(text, character, character);
    }

    // For a group chat: {{char}} is the character, {{user}} and the user's placeholders are the chat owner's.
    public static String format(String text, Character character, Character owner) {
        if (text == null || text.isEmpty()) return text;
        Map<String, String> values = placeholders(character, owner);
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder formatted = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            String value = values.get(placeholderKey(name));
            matcher.appendReplacement(formatted, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(formatted);
        return formatted.toString();
    }

    // The name a placeholder is looked up by. Takes off any brackets typed around it, so "[Age]" and "age" are the same.
    public static String placeholderKey(String name) {
        return name.replaceAll("^[\\[{\\s]+|[\\]}\\s]+$", "").toLowerCase(Locale.ROOT);
    }

    // The user's own, then the character's, then the names. A later one wins when two share a name.
    private static Map<String, String> placeholders(Character character, Character owner) {
        Map<String, String> values = new HashMap<>();
        owner.getUserPlaceholders().forEach((name, value) -> values.put(placeholderKey(name), value));
        character.getPlaceholders().forEach((name, value) -> values.put(placeholderKey(name), value));
        String name = character.getDisplayName().isBlank() ? "Character" : character.getDisplayName();
        String userName = userName(owner);
        for (String alias : List.of("char", "chara", "character")) values.put(alias, name);
        for (String alias : List.of("user", "usr")) values.put(alias, userName);
        return values;
    }

    // Entries whose keyword shows up in any kept message, from the character's lorebook and the user's.
    // A keyword can be several triggers separated by commas.
    // In a group chat the other characters' lorebooks count too, and an entry of theirs reads as them.
    private static String triggeredLore(Character character, Character owner, List<Character> others, List<ChatMessage> kept) {
        Map<String, String> lorebook = new LinkedHashMap<>(character.getLorebook());
        for (Character other : others) {
            for (Map.Entry<String, String> entry : other.getLorebook().entrySet()) {
                lorebook.putIfAbsent(entry.getKey(), format(entry.getValue(), other, owner));
            }
        }
        for (Map.Entry<String, String> entry : owner.getUserLorebook().entrySet()) {
            // A keyword both have keeps the character's entry.
            lorebook.putIfAbsent(entry.getKey(), entry.getValue());
        }
        if (lorebook.isEmpty()) return "";

        StringBuilder conversation = new StringBuilder();
        for (ChatMessage message : kept) {
            conversation.append(format(message.getContent(), character, owner).toLowerCase()).append('\n');
        }

        Map<String, String> triggered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : lorebook.entrySet()) {
            if (entry.getValue().isBlank()) continue;
            for (String trigger : format(entry.getKey(), character, owner).split(",")) {
                String key = trigger.trim().toLowerCase();
                if (!key.isEmpty() && conversation.indexOf(key) >= 0) {
                    triggered.put(entry.getKey(), entry.getValue().trim());
                    break;
                }
            }
        }
        return joinSections(List.copyOf(triggered.values()));
    }

    private static String joinSections(List<String> sections) {
        StringBuilder joined = new StringBuilder();
        for (String section : sections) {
            if (section == null || section.isBlank()) continue;
            if (!joined.isEmpty()) joined.append("\n\n");
            joined.append(section.trim());
        }
        return joined.toString();
    }

    // About one token per 3 characters. English is usually closer to 4, so this overestimates on purpose.
    // Cutting one turn too many is harmless, going over the context gets the whole request rejected.
    private static int estimateTokens(String text) {
        return text == null ? 0 : (text.length() + 2) / 3;
    }
}
