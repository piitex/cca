package me.piitex.cca.card;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;

/**
 * Reads SillyTavern and BackyardAI cards. A card is a png with base64 json hidden in its metadata.
 * Character and user cards only differ in which tag holds the json and what the keys are called.
 * <p>
 * The card image is also the icon, the caller passes the same file to setIconFile.
 * Everything here is lenient since these formats aren't consistent, a missing key just reads as empty.
 */
public final class CardImporter {
    private CardImporter() {
    }

    // Fields a card format doesn't have are empty, never null.
    public record CardData(String id, String displayName, String persona, String firstMessage,
                           String scenario, Map<String, String> lorebook) {
    }

    // SillyTavern uses the "chara" text chunk, BackyardAI puts it in the EXIF UserComment (checked first).
    // Model settings and suggested user personas in the card are ignored for now.
    public static CardData readCharacterCard(File file) throws ImageProcessingException, IOException {
        JSONObject root = findEmbeddedJson(file, true, "chara");
        if (root == null) {
            throw new IOException("No character card data found in " + file.getName());
        }
        JSONObject card = section(root, "character", "chara");
        JSONObject data = card.has("data") ? card.optJSONObject("data") : null;

        String id = firstNonEmpty(card, "aiName", "display-name", "name");
        if (id.isEmpty() && data != null) id = firstNonEmpty(data, "aiDisplayName", "name");

        String displayName = firstNonEmpty(card, "aiDisplayName", "name");
        if (displayName.isEmpty() && data != null) displayName = firstNonEmpty(data, "aiName", "name");

        String persona = concat(card, "aiPersona", "personality", "description");
        if (persona.isEmpty() && data != null) persona = concat(data, "aiPersona", "description", "personality");
        persona = persona.replace("!@!", "\n");

        String firstMessage = firstNonEmpty(card, "firstMessage", "first_mes");
        if (firstMessage.isEmpty() && data != null) firstMessage = firstNonEmpty(data, "firstMessage", "first_mes");

        String scenario = firstNonEmpty(card, "scenario");
        if (scenario.isEmpty() && data != null) scenario = firstNonEmpty(data, "scenario");

        return new CardData(id, displayName, persona, firstMessage, scenario, loreItems(card, data));
    }

    // SillyTavern's "user" text chunk. BackyardAI doesn't export user cards.
    public static CardData readUserCard(File file) throws ImageProcessingException, IOException {
        JSONObject root = findEmbeddedJson(file, false, "user");
        if (root == null) {
            throw new IOException("No user card data found in " + file.getName());
        }
        JSONObject card = section(root, "user");

        String displayName = card.optString("userDisplay", "");
        String persona = card.optString("userPersona", "");
        return new CardData("", displayName, persona, "", "", loreItems(card, null));
    }

    private static JSONObject findEmbeddedJson(File file, boolean checkExif, String keyword) throws ImageProcessingException, IOException {
        Metadata metadata = ImageMetadataReader.readMetadata(file);

        if (checkExif) {
            for (Directory directory : metadata.getDirectoriesOfType(ExifSubIFDDirectory.class)) {
                if (directory.containsTag(37510)) { // User Comment
                    JSONObject decoded = decode(directory.getDescription(37510), keyword);
                    if (decoded != null) return decoded;
                }
            }
        }

        for (Directory directory : metadata.getDirectories()) {
            for (Tag tag : directory.getTags()) {
                String description = tag.getDescription();
                if (description == null || !description.startsWith(keyword)) continue;
                JSONObject decoded = decode(description.replaceFirst("^" + keyword + ":?\\s*", ""), keyword);
                if (decoded != null) return decoded;
            }
        }

        return null;
    }

    private static JSONObject decode(String base64, String keyword) throws IOException {
        if (base64 == null) return null;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException notBase64) {
            // Some cards have more than one tag starting with the keyword, only one is the actual data.
            return null;
        }
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Card's " + keyword + " metadata is not valid JSON", e);
        }
    }

    private static JSONObject section(JSONObject root, String... keys) {
        for (String key : keys) {
            if (root.has(key)) return root.optJSONObject(key, root);
        }
        return root;
    }

    private static String firstNonEmpty(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "");
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String concat(JSONObject json, String... keys) {
        StringBuilder text = new StringBuilder();
        for (String key : keys) {
            String value = json.optString(key, "");
            if (value.isBlank()) continue;
            if (!text.isEmpty()) text.append("\n\n");
            text.append(value);
        }
        return text.toString();
    }

    // SillyTavern's flat loreItems [{key, value}], or the character book format
    // data.character_book.entries [{keys: [...], content}] where the keys are joined with commas.
    private static Map<String, String> loreItems(JSONObject card, JSONObject data) {
        Map<String, String> lore = new LinkedHashMap<>();

        if (card.has("loreItems")) {
            JSONArray items = card.optJSONArray("loreItems");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject entry = items.optJSONObject(i);
                    if (entry == null) continue;
                    String key = entry.optString("key", "");
                    if (!key.isEmpty()) lore.put(key, entry.optString("value", ""));
                }
            }
            return lore;
        }

        JSONObject book = data == null ? null : data.optJSONObject("character_book");
        JSONArray entries = book == null ? null : book.optJSONArray("entries");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                JSONArray keys = entry.optJSONArray("keys");
                if (keys == null || keys.isEmpty()) continue;
                StringBuilder key = new StringBuilder();
                for (int k = 0; k < keys.length(); k++) {
                    if (k > 0) key.append(", ");
                    key.append(keys.optString(k, ""));
                }
                lore.put(key.toString(), entry.optString("content", ""));
            }
        }

        return lore;
    }
}
