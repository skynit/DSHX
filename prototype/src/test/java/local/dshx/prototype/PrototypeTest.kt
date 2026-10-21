package local.dshx.prototype

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrototypeTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun workspace() = Workspace(temporary.newFolder())
    private fun import(workspace: Workspace, text: String = "- [ ] 核对导出\n", name: String = "sample.md") = workspace.import(name, text.byteInputStream())

    @Test fun sampleMatchesFixedExpectedOutput() {
        val assets = RuntimeEnvironment.getApplication().assets
        val source = assets.open("meeting.md").bufferedReader().use { it.readText() }
        val expected = assets.open("expected.md").bufferedReader().use { it.readText() }
        assertEquals(expected, Workspace.extractTasks("meeting.md", source))
    }

    @Test fun extractionKeepsSourceLineNumbersAndSkipsCompletedItems() {
        val result = Workspace.extractTasks("s.md", "# Title\n- [x] 已完成\n- [ ] 未完成\n")
        assertTrue(result.contains("未完成 （原文第 3 行）"))
        assertFalse(result.contains("已完成"))
    }

    @Test fun emptyTaskListDoesNotOverwriteWorkspaceCopy() {
        val work = workspace()
        val file = import(work, "# 只有标题\n")
        assertThrows(IllegalArgumentException::class.java) { work.extract(file) }
        assertEquals("# 只有标题\n", work.read(file))
        assertFalse(work.hasChange(file))
    }

    @Test fun importCreatesIndependentCopy() {
        val work = workspace()
        val source = temporary.newFile("source.md").apply { writeText("- [ ] 原文\n") }
        val file = source.inputStream().use { work.import("source.md", it) }
        work.extract(file)
        assertEquals("- [ ] 原文\n", source.readText())
        assertEquals(source.readText(), work.original(file))
        assertNotEquals(work.original(file), work.read(file))
    }

    @Test fun repeatedFileNamesRetainBothCopies() {
        val work = workspace()
        val first = import(work)
        val second = import(work)
        assertNotEquals(first.id, second.id)
        assertEquals(2, work.list().size)
    }

    @Test fun unsupportedTypeIsRejectedBeforeCreatingAnyCopy() {
        val work = workspace()
        assertThrows(IllegalArgumentException::class.java) { import(work, "text", "s.pdf") }
        assertTrue(work.list().isEmpty())
    }

    @Test fun oversizedImportIsRejectedWithoutPartialFiles() {
        val work = workspace()
        assertThrows(IllegalArgumentException::class.java) { import(work, "a".repeat(Workspace.MAX_BYTES + 1)) }
        assertTrue(work.list().isEmpty())
    }

    @Test fun exactSizeLimitIsAccepted() {
        val work = workspace()
        val file = import(work, "a".repeat(Workspace.MAX_BYTES))
        assertEquals(Workspace.MAX_BYTES, work.read(file).length)
    }

    @Test fun invalidUtf8IsRejected() {
        val work = workspace()
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { work.import("s.md", byteArrayOf(0xc3.toByte(), 0x28).inputStream()) }
        assertTrue(work.list().isEmpty())
    }

    @Test fun nulContentIsRejected() {
        val work = workspace()
        assertThrows(IllegalArgumentException::class.java) { import(work, "text\u0000more") }
        assertTrue(work.list().isEmpty())
    }

    @Test fun utf8BomIsAcceptedAndRemoved() {
        val work = workspace()
        assertEquals("text", work.read(import(work, "\uFEFFtext")))
    }

    @Test fun importDoesNotTrustPathInDisplayName() {
        val work = workspace()
        val file = import(work, "text", "../../folder/safe.md")
        assertEquals("safe.md", file.name)
        assertEquals("text", work.read(file))
    }

    @Test fun forgedFileIdCannotEscapeWorkspace() {
        assertThrows(IllegalArgumentException::class.java) { workspace().read(WorkFile("../secret", "secret.md")) }
    }

    @Test fun incompleteImportDirectoryIsNotShown() {
        val root = temporary.newFolder()
        File(root, "partial").mkdir()
        assertTrue(Workspace(root).list().isEmpty())
    }

    @Test fun jsonCanBeImportedButNotRunAsMarkdownDemo() {
        val work = workspace()
        val file = import(work, "{\"items\":[]}", "s.json")
        assertEquals("{\"items\":[]}", work.read(file))
        assertThrows(IllegalArgumentException::class.java) { work.extract(file) }
    }

    @Test fun undoRestoresTaskStartAndLeavesOriginalIntact() {
        val work = workspace()
        val file = import(work)
        val before = work.read(file)
        work.extract(file)
        assertEquals(file, work.undo(file))
        assertEquals(before, work.read(file))
        assertEquals(before, work.original(file))
        assertFalse(work.hasChange(file))
    }

    @Test fun secondUndoCannotRepeatTheSameWrite() {
        val work = workspace()
        val file = import(work)
        work.extract(file)
        work.undo(file)
        assertThrows(IllegalStateException::class.java) { work.undo(file) }
    }

    @Test fun undoConflictPreservesLaterEditAndCreatesRecoveryCopy() {
        val root = temporary.newFolder()
        val work = Workspace(root)
        val file = import(work)
        val original = work.read(file)
        work.extract(file)
        File(root, "${file.id}/current").writeText("后来的修改")
        val restored = work.undo(file)
        assertNotEquals(file.id, restored.id)
        assertEquals("后来的修改", work.read(file))
        assertEquals(original, work.read(restored))
        assertEquals(2, work.list().size)
    }

    @Test fun missingResultHashIsTreatedAsConflict() {
        val root = temporary.newFolder()
        val work = Workspace(root)
        val file = import(work)
        work.extract(file)
        File(root, "${file.id}/result-hash").delete()
        assertNotEquals(file.id, work.undo(file).id)
    }

    @Test fun conflictRecoveryKeepsLongFileNameWithinLimit() {
        val root = temporary.newFolder()
        val work = Workspace(root)
        val file = import(work, name = "a".repeat(117) + ".md")
        val before = work.read(file)
        work.extract(file)
        File(root, "${file.id}/current").writeText("后来的修改")
        val restored = work.undo(file)
        assertTrue(restored.name.length <= 120)
        assertTrue(restored.name.startsWith("恢复-"))
        assertTrue(restored.name.endsWith(".md"))
        assertEquals(before, work.read(restored))
        assertEquals("后来的修改", work.read(file))
    }

    @Test fun workspaceSurvivesNewWorkspaceInstance() {
        val root = temporary.newFolder()
        val first = Workspace(root)
        val file = import(first)
        first.extract(file)
        val reopened = Workspace(root)
        assertEquals(listOf(file), reopened.list())
        assertTrue(reopened.hasChange(file))
        assertEquals(first.read(file), reopened.read(file))
    }

    @Test fun approvalCannotBeSkipped() {
        val flow = TaskFlow()
        assertThrows(IllegalStateException::class.java) { flow.approve() }
        flow.start()
        assertThrows(IllegalStateException::class.java) { flow.complete() }
        flow.ask()
        flow.approve()
        assertEquals(TaskStage.DISPATCHED, flow.stage)
    }

    @Test fun stoppingPendingApprovalPreventsDispatch() {
        val flow = TaskFlow()
        flow.start(); flow.ask(); flow.stop()
        assertEquals(TaskStage.STOPPED, flow.stage)
        assertThrows(IllegalStateException::class.java) { flow.approve() }
    }

    @Test fun stoppingDispatchedActionRequiresUncertaintyResolution() {
        val flow = TaskFlow()
        flow.start(); flow.ask(); flow.approve(); flow.stop()
        assertEquals(TaskStage.STOPPING, flow.stage)
        flow.loseResult()
        assertEquals(TaskStage.UNKNOWN, flow.stage)
    }

    @Test fun humanVerificationDoesNotRewriteUnknownOrAllowRetry() {
        val flow = TaskFlow()
        flow.start(); flow.ask(); flow.approve(); flow.loseResult()
        flow.verify("已发生")
        assertEquals(TaskStage.UNKNOWN, flow.stage)
        assertEquals("已发生", flow.verification)
        assertThrows(IllegalStateException::class.java) { flow.start() }
        assertThrows(IllegalStateException::class.java) { flow.approve() }
    }

    @Test fun expiredApprovalCannotBeReused() {
        val flow = TaskFlow()
        flow.start(); flow.ask(); flow.expire()
        assertEquals(TaskStage.STOPPED, flow.stage)
        assertThrows(IllegalStateException::class.java) { flow.approve() }
    }

    @Test fun rejectionEndsTask() {
        val flow = TaskFlow()
        flow.start(); flow.ask(); flow.deny()
        assertEquals(TaskStage.STOPPED, flow.stage)
    }

    @Test fun repeatedStopDoesNotClaimDispatchedActionWasCancelled() {
        val flow = TaskFlow()
        flow.start(); flow.ask(); flow.approve(); flow.stop(); flow.stop()
        assertEquals(TaskStage.STOPPING, flow.stage)
    }

    @Test fun prototypeHasNoInternetPermission() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(PackageManager.PERMISSION_DENIED, app.packageManager.checkPermission(Manifest.permission.INTERNET, app.packageName))
    }

    @Test fun importReturningDuringPreparationIsNotSilentlyDropped() {
        idle()
        val app = RuntimeEnvironment.getApplication() as PrototypeApp
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        app.runWork({ started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }) { }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        app.runWork({ app.workspace.import("queued.md", "- [ ] 导入\n".byteInputStream()) }) { app.files = app.files + it }
        release.countDown()
        idle()
        assertTrue(app.files.any { it.name == "queued.md" })
        assertFalse(app.busy)
    }

    @Test fun homeShowsActualAndUnavailableCapabilitiesHonestly() = withActivity { activity ->
        val texts = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.any { it.contains("模型与手机操作为演练") })
        assertTrue(texts.any { it.contains("尚未选定") })
        assertFalse(button(activity, "插件检查尚未准备").isEnabled)
    }

    @Test fun fileFlowProducesInspectableResultAndRevokesConsentOnUndo() = withActivity { activity ->
        button(activity, "用周会样本开始").performClick()
        idle()
        val state = activity.application as PrototypeApp
        assertEquals("file", state.page)
        button(activity, "按本地规则提取未完成事项").performClick()
        assertFalse(state.changed)
        views(activity.window.decorView).filterIsInstance<CheckBox>().single().performClick()
        button(activity, "按本地规则提取未完成事项").performClick()
        idle()
        assertTrue(state.changed)
        assertTrue(state.text.contains("原文第 6 行"))
        button(activity, "撤销这次整理").performClick()
        idle()
        assertFalse(state.changed)
        assertFalse(state.allowed)
        assertTrue(state.text.startsWith("# 移动端发布周会"))
    }

    @Test fun taskEntryRemainsVisibleAfterLeavingTaskPage() = withActivity { activity ->
        button(activity, "进入操作演练").performClick()
        button(activity, "开始任务演练").performClick()
        button(activity, "离开此页，查看任务入口").performClick()
        assertTrue(views(activity.window.decorView).filterIsInstance<Button>().any { it.text.contains("查看 / 核实") })
        button(activity, "停止任务演练").performClick()
        assertEquals(TaskStage.STOPPED, (activity.application as PrototypeApp).flow.stage)
    }

    @Test fun reopeningExpiredApprovalDoesNotExtendIt() = withActivity { activity ->
        val state = activity.application as PrototypeApp
        button(activity, "进入操作演练").performClick()
        button(activity, "开始任务演练").performClick()
        button(activity, "演练：准备提交，进入确认").performClick()
        state.approvalDeadline = SystemClock.elapsedRealtime() - 1
        button(activity, "仅允许这一次（演练）").performClick()
        assertEquals(TaskStage.STOPPED, state.flow.stage)
    }

    @Test fun cancellingSystemExportKeepsLocalResult() = withActivity { activity ->
        button(activity, "用周会样本开始").performClick()
        idle()
        val state = activity.application as PrototypeApp
        val before = state.text
        button(activity, "另存为新文件").performClick()
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        assertEquals(before, state.text)
        assertNull(state.exportText)
    }

    @Test fun exportUsesFrozenResultEvenIfSelectionChangesBeforePickerReturns() = withActivity { activity ->
        button(activity, "用周会样本开始").performClick()
        idle()
        val state = activity.application as PrototypeApp
        val intended = state.text
        button(activity, "另存为新文件").performClick()
        val started = shadowOf(activity).nextStartedActivityForResult
        state.text = "另一份后来选中的内容"
        val uri = Uri.parse("content://test/output/frozen")
        val stream = ByteArrayOutputStream()
        shadowOf(activity.contentResolver).registerOutputStream(uri, stream)
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(uri))
        idle()
        assertEquals(intended, stream.toString("UTF-8"))
        assertEquals("另一份后来选中的内容", state.text)
    }

    @Test fun partialExternalWriteIsUnknownAndNeverAutomaticallyRetried() = withActivity { activity ->
        button(activity, "用周会样本开始").performClick()
        idle()
        val state = activity.application as PrototypeApp
        val before = state.text
        button(activity, "另存为新文件").performClick()
        val started = shadowOf(activity).nextStartedActivityForResult
        val uri = Uri.parse("content://test/output/failing")
        var attempts = 0
        val stream = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                attempts++
                super.write(bytes, offset, minOf(10, length))
                throw IOException("模拟外部存储中断")
            }
        }
        shadowOf(activity.contentResolver).registerOutputStream(uri, stream)
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(uri))
        idle()
        assertEquals(1, attempts)
        assertTrue(stream.size() > 0)
        assertTrue(state.message.orEmpty().contains("结果不明"))
        assertEquals(before, state.text)
        assertNull(state.exportText)
    }

    @Test fun activityRecreationKeepsTaskAndDoesNotRefreshApprovalDeadline() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            idle()
            var activity = controller.get()
            button(activity, "进入操作演练").performClick()
            button(activity, "开始任务演练").performClick()
            button(activity, "演练：准备提交，进入确认").performClick()
            val state = activity.application as PrototypeApp
            val deadline = state.approvalDeadline
            activity = controller.recreate().get()
            assertSame(state, activity.application)
            assertEquals(TaskStage.APPROVAL, state.flow.stage)
            assertEquals(deadline, state.approvalDeadline)
            button(activity, "停止任务演练").performClick()
            assertEquals(TaskStage.STOPPED, state.flow.stage)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w405dp-h900dp-mdpi")
    fun renderPhoneFlowsAndCheckTouchTargets() = withActivity { activity ->
        renderPreview(activity, "home", 405, 900)
        button(activity, "用周会样本开始").performClick()
        idle()
        renderPreview(activity, "file", 405, 900)
        button(activity, "手机任务").performClick()
        button(activity, "开始任务演练").performClick()
        button(activity, "演练：准备提交，进入确认").performClick()
        renderPreview(activity, "approval", 405, 900)
        button(activity, "仅允许这一次（演练）").performClick()
        button(activity, "停止任务演练").performClick()
        button(activity, "演练：等待后仍无回执").performClick()
        renderPreview(activity, "unknown", 405, 900)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w375dp-h812dp-night-mdpi")
    fun renderSmallPhoneWithLargeTextAndDarkTheme() {
        RuntimeEnvironment.setFontScale(2f)
        withActivity { activity -> renderPreview(activity, "home-dark-large-text", 375, 812) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w812dp-h375dp-land-mdpi")
    fun renderLandscapeWithoutHidingNavigation() = withActivity { activity ->
        renderPreview(activity, "home-landscape", 812, 375)
    }

    private fun renderPreview(activity: MainActivity, name: String, width: Int, height: Int) {
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        ShadowLooper.idleMainLooper()
        val density = activity.resources.displayMetrics.density
        views(root).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }.forEach {
            assertTrue("${it.text} 的触控区域不足 48dp", it.height / density >= 48f)
            assertTrue("${it.text} 横向超出内容区域", it.width <= width)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        views(root).filterIsInstance<Button>().filter { it.isEnabled }.forEach { button ->
            val visible = Rect()
            if (button.getGlobalVisibleRect(visible) && visible.height() == button.height) {
                val location = IntArray(2)
                button.getLocationOnScreen(location)
                // 复选框左侧是方框图标，文字应与其右侧的实际页面底色比较。
                val x = (location[0] + if (button is CompoundButton) button.width - 2 * density else 8 * density).toInt()
                val y = location[1] + button.height / 2
                if (x in 0 until width && y in 0 until height) {
                    val contrast = contrast(button.currentTextColor, bitmap.getPixel(x, y))
                    assertTrue("${button.text} 的已渲染文字对比度仅为 $contrast", contrast >= 4.5)
                }
            }
        }
        val output = File("build/visual-checks", "$name.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun linear(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) + 0.0722 * linear(Color.blue(color))
        }
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun withActivity(test: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { idle(); test(controller.get()) } finally { controller.pause().stop().destroy() }
    }

    private fun idle() {
        val app = RuntimeEnvironment.getApplication() as PrototypeApp
        repeat(8) {
            app.worker.submit { }.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            if (!app.busy) return
        }
        fail("原型后台文件处理没有在有界等待内完成")
    }

    private fun button(activity: MainActivity, text: String) = views(activity.window.decorView)
        .filterIsInstance<Button>().first { it.text.toString() == text }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
    } else emptyList()
}
