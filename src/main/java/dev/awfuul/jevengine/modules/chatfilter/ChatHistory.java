package dev.awfuul.jevengine.modules.chatfilter;

import dev.awfuul.jevengine.text.Normalized;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player has said recently.
 *
 * <p>Two things need this. A slur split across several messages is invisible in
 * any one of them, so the fragments have to be put back together before anyone
 * can judge them. And spam is a property of a sequence, not of a line.
 *
 * <p>Both of those are measured here rather than asked. Counting messages,
 * comparing them for sameness and working out a ratio of capitals are all exact
 * operations that code does perfectly and a model does badly. Jev gets the
 * numbers as facts and answers the part that actually needs judgment: whether
 * this is someone flooding chat or someone excited, and whether the letters that
 * fall out of the fragments mean anything.
 */
public final class ChatHistory {

    public record Settings(boolean splitEnabled,
                           long splitWindowMillis,
                           int maxFragments,
                           int maxFragmentLength,
                           int minJoinedLength,
                           long rateWindowMillis,
                           int memory) {

        public static Settings defaults() {
            return new Settings(true, 20_000L, 6, 8, 5, 15_000L, 12);
        }
    }

    /** Facts about the player's recent pace, for the spam question to read. */
    public record Rate(int messagesInWindow,
                       int identicalRepeats,
                       double capitalLetterRatio,
                       long millisSincePrevious) {
    }

    /** Consecutive short messages, run together. */
    public record Split(List<String> fragments, String joined) {
    }

    private record Entry(String letters, long at) {
    }

    private final Map<UUID, Deque<Entry>> byPlayer = new ConcurrentHashMap<>();

    /**
     * Records a message before it is judged, not after it is delivered. A
     * message that gets blocked still happened, and both flooding and a split
     * bypass are patterns a player can build out of messages nobody ever saw.
     */
    public void record(UUID player, Normalized normalized, long now, int memory) {
        Deque<Entry> entries = byPlayer.computeIfAbsent(player, id -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addLast(new Entry(normalized.lettersOnly(), now));
            while (entries.size() > Math.max(2, memory)) {
                entries.removeFirst();
            }
        }
    }

    public Rate rate(UUID player, Normalized normalized, long now, long windowMillis) {
        Deque<Entry> entries = byPlayer.get(player);
        int inWindow = 0;
        int identical = 0;
        long sincePrevious = -1L;

        if (entries != null) {
            synchronized (entries) {
                boolean skippedCurrent = false;
                List<Entry> snapshot = new ArrayList<>(entries);
                for (int i = snapshot.size() - 1; i >= 0; i--) {
                    Entry entry = snapshot.get(i);
                    if (now - entry.at() > windowMillis) {
                        break;
                    }
                    inWindow++;
                    if (!skippedCurrent) {
                        // The newest entry is the message being judged.
                        skippedCurrent = true;
                        continue;
                    }
                    if (sincePrevious < 0L) {
                        sincePrevious = now - entry.at();
                    }
                    if (!entry.letters().isEmpty()
                            && entry.letters().equals(normalized.lettersOnly())) {
                        identical++;
                    }
                }
            }
        }
        return new Rate(inWindow, identical, capitalRatio(normalized.raw()), sincePrevious);
    }

    /**
     * The trailing run of short messages, oldest first, joined with nothing
     * between them.
     *
     * <p>The run stops at the first message long enough to be a sentence, which
     * is what keeps this from gluing ordinary conversation into nonsense. It
     * still produces candidates from harmless chatter, and that is fine: a
     * candidate is a question, not an accusation, and "ok" plus "lol" reads as
     * nothing to anyone.
     */
    public Split split(UUID player, long now, Settings settings) {
        if (!settings.splitEnabled()) {
            return null;
        }
        Deque<Entry> entries = byPlayer.get(player);
        if (entries == null) {
            return null;
        }

        List<String> fragments = new ArrayList<>();
        synchronized (entries) {
            List<Entry> snapshot = new ArrayList<>(entries);
            for (int i = snapshot.size() - 1; i >= 0; i--) {
                Entry entry = snapshot.get(i);
                if (now - entry.at() > settings.splitWindowMillis()) {
                    break;
                }
                if (entry.letters().isEmpty()
                        || entry.letters().length() > settings.maxFragmentLength()) {
                    break;
                }
                fragments.add(entry.letters());
                if (fragments.size() >= settings.maxFragments()) {
                    break;
                }
            }
        }
        if (fragments.size() < 2) {
            return null;
        }

        java.util.Collections.reverse(fragments);
        String joined = String.join("", fragments);
        if (joined.length() < settings.minJoinedLength()) {
            return null;
        }
        return new Split(List.copyOf(fragments), joined);
    }

    public void forget(UUID player) {
        byPlayer.remove(player);
    }

    public void forgetAll() {
        byPlayer.clear();
    }

    public int tracked() {
        return byPlayer.size();
    }

    private static double capitalRatio(String raw) {
        int letters = 0;
        int capitals = 0;
        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            if (Character.isLetter(current)) {
                letters++;
                if (Character.isUpperCase(current)) {
                    capitals++;
                }
            }
        }
        return letters == 0 ? 0.0D : Math.round((double) capitals / letters * 100.0D) / 100.0D;
    }
}
