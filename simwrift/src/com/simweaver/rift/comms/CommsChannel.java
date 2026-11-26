package com.simweaver.rift.comms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One communication channel of a datasource (0.12.0): a Talk channel (voice, radio, chat, the
 * lobby) or an RPR / DIS radio frequency. Guarded by its {@link CommsTracker}: only touched while
 * holding the tracker's lock.
 */
final class CommsChannel {

    /** Seconds of activity history kept per channel. */
    static final int SECONDS = 120;
    /** Chat lines kept per channel. */
    static final int CHAT_LINES = 500;

    final String id;
    final String family;            // "hlacomms" (0.14.1; was "talk") or "radio"
    String kind;                    // hlacomms: voice, radio, chat, lobby; radio: radio
    String name;
    String description = "";
    long frequency;                 // Hz, 0 = unknown
    boolean present;                // its Talk channel object exists (always true for radio frequencies)
    boolean audio;                  // audio was heard
    boolean playable;               // ... in an encoding the page can play
    String encoding = "";           // the last audio encoding seen
    String codec = "";              // how the page gets it: "pcm" (the server converts) or "opus" (the browser decodes, 0.12.1)
    String unplayable = "";         // why the last audio could not be played
    final Map<String, Long> signals = new TreeMap<String, Long>();   // non-audio signal kinds -> count
    long frames;
    long bytes;
    long lastActivity;              // ms
    final long[] counts = new long[SECONDS];
    long countsSecond;              // the epoch second of the newest bucket
    /** Talk senders heard in this channel (radio channels list their transmitters live instead). */
    final Map<String, CommsSource> senders = new LinkedHashMap<String, CommsSource>();
    final Deque<ChatLine> chat = new ArrayDeque<ChatLine>();
    long chatCount;

    CommsChannel(String id, String family, String kind, String name) {
        this.id = id;
        this.family = family;
        this.kind = kind;
        this.name = name;
    }

    /** One more message / frame now. */
    void hit(long nowMillis, int size) {
        advance(nowMillis / 1000);
        counts[(int) (countsSecond % SECONDS)]++;
        frames++;
        bytes += size;
        lastActivity = nowMillis;
    }

    /** Moves the newest bucket to {@code second}, clearing the seconds in between. */
    void advance(long second) {
        if (countsSecond == 0) {
            countsSecond = second;
            return;
        }
        if (second <= countsSecond) {
            return;
        }
        long gap = Math.min(SECONDS, second - countsSecond);
        for (long s = 1; s <= gap; s++) {
            counts[(int) ((countsSecond + s) % SECONDS)] = 0;
        }
        countsSecond = second;
    }

    /** @return the count of one epoch second (0 when outside the history) */
    long countAt(long second) {
        if (countsSecond == 0 || second > countsSecond || second <= countsSecond - SECONDS) {
            return 0;
        }
        return counts[(int) (second % SECONDS)];
    }

    /** @return the history ending at {@code lastSecond}, oldest first */
    long[] history(long lastSecond) {
        long[] h = new long[SECONDS];
        for (int i = 0; i < SECONDS; i++) {
            h[i] = countAt(lastSecond - (SECONDS - 1) + i);
        }
        return h;
    }

    void addChat(ChatLine line) {
        chat.addLast(line);
        while (chat.size() > CHAT_LINES) {
            chat.removeFirst();
        }
        chatCount++;
    }

    List<ChatLine> chatLines(int limit) {
        List<ChatLine> all = new ArrayList<ChatLine>(chat);
        return all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
    }

    boolean isChat() {
        return "chat".equals(kind) || "lobby".equals(kind) || chatCount > 0;
    }

    /** A chat or image message. */
    static final class ChatLine {
        final long seq;
        final long time;
        final String sender;
        final String senderId;
        final String text;
        final String kind;          // "text" or "image"

        ChatLine(long seq, long time, String sender, String senderId, String text, String kind) {
            this.seq = seq;
            this.time = time;
            this.sender = sender;
            this.senderId = senderId;
            this.text = text;
            this.kind = kind;
        }
    }
}
