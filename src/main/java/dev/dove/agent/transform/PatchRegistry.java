package dev.dove.agent.transform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks up the patches registered for a class by its internal name.
 */
public final class PatchRegistry {

    private final Map<String, List<ClassPatch>> byTarget = new HashMap<>();
    private int size;

    public void register(ClassPatch patch) {
        for (String target : patch.targets()) {
            byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(patch);
        }
        size++;
    }

    public List<ClassPatch> patchesFor(String internalName) {
        return byTarget.getOrDefault(internalName, List.of());
    }

    public int size() {
        return size;
    }
}
