/*
 * DinoGram — independent implementation of the message mapping layer
 * that AyuGram for Android keeps out of its public repository.
 *
 * Based on AyuGram for Android (c) Radolyn, 2023 (GPL-2.0).
 * DinoGram changes (c) DINOV, 2026 (GPL-2.0).
 */

package com.radolyn.ayugram.proprietary;

import android.text.TextUtils;
import androidx.core.util.Pair;
import com.google.android.exoplayer2.util.Log;
import com.radolyn.ayugram.AyuConfig;
import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.AyuUtils;
import com.radolyn.ayugram.database.entities.AyuMessageBase;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.DeletedMessageReaction;
import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.messages.AyuSavePreferences;
import com.radolyn.ayugram.utils.AyuFileLocation;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.AbstractSerializedData;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts Telegram messages into AyuGram database rows (when a message is
 * deleted or edited) and back into Telegram messages (when the saved copy is
 * shown in a chat or in the edit history screen).
 */
public class AyuMessageUtils {
    private static final String TAG = "DinoGram";
    private static final int VECTOR_CONSTRUCTOR = 0x1cb5c415;

    // TL_message optional-field bits we rebuild ourselves.
    private static final int FLAG_FWD_FROM = 4;
    private static final int FLAG_REPLY_TO = 8;
    private static final int FLAG_REPLY_MARKUP = 64;
    private static final int FLAG_ENTITIES = 128;
    private static final int FLAG_FROM_ID = 256;
    private static final int FLAG_MEDIA = 512;
    private static final int FLAG_VIEWS = 1024;
    private static final int FLAG_VIA_BOT = 2048;
    private static final int FLAG_EDIT_DATE = 32768;
    private static final int FLAG_POST_AUTHOR = 65536;
    private static final int FLAG_GROUPED = 131072;
    private static final int FLAG_REACTIONS = 1048576;
    private static final int FLAG_RESTRICTION = 4194304;
    private static final int FLAG_REPLIES = 8388608;
    private static final int FLAG_TTL_PERIOD = 33554432;
    private static final int OPTIONAL_MASK = FLAG_FWD_FROM | FLAG_REPLY_TO | FLAG_REPLY_MARKUP | FLAG_ENTITIES
            | FLAG_FROM_ID | FLAG_MEDIA | FLAG_VIEWS | FLAG_VIA_BOT | FLAG_EDIT_DATE | FLAG_POST_AUTHOR
            | FLAG_GROUPED | FLAG_REACTIONS | FLAG_RESTRICTION | FLAG_REPLIES | FLAG_TTL_PERIOD;

    // =====================================================================
    // Telegram message -> database row
    // =====================================================================

    public static void map(AyuSavePreferences prefs, AyuMessageBase out) {
        var msg = prefs.getMessage();

        out.userId = prefs.getUserId();
        out.dialogId = prefs.getDialogId();
        out.messageId = prefs.getMessageId();
        out.topicId = Math.max(prefs.getTopicId(), 0);
        out.entityCreateDate = prefs.getRequestCatchTime();

        if (msg == null) {
            return;
        }

        out.groupedId = msg.grouped_id;
        out.peerId = MessageObject.getPeerId(msg.peer_id);
        out.fromId = MessageObject.getPeerId(msg.from_id);
        out.date = msg.date;
        out.flags = msg.flags;
        out.editDate = msg.edit_date;
        out.views = msg.views;

        if (msg.fwd_from != null) {
            out.fwdFlags = msg.fwd_from.flags;
            out.fwdFromId = MessageObject.getPeerId(msg.fwd_from.from_id);
            out.fwdName = msg.fwd_from.from_name;
            out.fwdDate = msg.fwd_from.date;
            out.fwdPostAuthor = msg.fwd_from.post_author;
        }

        if (msg.reply_to != null) {
            out.replyFlags = msg.reply_to.flags;
            out.replyMessageId = msg.reply_to.reply_to_msg_id;
            out.replyPeerId = MessageObject.getPeerId(msg.reply_to.reply_to_peer_id);
            out.replyTopId = msg.reply_to.reply_to_top_id;
            out.replyForumTopic = msg.reply_to.forum_topic;
        }

        out.text = msg.message;
        if (AyuConfig.saveFormatting && msg.entities != null && !msg.entities.isEmpty()) {
            out.textEntities = serializeVector(msg.entities);
        }
    }

