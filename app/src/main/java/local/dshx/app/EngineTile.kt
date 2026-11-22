package local.dshx.app

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** 用户添加的系统快捷块；仅点击才请求启动引擎。 */
class EngineTile : TileService() {
    private val engine get() = (application as DshApp).engine
    private val listener: (EngineState) -> Unit = { state ->
        qsTile?.apply {
            this.state = if (state.url != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = when {
                state.url != null -> "运行中"
                state.busy -> state.phase
                else -> "启动引擎"
            }
            updateTile()
        }
    }

    override fun onStartListening() { super.onStartListening(); engine.observe(listener) }
    override fun onStopListening() { engine.remove(listener); super.onStopListening() }
    override fun onDestroy() { engine.remove(listener); super.onDestroy() }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::startEngine) else startEngine()
    }

    private fun startEngine() {
        if (engine.state.url == null && !engine.state.busy) {
            runCatching { EngineService.start(this, explicit = true) }.onFailure {
                Toast.makeText(this, "无法启动 dsh，请在工作台重试。", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
