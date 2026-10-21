package local.dshx.prototype

enum class TaskStage { READY, RUNNING, APPROVAL, DISPATCHED, STOPPING, STOPPED, UNKNOWN, COMPLETE }

/** 只驱动走查演练，不签发凭证、不调用无障碍、不代替正式任务账本。 */
class TaskFlow(var stage: TaskStage = TaskStage.READY, var verification: String? = null) {
    fun start() { check(stage == TaskStage.READY); stage = TaskStage.RUNNING }
    fun ask() { check(stage == TaskStage.RUNNING); stage = TaskStage.APPROVAL }
    fun approve() { check(stage == TaskStage.APPROVAL); stage = TaskStage.DISPATCHED }
    fun deny() { check(stage == TaskStage.APPROVAL); stage = TaskStage.STOPPED }
    fun expire() { check(stage == TaskStage.APPROVAL); stage = TaskStage.STOPPED }
    fun stop() {
        stage = when (stage) {
            TaskStage.READY, TaskStage.RUNNING, TaskStage.APPROVAL -> TaskStage.STOPPED
            TaskStage.DISPATCHED -> TaskStage.STOPPING
            else -> stage
        }
    }
    fun loseResult() {
        check(stage == TaskStage.DISPATCHED || stage == TaskStage.STOPPING)
        stage = TaskStage.UNKNOWN
    }
    fun complete() { check(stage == TaskStage.DISPATCHED); stage = TaskStage.COMPLETE }
    fun verify(note: String) {
        check(stage == TaskStage.UNKNOWN)
        require(note in listOf("已发生", "未发现结果", "暂时无法确认"))
        verification = note
    }
}
