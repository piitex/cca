package me.piitex.cca.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a hub is arranged, for characters or for users: loose items and one level of folders.
 * <p>
 * Folders only exist in the config (a name and a list of ids), nothing is moved on disk.
 * Until the user drags things around everything is sorted by name, with numbers compared by value so
 * "Mira 2" comes before "Mira 10". Once they arrange things that order is saved and anything new goes
 * after it. Every change is saved right away.
 */
public final class HubLayout<T extends HubItem> {

    // Where the layout is saved. Keys are dotted config paths.
    public interface Storage {
        List<String> list(String key);

        void putList(String key, List<String> values);

        String string(String key);

        void putString(String key, String value);

        void remove(String key);

        // Called once after a batch of changes so the file is only written once.
        void flush();
    }

    // The config keys a hub saves under, so characters and users can share layout.conf.
    // The character keys are the ones older installs already have.
    public record Keys(String order, String folders, String folderPrefix) {
        public static final Keys CHARACTERS = new Keys("characterOrder", "folders", "folder.");
        public static final Keys USERS = new Keys("userOrder", "userFolders", "userFolder.");
    }

    public static final class Folder {
        private final String id;
        private String name;
        private final List<String> members = new ArrayList<>();

        private Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    // One card on the hub, either an item or a folder.
    public record Entry<T extends HubItem>(T item, Folder folder) {
        public boolean isFolder() {
            return folder != null;
        }
    }

    private static final String FOLDER_PREFIX = "folder:";

    private final Storage storage;
    private final Keys keys;
    private final Map<String, T> items = new LinkedHashMap<>();
    private final List<Folder> folders = new ArrayList<>();
    private final List<String> topOrder = new ArrayList<>();
    private long folderCounter = System.currentTimeMillis();

    public HubLayout(List<T> loaded, Storage storage, Keys keys) {
        this.storage = storage;
        this.keys = keys;
        for (T item : loaded) {
            items.put(item.getId(), item);
        }

        topOrder.addAll(storage.list(keys.order()));
        for (String id : storage.list(keys.folders())) {
            String name = storage.string(keys.folderPrefix() + id + ".name");
            Folder folder = new Folder(id, name == null || name.isBlank() ? "Folder" : name);
            for (String member : storage.list(keys.folderPrefix() + id + ".members")) {
                // Skip items that were deleted outside the app, or are somehow in two folders.
                if (items.containsKey(member) && folderOf(member) == null) folder.members.add(member);
            }
            folders.add(folder);
        }
    }

    public List<Folder> folders() {
        return List.copyOf(folders);
    }

    public boolean isEmpty() {
        return items.isEmpty() && folders.isEmpty();
    }

    public Folder folderOf(T item) {
        return folderOf(item.getId());
    }

    private Folder folderOf(String itemId) {
        return folders.stream().filter(folder -> folder.members.contains(itemId)).findAny().orElse(null);
    }

    // Loose items and folders, in the saved order then by name.
    public List<Entry<T>> topLevel() {
        List<Entry<T>> entries = new ArrayList<>();
        for (T item : items.values()) {
            if (folderOf(item) == null) entries.add(new Entry<>(item, null));
        }
        for (Folder folder : folders) {
            entries.add(new Entry<>(null, folder));
        }

        Map<String, Integer> saved = indexOf(topOrder);
        entries.sort(Comparator
                .comparingInt((Entry<T> e) -> saved.getOrDefault(key(e), Integer.MAX_VALUE))
                .thenComparing(HubLayout::sortName, HubLayout::compareNatural));
        return entries;
    }

    public List<T> members(Folder folder) {
        List<T> result = new ArrayList<>();
        for (String id : folder.members) {
            T item = items.get(id);
            if (item != null) result.add(item);
        }
        return result;
    }

    // True when there's nothing for "Sort A-Z" to undo.
    public boolean topLevelIsByName() {
        List<Entry<T>> now = topLevel();
        List<Entry<T>> byName = new ArrayList<>(now);
        byName.sort(Comparator.comparing(HubLayout::sortName, HubLayout::compareNatural));
        return now.equals(byName);
    }

    public boolean membersAreByName(Folder folder) {
        List<T> now = members(folder);
        List<T> byName = new ArrayList<>(now);
        byName.sort(Comparator.comparing(HubLayout::sortName, HubLayout::compareNatural));
        return now.equals(byName);
    }

    public Folder createFolder(String name) {
        Folder folder = new Folder(newFolderId(), name.trim());
        folders.add(folder);
        save();
        return folder;
    }

    public void renameFolder(Folder folder, String name) {
        folder.name = name.trim();
        save();
    }

    // The items go back to the top level where the folder was.
    public void deleteFolder(Folder folder) {
        int at = topOrder.indexOf(key(folder));
        folders.remove(folder);
        topOrder.remove(key(folder));
        if (at >= 0) {
            for (int i = 0; i < folder.members.size(); i++) {
                topOrder.add(Math.min(at + i, topOrder.size()), folder.members.get(i));
            }
        }
        save();
    }

