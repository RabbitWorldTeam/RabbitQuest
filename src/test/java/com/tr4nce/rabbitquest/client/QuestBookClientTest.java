package com.tr4nce.rabbitquest.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuestBookClientTest {
    @Test
    void computesWeeklyAndEventClaimableBadges() {
        CompoundTag weekly = new CompoundTag();
        ListTag claims = new ListTag();
        CompoundTag prevClaim = new CompoundTag();
        prevClaim.putBoolean("current", false);
        claims.add(prevClaim);
        CompoundTag currentClaim = new CompoundTag();
        currentClaim.putBoolean("current", true);
        claims.add(currentClaim);
        weekly.put("claims", claims);

        QuestBookClient.updateWeeklyBadges(weekly);
        assertEquals(2, QuestBookClient.badge(QuestBookClient.Tab.WEEKLY));

        CompoundTag events = new CompoundTag();
        ListTag eventList = new ListTag();
        CompoundTag ev = new CompoundTag();
        ev.putBoolean("completionClaimable", true);
        ListTag steps = new ListTag();
        CompoundTag s1 = new CompoundTag();
        s1.putBoolean("claimable", true);
        steps.add(s1);
        ev.put("steps", steps);
        eventList.add(ev);
        events.put("events", eventList);

        QuestBookClient.updateEventBadges(events);
        assertEquals(2, QuestBookClient.badge(QuestBookClient.Tab.EVENTS));
        assertEquals(0, QuestBookClient.badge(QuestBookClient.Tab.SHOP));
    }
}
