package io.github.ratr2.repack.dev

/**
 * What a link from /repack upload takes: one kind of file, with the one extension it has, and nothing else. The folder is the one in the plugin's folder
 * the file goes into.
 */
enum class UploadKind(val id: String, val label: String, val extension: String, val maxBytes: Int, val folder: String) {
    MODEL("model", "a Blockbench model", "bbmodel", 32 * MB, "models"),
    SKYBOX("skybox", "a skybox file", "yml", 256 * KB, "skyboxes"),
    SKYBOX_IMAGE("skybox-image", "a skybox picture", "png", 32 * MB, "skyboxes"),
    SKYBOX_SHADER("skybox-shader", "a skybox shader", "glsl", 64 * KB, "skyboxes"),
    IMPACT("impact", "an impact frame scene", "yml", 64 * KB, "impacts"),
    GUI("gui", "a GUI file", "yml", 256 * KB, "guis"),
    GUI_PICTURE("gui-picture", "a GUI picture", "png", 16 * MB, "guis"),
    ITEM("item", "a custom item file", "yml", 64 * KB, "items");

    companion object {
        fun of(id: String?): UploadKind? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }

        private val NAME = Regex("[a-z0-9_-]{1,48}")

        /**
         * The name a file is saved under, without its extension, or a reason it is refused. A name with an extension must have the extension of the kind;
         * anything but a plain name is refused, so an upload can never reach outside its folder.
         */
        fun cleanName(kind: UploadKind, given: String?): Pair<String?, String?> {
            var name = given?.trim()?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
            if (name.contains('.')) {
                val extension = name.substringAfterLast('.').lowercase()
                if (extension != kind.extension) return null to "This link takes .${kind.extension} files only."
                name = name.substringBeforeLast('.')
            }
            name = name.lowercase()
            return if (NAME.matches(name)) name to null else null to "The name uses a-z, 0-9, _ and - only, up to 48 characters."
        }
    }
}

private const val KB = 1024
private const val MB = 1024 * 1024
