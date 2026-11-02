package local.dshx.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Android 宿主持有的重启记录；先提交磁盘再受理。默认无文件仅用于纯状态测试。 */
class EngineControl(private val file: File? = null) {
    private data class Operation(val id: String, val session: String?, val state: String = "accepted",
                                 val createdAt: Long = System.currentTimeMillis(),
                                 val updatedAt: Long = createdAt, val message: String? = null) {
        val done get() = state in setOf("succeeded", "failed", "cancelled")
        fun json() = JSONObject().put("operationId", id).put("state", state).put("done", done)
            .put("createdAt", createdAt).put("updatedAt", updatedAt).put("message", message ?: JSONObject.NULL)
            .put("session", session ?: JSONObject.NULL)
    }
    private val operations = linkedMapOf<String, Operation>()
    @Volatile var revision: Long = 0
        private set
    private var storageError: String? = null

    init { restore() }

    @Synchronized fun request(session: String? = null): JSONObject {
        check(storageError == null) { storageError.orEmpty() }
        require(session == null || session.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "会话标识无效。" }
        val pending = operations.values.lastOrNull()?.takeUnless { it.done }
        if (pending != null) {
            check(pending.session == session) { "其他来源已受理重启，请等待本次操作结束。" }
            return pending.json().put("accepted", true)
        }
        val operation = Operation(UUID.randomUUID().toString(), session)
        val next = LinkedHashMap(operations).apply {
            put(operation.id, operation)
            while (size > 16) remove(keys.first())
        }
        try { save(next, revision + 1) }
        catch (_: Exception) { throw IllegalStateException("重启记录无法保存，未受理重启，请检查设备存储后重试。") }
        operations.clear(); operations.putAll(next); revision++
        return operation.json().put("accepted", true)
    }

    @Synchronized fun status(id: String? = null, session: String? = null): JSONObject {
        val operation = if (id == null) operations.values.lastOrNull { session == null || it.session == session } else
            requireNotNull(operations[id]) { "重启操作不存在或已超出保留范围。" }
        return (operation?.json() ?: JSONObject().put("state", "idle").put("done", true)).also {
            if (storageError != null) it.put("error", storageError)
        }
    }

    @Synchronized fun move(id: String, from: String, to: String, message: String? = null): Boolean {
        val operation = operations[id] ?: return false
        if (operation.done || operation.state != from) return false
        val changed = operation.copy(state = to, updatedAt = System.currentTimeMillis(), message = message?.let(Engine::redact)?.take(500))
        val next = LinkedHashMap(operations).apply { put(id, changed) }
        try { save(next, revision + 1) }
        catch (_: Exception) {
            storageError = "重启状态无法保存，已停止本次重启，请检查设备存储。"
            // 存储故障仍必须撤销内存派发；磁盘中的未结束状态将在宿主重建时终态化。
            operations[id] = changed.copy(state = if (to == "cancelled") "cancelled" else "failed", message = storageError)
            revision++
            return false
        }
        operations[id] = changed; revision++
        return true
    }

    @Synchronized fun cancel() {
        operations.values.lastOrNull()?.takeUnless { it.done }?.let {
            move(it.id, it.state, "cancelled", "用户已停止服务，待重启操作已撤销。")
        }
    }

    private fun save(next: Map<String, Operation>, nextRevision: Long) {
        val target = file ?: return
        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
        val text = JSONObject().put("format", 1).put("revision", nextRevision)
            .put("operations", JSONArray(next.values.map { it.json() })).toString()
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomic.finishWrite(output)
            check(atomic.openRead().bufferedReader().use { it.readText() } == text)
        } catch (error: Exception) {
            runCatching { atomic.failWrite(output) }
            throw error
        }
    }

    private fun restore() {
        val target = file ?: return
        if (!target.exists() && !File(target.path + ".bak").exists()) return
        try {
            val saved = AtomicFile(target).openRead().use { input ->
                require(target.length() <= 128 * 1024)
                JSONObject(input.bufferedReader().readText())
            }
            require(saved.getInt("format") == 1)
            val rows = saved.getJSONArray("operations")
            require(rows.length() <= 16)
            revision = saved.getLong("revision").also { require(it >= 0) }
            val recovered = linkedMapOf<String, Operation>()
            var interrupted = false
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val id = row.getString("operationId")
                val session = if (row.isNull("session")) null else row.getString("session")
                require(id.matches(Regex("[A-Za-z0-9-]{1,128}")) && id !in recovered)
                require(session == null || session.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
                val state = row.getString("state")
                require(state in setOf("accepted", "stopping", "starting", "succeeded", "failed", "cancelled"))
                var operation = Operation(id, session, state, row.getLong("createdAt"), row.getLong("updatedAt"),
                    if (row.isNull("message")) null else Engine.redact(row.getString("message")).take(500))
                if (!operation.done) {
                    interrupted = true
                    operation = operation.copy(state = "failed", updatedAt = System.currentTimeMillis(),
                        message = "宿主进程已重建，上次重启结果未确认；未自动重放，请检查引擎状态。")
                }
                recovered[id] = operation
            }
            operations.putAll(recovered)
            if (interrupted) { revision++; save(recovered, revision) }
        } catch (_: Exception) {
            storageError = "重启记录无法读取或保存，已禁止派发重启，请检查设备存储。"
        }
    }
}
