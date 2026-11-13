package io.github.ratr2.repack.dev

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** What the uploader page needs from the plugin. */
interface UploadAccess {
    /** Whether the player has dev mode on. */
    fun allowed(player: UUID): Boolean

    /** The folder a kind of file goes into, and the models folder items refer to. */
    fun folder(kind: UploadKind): File

    fun models(): File

    /** Called, on any thread, after a file was saved: [replaced] is whether it took the place of one. */
    fun saved(player: UUID, kind: UploadKind, file: File, replaced: Boolean)

    /** Where the file that is replaced is kept: one version back. */
    fun backups(): File
}

/**
 * The page at `/uploader?t=<token>`: a form for the one kind of file the token was made for, and the POST the form sends. The token is the only way in: a
 * request without a valid one gets the same plain answer whatever else is wrong with it, and one for a player whose dev mode is off gets nothing done.
 * Files are checked by [UploadValidation] before anything is written.
 */
class UploadRoute(private val tokens: UploadTokens, private val access: UploadAccess) : HttpHandler {

    override fun handle(exchange: HttpExchange) {
        val query = parse(exchange.requestURI.rawQuery)
        val grant = tokens.lookup(query["t"])
        if (grant == null) {
            respond(exchange, 401, "text/plain; charset=utf-8", "That link is not valid. Use /repack upload in the game for a new one.")
            return
        }
        if (!access.allowed(grant.player)) {
            respond(exchange, 403, "text/plain; charset=utf-8", "Dev mode is off. Turn it on with /repack devmode.")
            return
        }
        when (exchange.requestMethod) {
            "GET" -> respond(exchange, 200, "text/html; charset=utf-8", page(grant.kind))
            "POST" -> upload(exchange, grant, query["name"])
            else -> respond(exchange, 405, "text/plain; charset=utf-8", "GET or POST only.")
        }
    }