    // Moves the item into the folder, or back to the top level if folder is null.
    public void move(T item, Folder folder) {
        Folder from = folderOf(item);
        if (from == folder) return;
        String id = item.getId();

        if (from != null) from.members.remove(id);
        topOrder.remove(id);

        if (folder != null) {
            boolean byName = membersAreByName(folder);
            folder.members.add(id);
            if (byName) sortMembers(folder);
        } else if (from != null) {
            // Lands right next to the folder it came out of.
            int at = topOrder.indexOf(key(from));
            if (at >= 0) topOrder.add(at + 1, id);
        }
        save();
    }

    // Dropping one item onto another makes a folder with both, in the target's spot.
    public Folder group(T target, T dragged, String name) {
        Folder folder = new Folder(newFolderId(), name.trim());
        folder.members.add(target.getId());
        folder.members.add(dragged.getId());
        sortMembers(folder);

        topOrder.remove(dragged.getId());
        int at = topOrder.indexOf(target.getId());
        if (at >= 0) topOrder.set(at, key(folder));

        folders.add(folder);
        save();
        return folder;
    }

    public void setTopOrder(List<Entry<T>> order) {
        topOrder.clear();
        for (Entry<T> entry : order) {
            topOrder.add(key(entry));
        }
        save();
    }

    public void setMembersOrder(Folder folder, List<T> order) {
        folder.members.clear();
        for (T item : order) {
            folder.members.add(item.getId());
        }
        save();
    }

    public void resetTopOrder() {
        topOrder.clear();
        save();
    }

    public void resetMembersOrder(Folder folder) {
        sortMembers(folder);
        save();
    }

    public void add(T item) {
        items.put(item.getId(), item);
    }

    // A duplicate goes right next to the original, in the same folder.
    public void addBeside(T original, T copy) {
        items.put(copy.getId(), copy);
        Folder folder = folderOf(original);
        if (folder != null) {
            boolean byName = membersAreByName(folder);
            int at = folder.members.indexOf(original.getId());
            folder.members.add(at + 1, copy.getId());
            if (byName) sortMembers(folder);
        } else {
            int at = topOrder.indexOf(original.getId());
            if (at >= 0) topOrder.add(at + 1, copy.getId());
        }
        save();
    }

    public void remove(T item) {
        items.remove(item.getId());
        for (Folder folder : folders) {
            folder.members.remove(item.getId());
        }
        topOrder.remove(item.getId());
        save();
    }

    private String newFolderId() {
        return "f" + Long.toString(folderCounter++, 36);
    }

    private void sortMembers(Folder folder) {
        folder.members.sort(Comparator.comparing(id -> {
            T item = items.get(id);
            return item == null ? id : sortName(item);
        }, HubLayout::compareNatural));
    }

    private void save() {
        List<String> previous = storage.list(keys.folders());
        List<String> ids = new ArrayList<>();
        for (Folder folder : folders) {
            ids.add(folder.id);
            storage.putString(keys.folderPrefix() + folder.id + ".name", folder.name);
            storage.putList(keys.folderPrefix() + folder.id + ".members", folder.members);
        }
        // Clean up folders that were deleted.
        for (String id : previous) {
            if (!ids.contains(id)) storage.remove(keys.folderPrefix() + id);
        }
        storage.putList(keys.folders(), ids);
        storage.putList(keys.order(), topOrder);
        storage.flush();
    }

    private static Map<String, Integer> indexOf(List<String> keys) {
        Map<String, Integer> index = new HashMap<>();
        for (String key : keys) {
            index.putIfAbsent(key, index.size());
        }
        return index;
    }

    private static String key(Entry<?> entry) {
        return entry.isFolder() ? key(entry.folder()) : entry.item().getId();
    }

    private static String key(Folder folder) {
        return FOLDER_PREFIX + folder.id;
    }

    // The name shown on the card.
    public static String sortName(Entry<?> entry) {
        return entry.isFolder() ? entry.folder().name : sortName(entry.item());
    }

    public static String sortName(HubItem item) {
        return item.getId().isBlank() ? "Unnamed" : item.getId();
    }

    // Case insensitive, and a run of digits compares by its value.
    static int compareNatural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (java.lang.Character.isDigit(ca) && java.lang.Character.isDigit(cb)) {
                int endA = i, endB = j;
                while (endA < a.length() && java.lang.Character.isDigit(a.charAt(endA))) endA++;
                while (endB < b.length() && java.lang.Character.isDigit(b.charAt(endB))) endB++;
                String runA = a.substring(i, endA).replaceFirst("^0+(?=.)", "");
                String runB = b.substring(j, endB).replaceFirst("^0+(?=.)", "");
                if (runA.length() != runB.length()) return runA.length() - runB.length();
                int cmp = runA.compareTo(runB);
                if (cmp != 0) return cmp;
                i = endA;
                j = endB;
            } else {
                int cmp = java.lang.Character.compare(java.lang.Character.toLowerCase(ca), java.lang.Character.toLowerCase(cb));
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        int rest = (a.length() - i) - (b.length() - j);
        return rest != 0 ? rest : a.compareTo(b);
    }
}
