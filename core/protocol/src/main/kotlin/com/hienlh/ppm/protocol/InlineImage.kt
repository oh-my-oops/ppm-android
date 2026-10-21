package com.hienlh.ppm.protocol

/**
 * A picture sent inside a chat message (`images[]` of the `message` frame): its file as base64,
 * with no `data:` prefix. Told apart by identity, not content, as the web's objects are — two
 * byte-identical screenshots are still two attachments, and only the one picked goes inline.
 */
class InlineImage(val data: String, val mediaType: String)