    private fun upload(exchange: HttpExchange, grant: UploadTokens.Grant, given: String?) {
        val kind = grant.kind
        val (name, problem) = UploadKind.cleanName(kind, given)
        if (name == null) return fail(exchange, 400, listOfNotNull(problem))
        val length = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (length == null || length <= 0) return fail(exchange, 411, listOf("The file's size is missing."))
        if (length > kind.maxBytes) return fail(exchange, 413, listOf("The file is over ${kind.maxBytes / 1024} KB, the most this link takes."))
        val bytes = exchange.requestBody.readNBytes(length.toInt())
        if (bytes.size.toLong() != length) return fail(exchange, 400, listOf("The upload was cut short."))
        val errors = try {
            UploadValidation.check(kind, name, bytes, access.folder(kind), access.models())
        } catch (e: Exception) {
            listOf("The file could not be checked: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        if (errors.isNotEmpty()) return fail(exchange, 400, errors)
        val folder = access.folder(kind).apply { mkdirs() }
        // A file of the same name in another case is the same file for the pack (a model's id is lower case), and a second one would make every build fail.
        val wanted = "$name.${kind.extension}"
        val target = folder.listFiles { f -> f.isFile && f.name.equals(wanted, ignoreCase = true) }?.firstOrNull() ?: File(folder, wanted)
        // The path is built from a checked name, so it cannot leave the folder; this checks it again anyway.
        if (target.canonicalFile.parentFile != folder.canonicalFile) return fail(exchange, 400, listOf("The name is not allowed."))
        val replaced = target.exists()
        if (replaced) {
            val backups = access.backups().apply { mkdirs() }
            Files.copy(target.toPath(), File(backups, "${kind.folder}-${target.name}").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // Two uploads of one name each have a temporary file of their own; one that fails leaves nothing.
        val temp = File.createTempFile(".upload-", ".tmp", folder)
        try {
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
        access.saved(grant.player, kind, target, replaced)
        respond(exchange, 200, "application/json; charset=utf-8", """{"ok":true,"file":${json(target.name)},"replaced":$replaced}""")
    }

    private fun fail(exchange: HttpExchange, status: Int, errors: List<String>) {
        respond(exchange, status, "application/json; charset=utf-8", """{"ok":false,"errors":[${errors.joinToString(",") { json(it) }}]}""")
    }

    private fun respond(exchange: HttpExchange, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.apply {
            add("Content-Type", type)
            add("Cache-Control", "no-store")
            add("X-Content-Type-Options", "nosniff")
            add("Referrer-Policy", "no-referrer")
            add("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'none'")
        }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** The query's parameters; a query that is not valid percent-encoding has none, so it is answered like a request without a token. */
    private fun parse(raw: String?): Map<String, String> = try {
        raw.orEmpty().split('&').filter { it.contains('=') }.associate {
            val (key, value) = it.split('=', limit = 2)
            URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }
    } catch (e: IllegalArgumentException) {
        emptyMap()
    }

    private fun page(kind: UploadKind): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Repack upload</title>
<style>
body{font:16px system-ui,sans-serif;background:#16161d;color:#e8e8f0;margin:0;display:grid;place-items:center;min-height:100vh}
main{width:min(30rem,92vw);background:#22222c;border-radius:12px;padding:1.5rem}
h1{font-size:1.2rem;margin:0 0 .25rem}p{color:#a8a8b8;margin:.25rem 0 1rem}
#drop{border:2px dashed #5a5a70;border-radius:10px;padding:1.5rem;text-align:center;cursor:pointer}#drop.over{border-color:#9b7bff}
label{display:block;margin:1rem 0 .25rem;color:#a8a8b8}input[type=text]{width:100%;box-sizing:border-box;padding:.5rem;border-radius:6px;border:1px solid #5a5a70;background:#16161d;color:inherit}
button{margin-top:1rem;padding:.6rem 1.2rem;border:0;border-radius:6px;background:#7c5cff;color:#fff;font-size:1rem;cursor:pointer}button:disabled{opacity:.5}
#out{margin-top:1rem;white-space:pre-wrap}.ok{color:#7fe08a}.bad{color:#ff8a8a}
</style></head><body><main>
<h1>Upload ${kind.label}</h1>
<p>This link takes .${kind.extension} files only, up to ${kind.maxBytes / 1024} KB. It goes into the ${kind.folder} folder.</p>
<div id="drop">Drop a .${kind.extension} file here, or click to choose one</div>
<input id="file" type="file" accept=".${kind.extension}" hidden>
<label for="name">Name</label><input id="name" type="text" placeholder="a-z, 0-9, _ and -" autocomplete="off">
<button id="go" disabled>Upload</button>
<div id="out" role="status"></div>
</main>
<script>
const ext=${json(kind.extension)},drop=document.getElementById('drop'),file=document.getElementById('file'),name=document.getElementById('name'),go=document.getElementById('go'),out=document.getElementById('out');
let chosen=null;
function pick(f){if(!f)return;chosen=f;drop.textContent=f.name+' ('+Math.ceil(f.size/1024)+' KB)';if(!name.value)name.value=f.name.replace(/\.[^.]*${'$'}/,'').toLowerCase().replace(/[^a-z0-9_-]/g,'-').slice(0,48);go.disabled=false;out.textContent='';}
drop.onclick=()=>file.click();file.onchange=()=>pick(file.files[0]);
drop.ondragover=e=>{e.preventDefault();drop.classList.add('over')};drop.ondragleave=()=>drop.classList.remove('over');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('over');pick(e.dataTransfer.files[0])};
go.onclick=async()=>{go.disabled=true;out.className='';out.textContent='Uploading...';
 try{const t=new URLSearchParams(location.search).get('t');
  const r=await fetch('/uploader?t='+encodeURIComponent(t)+'&name='+encodeURIComponent(name.value+'.'+ext),{method:'POST',body:chosen});
  const text=await r.text();let j=null;try{j=JSON.parse(text)}catch(e){}
  if(j&&j.ok){out.className='ok';out.textContent='Saved '+j.file+(j.replaced?' (it replaced one of that name; the old one is in backups).':'.')+' The pack is rebuilding. Use /repack test in the game to try it.';}
  else{out.className='bad';out.textContent=(j&&j.errors?j.errors.join('\n'):text)}
 }catch(e){out.className='bad';out.textContent='The upload failed: '+e}
 go.disabled=false};
</script></body></html>"""

    private fun json(text: String): String = buildString {
        append('"')
        for (c in text) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
