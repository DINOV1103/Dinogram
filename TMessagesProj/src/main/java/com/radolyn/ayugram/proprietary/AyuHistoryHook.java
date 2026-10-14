/*
 * DinoGram — independent implementation of the chat history hook
 * that AyuGram for Android keeps out of its public repository.
 *
 * Based on AyuGram for Android (c) Radolyn, 2023 (GPL-2.0).
 * DinoGram changes (c) DINOV, 2026 (GPL-2.0).
 */

package com.radolyn.ayugram.proprietary;

import android.util.SparseArray;
import androidx.core.util.Pair;
import com.google.android.exoplayer2.util.Log;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.messages.AyuMessagesController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Injects saved (deleted) messages into a freshly loaded history batch so they
 * show up in the chat, marked as deleted.
 */
public class AyuHistoryHook {
    private static final String TAG = "DinoGram";

    /**
     * @return first = smallest real message id, second = biggest real message id.
     * Local (not yet sent) messages with non-positive ids are ignored in regular chats.
     */
    public static Pair<Integer, Integer> getMinAndMaxIds(ArrayList<MessageObject> messArr) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        boolean found = false;

        if (messArr != null) {
            for (var obj : messArr) {
                if (obj == null || obj.messageOwner == null) {
                    continue;
                }
                int id = obj.getId();
                if (id == 0) {
                    continue;
                }
                boolean secret = DialogObject.isEncryptedDialog(obj.getDialogId());
                if (!secret && id < 0) {
                    continue;
                }
                min = Math.min(min, id);
                max = Math.max(max, id);
                found = true;
            }
        }

        if (!found) {
            return new Pair<>(0, 0);
        }
        return new Pair<>(min, max);
    }

    public static void doHook(int currentAccount, ArrayList<MessageObject> messArr, SparseArray<MessageObject>[] messagesDict,
                              int startId, int endId, long dialogId, int limit, int topicId, boolean isSecretChat) {
        try {
            doHookInner(currentAccount, messArr, messagesDict, startId, endId, dialogId, limit, topicId, isSecretChat);
        } catch (Exception e) {
            Log.e(TAG, "history hook failed", e);
            FileLog.e(e);
        }
    }

    private static void doHookInner(int currentAccount, ArrayList<MessageObject> messArr, SparseArray<MessageObject>[] messagesDict,
                                    int startId, int endId, long dialogId, int limit, int topicId, boolean isSecretChat) {
        if (messArr == null) {
            return;
        }

        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        // messages from the "General" forum topic are saved with topic 0
        long effectiveTopic = topicId == 1 ? 0 : Math.max(topicId, 0);

        List<DeletedMessageFull> saved = AyuMessagesController.getInstance()
                .getMessages(userId, dialogId, effectiveTopic, startId, endId, limit);
        if (saved == null || saved.isEmpty()) {
            return;
        }

        var existing = new HashSet<Integer>();
        for (var obj : messArr) {
            if (obj != null) {
                existing.add(obj.getId());
            }
        }

        var shown = messagesDict != null && messagesDict.length > 0 ? messagesDict[0] : null;

        for (var full : saved) {
            if (full == null || full.message == null) {
                continue;
            }

            int id = full.message.messageId;
            if (existing.contains(id) || (shown != null && shown.indexOfKey(id) >= 0)) {
                continue;
            }

            var msg = new TLRPC.TL_message();
            AyuMessageUtils.map(full.message, msg, currentAccount);
            AyuMessageUtils.mapMedia(full.message, msg);
            AyuMessageUtils.mapReactions(full, msg);
            msg.dialog_id = dialogId;
            msg.ayuDeleted = true;

            var obj = new MessageObject(currentAccount, msg, true, true);
            insertOrdered(messArr, obj, isSecretChat);
            existing.add(id);
        }
    }

    /**
     * History batches are ordered newest → oldest. Regular chats are ordered by id;
     * secret chats use local ids, so order them by date instead.
     */
    private static void insertOrdered(ArrayList<MessageObject> messArr, MessageObject obj, boolean isSecretChat) {
        int id = obj.getId();
        int date = obj.messageOwner.date;

        for (int i = 0; i < messArr.size(); i++) {
            var other = messArr.get(i);
            if (other == null || other.messageOwner == null) {
                continue;
            }

            if (isSecretChat) {
                if (other.messageOwner.date < date) {
                    messArr.add(i, obj);
                    return;
                }
            } else {
                int otherId = other.getId();
                // local (unsent) messages with non-positive ids always stay on top
                if (otherId > 0 && otherId < id) {
                    messArr.add(i, obj);
                    return;
                }
            }
        }

        messArr.add(obj);
    }
}
