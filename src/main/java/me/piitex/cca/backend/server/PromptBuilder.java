package me.piitex.cca.backend.server;

import me.piitex.cca.model.Character;
import me.piitex.cca.model.ChatMessage;
import me.piitex.cca.model.Role;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // For a server that doesn't continue a trailing assistant message on its own (llama.cpp does).
    private static final String CONTINUE_NOTE = """
            Continue your last message exactly where it left off. Write only the continuation, \
            don't repeat anything already written and don't start a new reply.""";

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
        String userPersona = character.getUserPersona().isBlank() ? "" : "About {{user}}:\n" + character.getUserPersona().trim();
        String base = joinSections(List.of(
                INSTRUCTIONS,
                character.getPersona(),
                userPersona,
                character.getChatScenario()));

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
        String lore = triggeredLore(character, kept);
        int loreCost = estimateTokens(lore);
        while (used + loreCost > budget && kept.size() > 1) {
            used -= estimateTokens(kept.removeFirst().getContent());
        }

        String system = format(joinSections(List.of(base, lore)), character);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", system));
        for (ChatMessage message : kept) {
            String role = message.getSender() == Role.USER ? "user" : "assistant";
            messages.put(new JSONObject().put("role", role).put("content", format(message.getContent(), character)));
        }
        return messages;
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

    // Replaces {{char}} and {{user}}, and the single brace versions some cards use.
    public static String format(String text, Character character) {
        String name = character.getDisplayName().isBlank() ? "Character" : character.getDisplayName();
        String userName = userName(character);
        return text.replace("{{char}}", name).replace("{char}", name)
                .replace("{{character}}", name).replace("{character}", name)
                .replace("{{user}}", userName).replace("{user}", userName);
    }

    // Entries whose keyword shows up in any kept message, from the character's lorebook and the user's.
    // A keyword can be several triggers separated by commas.
    private static String triggeredLore(Character character, List<ChatMessage> kept) {
        Map<String, String> lorebook = new LinkedHashMap<>(character.getLorebook());
        for (Map.Entry<String, String> entry : character.getUserLorebook().entrySet()) {
            // A keyword both have keeps the character's entry.
            lorebook.putIfAbsent(entry.getKey(), entry.getValue());
        }
        if (lorebook.isEmpty()) return "";

        StringBuilder conversation = new StringBuilder();
        for (ChatMessage message : kept) {
            conversation.append(message.getContent().toLowerCase()).append('\n');
        }

        Map<String, String> triggered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : lorebook.entrySet()) {
            if (entry.getValue().isBlank()) continue;
            for (String trigger : entry.getKey().split(",")) {
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
