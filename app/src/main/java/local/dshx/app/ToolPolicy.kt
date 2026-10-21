package local.dshx.app

/** Live session registry owned by the Android host. All registered sessions use Full. */
class ToolPolicy(private val changed: () -> Unit = {}) {
    data class Session(val id: String, val title: String, val root: String, val mode: String, val parent: String?)
    data class State(val instance: String? = null, val connected: Boolean = false, val sessions: List<Session> = emptyList(), val revision: Long = 0)

    @Volatile var state = State()
        private set
    private data class Owner(val root: String, val parent: String?)
    private val owners = mutableMapOf<String, Owner>()

    @Synchronized fun begin(instance: String) {
        owners.clear()
        publish(State(instance = instance))
    }

    @Synchronized fun connected(instance: String) {
        require(state.instance == instance)
        publish(state.copy(connected = true))
    }

    @Synchronized fun disconnect(instance: String) {
        if (state.instance == instance) { owners.clear(); publish(State(instance)) }
    }

    @Synchronized fun register(instance: String, id: String, title: String, parent: String?): String {
        require(state.instance == instance && state.connected) { "引擎连接已失效" }
        require(validId(id) && (parent == null || validId(parent))) { "会话标识无效" }
        val old = state.sessions.find { it.id == id }
        val owner = parent?.let { key -> requireNotNull(state.sessions.find { it.id == key }) { "父会话未登记" } }
        val root = owner?.root ?: id
        val binding = Owner(root, parent)
        require(owners[id] == null || owners[id] == binding) { "不能变更会话归属" }
        require(old != null || state.sessions.size < 64) { "当前会话数量已达上限" }
        require(id in owners || owners.size < 256) { "本次运行会话数量已达上限，请结束工作区后重试" }
        val label = title.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim().take(100).ifBlank { "未命名会话" }
        owners[id] = binding
        val session = Session(id, label, root, FULL, parent)
        if (old != session) publish(state.copy(sessions = state.sessions.filterNot { it.id == id } + session))
        return session.mode
    }

    @Synchronized fun mode(instance: String, id: String): String {
        require(state.instance == instance && state.connected) { "引擎连接已失效" }
        return requireNotNull(state.sessions.find { it.id == id }) { "会话未登记或已结束" }.mode
    }

    @Synchronized fun end(instance: String, id: String) {
        if (state.instance != instance) return
        val session = state.sessions.find { it.id == id } ?: return
        val removed = mutableSetOf(session.id)
        do {
            val size = removed.size
            state.sessions.filter { it.parent in removed }.forEach { removed.add(it.id) }
        } while (size != removed.size)
        publish(state.copy(sessions = state.sessions.filterNot { it.id in removed }))
    }

    private fun publish(value: State) {
        state = value.copy(revision = state.revision + 1)
        changed()
    }

    companion object {
        const val FULL = "full"
        private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9._:-]{1,128}"))
    }
}
