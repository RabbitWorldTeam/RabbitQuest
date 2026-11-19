package com.tr4nce.rabbitquest.client;

import java.lang.reflect.Method;
import net.neoforged.fml.ModList;

final class JeCharactersSearch {
    private static Method matcher = findMatcher();

    private JeCharactersSearch() {}

    static boolean matchesTitle(String title, String term) {
        if (matcher == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(matcher.invoke(null, title, term));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            matcher = null;
            return false;
        }
    }

    private static Method findMatcher() {
        if (!ModList.get().isLoaded("jecharacters")) {
            return null;
        }
        try {
            return Class.forName("me.towdium.jecharacters.utils.Match", false, JeCharactersSearch.class.getClassLoader())
                    .getMethod("contains", String.class, CharSequence.class);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }
}