    /**
     * @param saveMediaFile true  — copy the media file into the DinoGram attachments folder
     *                      (the original cache file is about to disappear);
     *                      false — only reference the current cache path (edit history
     *                      where the media itself did not change).
     */
    public static void mapMedia(AyuSavePreferences prefs, AyuMessageBase out, boolean saveMediaFile) {
        var msg = prefs.getMessage();
        out.documentType = AyuConstants.DOCUMENT_TYPE_NONE;

        if (msg == null) {
            return;
        }

        var media = MessageObject.getMedia(msg);
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty) {
            return;
        }

        try {
            if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null && !(media.photo instanceof TLRPC.TL_photoEmpty)) {
                out.documentType = AyuConstants.DOCUMENT_TYPE_PHOTO;
                out.thumbsSerialized = serializeVector(inlineSizesOnly(media.photo.sizes));
                out.mediaPath = resolveMediaPath(prefs, msg, saveMediaFile);
            } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null && !(media.document instanceof TLRPC.TL_documentEmpty)) {
                var document = media.document;
                out.documentSerialized = serializeObject(document);
                out.mimeType = document.mime_type;

                if (MessageObject.isStickerDocument(document)
                        || MessageObject.isAnimatedStickerDocument(document, true)
                        || MessageObject.isVideoStickerDocument(document)) {
                    out.documentType = AyuConstants.DOCUMENT_TYPE_STICKER;
                    return;
                }

                out.documentType = AyuConstants.DOCUMENT_TYPE_FILE;
                out.documentAttributesSerialized = serializeVector(document.attributes);
                out.thumbsSerialized = serializeVector(inlineSizesOnly(document.thumbs));
                out.mediaPath = resolveMediaPath(prefs, msg, saveMediaFile);
                out.hqThumbPath = resolveThumbPath(prefs, document, saveMediaFile);
            }
            // other media types (geo, contact, poll, web page, dice...) keep only text
        } catch (Exception e) {
            Log.e(TAG, "mapMedia failed", e);
            FileLog.e(e);
        }
    }

    // =====================================================================
    // Database row -> Telegram message
    // =====================================================================

    public static void map(AyuMessageBase in, TLRPC.Message out, int accountId) {
        var controller = MessagesController.getInstance(accountId);

        out.id = in.messageId;
        out.dialog_id = in.dialogId;
        out.date = in.date;
        out.edit_date = in.editDate;
        out.views = in.views;
        out.grouped_id = in.groupedId;

        int flags = in.flags & ~OPTIONAL_MASK;

        out.peer_id = in.peerId != 0 ? controller.getPeer(in.peerId) : controller.getPeer(in.dialogId);
        if (in.fromId != 0) {
            out.from_id = controller.getPeer(in.fromId);
            flags |= FLAG_FROM_ID;
        } else {
            out.from_id = out.peer_id;
        }

        if (in.fwdFlags != 0 || in.fwdFromId != 0 || !TextUtils.isEmpty(in.fwdName)) {
            var fwd = new TLRPC.TL_messageFwdHeader();
            fwd.flags = in.fwdFlags;
            if (in.fwdFromId != 0) {
                fwd.from_id = controller.getPeer(in.fwdFromId);
                fwd.flags |= 1;
            } else {
                fwd.flags &= ~1;
            }
            fwd.from_name = in.fwdName;
            if (TextUtils.isEmpty(in.fwdName)) {
                fwd.flags &= ~32;
            } else {
                fwd.flags |= 32;
            }
            fwd.date = in.fwdDate;
            fwd.post_author = in.fwdPostAuthor;
            if (TextUtils.isEmpty(in.fwdPostAuthor)) {
                fwd.flags &= ~8;
            } else {
                fwd.flags |= 8;
            }
            fwd.flags &= ~(4 | 16 | 64); // channel_post / saved_from / psa_type are not stored
            fwd.imported = (fwd.flags & 128) != 0;
            out.fwd_from = fwd;
            flags |= FLAG_FWD_FROM;
        }

        if (in.replyMessageId != 0 || in.replyTopId != 0) {
            var reply = new TLRPC.TL_messageReplyHeader();
            reply.flags = in.replyFlags;
            reply.reply_to_msg_id = in.replyMessageId;
            if (in.replyPeerId != 0) {
                reply.reply_to_peer_id = controller.getPeer(in.replyPeerId);
                reply.flags |= 1;
            } else {
                reply.flags &= ~1;
            }
            reply.reply_to_top_id = in.replyTopId;
            if (in.replyTopId != 0) {
                reply.flags |= 2;
            } else {
                reply.flags &= ~2;
            }
            reply.forum_topic = in.replyForumTopic;
            reply.reply_to_scheduled = (reply.flags & 4) != 0;
            out.reply_to = reply;
            flags |= FLAG_REPLY_TO;
        }

        out.message = in.text == null ? "" : in.text;
        out.entities = new ArrayList<>();
        if (in.textEntities != null && in.textEntities.length > 0) {
            var entities = deserializeVector(in.textEntities,
                    (stream, constructor) -> TLRPC.MessageEntity.TLdeserialize(stream, constructor, false));
            out.entities.addAll(entities);
        }
        if (!out.entities.isEmpty()) {
            flags |= FLAG_ENTITIES;
        }

        if (out.views != 0) {
            flags |= FLAG_VIEWS;
        }
        if (out.edit_date != 0) {
            flags |= FLAG_EDIT_DATE;
        }
        if (out.grouped_id != 0) {
            flags |= FLAG_GROUPED;
        }

        out.flags = flags;
        applyFlagBooleans(out);
        out.unread = false;
        out.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
    }

    public static void mapMedia(AyuMessageBase in, TLRPC.Message out) {
        TLRPC.MessageMedia media = null;

        try {
            switch (in.documentType) {
                case AyuConstants.DOCUMENT_TYPE_PHOTO:
                    media = buildPhotoMedia(in, out);
                    break;
                case AyuConstants.DOCUMENT_TYPE_STICKER:
                    media = buildStickerMedia(in);
                    break;
                case AyuConstants.DOCUMENT_TYPE_FILE:
                    media = buildFileMedia(in, out);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "mapMedia (restore) failed", e);
            FileLog.e(e);
            media = null;
        }

        if (media == null) {
            out.media = new TLRPC.TL_messageMediaEmpty();
            out.flags &= ~FLAG_MEDIA;
        } else {
            out.media = media;
            out.flags |= FLAG_MEDIA;
        }
    }

    public static void mapReactions(DeletedMessageFull full, TLRPC.Message out) {
        if (full == null || full.reactions == null || full.reactions.isEmpty()) {
            return;
        }

        var reactions = new TLRPC.TL_messageReactions();
        for (DeletedMessageReaction r : full.reactions) {
            TLRPC.Reaction reaction;
            if (r.isCustom) {
                var custom = new TLRPC.TL_reactionCustomEmoji();
                custom.document_id = r.documentId;
                reaction = custom;
            } else if (!TextUtils.isEmpty(r.emoticon)) {
                var emoji = new TLRPC.TL_reactionEmoji();
                emoji.emoticon = r.emoticon;
                reaction = emoji;
            } else {
                continue;
            }

            var count = new TLRPC.TL_reactionCount();
            count.reaction = reaction;
            count.count = r.count;
            count.chosen = r.selfSelected;
            if (r.selfSelected) {
                count.flags |= 1;
                count.chosen_order = 1;
            }
            reactions.results.add(count);
        }

        if (!reactions.results.isEmpty()) {
            out.reactions = reactions;
            out.flags |= FLAG_REACTIONS;
        }
    }

    // =====================================================================
    // media helpers
    // =====================================================================

    private static TLRPC.MessageMedia buildPhotoMedia(AyuMessageBase in, TLRPC.Message out) {
        var photo = new TLRPC.TL_photo();
        photo.id = Utilities.random.nextLong();
        photo.file_reference = new byte[0];
        photo.date = in.date;
        photo.sizes = new ArrayList<>();

        if (in.thumbsSerialized != null) {
            photo.sizes.addAll(deserializeVector(in.thumbsSerialized,
                    (stream, constructor) -> TLRPC.PhotoSize.TLdeserialize(0, 0, 0, stream, constructor, false)));
        }

        var localSize = localPhotoSize(in.mediaPath, "y");
        if (localSize != null) {
            photo.sizes.add(localSize);
            out.attachPath = in.mediaPath;
        }

        if (photo.sizes.isEmpty()) {
            return null;
        }

        var media = new TLRPC.TL_messageMediaPhoto();
        media.photo = photo;
        media.flags |= 1;
        return media;
    }

    private static TLRPC.MessageMedia buildStickerMedia(AyuMessageBase in) {
        var document = deserializeDocument(in.documentSerialized);
        if (document == null) {
            return null;
        }

        var media = new TLRPC.TL_messageMediaDocument();
        media.document = document;
        media.flags |= 1;
        return media;
    }

    private static TLRPC.MessageMedia buildFileMedia(AyuMessageBase in, TLRPC.Message out) {
        TLRPC.Document document = deserializeDocument(in.documentSerialized);

        if (document == null) {
            document = new TLRPC.TL_document();
            document.id = Utilities.random.nextLong();
            document.file_reference = new byte[0];
            document.date = in.date;
            document.mime_type = in.mimeType == null ? "application/octet-stream" : in.mimeType;
            if (in.documentAttributesSerialized != null) {
                document.attributes.addAll(deserializeVector(in.documentAttributesSerialized,
                        (stream, constructor) -> TLRPC.DocumentAttribute.TLdeserialize(stream, constructor, false)));
            }
        }

        // thumbnails: keep only inline (offline) previews + our saved HQ thumbnail
        final long documentId = document.id;
        document.thumbs = new ArrayList<>();
        if (in.thumbsSerialized != null) {
            document.thumbs.addAll(deserializeVector(in.thumbsSerialized,
                    (stream, constructor) -> TLRPC.PhotoSize.TLdeserialize(0, documentId, 0, stream, constructor, false)));
        }
        var hqThumb = localPhotoSize(in.hqThumbPath, "m");
        if (hqThumb != null) {
            document.thumbs.add(hqThumb);
        }
        document.video_thumbs = new ArrayList<>();

        if (!TextUtils.isEmpty(in.mediaPath) && new File(in.mediaPath).exists()) {
            document.localPath = in.mediaPath;
            document.size = new File(in.mediaPath).length();
            out.attachPath = in.mediaPath;
        }

        var media = new TLRPC.TL_messageMediaDocument();
        media.document = document;
        media.flags |= 1;
        return media;
    }

    private static TLRPC.PhotoSize localPhotoSize(String path, String type) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        var file = new File(path);
        if (!file.exists()) {
            return null;
        }

        Pair<Integer, Integer> size = AyuUtils.extractImageSizeFromName(file.getName());
        if (size == null) {
            size = AyuUtils.extractImageSizeFromFile(path);
        }

        var photoSize = new TLRPC.TL_photoSize();
        photoSize.type = type;
        photoSize.location = new AyuFileLocation(path);
        photoSize.w = size != null ? size.first : 1280;
        photoSize.h = size != null ? size.second : 1280;
        photoSize.size = (int) Math.min(Integer.MAX_VALUE, file.length());
        return photoSize;
    }

    private static String resolveMediaPath(AyuSavePreferences prefs, TLRPC.Message msg, boolean saveMediaFile) {
        File source = FileLoader.getInstance(prefs.getAccountId()).getPathToMessage(msg);
        if (source == null) {
            return null;
        }

        if (!saveMediaFile) {
            return source.getAbsolutePath();
        }

        if (!source.exists() || !shouldSaveMedia(prefs)) {
            return null;
        }

        return copyToAttachments(source, AyuUtils.getFilename(msg, source));
    }

    private static String resolveThumbPath(AyuSavePreferences prefs, TLRPC.Document document, boolean saveMediaFile) {
        if (document.thumbs == null || document.thumbs.isEmpty()) {
            return null;
        }

        var thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320);
        if (thumb == null || thumb instanceof TLRPC.TL_photoStrippedSize || thumb instanceof TLRPC.TL_photoPathSize) {
            return null;
        }

        var source = FileLoader.getInstance(prefs.getAccountId()).getPathToAttach(thumb, true);
        if (source == null || !source.exists()) {
            return null;
        }

        if (!saveMediaFile) {
            return source.getAbsolutePath();
        }
        if (!shouldSaveMedia(prefs)) {
            return null;
        }

        var name = "thumb#" + thumb.w + "x" + thumb.h + "@" + AyuUtils.generateRandomString(6) + ".jpg";
        return copyToAttachments(source, name);
    }

    private static String copyToAttachments(File source, String fileName) {
        try {
            var folder = AyuMessagesController.attachmentsPath;
            if (!folder.exists()) {
                folder.mkdirs();
            }
            var target = new File(folder, fileName);
            // copy (never move): the same cached file can be shared by other messages
            if (AndroidUtilities.copyFile(source, target)) {
                return target.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e(TAG, "failed to copy attachment " + source, e);
            FileLog.e(e);
        }
        return null;
    }

    private static boolean shouldSaveMedia(AyuSavePreferences prefs) {
        if (!AyuConfig.saveMedia) {
            return false;
        }

        long dialogId = prefs.getDialogId();
        if (DialogObject.isEncryptedDialog(dialogId) || DialogObject.isUserDialog(dialogId)) {
            return AyuConfig.saveMediaInPrivateChats;
        }

        var chat = MessagesController.getInstance(prefs.getAccountId()).getChat(-dialogId);
        if (chat == null) {
            return AyuConfig.saveMediaInPrivateGroups;
        }

        boolean isPublic = ChatObject.isPublic(chat);
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            return isPublic ? AyuConfig.saveMediaInPublicChannels : AyuConfig.saveMediaInPrivateChannels;
        }
        return isPublic ? AyuConfig.saveMediaInPublicGroups : AyuConfig.saveMediaInPrivateGroups;
    }

    private static ArrayList<TLRPC.PhotoSize> inlineSizesOnly(ArrayList<TLRPC.PhotoSize> sizes) {
        var result = new ArrayList<TLRPC.PhotoSize>();
        if (sizes == null) {
            return result;
        }
        for (var size : sizes) {
            if (size instanceof TLRPC.TL_photoStrippedSize || size instanceof TLRPC.TL_photoCachedSize || size instanceof TLRPC.TL_photoPathSize) {
                result.add(size);
            }
        }
        return result;
    }

    private static void applyFlagBooleans(TLRPC.Message msg) {
        int flags = msg.flags;
        msg.out = (flags & 2) != 0;
        msg.mentioned = (flags & 16) != 0;
        msg.media_unread = false;
        msg.silent = (flags & 8192) != 0;
        msg.post = (flags & 16384) != 0;
        msg.from_scheduled = (flags & 262144) != 0;
        msg.legacy = (flags & 524288) != 0;
        msg.edit_hide = (flags & 2097152) != 0;
        msg.pinned = (flags & 16777216) != 0;
        msg.noforwards = false;
        msg.ayuNoforwards = (flags & 67108864) != 0;
        msg.topic_start = (flags & 134217728) != 0;
        msg.flags &= ~(32 | 67108864); // media_unread, noforwards
    }

    // =====================================================================
    // TL (de)serialization helpers
    // =====================================================================

    private interface Reader<T> {
        T read(AbstractSerializedData stream, int constructor);
    }

    private static byte[] serializeObject(TLObject object) {
        if (object == null) {
            return null;
        }
        var data = new SerializedData(object.getObjectSize());
        object.serializeToStream(data);
        var bytes = data.toByteArray();
        data.cleanup();
        return bytes;
    }

    private static byte[] serializeVector(List<? extends TLObject> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        var sizing = new SerializedData(true);
        writeVector(sizing, list);
        var data = new SerializedData(sizing.length());
        writeVector(data, list);
        var bytes = data.toByteArray();
        data.cleanup();
        return bytes;
    }

    private static void writeVector(AbstractSerializedData data, List<? extends TLObject> list) {
        data.writeInt32(VECTOR_CONSTRUCTOR);
        data.writeInt32(list.size());
        for (var obj : list) {
            obj.serializeToStream(data);
        }
    }

    private static <T> ArrayList<T> deserializeVector(byte[] bytes, Reader<T> reader) {
        var result = new ArrayList<T>();
        if (bytes == null || bytes.length < 8) {
            return result;
        }
        var data = new SerializedData(bytes);
        try {
            int magic = data.readInt32(false);
            if (magic != VECTOR_CONSTRUCTOR) {
                return result;
            }
            int count = data.readInt32(false);
            for (int i = 0; i < count; i++) {
                T item = reader.read(data, data.readInt32(false));
                if (item == null) {
                    break;
                }
                result.add(item);
            }
        } catch (Exception e) {
            Log.e(TAG, "deserializeVector failed", e);
        } finally {
            data.cleanup();
        }
        return result;
    }

    private static TLRPC.Document deserializeDocument(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        var data = new SerializedData(bytes);
        try {
            return TLRPC.Document.TLdeserialize(data, data.readInt32(false), false);
        } catch (Exception e) {
            Log.e(TAG, "deserializeDocument failed", e);
            return null;
        } finally {
            data.cleanup();
        }
    }
}
